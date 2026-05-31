create index if not exists idx_tutor_profiles_public_sort
  on tutor_profiles(status, rating_avg desc, created_at desc);

create index if not exists idx_tutor_subjects_tutor_subject_grade
  on tutor_subjects(tutor_id, subject_id, grade_level_id);

create index if not exists idx_tutor_locations_tutor_mode
  on tutor_locations(tutor_id, teaching_mode);

create index if not exists idx_tutor_availability_tutor_active_sort
  on tutor_availability(tutor_id, is_active, day_of_week, start_time);