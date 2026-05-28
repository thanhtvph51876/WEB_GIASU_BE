alter table users drop constraint if exists users_role_check;

do $$
declare
  role_constraint text;
begin
  for role_constraint in
    select conname
    from pg_constraint
    where conrelid = 'users'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%role%'
  loop
    execute format('alter table users drop constraint %I', role_constraint);
  end loop;
end $$;

alter table users
  add constraint users_role_check check (
    role in (
      'student',
      'parent',
      'tutor',
      'admin',
      'finance_admin',
      'tutor_admin',
      'support_admin',
      'verification_admin',
      'system_admin'
    )
  );
