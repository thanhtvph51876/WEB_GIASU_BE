alter table uploaded_files
  add column if not exists sha256_hash text,
  add column if not exists purpose varchar(80) not null default 'general',
  add column if not exists risk_score int not null default 0;

create index if not exists idx_uploaded_files_hash on uploaded_files(sha256_hash);
create index if not exists idx_uploaded_files_purpose on uploaded_files(purpose);

create table if not exists user_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  refresh_token_id uuid references refresh_tokens(id) on delete set null,
  ip_address varchar(80),
  user_agent text,
  status varchar(30) not null default 'active' check (status in ('active','revoked','expired')),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz
);

create index if not exists idx_user_sessions_user on user_sessions(user_id, status);

create table if not exists user_verifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  verification_type varchar(40) not null check (verification_type in ('student_card','tutor_identity','tutor_certificate')),
  school_name varchar(255),
  student_code varchar(120),
  full_name_input varchar(255),
  school_email varchar(255),
  card_file_id uuid references uploaded_files(id),
  selfie_file_id uuid references uploaded_files(id),
  document_file_id uuid references uploaded_files(id),
  ocr_full_name varchar(255),
  ocr_student_code varchar(120),
  ocr_school varchar(255),
  ocr_confidence numeric(5,2),
  email_verified boolean not null default false,
  duplicate_file boolean not null default false,
  risk_score int not null default 0,
  status varchar(40) not null default 'draft'
    check (status in ('draft','pending_review','approved','rejected','need_more_info')),
  reject_reason text,
  reviewed_by uuid references users(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_user_verifications_user on user_verifications(user_id, status);
create index if not exists idx_user_verifications_status on user_verifications(status, created_at);
create index if not exists idx_user_verifications_type on user_verifications(verification_type);

create table if not exists verification_agreements (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  verification_id uuid not null references user_verifications(id) on delete cascade,
  agreement_version varchar(60) not null,
  agreement_title varchar(255) not null,
  agreement_content text not null,
  agreement_content_hash text not null,
  uploaded_file_hash text,
  signer_full_name varchar(255) not null,
  signer_email varchar(255),
  otp_verified boolean not null default false,
  ip_address varchar(80),
  user_agent text,
  signed_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create index if not exists idx_verification_agreements_user on verification_agreements(user_id);
create unique index if not exists ux_verification_agreements_verification
  on verification_agreements(verification_id);

alter table notifications
  add column if not exists deleted_at timestamptz,
  add column if not exists updated_at timestamptz not null default now();

create index if not exists idx_notifications_user_deleted
  on notifications(user_id, deleted_at, created_at desc);

alter table payments drop constraint if exists payments_session_unique_guard;
do $$
begin
  if not exists (
    select 1 from payments where session_id is not null group by session_id having count(*) > 1
  ) then
    execute 'create unique index if not exists ux_payments_session on payments(session_id) where session_id is not null';
  end if;
end $$;

do $$
begin
  if not exists (
    select 1 from tutor_earnings where session_id is not null group by session_id having count(*) > 1
  ) then
    execute 'create unique index if not exists ux_tutor_earnings_session on tutor_earnings(session_id) where session_id is not null';
  end if;
end $$;

alter table payouts drop constraint if exists payouts_status_check;
alter table payouts
  add constraint payouts_status_check
  check (status in ('pending','processing','approved','paid','completed','rejected'));

do $$
begin
  if not exists (
    select 1
    from payment_webhook_events
    where gateway_transaction_id is not null
    group by gateway, gateway_transaction_id
    having count(*) > 1
  ) then
    execute 'create unique index if not exists ux_payment_webhook_events_gateway_txn on payment_webhook_events(gateway, gateway_transaction_id) where gateway_transaction_id is not null';
  end if;
end $$;
