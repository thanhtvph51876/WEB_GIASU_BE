create extension if not exists pgcrypto;

create table users (
  id uuid primary key default gen_random_uuid(),
  email varchar(255) not null unique,
  password_hash varchar(255) not null,
  full_name varchar(255) not null,
  phone varchar(50),
  avatar_url text,
  role varchar(30) not null check (role in ('student','parent','tutor','admin')),
  status varchar(30) not null default 'active' check (status in ('active','inactive','suspended')),
  email_verified boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_login_at timestamptz
);

create table student_profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references users(id) on delete cascade,
  grade_level varchar(100),
  school varchar(255),
  learning_goals text,
  preferred_learning_mode varchar(20) default 'both' check (preferred_learning_mode in ('online','offline','both')),
  address text,
  province varchar(120),
  district varchar(120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table parent_profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references users(id) on delete cascade,
  relationship_to_student varchar(120),
  student_name varchar(255),
  student_grade varchar(120),
  address text,
  province varchar(120),
  district varchar(120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table tutor_profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references users(id) on delete cascade,
  headline varchar(255),
  bio text,
  gender varchar(30),
  date_of_birth date,
  education varchar(255),
  university varchar(255),
  major varchar(255),
  experience_years int not null default 0 check (experience_years >= 0),
  teaching_method text,
  hourly_rate_min int check (hourly_rate_min is null or hourly_rate_min >= 0),
  hourly_rate_max int check (hourly_rate_max is null or hourly_rate_max >= 0),
  rating_avg numeric(3,2) not null default 0,
  rating_count int not null default 0,
  total_sessions int not null default 0,
  total_students int not null default 0,
  response_rate numeric(5,2) not null default 0,
  status varchar(30) not null default 'draft' check (status in ('draft','pending','need_update','approved','rejected','suspended','inactive')),
  status_reason text,
  approved_at timestamptz,
  approved_by uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint tutor_hourly_rate_range check (hourly_rate_min is null or hourly_rate_max is null or hourly_rate_min <= hourly_rate_max)
);

create table subjects (
  id uuid primary key default gen_random_uuid(),
  name varchar(120) not null unique,
  slug varchar(160) not null unique,
  description text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table grade_levels (
  id uuid primary key default gen_random_uuid(),
  name varchar(120) not null unique,
  sort_order int not null default 0
);

create table tutor_subjects (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  subject_id uuid not null references subjects(id),
  grade_level_id uuid references grade_levels(id),
  created_at timestamptz not null default now(),
  unique(tutor_id, subject_id, grade_level_id)
);

create table tutor_locations (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  province varchar(120) not null,
  district varchar(120),
  teaching_mode varchar(20) not null default 'both' check (teaching_mode in ('online','offline','both')),
  created_at timestamptz not null default now()
);

create table tutor_availability (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  day_of_week int not null check (day_of_week between 0 and 6),
  start_time time not null,
  end_time time not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint tutor_availability_time check (end_time > start_time)
);

create table tutor_documents (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id) on delete cascade,
  document_type varchar(30) not null check (document_type in ('degree','certificate','id_card','identity','student_card','other')),
  file_name varchar(255) not null,
  file_url text not null,
  file_size bigint,
  mime_type varchar(120),
  status varchar(30) not null default 'pending' check (status in ('pending','approved','rejected')),
  review_note text,
  reviewed_by uuid references users(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table learning_requests (
  id uuid primary key default gen_random_uuid(),
  request_code varchar(50) not null unique,
  requester_id uuid references users(id),
  student_name varchar(255),
  parent_name varchar(255),
  phone varchar(50),
  email varchar(255),
  student_grade varchar(120),
  subject_id uuid not null references subjects(id),
  grade_level_id uuid references grade_levels(id),
  goal varchar(50) default 'improve_grades',
  learning_mode varchar(20) not null default 'both' check (learning_mode in ('online','offline','both')),
  province varchar(120),
  district varchar(120),
  budget_min int check (budget_min is null or budget_min >= 0),
  budget_max int check (budget_max is null or budget_max >= 0),
  preferred_gender varchar(30),
  preferred_schedule text,
  learning_goal text,
  note text,
  status varchar(30) not null default 'new' check (status in ('new','consulting','matched','trial_scheduled','trial_completed','active','rematch','cancelled','completed')),
  assigned_tutor_id uuid references tutor_profiles(id),
  assigned_by uuid references users(id),
  assigned_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint learning_request_budget_range check (budget_min is null or budget_max is null or budget_min <= budget_max)
);

create table trial_bookings (
  id uuid primary key default gen_random_uuid(),
  learning_request_id uuid references learning_requests(id),
  student_id uuid references users(id),
  tutor_id uuid not null references tutor_profiles(id),
  subject_id uuid not null references subjects(id),
  grade_level_id uuid references grade_levels(id),
  student_name varchar(255),
  parent_name varchar(255),
  phone varchar(50),
  email varchar(255),
  preferred_time text,
  learning_mode varchar(20) not null default 'online' check (learning_mode in ('online','offline')),
  scheduled_start timestamptz,
  scheduled_end timestamptz,
  location text,
  meeting_url text,
  goal text,
  status varchar(30) not null default 'pending' check (status in ('pending','assigned','accepted','rejected','scheduled','completed','no_show_student','no_show_tutor','converted','cancelled','expired')),
  tutor_response_note text,
  result_note text,
  converted_class_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint booking_schedule_range check (scheduled_start is null or scheduled_end is null or scheduled_end > scheduled_start)
);

create table tutoring_classes (
  id uuid primary key default gen_random_uuid(),
  learning_request_id uuid references learning_requests(id),
  trial_booking_id uuid unique references trial_bookings(id),
  student_id uuid not null references users(id),
  tutor_id uuid not null references tutor_profiles(id),
  subject_id uuid not null references subjects(id),
  grade_level_id uuid references grade_levels(id),
  title varchar(255) not null,
  learning_mode varchar(20) not null check (learning_mode in ('online','offline')),
  location text,
  meeting_url text,
  hourly_rate int check (hourly_rate is null or hourly_rate >= 0),
  sessions_per_week int check (sessions_per_week is null or sessions_per_week > 0),
  start_date date,
  end_date date,
  status varchar(30) not null default 'active' check (status in ('trial','active','paused','completed','cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table trial_bookings add constraint fk_trial_booking_converted_class foreign key (converted_class_id) references tutoring_classes(id);

create table class_sessions (
  id uuid primary key default gen_random_uuid(),
  class_id uuid not null references tutoring_classes(id) on delete cascade,
  student_id uuid not null references users(id),
  tutor_id uuid not null references tutor_profiles(id),
  scheduled_start timestamptz not null,
  scheduled_end timestamptz not null,
  actual_start timestamptz,
  actual_end timestamptz,
  status varchar(30) not null default 'scheduled' check (status in ('scheduled','upcoming','completed','cancelled','student_absent','tutor_absent')),
  tutor_note text,
  student_note text,
  completed_by uuid references users(id),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint class_session_schedule_range check (scheduled_end > scheduled_start)
);

create table reviews (
  id uuid primary key default gen_random_uuid(),
  session_id uuid unique references class_sessions(id),
  class_id uuid references tutoring_classes(id),
  tutor_id uuid not null references tutor_profiles(id),
  reviewer_id uuid not null references users(id),
  rating int not null check (rating between 1 and 5),
  comment text,
  status varchar(30) not null default 'visible' check (status in ('visible','hidden','flagged')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table conversations (
  id uuid primary key default gen_random_uuid(),
  title varchar(255),
  type varchar(30) not null default 'direct' check (type in ('direct','class','booking','support')),
  class_id uuid references tutoring_classes(id),
  booking_id uuid references trial_bookings(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table conversation_members (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  last_read_at timestamptz,
  unique(conversation_id, user_id)
);

create table messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  sender_id uuid references users(id),
  content text not null,
  message_type varchar(30) not null default 'text' check (message_type in ('text','system')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  title varchar(255) not null,
  message text not null,
  type varchar(30) not null default 'info' check (type in ('info','success','warning','error')),
  status varchar(30) not null default 'unread' check (status in ('unread','read')),
  action_url text,
  entity_type varchar(80),
  entity_id uuid,
  created_at timestamptz not null default now(),
  read_at timestamptz
);

create table payments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  tutor_id uuid references tutor_profiles(id),
  class_id uuid references tutoring_classes(id),
  session_id uuid references class_sessions(id),
  amount int not null check (amount >= 0),
  currency varchar(10) not null default 'VND',
  description text,
  status varchar(30) not null default 'pending' check (status in ('pending','paid','completed','failed','refunded','partially_refunded','cancelled')),
  paid_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table tutor_earnings (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id),
  session_id uuid references class_sessions(id),
  payment_id uuid references payments(id),
  gross_amount int not null check (gross_amount >= 0),
  platform_fee int not null default 0 check (platform_fee >= 0),
  net_amount int not null check (net_amount >= 0),
  status varchar(30) not null default 'pending' check (status in ('pending','available','paid','cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table payouts (
  id uuid primary key default gen_random_uuid(),
  tutor_id uuid not null references tutor_profiles(id),
  amount int not null check (amount > 0),
  status varchar(30) not null default 'pending' check (status in ('pending','processing','completed','rejected')),
  bank_name varchar(120),
  bank_account varchar(120),
  account_holder varchar(255),
  admin_note text,
  processed_by uuid references users(id),
  processed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table audit_logs (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid references users(id),
  actor_role varchar(50),
  action varchar(120) not null,
  entity_type varchar(120) not null,
  entity_id uuid,
  description text not null,
  metadata jsonb,
  ip_address varchar(80),
  user_agent text,
  created_at timestamptz not null default now()
);

create table system_settings (
  id uuid primary key default gen_random_uuid(),
  key varchar(120) not null unique,
  value jsonb not null,
  description text,
  updated_by uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table contact_requests (
  id uuid primary key default gen_random_uuid(),
  full_name varchar(255) not null,
  email varchar(255),
  phone varchar(50),
  message text not null,
  status varchar(30) not null default 'new' check (status in ('new','contacted','resolved','ignored')),
  assigned_to uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table uploaded_files (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid references users(id),
  file_name varchar(255) not null,
  file_url text not null,
  file_size bigint,
  mime_type varchar(120),
  created_at timestamptz not null default now()
);

create index idx_users_role on users(role);
create index idx_tutor_profiles_status on tutor_profiles(status);
create index idx_learning_requests_status on learning_requests(status);
create index idx_trial_bookings_tutor_status on trial_bookings(tutor_id, status);
create index idx_tutoring_classes_tutor_status on tutoring_classes(tutor_id, status);
create index idx_class_sessions_tutor_status on class_sessions(tutor_id, status);
create index idx_notifications_user_status on notifications(user_id, status);
create index idx_audit_logs_created_at on audit_logs(created_at desc);
