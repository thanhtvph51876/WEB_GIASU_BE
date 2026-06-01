-- Store clear admin ownership for contact processing and keep admin-heavy
-- contact views on indexed scans when the inbox grows.

alter table contact_requests
  add column if not exists handled_at timestamptz,
  add column if not exists handler_note text;

create index if not exists idx_contact_requests_assigned_status_created
  on contact_requests(assigned_to, status, created_at desc);

create index if not exists idx_contact_requests_handled_at
  on contact_requests(handled_at desc)
  where handled_at is not null;

-- Bulk admin notifications resolve recipients by role/status, then insert in
-- batches. This index supports that recipient lookup without scanning users.
create index if not exists idx_users_active_role_created
  on users(status, role, created_at desc)
  where status = 'active';
