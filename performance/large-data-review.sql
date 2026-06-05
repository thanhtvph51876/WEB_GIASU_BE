-- Large-data review checklist for Gia Su Platform.
-- Run on staging/prod-like PostgreSQL after Flyway migrations, never on a busy production primary.
--
-- Suggested psql usage:
--   \timing on
--   begin;
--   set local statement_timeout = '30s';
--   set local work_mem = '64MB';
--   <run selected EXPLAIN ANALYZE statements>
--   rollback;

-- 1. Admin tutor queue: status + search must use status and trigram/search indexes.
explain (analyze, buffers)
select tp.id
from tutor_profiles tp
join users u on u.id = tp.user_id
where tp.status = 'pending'
  and (
    lower(coalesce(u.full_name, '')) like '%nguyen%'
    or lower(coalesce(u.email, '')) like '%nguyen%'
    or lower(coalesce(tp.university, '')) like '%nguyen%'
    or lower(coalesce(tp.major, '')) like '%nguyen%'
    or lower(coalesce(tp.student_code, '')) like '%nguyen%'
    or exists (
      select 1
      from tutor_subjects ts
      join subjects s on s.id = ts.subject_id
      where ts.tutor_id = tp.id and lower(coalesce(s.name, '')) like '%nguyen%'
    )
  )
order by tp.created_at desc
limit 25 offset 0;

-- 2. Admin booking queue: status + customer text search.
explain (analyze, buffers)
select tb.id
from trial_bookings tb
left join subjects s on s.id = tb.subject_id
left join grade_levels gl on gl.id = tb.grade_level_id
where tb.status = 'pending'
  and (
    lower(coalesce(tb.student_name, '')) like '%090%'
    or lower(coalesce(tb.parent_name, '')) like '%090%'
    or lower(coalesce(tb.phone, '')) like '%090%'
    or lower(coalesce(tb.email, '')) like '%090%'
    or lower(coalesce(s.name, '')) like '%090%'
    or lower(coalesce(gl.name, '')) like '%090%'
  )
order by tb.created_at desc
limit 25 offset 0;

-- 3. Admin classes and sessions: status lists should be bounded and index-backed.
explain (analyze, buffers)
select tc.id
from tutoring_classes tc
where tc.status = 'active'
order by tc.updated_at desc
limit 25 offset 0;

explain (analyze, buffers)
select cs.id
from class_sessions cs
where cs.status in ('upcoming', 'scheduled')
  and cs.scheduled_start >= now()
order by cs.scheduled_start asc
limit 25 offset 0;

-- 4. Admin finance logs: payment, transaction, webhook, refund.
explain (analyze, buffers)
select p.id
from payments p
left join users u on u.id = p.user_id
where p.status = 'paid'
  and p.gateway = 'bank_qr'
order by p.created_at desc
limit 25 offset 0;

explain (analyze, buffers)
select tx.id
from payment_transactions tx
where tx.status = 'success'
  and tx.gateway = 'bank_qr'
order by tx.created_at desc
limit 25 offset 0;

explain (analyze, buffers)
select wh.id
from payment_webhook_events wh
where wh.gateway = 'bank_qr'
  and wh.processed = false
order by wh.received_at desc
limit 25 offset 0;

explain (analyze, buffers)
select pr.id
from payment_refunds pr
where pr.status = 'pending'
order by pr.created_at desc
limit 25 offset 0;

-- 5. Messaging and notifications: per-user queues should stay page bounded.
explain (analyze, buffers)
select c.id
from conversations c
join conversation_members cm on cm.conversation_id = c.id
where cm.user_id = '00000000-0000-0000-0000-000000000000'
order by c.updated_at desc
limit 50 offset 0;

explain (analyze, buffers)
select *
from (
  select m.id, m.created_at
  from messages m
  where m.conversation_id = '00000000-0000-0000-0000-000000000000'
  order by m.created_at desc
  limit 50 offset 0
) recent_messages
order by created_at;

explain (analyze, buffers)
select n.id
from notifications n
where n.user_id = '00000000-0000-0000-0000-000000000000'
  and n.deleted_at is null
order by n.created_at desc
limit 50 offset 0;
