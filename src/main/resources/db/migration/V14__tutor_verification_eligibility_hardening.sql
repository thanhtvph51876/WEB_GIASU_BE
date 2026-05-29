alter table tutor_profiles drop constraint if exists tutor_profiles_status_check;
alter table tutor_profiles
  add constraint tutor_profiles_status_check
  check (status in (
    'draft',
    'submitted',
    'pending',
    'pending_verification',
    'need_update',
    'needs_more_documents',
    'verified',
    'approved',
    'rejected',
    'suspended',
    'inactive'
  ));

alter table user_verifications drop constraint if exists user_verifications_status_check;
alter table user_verifications
  add constraint user_verifications_status_check
  check (status in ('draft','uploaded','pending_review','approved','rejected','need_more_info','expired'));

alter table user_verifications
  add column if not exists expires_at timestamptz,
  add column if not exists risk_breakdown jsonb not null default '[]'::jsonb;

create table if not exists tutor_commitments (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  commitment_version varchar(60) not null,
  accepted_terms_hash text not null,
  full_name_at_signing varchar(255) not null,
  identity_number_masked varchar(80),
  signed_at timestamptz not null default now(),
  signed_ip varchar(80),
  signed_user_agent text,
  status varchar(30) not null default 'signed' check (status in ('signed','revoked','superseded')),
  created_at timestamptz not null default now()
);

create index if not exists idx_tutor_commitments_tutor on tutor_commitments(tutor_id, status, signed_at desc);

create unique index if not exists ux_tutor_commitments_current
  on tutor_commitments(tutor_id, commitment_version)
  where status = 'signed';

insert into tutor_commitments(tutor_id, commitment_version, accepted_terms_hash, full_name_at_signing, signed_at, signed_ip, signed_user_agent, status)
select distinct on (tp.id)
  tp.id,
  va.agreement_version,
  va.agreement_content_hash,
  va.signer_full_name,
  va.signed_at,
  va.ip_address,
  va.user_agent,
  'signed'
from tutor_profiles tp
join user_verifications uv on uv.user_id = tp.user_id
join verification_agreements va on va.verification_id = uv.id
where uv.verification_type in ('tutor_identity','tutor_certificate')
order by tp.id, va.signed_at desc
on conflict do nothing;
