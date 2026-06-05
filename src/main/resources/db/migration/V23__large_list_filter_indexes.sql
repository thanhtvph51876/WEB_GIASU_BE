-- Server-side filters added for large public/admin lists need matching indexes.
-- Trigram indexes keep contains-search usable when queues grow beyond one page.

create extension if not exists pg_trgm;

create index if not exists idx_learning_requests_public_status_created
  on learning_requests(public_visible, status, created_at desc);

create index if not exists idx_learning_requests_public_location_trgm
  on learning_requests using gin ((lower(coalesce(province, '') || ' ' || coalesce(district, ''))) gin_trgm_ops)
  where public_visible = true;

create index if not exists idx_learning_requests_admin_search_trgm
  on learning_requests using gin ((lower(coalesce(request_code, '') || ' ' || coalesce(student_name, '') || ' ' || coalesce(parent_name, '') || ' ' || coalesce(phone, ''))) gin_trgm_ops);

create index if not exists idx_reviews_class_status_created
  on reviews(class_id, status, created_at desc);

create index if not exists idx_reviews_rating_status_created
  on reviews(rating, status, created_at desc);

create index if not exists idx_contact_requests_search_trgm
  on contact_requests using gin ((lower(coalesce(full_name, '') || ' ' || coalesce(email, '') || ' ' || coalesce(phone, '') || ' ' || coalesce(message, ''))) gin_trgm_ops);

create index if not exists idx_audit_logs_filter_created
  on audit_logs(actor_role, action, entity_type, created_at desc);

create index if not exists idx_audit_logs_search_trgm
  on audit_logs using gin ((lower(coalesce(description, '') || ' ' || coalesce(action, '') || ' ' || coalesce(entity_type, ''))) gin_trgm_ops);

create index if not exists idx_users_full_name_trgm
  on users using gin ((lower(coalesce(full_name, ''))) gin_trgm_ops);
