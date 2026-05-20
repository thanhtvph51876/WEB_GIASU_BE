insert into subjects (name, slug, description) values
  ('Toan', 'toan', 'Toan hoc pho thong'),
  ('Ngu van', 'ngu-van', 'Ngu van va tap lam van'),
  ('Tieng Anh', 'tieng-anh', 'Tieng Anh giao tiep va hoc thuat'),
  ('Vat ly', 'vat-ly', 'Vat ly THCS/THPT'),
  ('Hoa hoc', 'hoa-hoc', 'Hoa hoc THCS/THPT'),
  ('Sinh hoc', 'sinh-hoc', 'Sinh hoc THCS/THPT'),
  ('Lich su', 'lich-su', 'Lich su'),
  ('Dia ly', 'dia-ly', 'Dia ly'),
  ('Tin hoc', 'tin-hoc', 'Lap trinh va tin hoc'),
  ('IELTS', 'ielts', 'Luyen thi IELTS')
on conflict (slug) do nothing;

insert into grade_levels (name, sort_order) values
  ('Lop 1', 1),
  ('Lop 2', 2),
  ('Lop 3', 3),
  ('Lop 4', 4),
  ('Lop 5', 5),
  ('Lop 6', 6),
  ('Lop 7', 7),
  ('Lop 8', 8),
  ('Lop 9', 9),
  ('Lop 10', 10),
  ('Lop 11', 11),
  ('Lop 12', 12)
on conflict (name) do nothing;

insert into system_settings (key, value, description) values
  ('bookingEnabled', 'true'::jsonb, 'Cho phep dat lich hoc thu'),
  ('tutorRegistrationEnabled', 'true'::jsonb, 'Cho phep dang ky gia su'),
  ('autoMatchingEnabled', 'true'::jsonb, 'Cho phep goi y ghep gia su'),
  ('commissionRate', '0.15'::jsonb, 'Ty le phi nen tang'),
  ('trialLessonPolicy', '"Hoc thu 1 buoi, co the chuyen thanh lop chinh thuc sau khi hoan tat."'::jsonb, 'Chinh sach hoc thu'),
  ('notificationSettings', '{"email": false, "inApp": true, "paymentAlerts": true, "reviewAlerts": true}'::jsonb, 'Cau hinh thong bao'),
  ('maintenanceMode', 'false'::jsonb, 'Che do bao tri')
on conflict (key) do nothing;
