create table if not exists admin_risk_flags (
  id uuid primary key default gen_random_uuid(),
  entity_type varchar(40) not null check (entity_type in ('USER','TUTOR','STUDENT','PARENT')),
  entity_id uuid not null,
  level varchar(20) not null default 'MEDIUM' check (level in ('LOW','MEDIUM','HIGH','CRITICAL')),
  reason varchar(160) not null,
  note text,
  active boolean not null default true,
  created_by uuid references users(id),
  resolved_by uuid references users(id),
  resolved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_admin_risk_flags_entity
  on admin_risk_flags(entity_type, entity_id, active, level, created_at desc);

create index if not exists idx_admin_risk_flags_active
  on admin_risk_flags(active, level, created_at desc);
