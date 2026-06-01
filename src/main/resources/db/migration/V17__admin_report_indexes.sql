create index if not exists idx_learning_requests_created_at
  on learning_requests(created_at);

create index if not exists idx_learning_requests_subject_id
  on learning_requests(subject_id);

create index if not exists idx_learning_requests_learning_mode
  on learning_requests(learning_mode);

create index if not exists idx_tutoring_classes_status
  on tutoring_classes(status);

create index if not exists idx_reviews_rating_created
  on reviews(rating, created_at desc);
