-- Admin/data-heavy screens should use ordered index scans instead of table scans
-- when queues, lists, badges and reconciliation widgets grow large.

create index if not exists idx_users_created_at_desc
  on users(created_at desc);

create index if not exists idx_users_role_status_created
  on users(role, status, created_at desc);

create index if not exists idx_student_profiles_created_at_desc
  on student_profiles(created_at desc);

create index if not exists idx_parent_profiles_created_at_desc
  on parent_profiles(created_at desc);

create index if not exists idx_tutor_profiles_status_created
  on tutor_profiles(status, created_at desc);

create index if not exists idx_tutor_profiles_admin_quality
  on tutor_profiles(status, rating_avg, response_rate, total_sessions desc);

create index if not exists idx_tutor_documents_tutor_created
  on tutor_documents(tutor_id, created_at desc);

create index if not exists idx_learning_requests_status_created_desc
  on learning_requests(status, created_at desc);

create index if not exists idx_learning_requests_assignee_status_created
  on learning_requests(assigned_tutor_id, status, created_at desc);

create index if not exists idx_learning_requests_open_created
  on learning_requests(created_at)
  where status not in ('cancelled','completed','closed','converted_to_class');

create index if not exists idx_trial_bookings_status_created_desc
  on trial_bookings(status, created_at desc);

create index if not exists idx_trial_bookings_status_updated_desc
  on trial_bookings(status, updated_at desc);

create index if not exists idx_trial_bookings_scheduled_upcoming
  on trial_bookings(scheduled_start)
  where status = 'scheduled';

create index if not exists idx_trial_bookings_request_tutor_created
  on trial_bookings(learning_request_id, tutor_id, created_at desc);

create index if not exists idx_tutoring_classes_updated_desc
  on tutoring_classes(updated_at desc);

create index if not exists idx_tutoring_classes_student_status
  on tutoring_classes(student_id, status);

create index if not exists idx_class_sessions_class_status
  on class_sessions(class_id, status);

create index if not exists idx_class_sessions_student_status
  on class_sessions(student_id, status);

create index if not exists idx_reviews_created_desc
  on reviews(created_at desc);

create index if not exists idx_reviews_tutor_status_created
  on reviews(tutor_id, status, created_at desc);

create index if not exists idx_reviews_reviewer_created
  on reviews(reviewer_id, created_at desc);

create index if not exists idx_payments_created_desc
  on payments(created_at desc);

create index if not exists idx_payments_user_created
  on payments(user_id, created_at desc);

create index if not exists idx_payments_tutor_created
  on payments(tutor_id, created_at desc);

create index if not exists idx_payment_transactions_created_desc
  on payment_transactions(created_at desc);

create index if not exists idx_payment_refunds_created_desc
  on payment_refunds(created_at desc);

create index if not exists idx_payouts_tutor_created
  on payouts(tutor_id, created_at desc);

create index if not exists idx_tutor_earnings_tutor_status_created
  on tutor_earnings(tutor_id, status, created_at);

create index if not exists idx_user_verifications_admin_risk
  on user_verifications(status, risk_score desc, created_at);

create index if not exists idx_booking_no_show_created_desc
  on booking_no_show_records(created_at desc);

create index if not exists idx_booking_disputes_created_desc
  on booking_disputes(created_at desc);

create index if not exists idx_contact_requests_created_desc
  on contact_requests(created_at desc);

create index if not exists idx_contact_requests_status_created
  on contact_requests(status, created_at desc);

create index if not exists idx_conversations_updated_desc
  on conversations(updated_at desc);

create index if not exists idx_conversation_members_conversation_joined
  on conversation_members(conversation_id, joined_at);

create index if not exists idx_notifications_user_created_not_deleted
  on notifications(user_id, created_at desc)
  where deleted_at is null;

create index if not exists idx_notifications_created_not_deleted
  on notifications(created_at desc)
  where deleted_at is null;
