-- Operational list endpoints now filter and paginate on the server.
-- These indexes keep admin finance, booking, class, session and notification queues stable at larger volumes.

create extension if not exists pg_trgm;

create index if not exists idx_trial_bookings_status_created
  on trial_bookings(status, created_at desc);

create index if not exists idx_trial_bookings_search_trgm
  on trial_bookings using gin ((lower(
    coalesce(student_name, '') || ' ' ||
    coalesce(parent_name, '') || ' ' ||
    coalesce(phone, '') || ' ' ||
    coalesce(email, '')
  )) gin_trgm_ops);

create index if not exists idx_tutoring_classes_status_updated
  on tutoring_classes(status, updated_at desc);

create index if not exists idx_tutoring_classes_title_trgm
  on tutoring_classes using gin ((lower(coalesce(title, ''))) gin_trgm_ops);

create index if not exists idx_class_sessions_status_scheduled
  on class_sessions(status, scheduled_start desc);

create index if not exists idx_payments_status_gateway_created
  on payments(status, gateway, created_at desc);

create index if not exists idx_payments_search_trgm
  on payments using gin ((lower(
    coalesce(description, '') || ' ' ||
    coalesce(gateway, '') || ' ' ||
    id::text
  )) gin_trgm_ops);

create index if not exists idx_payouts_status_created_desc
  on payouts(status, created_at desc);

create index if not exists idx_payouts_search_trgm
  on payouts using gin ((lower(
    coalesce(bank_name, '') || ' ' ||
    coalesce(bank_account, '') || ' ' ||
    coalesce(account_holder, '') || ' ' ||
    id::text
  )) gin_trgm_ops);

create index if not exists idx_payment_transactions_status_gateway_created
  on payment_transactions(status, gateway, created_at desc);

create index if not exists idx_payment_transactions_search_trgm
  on payment_transactions using gin ((lower(
    coalesce(gateway_order_id, '') || ' ' ||
    coalesce(gateway_transaction_id, '') || ' ' ||
    payment_id::text || ' ' ||
    id::text
  )) gin_trgm_ops);

create index if not exists idx_payment_webhook_events_gateway_processed_received
  on payment_webhook_events(gateway, processed, received_at desc);

create index if not exists idx_payment_webhook_events_error_received
  on payment_webhook_events(received_at desc)
  where processing_error is not null;

create index if not exists idx_payment_webhook_events_search_trgm
  on payment_webhook_events using gin ((lower(
    coalesce(event_id, '') || ' ' ||
    coalesce(gateway_order_id, '') || ' ' ||
    coalesce(gateway_transaction_id, '') || ' ' ||
    coalesce(payment_id::text, '') || ' ' ||
    id::text
  )) gin_trgm_ops);

create index if not exists idx_payment_refunds_status_created
  on payment_refunds(status, created_at desc);

create index if not exists idx_payment_refunds_search_trgm
  on payment_refunds using gin ((lower(
    coalesce(reason, '') || ' ' ||
    coalesce(gateway_refund_id, '') || ' ' ||
    payment_id::text || ' ' ||
    id::text
  )) gin_trgm_ops);

create index if not exists idx_notifications_user_created_visible
  on notifications(user_id, created_at desc)
  where deleted_at is null;

create index if not exists idx_notifications_status_type_created_visible
  on notifications(status, type, created_at desc)
  where deleted_at is null;

create index if not exists idx_notifications_search_trgm
  on notifications using gin ((lower(
    coalesce(title, '') || ' ' ||
    coalesce(message, '') || ' ' ||
    coalesce(entity_type, '') || ' ' ||
    user_id::text
  )) gin_trgm_ops)
  where deleted_at is null;

create index if not exists idx_booking_disputes_queue
  on booking_disputes(priority, sla_due_at, created_at desc);

create index if not exists idx_booking_disputes_filter
  on booking_disputes(status, priority, assigned_admin_id, sla_due_at);

create index if not exists idx_booking_disputes_search_trgm
  on booking_disputes using gin ((lower(
    coalesce(title, '') || ' ' ||
    coalesce(description, '') || ' ' ||
    coalesce(reason, '') || ' ' ||
    coalesce(resolution, '') || ' ' ||
    coalesce(resolution_note, '') || ' ' ||
    id::text || ' ' ||
    booking_id::text
  )) gin_trgm_ops);
