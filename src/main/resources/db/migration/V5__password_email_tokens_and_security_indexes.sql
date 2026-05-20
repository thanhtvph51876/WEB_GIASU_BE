create table if not exists password_reset_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used boolean not null default false,
  created_at timestamptz not null default now(),
  used_at timestamptz
);

create index if not exists idx_password_reset_tokens_user on password_reset_tokens(user_id);
create index if not exists idx_password_reset_tokens_expires_at on password_reset_tokens(expires_at);
create index if not exists idx_password_reset_tokens_used on password_reset_tokens(used);

create table if not exists email_verification_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used boolean not null default false,
  created_at timestamptz not null default now(),
  used_at timestamptz
);

create index if not exists idx_email_verification_tokens_user on email_verification_tokens(user_id);
create index if not exists idx_email_verification_tokens_expires_at on email_verification_tokens(expires_at);
create index if not exists idx_email_verification_tokens_used on email_verification_tokens(used);

create index if not exists idx_payments_user_status on payments(user_id, status);
create index if not exists idx_payments_tutor_status on payments(tutor_id, status);
create index if not exists idx_uploaded_files_visibility on uploaded_files(visibility);
create index if not exists idx_conversation_members_user on conversation_members(user_id);
create index if not exists idx_messages_conversation_created on messages(conversation_id, created_at);
