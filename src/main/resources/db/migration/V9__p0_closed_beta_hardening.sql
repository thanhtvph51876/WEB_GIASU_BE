alter table uploaded_files
  add column if not exists deleted_at timestamptz;

create index if not exists idx_uploaded_files_not_deleted
  on uploaded_files(id)
  where deleted_at is null;

alter table learning_requests
  add column if not exists public_visible boolean not null default false;

update learning_requests
set public_visible = true
where requester_id is not null
  and status in ('new','consulting','matched','trial_scheduled');

create index if not exists idx_learning_requests_public_visible
  on learning_requests(public_visible, status, created_at desc);

alter table verification_agreements
  add column if not exists agreement_content_snapshot text;

update verification_agreements
set agreement_content_snapshot = agreement_content
where agreement_content_snapshot is null;
