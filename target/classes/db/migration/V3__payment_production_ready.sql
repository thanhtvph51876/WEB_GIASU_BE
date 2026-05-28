alter table payments drop constraint if exists payments_status_check;

alter table payments
  add column if not exists payment_method varchar(60),
  add column if not exists gateway varchar(60),
  add column if not exists checkout_url text,
  add column if not exists qr_code_url text,
  add column if not exists expired_at timestamptz;

alter table payments
  add constraint payments_status_check
  check (status in ('pending','processing','paid','completed','failed','expired','refunded','partially_refunded','cancelled'));

create table if not exists payment_transactions (
  id uuid primary key default gen_random_uuid(),
  payment_id uuid not null references payments(id) on delete cascade,
  gateway varchar(60) not null,
  gateway_order_id varchar(160),
  gateway_transaction_id varchar(160),
  amount int not null check (amount >= 0),
  currency varchar(10) not null default 'VND',
  status varchar(30) not null default 'created'
    check (status in ('created','pending','success','failed','cancelled','expired','refunded')),
  request_payload jsonb,
  raw_response jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists ux_payment_transactions_gateway_order
  on payment_transactions(gateway, gateway_order_id)
  where gateway_order_id is not null;

create unique index if not exists ux_payment_transactions_gateway_txn
  on payment_transactions(gateway, gateway_transaction_id)
  where gateway_transaction_id is not null;

create table if not exists payment_webhook_events (
  id uuid primary key default gen_random_uuid(),
  gateway varchar(60) not null,
  event_id varchar(180),
  payment_id uuid references payments(id) on delete set null,
  gateway_order_id varchar(160),
  gateway_transaction_id varchar(160),
  signature_valid boolean not null default false,
  processed boolean not null default false,
  processing_error text,
  payload jsonb not null,
  received_at timestamptz not null default now(),
  processed_at timestamptz
);

create index if not exists idx_payment_webhook_gateway_event
  on payment_webhook_events(gateway, event_id)
  where event_id is not null;

create table if not exists payment_refunds (
  id uuid primary key default gen_random_uuid(),
  payment_id uuid not null references payments(id) on delete cascade,
  amount int not null check (amount > 0),
  reason text,
  status varchar(30) not null default 'pending'
    check (status in ('pending','processing','succeeded','failed','cancelled')),
  gateway_refund_id varchar(160),
  requested_by uuid references users(id),
  processed_by uuid references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists invoices (
  id uuid primary key default gen_random_uuid(),
  payment_id uuid not null unique references payments(id) on delete cascade,
  invoice_no varchar(80) not null unique,
  user_id uuid not null references users(id),
  amount int not null check (amount >= 0),
  currency varchar(10) not null default 'VND',
  status varchar(30) not null default 'issued'
    check (status in ('issued','paid','cancelled')),
  issued_at timestamptz not null default now(),
  paid_at timestamptz,
  file_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists receipts (
  id uuid primary key default gen_random_uuid(),
  payment_id uuid not null unique references payments(id) on delete cascade,
  receipt_no varchar(80) not null unique,
  user_id uuid not null references users(id),
  amount int not null check (amount >= 0),
  currency varchar(10) not null default 'VND',
  issued_at timestamptz not null default now(),
  file_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into system_settings (key, value, description) values
  ('paymentMode', '"sandbox"'::jsonb, 'sandbox | production'),
  ('enabledGateways', '["bank_qr","vnpay","momo","payos","stripe"]'::jsonb, 'Danh sach gateway thanh toan duoc bat'),
  ('defaultGateway', '"bank_qr"'::jsonb, 'Gateway mac dinh'),
  ('paymentTimeoutMinutes', '30'::jsonb, 'Thoi gian het han checkout'),
  ('refundPolicy', '"Hoan tien theo chinh sach dich vu va trang thai buoi hoc."'::jsonb, 'Chinh sach hoan tien'),
  ('invoicePrefix', '"INV"'::jsonb, 'Tien to hoa don'),
  ('receiptPrefix', '"REC"'::jsonb, 'Tien to bien lai')
on conflict (key) do nothing;

insert into invoices (payment_id, invoice_no, user_id, amount, currency, status, paid_at)
select p.id,
       'INV-' || to_char(p.created_at, 'YYYYMM') || '-' || substr(p.id::text, 1, 8),
       p.user_id,
       p.amount,
       p.currency,
       case when p.status in ('paid','completed') then 'paid' else 'issued' end,
       p.paid_at
from payments p
where not exists (select 1 from invoices i where i.payment_id = p.id);

insert into receipts (payment_id, receipt_no, user_id, amount, currency, issued_at)
select p.id,
       'REC-' || to_char(coalesce(p.paid_at, p.created_at), 'YYYYMM') || '-' || substr(p.id::text, 1, 8),
       p.user_id,
       p.amount,
       p.currency,
       coalesce(p.paid_at, now())
from payments p
where p.status in ('paid','completed')
  and not exists (select 1 from receipts r where r.payment_id = p.id);

create index if not exists idx_payment_transactions_payment on payment_transactions(payment_id);
create index if not exists idx_payment_webhook_events_received on payment_webhook_events(received_at desc);
create index if not exists idx_payment_refunds_payment on payment_refunds(payment_id);
