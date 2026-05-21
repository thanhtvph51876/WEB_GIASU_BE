create table if not exists password_reset_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now(),
  ip_address varchar(80),
  user_agent text
);

create table if not exists email_verification_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now(),
  ip_address varchar(80),
  user_agent text
);

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

create table if not exists tutor_favorites (
  user_id uuid not null references users(id) on delete cascade,
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, tutor_id)
);

create index if not exists idx_password_reset_tokens_user on password_reset_tokens(user_id);
create index if not exists idx_password_reset_tokens_expires on password_reset_tokens(expires_at);
create index if not exists idx_email_verification_tokens_user on email_verification_tokens(user_id);
create index if not exists idx_email_verification_tokens_expires on email_verification_tokens(expires_at);
create index if not exists idx_auth_email_outbox_status on auth_email_outbox(status, created_at);
create index if not exists idx_tutor_favorites_tutor on tutor_favorites(tutor_id);
