-- 003: 분량 개수(10문제·단어 30개) 지원, 교재(TEXTBOOK)와 일반 할 일(TASK) 구분
alter table public.study_tasks
  add column planned_amount numeric,
  add column done_amount numeric,
  add column task_kind text not null default 'TEXTBOOK' check (task_kind in ('TEXTBOOK', 'TASK'));

-- 완료량: 범위가 있으면 범위 길이, 없으면 개수
create or replace function private.task_units(p_start numeric, p_end numeric, p_amount numeric)
returns numeric language sql immutable set search_path = '' as $$
  select case when p_start is not null and p_end is not null then greatest(p_end - p_start + 1, 0) else p_amount end;
$$;
grant execute on function private.task_units(numeric, numeric, numeric) to authenticated;

create or replace function private.refresh_material_progress(p_student uuid)
returns void language sql security definer set search_path = '' as $$
  update public.materials m
     set current_position = agg.max_end,
         units_done = coalesce(agg.units, 0),
         last_studied_on = agg.last_day
    from (
      select mm.id,
             max(t.done_end) as max_end,
             sum(private.task_units(t.done_start, t.done_end, t.done_amount)) as units,
             max(t.plan_date) filter (where t.done_end is not null or t.done_amount is not null) as last_day
        from public.materials mm
        left join public.study_tasks t on t.material_id = mm.id
       where mm.student_id = p_student
       group by mm.id
    ) agg
   where m.id = agg.id;
$$;

create or replace function private.ingest_report()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  t jsonb; v_subject uuid; v_material uuid; v_kind text;
begin
  delete from public.study_tasks where report_id = new.id;
  delete from public.habit_checks where report_id = new.id;
  delete from public.class_summaries where report_id = new.id;

  if new.status = 'sent' then
    for t in select * from jsonb_array_elements(coalesce(new.result -> 'studyTasks', '[]'::jsonb)) loop
      v_kind := case when upper(coalesce(t ->> 'kind', '')) = 'TASK' then 'TASK' else 'TEXTBOOK' end;
      v_subject := private.upsert_subject(new.student_id, t ->> 'subject');
      v_material := case when v_kind = 'TEXTBOOK'
        then private.upsert_material(new.student_id, v_subject, t ->> 'material', t ->> 'unitType', new.plan_date)
        else null end;
      insert into public.study_tasks (report_id, student_id, plan_date, subject_id, material_id, raw_text, unit_type,
        planned_start, planned_end, done_start, done_end, planned_amount, done_amount, task_kind,
        planned_minutes, actual_minutes, study_mode, status)
      values (new.id, new.student_id, new.plan_date, v_subject, v_material, coalesce(t ->> 'rawText', ''),
        nullif(t ->> 'unitType', ''),
        private.num(t ->> 'plannedStart'), private.num(t ->> 'plannedEnd'),
        private.num(t ->> 'doneStart'), private.num(t ->> 'doneEnd'),
        private.num(t ->> 'plannedAmount'), private.num(t ->> 'doneAmount'), v_kind,
        private.num(t ->> 'plannedMinutes')::integer, private.num(t ->> 'actualMinutes')::integer,
        nullif(t ->> 'studyMode', ''),
        case when t ->> 'status' in ('DONE', 'PARTIAL', 'NOT_DONE', 'UNKNOWN') then t ->> 'status' else 'UNKNOWN' end);
    end loop;

    for t in select * from jsonb_array_elements(coalesce(new.result -> 'habitChecks', '[]'::jsonb)) loop
      if nullif(t ->> 'key', '') is not null then
        insert into public.habit_checks (report_id, student_id, plan_date, habit_key, passed, note)
        values (new.id, new.student_id, new.plan_date, t ->> 'key',
          coalesce(case lower(t ->> 'passed') when 'true' then true when 'false' then false end, false),
          coalesce(t ->> 'note', ''))
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
revoke all on function private.ingest_report() from public, anon, authenticated;
revoke all on function private.refresh_material_progress(uuid) from public, anon, authenticated;

create or replace view public.material_progress with (security_invoker = true) as
select m.id, m.student_id, m.subject_id, s.name as subject_name, m.title, m.unit_type,
       m.total_units, m.target_date, m.current_position, m.units_done, m.started_on, m.last_studied_on, m.status,
       case when m.total_units > 0 then round(100.0 * coalesce(m.current_position, m.units_done, 0) / m.total_units, 1) end as progress_pct,
       round(coalesce(p.units_14d, 0) / 14.0, 2) as pace_per_day_14d,
       case when m.total_units > 0 and coalesce(p.units_14d, 0) > 0
            then current_date + ceil((m.total_units - coalesce(m.current_position, m.units_done, 0)) / (p.units_14d / 14.0))::int end as predicted_finish
  from public.materials m
  left join public.subjects s on s.id = m.subject_id
  left join lateral (
    select sum(private.task_units(t.done_start, t.done_end, t.done_amount)) as units_14d
      from public.study_tasks t
     where t.material_id = m.id and t.plan_date >= current_date - 13
  ) p on true;

create or replace view public.subject_weekly_stats with (security_invoker = true) as
select t.student_id, date_trunc('week', t.plan_date)::date as week_start, t.subject_id, s.name as subject_name,
       count(*) as tasks, count(*) filter (where t.status = 'DONE') as done_tasks,
       count(*) filter (where t.status = 'PARTIAL') as partial_tasks,
       coalesce(sum(t.planned_minutes), 0) as planned_minutes, coalesce(sum(t.actual_minutes), 0) as actual_minutes,
       coalesce(sum(private.task_units(t.done_start, t.done_end, t.done_amount)), 0) as units_done
  from public.study_tasks t
  left join public.subjects s on s.id = t.subject_id
 group by t.student_id, date_trunc('week', t.plan_date)::date, t.subject_id, s.name;

-- 엔진은 Gemini만 사용
update public.admin_settings
   set settings = (settings - 'mlxBaseUrl' - 'mlxModel') || '{"analysisEngine": "gemini"}'::jsonb
 where id = 1;
