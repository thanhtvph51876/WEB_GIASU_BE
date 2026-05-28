alter table tutor_profiles
  add column if not exists student_code varchar(120);
