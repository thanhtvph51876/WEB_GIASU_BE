create extension if not exists unaccent;

alter table system_settings
    add column if not exists value_type varchar(30) not null default 'json',
    add column if not exists is_sensitive boolean not null default false;

alter table audit_logs
    add column if not exists old_value jsonb,
    add column if not exists new_value jsonb;

create table if not exists locations (
                                         id uuid primary key default gen_random_uuid(),
    code varchar(80) not null unique,
    name varchar(255) not null,
    type varchar(40) not null check (type in ('PROVINCE','WARD','SPECIAL_ZONE')),
    parent_id uuid references locations(id),
    full_path text not null,
    is_active boolean not null default true,
    effective_from date not null default current_date,
    effective_to date,
    source varchar(120),
    version varchar(60) not null default 'v1',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists subject_categories (
                                                  id uuid primary key default gen_random_uuid(),
    name varchar(255) not null,
    slug varchar(255) not null unique,
    parent_id uuid references subject_categories(id),
    sort_order int not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

alter table subjects
    add column if not exists category_id uuid references subject_categories(id),
    add column if not exists code varchar(80),
    add column if not exists normalized_name varchar(255),
    add column if not exists is_academic_subject boolean not null default true,
    add column if not exists is_language boolean not null default false,
    add column if not exists is_test_prep boolean not null default false,
    add column if not exists is_skill boolean not null default false,
    add column if not exists is_active boolean not null default true;

update subjects
set code = coalesce(code, upper(regexp_replace(slug, '[^a-zA-Z0-9]+', '_', 'g'))),
    normalized_name = coalesce(normalized_name, lower(unaccent(name)))
where code is null or normalized_name is null;

create unique index if not exists ux_subjects_code on subjects(code) where code is not null;

create table if not exists subject_aliases (
                                               id uuid primary key default gen_random_uuid(),
    subject_id uuid not null references subjects(id) on delete cascade,
    alias varchar(255) not null,
    normalized_alias varchar(255) not null,
    created_at timestamptz not null default now(),
    unique(subject_id, normalized_alias)
    );

create table if not exists education_levels (
                                                id uuid primary key default gen_random_uuid(),
    code varchar(80) not null unique,
    name varchar(255) not null,
    description text,
    sort_order int not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists grades (
                                      id uuid primary key default gen_random_uuid(),
    education_level_id uuid references education_levels(id),
    code varchar(80) not null unique,
    name varchar(255) not null,
    sort_order int not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists languages (
                                         id uuid primary key default gen_random_uuid(),
    code varchar(30) not null unique,
    name varchar(255) not null,
    native_name varchar(255),
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists certificates (
                                            id uuid primary key default gen_random_uuid(),
    code varchar(80) not null unique,
    name varchar(255) not null,
    language_id uuid references languages(id),
    description text,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists teaching_modes (
                                              id uuid primary key default gen_random_uuid(),
    code varchar(40) not null unique,
    name varchar(255) not null,
    description text,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists cancellation_policies (
                                                     id uuid primary key default gen_random_uuid(),
    code varchar(80) not null unique,
    name varchar(255) not null,
    description text,
    applies_to varchar(60) not null,
    free_cancel_before_hours int not null default 24,
    penalty_type varchar(40) not null default 'WARNING',
    penalty_value numeric(12,2) not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists households (
                                          id uuid primary key default gen_random_uuid(),
    name varchar(255) not null,
    owner_parent_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

alter table student_profiles
    alter column user_id drop not null,
  add column if not exists household_id uuid references households(id) on delete set null,
  add column if not exists full_name varchar(255),
  add column if not exists date_of_birth date,
  add column if not exists gender varchar(40),
  add column if not exists grade_id uuid references grades(id),
  add column if not exists school_name varchar(255),
  add column if not exists learning_goal text,
  add column if not exists note text;

update student_profiles sp
set full_name = coalesce(sp.full_name, u.full_name),
    school_name = coalesce(sp.school_name, sp.school),
    learning_goal = coalesce(sp.learning_goal, sp.learning_goals)
    from users u
where sp.user_id = u.id;

create table if not exists guardian_student_links (
                                                      id uuid primary key default gen_random_uuid(),
    guardian_user_id uuid not null references users(id) on delete cascade,
    student_profile_id uuid not null references student_profiles(id) on delete cascade,
    relationship varchar(40) not null check (relationship in ('FATHER','MOTHER','SIBLING','GUARDIAN','OTHER')),
    can_pay boolean not null default true,
    can_book boolean not null default true,
    can_message_tutor boolean not null default true,
    can_view_report boolean not null default true,
    can_manage_profile boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(guardian_user_id, student_profile_id)
    );

alter table learning_requests
    add column if not exists student_profile_id uuid references student_profiles(id),
drop constraint if exists learning_requests_status_check;
alter table learning_requests
    add constraint learning_requests_status_check check (status in (
                                                                    'new','consulting','matched','trial_scheduled','trial_completed','active','rematch','cancelled','completed',
                                                                    'draft','submitted','matching','waiting_tutor_proposal','proposal_received','waiting_parent_confirmation',
                                                                    'converted_to_class','expired','closed'
        ));

create table if not exists tutor_proposals (
                                               id uuid primary key default gen_random_uuid(),
    learning_request_id uuid not null references learning_requests(id) on delete cascade,
    tutor_id uuid not null references tutor_profiles(id) on delete cascade,
    proposed_fee int not null check (proposed_fee >= 0),
    fee_unit varchar(40) not null check (fee_unit in ('PER_SESSION','PER_HOUR','PER_MONTH')),
    teaching_mode varchar(40) not null,
    available_slots jsonb not null default '[]'::jsonb,
    proposed_start_date date,
    teaching_plan text,
    relevant_experience text,
    expected_outcome text,
    message_to_parent text,
    trial_session_type varchar(60),
    trial_fee int check (trial_fee is null or trial_fee >= 0),
    status varchar(40) not null default 'SENT' check (status in ('SENT','VIEWED','SHORTLISTED','ACCEPTED','REJECTED','EXPIRED','WITHDRAWN')),
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(learning_request_id, tutor_id)
    );

create table if not exists tutor_availability_slots (
                                                        id uuid primary key default gen_random_uuid(),
    tutor_id uuid not null references tutor_profiles(id) on delete cascade,
    start_time timestamptz not null,
    end_time timestamptz not null,
    status varchar(40) not null default 'AVAILABLE' check (status in ('AVAILABLE','HELD','BOOKED','BLOCKED','DAY_OFF')),
    teaching_mode varchar(40),
    location_id uuid references locations(id),
    note text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint tutor_availability_slots_time check (end_time > start_time)
    );

create table if not exists tutor_teaching_packages (
                                                       id uuid primary key default gen_random_uuid(),
    tutor_id uuid not null references tutor_profiles(id) on delete cascade,
    name varchar(255) not null,
    description text,
    package_type varchar(80) not null,
    session_count int,
    fee int not null default 0,
    fee_unit varchar(40) not null default 'PER_SESSION',
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

alter table trial_bookings
    add column if not exists proposal_id uuid references tutor_proposals(id),
    add column if not exists student_profile_id uuid references student_profiles(id),
    add column if not exists parent_confirmed_at timestamptz,
    add column if not exists tutor_confirmed_at timestamptz,
    add column if not exists cancellation_policy_id uuid references cancellation_policies(id),
    add column if not exists cancelled_by uuid references users(id),
    add column if not exists cancellation_reason text,
    add column if not exists reschedule_note text,
    add column if not exists dispute_status varchar(40),
drop constraint if exists trial_bookings_status_check;
alter table trial_bookings
    add constraint trial_bookings_status_check check (status in (
                                                                 'pending','assigned','accepted','rejected','scheduled','completed','no_show_student','no_show_tutor','converted','cancelled','expired',
                                                                 'requested','tutor_confirmed','parent_confirmed','reschedule_requested','cancelled_by_parent','cancelled_by_tutor',
                                                                 'no_show_parent','converted_to_class','rejected_after_trial'
        ));

create table if not exists booking_cancellation_records (
                                                            id uuid primary key default gen_random_uuid(),
    booking_id uuid not null references trial_bookings(id) on delete cascade,
    actor_user_id uuid references users(id),
    actor_role varchar(50),
    old_status varchar(40),
    new_status varchar(40),
    reason text,
    note text,
    cancelled_before_hours int,
    penalty_applied boolean not null default false,
    penalty_type varchar(40),
    penalty_value numeric(12,2),
    created_at timestamptz not null default now()
    );

create table if not exists booking_no_show_records (
                                                       id uuid primary key default gen_random_uuid(),
    booking_id uuid not null references trial_bookings(id) on delete cascade,
    actor_user_id uuid references users(id),
    actor_role varchar(50),
    no_show_party varchar(40) not null check (no_show_party in ('PARENT','STUDENT','TUTOR')),
    reason text,
    note text,
    created_at timestamptz not null default now()
    );

create table if not exists booking_disputes (
                                                id uuid primary key default gen_random_uuid(),
    booking_id uuid not null references trial_bookings(id) on delete cascade,
    opened_by uuid references users(id),
    status varchar(40) not null default 'OPEN' check (status in ('OPEN','IN_REVIEW','RESOLVED','REJECTED')),
    reason text,
    resolution text,
    resolved_by uuid references users(id),
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists learning_request_status_history (
                                                               id uuid primary key default gen_random_uuid(),
    learning_request_id uuid not null references learning_requests(id) on delete cascade,
    actor_user_id uuid references users(id),
    actor_role varchar(50),
    old_status varchar(40),
    new_status varchar(40) not null,
    reason text,
    note text,
    created_at timestamptz not null default now()
    );

create table if not exists tutor_proposal_status_history (
                                                             id uuid primary key default gen_random_uuid(),
    tutor_proposal_id uuid not null references tutor_proposals(id) on delete cascade,
    actor_user_id uuid references users(id),
    actor_role varchar(50),
    old_status varchar(40),
    new_status varchar(40) not null,
    reason text,
    note text,
    created_at timestamptz not null default now()
    );

create table if not exists trial_booking_status_history (
                                                            id uuid primary key default gen_random_uuid(),
    trial_booking_id uuid not null references trial_bookings(id) on delete cascade,
    actor_user_id uuid references users(id),
    actor_role varchar(50),
    old_status varchar(40),
    new_status varchar(40) not null,
    reason text,
    note text,
    created_at timestamptz not null default now()
    );

create table if not exists tutor_performance_snapshots (
                                                           id uuid primary key default gen_random_uuid(),
    tutor_id uuid not null references tutor_profiles(id) on delete cascade,
    period_start date not null,
    period_end date not null,
    response_rate numeric(6,2) not null default 0,
    average_response_time_minutes int not null default 0,
    proposal_acceptance_rate numeric(6,2) not null default 0,
    trial_to_class_conversion_rate numeric(6,2) not null default 0,
    cancellation_rate numeric(6,2) not null default 0,
    no_show_rate numeric(6,2) not null default 0,
    average_rating numeric(3,2) not null default 0,
    total_completed_sessions int not null default 0,
    monthly_earnings int not null default 0,
    payout_pending int not null default 0,
    repeat_parent_count int not null default 0,
    created_at timestamptz not null default now(),
    unique(tutor_id, period_start, period_end)
    );

create table if not exists class_session_reports (
                                                     id uuid primary key default gen_random_uuid(),
    session_id uuid not null unique references class_sessions(id) on delete cascade,
    tutor_id uuid not null references tutor_profiles(id) on delete cascade,
    content_summary text,
    student_comment text,
    homework text,
    next_session_plan text,
    progress_level varchar(40),
    sent_to_parent_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists assignments (
                                           id uuid primary key default gen_random_uuid(),
    class_id uuid not null references tutoring_classes(id) on delete cascade,
    student_id uuid references users(id),
    student_profile_id uuid references student_profiles(id),
    tutor_id uuid not null references tutor_profiles(id),
    title varchar(255) not null,
    description text,
    due_at timestamptz,
    status varchar(40) not null default 'OPEN' check (status in ('OPEN','SUBMITTED','REVIEWED','CANCELLED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists learning_materials (
                                                  id uuid primary key default gen_random_uuid(),
    class_id uuid not null references tutoring_classes(id) on delete cascade,
    student_id uuid references users(id),
    student_profile_id uuid references student_profiles(id),
    tutor_id uuid not null references tutor_profiles(id),
    title varchar(255) not null,
    description text,
    file_id uuid references uploaded_files(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );

create table if not exists student_check_ins (
                                                 id uuid primary key default gen_random_uuid(),
    session_id uuid not null references class_sessions(id) on delete cascade,
    student_id uuid not null references users(id) on delete cascade,
    checked_in_at timestamptz not null default now(),
    note text,
    unique(session_id, student_id)
    );

create index if not exists idx_locations_type_parent_active on locations(type, parent_id, is_active);
create index if not exists idx_subjects_category_active on subjects(category_id, is_active);
create index if not exists idx_subject_aliases_normalized on subject_aliases(normalized_alias);
create index if not exists idx_student_profiles_household on student_profiles(household_id);
create index if not exists idx_guardian_student_links_guardian_student on guardian_student_links(guardian_user_id, student_profile_id);
create index if not exists idx_tutor_proposals_tutor_status on tutor_proposals(tutor_id, status);
create index if not exists idx_tutor_proposals_request_status on tutor_proposals(learning_request_id, status);
create index if not exists idx_tutor_availability_slots_tutor_time on tutor_availability_slots(tutor_id, start_time, end_time);
create index if not exists idx_trial_bookings_status_scheduled on trial_bookings(status, scheduled_start);
create index if not exists idx_audit_logs_entity on audit_logs(entity_type, entity_id);
create index if not exists idx_payments_status_created on payments(status, created_at);
create index if not exists idx_payouts_status_created on payouts(status, created_at);
create index if not exists idx_booking_disputes_status on booking_disputes(status, created_at);
create index if not exists idx_learning_requests_student_profile on learning_requests(student_profile_id);

insert into education_levels(code, name, description, sort_order) values
                                                                      ('MAM_NON','Mầm non','Giáo dục mầm non',1),
                                                                      ('TIEU_HOC','Tiểu học','Lớp 1 đến lớp 5',2),
                                                                      ('THCS','Trung học cơ sở','Lớp 6 đến lớp 9',3),
                                                                      ('THPT','Trung học phổ thông','Lớp 10 đến lớp 12',4),
                                                                      ('DAI_HOC','Đại học','Bậc đại học và người đi làm',5),
                                                                      ('LUYEN_THI','Luyện thi','Các gói luyện thi',6)
    on conflict(code) do update set name = excluded.name, description = excluded.description, sort_order = excluded.sort_order, is_active = true;

with levels as (select id, code from education_levels)
insert into grades(education_level_id, code, name, sort_order) values
  ((select id from levels where code='MAM_NON'),'PRE_SCHOOL','Mầm non',0),
  ((select id from levels where code='TIEU_HOC'),'GRADE_1','Lớp 1',1),
  ((select id from levels where code='TIEU_HOC'),'GRADE_2','Lớp 2',2),
  ((select id from levels where code='TIEU_HOC'),'GRADE_3','Lớp 3',3),
  ((select id from levels where code='TIEU_HOC'),'GRADE_4','Lớp 4',4),
  ((select id from levels where code='TIEU_HOC'),'GRADE_5','Lớp 5',5),
  ((select id from levels where code='THCS'),'GRADE_6','Lớp 6',6),
  ((select id from levels where code='THCS'),'GRADE_7','Lớp 7',7),
  ((select id from levels where code='THCS'),'GRADE_8','Lớp 8',8),
  ((select id from levels where code='THCS'),'GRADE_9','Lớp 9',9),
  ((select id from levels where code='THPT'),'GRADE_10','Lớp 10',10),
  ((select id from levels where code='THPT'),'GRADE_11','Lớp 11',11),
  ((select id from levels where code='THPT'),'GRADE_12','Lớp 12',12),
  ((select id from levels where code='LUYEN_THI'),'EXAM_GRADE_6','Thi vào lớp 6',20),
  ((select id from levels where code='LUYEN_THI'),'EXAM_GRADE_10','Thi vào lớp 10',21),
  ((select id from levels where code='LUYEN_THI'),'EXAM_THPT','Thi THPT Quốc gia',22),
  ((select id from levels where code='LUYEN_THI'),'DGNL','Đánh giá năng lực',23),
  ((select id from levels where code='LUYEN_THI'),'DGTD','Đánh giá tư duy',24),
  ((select id from levels where code='LUYEN_THI'),'HSG','Luyện thi học sinh giỏi',25)
on conflict(code) do update set name = excluded.name, education_level_id = excluded.education_level_id, sort_order = excluded.sort_order, is_active = true;

insert into subject_categories(name, slug, sort_order) values
                                                           ('Môn phổ thông','mon-pho-thong',1),
                                                           ('Tiểu học / mầm non','tieu-hoc-mam-non',2),
                                                           ('Ngoại ngữ','ngoai-ngu',3),
                                                           ('Chứng chỉ','chung-chi',4),
                                                           ('Công nghệ / kỹ năng','cong-nghe-ky-nang',5),
                                                           ('Luyện thi','luyen-thi',6)
    on conflict(slug) do update set name = excluded.name, sort_order = excluded.sort_order, is_active = true;

with cats as (select id, slug from subject_categories), seed(code, name, category_slug, academic, language_flag, test_prep, skill) as (values
                                                                                                                                           ('TOAN','Toán','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('NGU_VAN','Ngữ văn','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('TIENG_ANH','Tiếng Anh','ngoai-ngu',true,true,false,false),
                                                                                                                                           ('VAT_LY','Vật lý','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('HOA_HOC','Hóa học','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('SINH_HOC','Sinh học','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('LICH_SU','Lịch sử','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('DIA_LY','Địa lý','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('TIN_HOC','Tin học','mon-pho-thong',true,false,false,true),
                                                                                                                                           ('CONG_NGHE','Công nghệ','mon-pho-thong',true,false,false,true),
                                                                                                                                           ('GDCD','Giáo dục công dân','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('GDKT_PL','Giáo dục kinh tế và pháp luật','mon-pho-thong',true,false,false,false),
                                                                                                                                           ('AM_NHAC','Âm nhạc','mon-pho-thong',true,false,false,true),
                                                                                                                                           ('MY_THUAT','Mỹ thuật','mon-pho-thong',true,false,false,true),
                                                                                                                                           ('GDTC','Giáo dục thể chất','mon-pho-thong',true,false,false,true),
                                                                                                                                           ('TIENG_VIET','Tiếng Việt','tieu-hoc-mam-non',true,false,false,false),
                                                                                                                                           ('TOAN_TIEU_HOC','Toán tiểu học','tieu-hoc-mam-non',true,false,false,false),
                                                                                                                                           ('TNXH','Tự nhiên và xã hội','tieu-hoc-mam-non',true,false,false,false),
                                                                                                                                           ('KHOA_HOC','Khoa học','tieu-hoc-mam-non',true,false,false,false),
                                                                                                                                           ('LS_DL_TIEU_HOC','Lịch sử và Địa lý tiểu học','tieu-hoc-mam-non',true,false,false,false),
                                                                                                                                           ('TIEN_TIEU_HOC','Tiền tiểu học','tieu-hoc-mam-non',true,false,false,true),
                                                                                                                                           ('KY_NANG_DOC_VIET','Kỹ năng đọc viết','tieu-hoc-mam-non',true,false,false,true),
                                                                                                                                           ('GIAO_DUC_MAM_NON','Giáo dục mầm non','tieu-hoc-mam-non',true,false,false,true),
                                                                                                                                           ('TIENG_TRUNG','Tiếng Trung','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_NHAT','Tiếng Nhật','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_HAN','Tiếng Hàn','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_PHAP','Tiếng Pháp','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_NGA','Tiếng Nga','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_DUC','Tiếng Đức','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_TAY_BAN_NHA','Tiếng Tây Ban Nha','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('TIENG_VIET_NUOC_NGOAI','Tiếng Việt cho người nước ngoài','ngoai-ngu',false,true,false,false),
                                                                                                                                           ('IELTS','IELTS','chung-chi',false,true,true,false),
                                                                                                                                           ('TOEIC','TOEIC','chung-chi',false,true,true,false),
                                                                                                                                           ('TOEFL','TOEFL','chung-chi',false,true,true,false),
                                                                                                                                           ('CAMBRIDGE','Cambridge English','chung-chi',false,true,true,false),
                                                                                                                                           ('HSK','HSK','chung-chi',false,true,true,false),
                                                                                                                                           ('JLPT','JLPT','chung-chi',false,true,true,false),
                                                                                                                                           ('TOPIK','TOPIK','chung-chi',false,true,true,false),
                                                                                                                                           ('DELF','DELF','chung-chi',false,true,true,false),
                                                                                                                                           ('DALF','DALF','chung-chi',false,true,true,false),
                                                                                                                                           ('GOETHE','Goethe-Zertifikat','chung-chi',false,true,true,false),
                                                                                                                                           ('TIN_HOC_VAN_PHONG','Tin học văn phòng','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('LAP_TRINH_CO_BAN','Lập trình cơ bản','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('PYTHON','Python','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('JAVA','Java','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('JAVASCRIPT','JavaScript','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('WEB_DEVELOPMENT','Web Development','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('AI_CO_BAN','AI cơ bản','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('DATA_ANALYSIS','Data Analysis','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('ROBOTICS_STEM','Robotics/STEM','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('SCRATCH','Scratch','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('EXCEL','Excel','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('POWERPOINT','PowerPoint','cong-nghe-ky-nang',false,false,false,true),
                                                                                                                                           ('THI_LOP_6','Thi vào lớp 6','luyen-thi',false,false,true,false),
                                                                                                                                           ('THI_LOP_10','Thi vào lớp 10','luyen-thi',false,false,true,false),
                                                                                                                                           ('THI_THPT','Thi THPT Quốc gia','luyen-thi',false,false,true,false),
                                                                                                                                           ('DANH_GIA_NANG_LUC','Đánh giá năng lực','luyen-thi',false,false,true,false),
                                                                                                                                           ('DANH_GIA_TU_DUY','Đánh giá tư duy','luyen-thi',false,false,true,false),
                                                                                                                                           ('LUYEN_THI_HSG','Luyện thi học sinh giỏi','luyen-thi',false,false,true,false)
)
insert into subjects(code, name, slug, normalized_name, category_id, description, is_academic_subject, is_language, is_test_prep, is_skill, is_active)
select seed.code, seed.name, lower(replace(seed.code, '_', '-')), lower(unaccent(seed.name)), cats.id, '', seed.academic, seed.language_flag, seed.test_prep, seed.skill, true
from seed join cats on cats.slug = seed.category_slug
    on conflict(code) where code is not null do update set
    name = excluded.name,
                                                    normalized_name = excluded.normalized_name,
                                                    category_id = excluded.category_id,
                                                    is_academic_subject = excluded.is_academic_subject,
                                                    is_language = excluded.is_language,
                                                    is_test_prep = excluded.is_test_prep,
                                                    is_skill = excluded.is_skill,
                                                    is_active = true;

insert into subject_aliases(subject_id, alias, normalized_alias)
select s.id, alias_value, lower(unaccent(alias_value))
from subjects s
         join lateral (values (s.name), (replace(s.name, 'Tiếng ', '')), (s.code)) aliases(alias_value) on true
where alias_value is not null and alias_value <> ''
    on conflict(subject_id, normalized_alias) do nothing;

insert into languages(code, name, native_name) values
                                                   ('vi','Tiếng Việt','Tiếng Việt'), ('en','Tiếng Anh','English'), ('zh','Tiếng Trung','中文'),
                                                   ('ja','Tiếng Nhật','日本語'), ('ko','Tiếng Hàn','한국어'), ('fr','Tiếng Pháp','Français'),
                                                   ('ru','Tiếng Nga','Русский'), ('de','Tiếng Đức','Deutsch'), ('es','Tiếng Tây Ban Nha','Español')
    on conflict(code) do update set name = excluded.name, native_name = excluded.native_name, is_active = true;

insert into certificates(code, name, language_id, description)
select c.code, c.name, l.id, c.description
from (values
          ('IELTS','IELTS','en','International English Language Testing System'),
          ('TOEIC','TOEIC','en','Test of English for International Communication'),
          ('TOEFL','TOEFL','en','Test of English as a Foreign Language'),
          ('CAMBRIDGE','Cambridge English','en','Cambridge English Qualifications'),
          ('HSK','HSK','zh','Hanyu Shuiping Kaoshi'),
          ('JLPT','JLPT','ja','Japanese-Language Proficiency Test'),
          ('TOPIK','TOPIK','ko','Test of Proficiency in Korean'),
          ('DELF','DELF','fr','Diplôme d''études en langue française'),
          ('DALF','DALF','fr','Diplôme approfondi de langue française'),
          ('GOETHE','Goethe-Zertifikat','de','Goethe German certificate')
     ) c(code, name, language_code, description)
         left join languages l on l.code = c.language_code
    on conflict(code) do update set name = excluded.name, language_id = excluded.language_id, description = excluded.description, is_active = true;

insert into teaching_modes(code, name, description) values
                                                        ('ONLINE','Online','Học trực tuyến'),
                                                        ('OFFLINE','Offline','Học trực tiếp'),
                                                        ('HYBRID','Hybrid','Kết hợp online và offline')
    on conflict(code) do update set name = excluded.name, description = excluded.description, is_active = true;

insert into cancellation_policies(code, name, description, applies_to, free_cancel_before_hours, penalty_type, penalty_value) values
                                                                                                                                  ('TRIAL_ONLINE_DEFAULT','Hủy học thử online','Miễn phí nếu hủy trước 12 giờ, hủy sát giờ ghi nhận cảnh báo.','TRIAL_ONLINE',12,'WARNING',0),
                                                                                                                                  ('TRIAL_OFFLINE_DEFAULT','Hủy học thử offline','Miễn phí nếu hủy trước 24 giờ, hủy sát giờ ghi nhận penalty vận hành.','TRIAL_OFFLINE',24,'WARNING',0),
                                                                                                                                  ('CLASS_SESSION_DEFAULT','Hủy buổi học chính thức','Miễn phí nếu hủy trước 24 giờ.','CLASS_SESSION',24,'WARNING',0)
    on conflict(code) do update set name = excluded.name, description = excluded.description, applies_to = excluded.applies_to,
                             free_cancel_before_hours = excluded.free_cancel_before_hours, penalty_type = excluded.penalty_type,
                             penalty_value = excluded.penalty_value, is_active = true;

insert into locations(code, name, type, full_path, source, version) values
                                                                        ('VN-HN','Hà Nội','PROVINCE','Hà Nội','seed','v1'),
                                                                        ('VN-HCM','Thành phố Hồ Chí Minh','PROVINCE','Thành phố Hồ Chí Minh','seed','v1'),
                                                                        ('VN-DN','Đà Nẵng','PROVINCE','Đà Nẵng','seed','v1')
    on conflict(code) do update set name = excluded.name, full_path = excluded.full_path, is_active = true, updated_at = now();

insert into system_settings(key, value, value_type, description, is_sensitive) values
                                                                                   ('paymentMode','"provider"'::jsonb,'string','Chế độ thanh toán production dùng provider thật.',false),
                                                                                   ('defaultGateway','"payos"'::jsonb,'string','Gateway thanh toán mặc định.',false),
                                                                                   ('enabledGateways','["bank_qr","vnpay","momo","payos","stripe"]'::jsonb,'json','Danh sách gateway bật.',false),
                                                                                   ('commissionRate','0.15'::jsonb,'number','Tỷ lệ hoa hồng nền tảng.',false),
                                                                                   ('verificationProvider','"external"'::jsonb,'string','Provider xác minh giấy tờ.',false)
    on conflict(key) do nothing;

alter table tutoring_classes
    add column if not exists student_profile_id uuid references student_profiles(id);
alter table class_sessions
    add column if not exists student_profile_id uuid references student_profiles(id);
create index if not exists idx_tutoring_classes_student_profile on tutoring_classes(student_profile_id);
create index if not exists idx_class_sessions_student_profile on class_sessions(student_profile_id);