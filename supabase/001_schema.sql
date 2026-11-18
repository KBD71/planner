-- 플래너 코치 AI · Supabase 스키마 (프로젝트: planner / mathtox 조직)
-- 관리자 1명 + 학생 여러 명. 학생은 자기 제출·자기에게 보낸 리포트만 볼 수 있음.

create schema if not exists private;

-- 1) 사용자 프로필 (auth.users 1:1)
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  role text not null default 'student' check (role in ('admin', 'student')),
  student_type text check (student_type in ('HIGH_SCHOOL', 'ELEMENTARY')),
  display_name text not null default '',
  bible_member_name text,
  bible_member_phone text,
  created_at timestamptz not null default now()
);

-- 관리자 여부 (정책에서 사용, Data API에 노출되지 않는 private 스키마)
create or replace function private.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.profiles p
    where p.id = (select auth.uid()) and p.role = 'admin'
  );
$$;
revoke all on function private.is_admin() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.is_admin() to authenticated;

-- 새 로그인 계정이 생기면 학생 프로필 자동 생성 (역할·이름은 관리자가 나중에 지정)
create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(split_part(new.email, '@', 1), ''))
  on conflict (id) do nothing;
  return new;
end;
$$;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- 2) 학생 제출 (플래너 사진)
create table public.submissions (
  id uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profiles (id) on delete cascade,
  plan_date date not null,
  photo_path text,                -- storage: planner-photos/<student_id>/<file>, 보내면 삭제 후 null
  student_memo text not null default '',
  status text not null default 'submitted'
    check (status in ('submitted', 'analyzing', 'analyzed', 'sent', 'failed')),
  error_message text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index submissions_student_id_idx on public.submissions (student_id, plan_date desc);
create index submissions_status_idx on public.submissions (status) where status in ('submitted', 'analyzing');

-- 3) 일일 리포트 (분석 결과: 텍스트 데이터만, 그림은 임시 저장 후 삭제)
create table public.reports (
  id uuid primary key default gen_random_uuid(),
  submission_id uuid unique references public.submissions (id) on delete set null,
  student_id uuid not null references public.profiles (id) on delete cascade,
  plan_date date not null,
  engine text not null default 'gemini',          -- gemini | mlx
  report_mode text not null default 'image' check (report_mode in ('image', 'report')),
  result jsonb not null default '{}'::jsonb,      -- 점수·수정안·시간표·수업요약·베껴쓰기 등
  image_path text,                                -- storage: planner-images/<student_id>/<file>, 보존기간 후 삭제
  status text not null default 'draft' check (status in ('draft', 'sent')),
  sent_at timestamptz,
  student_read_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index reports_student_id_idx on public.reports (student_id, plan_date desc);

-- 4) 주간 리포트
create table public.weekly_reports (
  id uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profiles (id) on delete cascade,
  week_start date not null,
  result jsonb not null default '{}'::jsonb,
  status text not null default 'draft' check (status in ('draft', 'sent')),
  sent_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (student_id, week_start)
);

-- 5) 관리자 설정 (모든 관리자 기기에서 동기화)
create table public.admin_settings (
  id smallint primary key default 1 check (id = 1),
  settings jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);
insert into public.admin_settings (id, settings) values (1, '{}'::jsonb);

-- updated_at 자동 갱신
create or replace function private.touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin new.updated_at = now(); return new; end; $$;
create trigger submissions_touch before update on public.submissions for each row execute function private.touch_updated_at();
create trigger reports_touch before update on public.reports for each row execute function private.touch_updated_at();
create trigger weekly_reports_touch before update on public.weekly_reports for each row execute function private.touch_updated_at();
create trigger admin_settings_touch before update on public.admin_settings for each row execute function private.touch_updated_at();

-- 권한 (anon에는 아무것도 주지 않음)
grant select, update on public.profiles to authenticated;
grant select, insert, update, delete on public.submissions to authenticated;
grant select, insert, update, delete on public.reports to authenticated;
grant select, insert, update, delete on public.weekly_reports to authenticated;
grant select, update on public.admin_settings to authenticated;

alter table public.profiles enable row level security;
alter table public.submissions enable row level security;
alter table public.reports enable row level security;
alter table public.weekly_reports enable row level security;
alter table public.admin_settings enable row level security;

-- profiles: 본인 조회, 관리자는 전체 조회·수정
create policy profiles_select on public.profiles for select to authenticated
  using (id = (select auth.uid()) or (select private.is_admin()));
create policy profiles_admin_update on public.profiles for update to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- submissions: 학생은 자기 것 조회·제출, 아직 처리 전이면 취소(삭제) 가능. 관리자는 전체.
create policy submissions_select on public.submissions for select to authenticated
  using (student_id = (select auth.uid()) or (select private.is_admin()));
create policy submissions_student_insert on public.submissions for insert to authenticated
  with check (student_id = (select auth.uid()) and status = 'submitted');
create policy submissions_student_delete on public.submissions for delete to authenticated
  using ((student_id = (select auth.uid()) and status = 'submitted') or (select private.is_admin()));
create policy submissions_admin_update on public.submissions for update to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy submissions_admin_insert on public.submissions for insert to authenticated
  with check ((select private.is_admin()));

-- reports: 학생은 자기에게 '보낸' 리포트만 조회, 읽음 표시만 갱신. 관리자는 전체.
create policy reports_select on public.reports for select to authenticated
  using ((student_id = (select auth.uid()) and status = 'sent') or (select private.is_admin()));
create policy reports_admin_all on public.reports for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- weekly_reports: 같은 규칙
create policy weekly_select on public.weekly_reports for select to authenticated
  using ((student_id = (select auth.uid()) and status = 'sent') or (select private.is_admin()));
create policy weekly_admin_all on public.weekly_reports for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- admin_settings: 관리자만
create policy admin_settings_admin on public.admin_settings for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- 학생 읽음 표시 (RLS 우회 없이 자기 리포트의 읽음 시각만 기록)
create or replace function public.mark_report_read(p_report_id uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.reports
     set student_read_at = coalesce(student_read_at, now())
   where id = p_report_id
     and student_id = (select auth.uid())
     and status = 'sent';
$$;
revoke all on function public.mark_report_read(uuid) from public, anon;
grant execute on function public.mark_report_read(uuid) to authenticated;

-- 6) 스토리지 버킷 (비공개)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('planner-photos', 'planner-photos', false, 10485760, array['image/jpeg', 'image/png', 'image/webp', 'image/heic']),
  ('planner-images', 'planner-images', false, 10485760, array['image/png', 'image/jpeg', 'image/webp']);

-- 사진: 학생은 자기 폴더(<uid>/...)에 올리기·보기·지우기, 관리자는 전체
create policy photos_student_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'planner-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy photos_select on storage.objects for select to authenticated
  using (bucket_id = 'planner-photos' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select private.is_admin())));
create policy photos_delete on storage.objects for delete to authenticated
  using (bucket_id = 'planner-photos' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select private.is_admin())));

-- 생성 그림: 관리자만 올리기·지우기, 학생은 자기 폴더 보기
create policy images_admin_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'planner-images' and (select private.is_admin()));
create policy images_admin_update on storage.objects for update to authenticated
  using (bucket_id = 'planner-images' and (select private.is_admin()));
create policy images_select on storage.objects for select to authenticated
  using (bucket_id = 'planner-images' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select private.is_admin())));
create policy images_admin_delete on storage.objects for delete to authenticated
  using (bucket_id = 'planner-images' and (select private.is_admin()));
