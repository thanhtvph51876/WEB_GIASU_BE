-- Production-level admin case management fields for the existing booking_disputes table.
-- The legacy columns stay in place so older API consumers keep working.

alter table booking_disputes drop constraint if exists booking_disputes_status_check;

update booking_disputes set status = 'NEW' where status = 'OPEN';
update booking_disputes set status = 'INVESTIGATING' where status = 'IN_REVIEW';

alter table booking_disputes
  add constraint booking_disputes_status_check check (status in (
    'NEW',
    'ASSIGNED',
    'INVESTIGATING',
    'WAITING_PARENT',
    'WAITING_TUTOR',
    'PROPOSED_RESOLUTION',
    'RESOLVED',
    'CLOSED',
    'ESCALATED',
    'REJECTED',
    -- Compatibility for pre-V21 local databases and old clients.
    'OPEN',
    'IN_REVIEW'
  ));

alter table booking_disputes
  add column if not exists related_type varchar(40) not null default 'BOOKING',
  add column if not exists related_id uuid,
  add column if not exists reporter_type varchar(40) not null default 'ADMIN',
  add column if not exists reporter_id uuid references users(id),
  add column if not exists target_user_id uuid references users(id),
  add column if not exists title varchar(255),
  add column if not exists description text,
  add column if not exists priority varchar(20) not null default 'MEDIUM',
  add column if not exists risk_level varchar(20) not null default 'MEDIUM',
  add column if not exists sla_due_at timestamptz,
  add column if not exists assigned_admin_id uuid references users(id),
  add column if not exists resolution_type varchar(40),
  add column if not exists resolution_note text,
  add column if not exists internal_notes jsonb not null default '[]'::jsonb,
  add column if not exists evidence_files jsonb not null default '[]'::jsonb,
  add column if not exists closed_at timestamptz;

update booking_disputes
set related_id = coalesce(related_id, booking_id),
    reporter_id = coalesce(reporter_id, opened_by),
    title = coalesce(title, 'Khiếu nại booking ' || booking_id::text),
    description = coalesce(description, reason),
    sla_due_at = coalesce(sla_due_at, created_at + interval '48 hours'),
    resolution_note = coalesce(resolution_note, resolution),
    closed_at = case when status in ('CLOSED') then coalesce(closed_at, resolved_at) else closed_at end;

alter table booking_disputes
  drop constraint if exists booking_disputes_priority_check,
  add constraint booking_disputes_priority_check check (priority in ('LOW','MEDIUM','HIGH','CRITICAL'));

alter table booking_disputes
  drop constraint if exists booking_disputes_risk_level_check,
  add constraint booking_disputes_risk_level_check check (risk_level in ('LOW','MEDIUM','HIGH','CRITICAL'));

alter table booking_disputes
  drop constraint if exists booking_disputes_related_type_check,
  add constraint booking_disputes_related_type_check check (related_type in ('BOOKING','CLASS','SESSION','PAYMENT','PAYOUT','REVIEW','USER','TUTOR','OTHER'));

alter table booking_disputes
  drop constraint if exists booking_disputes_reporter_type_check,
  add constraint booking_disputes_reporter_type_check check (reporter_type in ('PARENT','STUDENT','TUTOR','ADMIN'));

alter table booking_disputes
  drop constraint if exists booking_disputes_resolution_type_check,
  add constraint booking_disputes_resolution_type_check check (resolution_type is null or resolution_type in (
    'NO_ACTION',
    'WARNING',
    'REFUND',
    'PARTIAL_REFUND',
    'COMPENSATION',
    'TUTOR_SUSPENDED',
    'BOOKING_CANCELLED',
    'CLASS_CANCELLED',
    'OTHER'
  ));

create table if not exists booking_dispute_timeline_events (
  id uuid primary key default gen_random_uuid(),
  dispute_id uuid not null references booking_disputes(id) on delete cascade,
  event_type varchar(60) not null,
  status_from varchar(40),
  status_to varchar(40),
  actor_user_id uuid references users(id),
  actor_role varchar(50),
  note text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists admin_internal_notes (
  id uuid primary key default gen_random_uuid(),
  entity_type varchar(40) not null,
  entity_id uuid not null,
  content text not null,
  visibility varchar(40) not null default 'INTERNAL_ONLY',
  created_by uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_booking_disputes_case_queue
  on booking_disputes(status, priority, risk_level, sla_due_at, created_at desc);

create index if not exists idx_booking_disputes_assigned_admin
  on booking_disputes(assigned_admin_id, status, sla_due_at);

create index if not exists idx_booking_dispute_timeline
  on booking_dispute_timeline_events(dispute_id, created_at desc);

create index if not exists idx_admin_internal_notes_entity
  on admin_internal_notes(entity_type, entity_id, created_at desc);
