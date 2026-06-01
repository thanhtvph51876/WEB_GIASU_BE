alter table refresh_tokens
  add column if not exists revoked_at timestamptz,
  add column if not exists replaced_by_token_id uuid references refresh_tokens(id),
  add column if not exists ip_address varchar(80),
  add column if not exists user_agent text;

alter table password_reset_tokens
  add column if not exists used boolean not null default false,
  add column if not exists used_at timestamptz,
  add column if not exists ip_address varchar(80),
  add column if not exists user_agent text;

alter table email_verification_tokens
  add column if not exists used boolean not null default false,
  add column if not exists used_at timestamptz,
  add column if not exists ip_address varchar(80),
  add column if not exists user_agent text;

create table if not exists auth_email_outbox (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  email varchar(255) not null,
  type varchar(60) not null check (type in ('password_reset','email_verification')),
  subject varchar(255) not null,
  body text not null,
  action_url text,
  status varchar(30) not null default 'queued' check (status in ('queued','sent','failed')),
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  error text
);

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

create index if not exists idx_auth_email_outbox_status on auth_email_outbox(status, created_at);
create index if not exists idx_user_sessions_user on user_sessions(user_id, status);