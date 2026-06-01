create materialized view if not exists admin_report_overview_mv as
select
  1 as id,
  (select count(*)::bigint from users) as total_users,
  (select count(*)::bigint from tutor_profiles) as total_tutors,
  (
    select count(*)::bigint
    from tutor_profiles
    where status in ('submitted','pending','pending_verification','needs_more_documents','need_update','verified')
  ) as pending_tutors,
  (
    select count(*)::bigint
    from users
    where role in ('student','parent')
  ) as total_students,
  (
    select count(*)::bigint
    from learning_requests
    where status = 'new'
  ) as new_requests,
  (
    select count(*)::bigint
    from tutoring_classes
    where status = 'active'
  ) as active_classes,
  (
    select count(*)::bigint
    from trial_bookings
    where status in ('pending','assigned','accepted')
  ) as pending_bookings,
  (
    select coalesce(sum(amount), 0)::bigint
    from payments
    where status in ('paid','completed')
  ) as total_revenue;

create unique index if not exists ux_admin_report_overview_mv
  on admin_report_overview_mv(id);

create materialized view if not exists admin_report_request_trends_mv as
select
  date_trunc('month', coalesce(created_at, updated_at, now()))::date as bucket_month,
  count(*)::bigint as count
from learning_requests
group by bucket_month;

create unique index if not exists ux_admin_report_request_trends_mv
  on admin_report_request_trends_mv(bucket_month);

create materialized view if not exists admin_report_conversion_funnel_mv as
select
  coalesce(status, 'unknown') as stage,
  count(*)::bigint as count
from learning_requests
group by coalesce(status, 'unknown');

create unique index if not exists ux_admin_report_conversion_funnel_mv
  on admin_report_conversion_funnel_mv(stage);

create materialized view if not exists admin_report_subject_distribution_mv as
select
  s.id as subject_id,
  coalesce(s.name, 'Chua phan loai') as subject,
  count(lr.id)::bigint as count
from subjects s
left join learning_requests lr on lr.subject_id = s.id
group by s.id, s.name;

create unique index if not exists ux_admin_report_subject_distribution_mv
  on admin_report_subject_distribution_mv(subject_id);

create materialized view if not exists admin_report_tutor_status_distribution_mv as
select
  coalesce(status, 'unknown') as status,
  count(*)::bigint as count
from tutor_profiles
group by coalesce(status, 'unknown');

create unique index if not exists ux_admin_report_tutor_status_distribution_mv
  on admin_report_tutor_status_distribution_mv(status);

create materialized view if not exists admin_report_teaching_mode_distribution_mv as
select
  coalesce(learning_mode, 'unknown') as mode,
  count(*)::bigint as count
from learning_requests
group by coalesce(learning_mode, 'unknown');

create unique index if not exists ux_admin_report_teaching_mode_distribution_mv
  on admin_report_teaching_mode_distribution_mv(mode);

create materialized view if not exists admin_report_revenue_mv as
select
  date_trunc('month', coalesce(created_at, paid_at, now()))::date as bucket_month,
  coalesce(sum(amount), 0)::bigint as revenue
from payments
where status in ('paid','completed')
group by bucket_month;

create unique index if not exists ux_admin_report_revenue_mv
  on admin_report_revenue_mv(bucket_month);

create materialized view if not exists admin_report_payment_status_distribution_mv as
select
  coalesce(status, 'unknown') as status,
  count(*)::bigint as count
from payments
group by coalesce(status, 'unknown');

create unique index if not exists ux_admin_report_payment_status_distribution_mv
  on admin_report_payment_status_distribution_mv(status);
