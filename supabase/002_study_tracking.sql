-- 과목·교재·진도·습관 자동 누적
-- 리포트가 '보냄(sent)' 상태가 되면 result(JSON)를 읽어 아래 표로 자동 분해한다.
-- result.studyTasks[]   : {subject, material, unitType, plannedStart, plannedEnd, doneStart, doneEnd,
--                          plannedMinutes, actualMinutes, studyMode, status, rawText}
-- result.habitChecks[]  : {key, passed, note}
-- result.classSummaries[]: {subject, summaryText, quality, feedback, betterExample}

create or replace function private.norm(p text)
returns text language sql immutable set search_path = '' as $$
  select lower(regexp_replace(coalesce(p, ''), '[\s·\-_()\[\]]+', '', 'g'));
$$;

create or replace function private.num(p text)
returns numeric language sql immutable set search_path = '' as $$
  select case when p ~ '^\s*-?\d+(\.\d+)?\s*$' then p::numeric end;
$$;

create table public.subjects (
  id uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profiles (id) on delete cascade,
  name text not null,
  norm_name text not null,
  color text,
  created_at timestamptz not null default now(),
  unique (student_id, norm_name)
);

create table public.materials (
  id uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profiles (id) on delete cascade,
  subject_id uuid references public.subjects (id) on delete set null,
  title text not null,
  norm_title text not null,
  unit_type text not null default '쪽',          -- 쪽 | 강 | 문항 | 회 | 장 | 단원
  total_units numeric,                           -- 관리자가 나중에 입력(없으면 진도율 대신 누적량만 표시)
  target_date date,
  current_position numeric,                      -- 지금까지 한 가장 뒤 위치(예: 135쪽)
  units_done numeric not null default 0,         -- 누적 학습량
  started_on date,
  last_studied_on date,
  status text not null default 'active' check (status in ('active', 'done', 'paused')),
  auto_created boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (student_id, norm_title)
);
create index materials_subject_id_idx on public.materials (subject_id);

create table public.study_tasks (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references public.reports (id) on delete cascade,
  student_id uuid not null references public.profiles (id) on delete cascade,
  plan_date date not null,
  subject_id uuid references public.subjects (id) on delete set null,
  material_id uuid references public.materials (id) on delete set null,
  raw_text text not null default '',
  unit_type text,
  planned_start numeric,
  planned_end numeric,
  done_start numeric,
  done_end numeric,
  planned_minutes integer,
  actual_minutes integer,
  study_mode text,                                -- SELF_STUDY | LECTURE | REVIEW
  status text not null default 'UNKNOWN' check (status in ('DONE', 'PARTIAL', 'NOT_DONE', 'UNKNOWN')),
  created_at timestamptz not null default now()
);
create index study_tasks_report_id_idx on public.study_tasks (report_id);
create index study_tasks_student_date_idx on public.study_tasks (student_id, plan_date);
create index study_tasks_material_date_idx on public.study_tasks (material_id, plan_date);
create index study_tasks_subject_id_idx on public.study_tasks (subject_id);

create table public.habit_checks (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references public.reports (id) on delete cascade,
  student_id uuid not null references public.profiles (id) on delete cascade,
  plan_date date not null,
  habit_key text not null,     -- BIBLE | VOLUME_SPECIFIED | COLOR_TIMETABLE | REVIEW_PAIR | NEXT_DAY_RESERVE | CLASS_SUMMARY
  passed boolean not null,
  note text not null default '',
  unique (report_id, habit_key)
);
create index habit_checks_student_date_idx on public.habit_checks (student_id, plan_date);

create table public.class_summaries (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references public.reports (id) on delete cascade,
  student_id uuid not null references public.profiles (id) on delete cascade,
  plan_date date not null,
  subject_id uuid references public.subjects (id) on delete set null,
  summary_text text not null default '',
  quality text check (quality in ('GOOD', 'VAGUE', 'MISSING')),
  feedback text not null default ''
);
create index class_summaries_report_id_idx on public.class_summaries (report_id);
create index class_summaries_student_date_idx on public.class_summaries (student_id, plan_date);
create index class_summaries_subject_id_idx on public.class_summaries (subject_id);

create trigger materials_touch before update on public.materials for each row execute function private.touch_updated_at();

-- 과목 찾기/만들기
create or replace function private.upsert_subject(p_student uuid, p_name text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if nullif(btrim(coalesce(p_name, '')), '') is null then return null; end if;
  insert into public.subjects as s (student_id, name, norm_name)
  values (p_student, btrim(p_name), private.norm(p_name))
  on conflict (student_id, norm_name) do update set name = s.name
  returning s.id into v_id;
  return v_id;
end; $$;

-- 교재 찾기/만들기
create or replace function private.upsert_material(p_student uuid, p_subject uuid, p_title text, p_unit text, p_date date)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if nullif(btrim(coalesce(p_title, '')), '') is null then return null; end if;
  insert into public.materials as m (student_id, subject_id, title, norm_title, unit_type, started_on, auto_created)
  values (p_student, p_subject, btrim(p_title), private.norm(p_title), coalesce(nullif(btrim(p_unit), ''), '쪽'), p_date, true)
  on conflict (student_id, norm_title) do update
    set subject_id = coalesce(m.subject_id, excluded.subject_id),
        started_on = least(coalesce(m.started_on, excluded.started_on), excluded.started_on)
  returning m.id into v_id;
  return v_id;
end; $$;

-- 교재별 누적 진도 다시 계산
create or replace function private.refresh_material_progress(p_student uuid)
returns void language sql security definer set search_path = '' as $$
  update public.materials m
     set current_position = agg.max_end,
         units_done = coalesce(agg.units, 0),
         last_studied_on = agg.last_day
    from (
      select mm.id,
             max(t.done_end) as max_end,
             sum(greatest(t.done_end - t.done_start + 1, 0)) as units,
             max(t.plan_date) filter (where t.done_end is not null) as last_day
        from public.materials mm
        left join public.study_tasks t on t.material_id = mm.id and t.done_start is not null and t.done_end is not null
       where mm.student_id = p_student
       group by mm.id
    ) agg
   where m.id = agg.id;
$$;

-- 리포트 → 과목·교재·할일·습관·수업요약 분해
create or replace function private.ingest_report()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  t jsonb; v_subject uuid; v_material uuid;
begin
  delete from public.study_tasks where report_id = new.id;
  delete from public.habit_checks where report_id = new.id;
  delete from public.class_summaries where report_id = new.id;

  if new.status = 'sent' then
    for t in select * from jsonb_array_elements(coalesce(new.result -> 'studyTasks', '[]'::jsonb)) loop
      v_subject := private.upsert_subject(new.student_id, t ->> 'subject');
      v_material := private.upsert_material(new.student_id, v_subject, t ->> 'material', t ->> 'unitType', new.plan_date);
      insert into public.study_tasks (report_id, student_id, plan_date, subject_id, material_id, raw_text, unit_type,
        planned_start, planned_end, done_start, done_end, planned_minutes, actual_minutes, study_mode, status)
      values (new.id, new.student_id, new.plan_date, v_subject, v_material, coalesce(t ->> 'rawText', ''),
        nullif(t ->> 'unitType', ''),
        private.num(t ->> 'plannedStart'), private.num(t ->> 'plannedEnd'),
        private.num(t ->> 'doneStart'), private.num(t ->> 'doneEnd'),
        private.num(t ->> 'plannedMinutes')::integer, private.num(t ->> 'actualMinutes')::integer,
        nullif(t ->> 'studyMode', ''),
        case when t ->> 'status' in ('DONE', 'PARTIAL', 'NOT_DONE', 'UNKNOWN') then t ->> 'status' else 'UNKNOWN' end);
    end loop;

    for t in select * from jsonb_array_elements(coalesce(new.result -> 'habitChecks', '[]'::jsonb)) loop
      if nullif(t ->> 'key', '') is not null then
        insert into public.habit_checks (report_id, student_id, plan_date, habit_key, passed, note)
        values (new.id, new.student_id, new.plan_date, t ->> 'key', coalesce(case lower(t ->> 'passed') when 'true' then true when 'false' then false end, false), coalesce(t ->> 'note', ''))
        on conflict (report_id, habit_key) do nothing;
      end if;
    end loop;

    for t in select * from jsonb_array_elements(coalesce(new.result -> 'classSummaries', '[]'::jsonb)) loop
      insert into public.class_summaries (report_id, student_id, plan_date, subject_id, summary_text, quality, feedback)
      values (new.id, new.student_id, new.plan_date, private.upsert_subject(new.student_id, t ->> 'subject'),
        coalesce(t ->> 'summaryText', ''),
        case when t ->> 'quality' in ('GOOD', 'VAGUE', 'MISSING') then t ->> 'quality' end,
        coalesce(t ->> 'feedback', ''));
    end loop;
  end if;

  perform private.refresh_material_progress(new.student_id);
  return new;
end; $$;

create trigger reports_ingest
  after insert or update of status, result on public.reports
  for each row execute function private.ingest_report();

-- 교재별 진도 현황 (진도율·최근 2주 속도·예상 완료일)
create view public.material_progress with (security_invoker = true) as
select m.id, m.student_id, m.subject_id, s.name as subject_name, m.title, m.unit_type,
       m.total_units, m.target_date, m.current_position, m.units_done, m.started_on, m.last_studied_on, m.status,
       case when m.total_units > 0 then round(100.0 * coalesce(m.current_position, 0) / m.total_units, 1) end as progress_pct,
       round(coalesce(p.units_14d, 0) / 14.0, 2) as pace_per_day_14d,
       case when m.total_units > 0 and coalesce(p.units_14d, 0) > 0
            then current_date + ceil((m.total_units - coalesce(m.current_position, 0)) / (p.units_14d / 14.0))::int end as predicted_finish
  from public.materials m
  left join public.subjects s on s.id = m.subject_id
  left join lateral (
    select sum(greatest(t.done_end - t.done_start + 1, 0)) as units_14d
      from public.study_tasks t
     where t.material_id = m.id and t.plan_date >= current_date - 13 and t.done_end is not null
  ) p on true;

-- 과목별 주간 계획 대비 실행
create view public.subject_weekly_stats with (security_invoker = true) as
select t.student_id, date_trunc('week', t.plan_date)::date as week_start, t.subject_id, s.name as subject_name,
       count(*) as tasks, count(*) filter (where t.status = 'DONE') as done_tasks,
       count(*) filter (where t.status = 'PARTIAL') as partial_tasks,
       coalesce(sum(t.planned_minutes), 0) as planned_minutes, coalesce(sum(t.actual_minutes), 0) as actual_minutes,
       coalesce(sum(greatest(t.done_end - t.done_start + 1, 0)), 0) as units_done
  from public.study_tasks t
  left join public.subjects s on s.id = t.subject_id
 group by t.student_id, date_trunc('week', t.plan_date)::date, t.subject_id, s.name;

-- 습관별 주간 달성
create view public.habit_weekly_stats with (security_invoker = true) as
select h.student_id, date_trunc('week', h.plan_date)::date as week_start, h.habit_key,
       count(*) as checks, count(*) filter (where h.passed) as passed
  from public.habit_checks h
 group by h.student_id, date_trunc('week', h.plan_date)::date, h.habit_key;

-- 권한·RLS: 학생은 자기 것 조회, 관리자는 전체 조회·수정. 쓰기는 주로 트리거가 담당.
grant select on public.subjects, public.materials, public.study_tasks, public.habit_checks, public.class_summaries to authenticated;
grant insert, update, delete on public.subjects, public.materials, public.study_tasks, public.habit_checks, public.class_summaries to authenticated;
grant select on public.material_progress, public.subject_weekly_stats, public.habit_weekly_stats to authenticated;

alter table public.subjects enable row level security;
alter table public.materials enable row level security;
alter table public.study_tasks enable row level security;
alter table public.habit_checks enable row level security;
alter table public.class_summaries enable row level security;

create policy subjects_select on public.subjects for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy subjects_admin on public.subjects for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy materials_select on public.materials for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy materials_admin on public.materials for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy study_tasks_select on public.study_tasks for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy study_tasks_admin on public.study_tasks for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy habit_checks_select on public.habit_checks for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy habit_checks_admin on public.habit_checks for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy class_summaries_select on public.class_summaries for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy class_summaries_admin on public.class_summaries for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

revoke all on function private.upsert_subject(uuid, text) from public, anon, authenticated;
revoke all on function private.upsert_material(uuid, uuid, text, text, date) from public, anon, authenticated;
revoke all on function private.refresh_material_progress(uuid) from public, anon, authenticated;
revoke all on function private.ingest_report() from public, anon, authenticated;
