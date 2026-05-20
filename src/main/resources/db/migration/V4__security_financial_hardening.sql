create table if not exists refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  revoked boolean not null default false,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  replaced_by_token_id uuid references refresh_tokens(id),
  ip_address varchar(80),
  user_agent text
);

create index if not exists idx_refresh_tokens_user_id on refresh_tokens(user_id);
create index if not exists idx_refresh_tokens_expires_at on refresh_tokens(expires_at);
create index if not exists idx_refresh_tokens_revoked on refresh_tokens(revoked);

alter table uploaded_files
  add column if not exists original_file_name varchar(255),
  add column if not exists storage_path text,
  add column if not exists visibility varchar(30) not null default 'private',
  add column if not exists entity_type varchar(80),
  add column if not exists entity_id uuid,
  add column if not exists updated_at timestamptz not null default now();

update uploaded_files
set original_file_name = coalesce(original_file_name, file_name),
    storage_path = coalesce(storage_path, file_name),
    visibility = coalesce(visibility, 'private')
where original_file_name is null or storage_path is null;

alter table uploaded_files
  alter column original_file_name set not null,
  alter column storage_path set not null;

alter table uploaded_files drop constraint if exists uploaded_files_visibility_check;
alter table uploaded_files
  add constraint uploaded_files_visibility_check
  check (visibility in ('public','private'));

alter table tutor_documents
  add column if not exists file_id uuid references uploaded_files(id);

alter table tutor_earnings drop constraint if exists tutor_earnings_status_check;
alter table tutor_earnings
  add constraint tutor_earnings_status_check
  check (status in ('pending','available','payout_pending','paid','cancelled'));

create table if not exists payout_earning_items (
  id uuid primary key default gen_random_uuid(),
  payout_id uuid not null references payouts(id) on delete cascade,
  earning_id uuid not null references tutor_earnings(id),
  amount int not null check (amount > 0),
  created_at timestamptz not null default now(),
  unique(payout_id, earning_id)
);

create index if not exists idx_payout_earning_items_payout on payout_earning_items(payout_id);
create index if not exists idx_payout_earning_items_earning on payout_earning_items(earning_id);

drop index if exists idx_payment_webhook_gateway_event;
create unique index if not exists ux_payment_webhook_events_gateway_event
  on payment_webhook_events(gateway, event_id)
  where event_id is not null;

create unique index if not exists ux_reviews_session_reviewer
  on reviews(session_id, reviewer_id)
  where session_id is not null;

create index if not exists idx_learning_requests_requester on learning_requests(requester_id);
create index if not exists idx_learning_requests_assigned_tutor on learning_requests(assigned_tutor_id);
create index if not exists idx_trial_bookings_student on trial_bookings(student_id);
create index if not exists idx_trial_bookings_tutor on trial_bookings(tutor_id);
create index if not exists idx_trial_bookings_status on trial_bookings(status);
create index if not exists idx_class_sessions_class on class_sessions(class_id);
create index if not exists idx_class_sessions_student on class_sessions(student_id);
create index if not exists idx_class_sessions_tutor on class_sessions(tutor_id);
create index if not exists idx_class_sessions_scheduled_start on class_sessions(scheduled_start);
create index if not exists idx_class_sessions_status on class_sessions(status);
create index if not exists idx_tutor_earnings_tutor on tutor_earnings(tutor_id);
create index if not exists idx_tutor_earnings_status on tutor_earnings(status);
create index if not exists idx_uploaded_files_owner on uploaded_files(owner_id);
create index if not exists idx_uploaded_files_entity on uploaded_files(entity_type, entity_id);
