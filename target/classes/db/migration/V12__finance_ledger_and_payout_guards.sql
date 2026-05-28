create table if not exists earning_ledger (
  id uuid primary key default gen_random_uuid(),
  earning_id uuid references tutor_earnings(id),
  tutor_id uuid not null references tutor_profiles(id),
  payment_id uuid references payments(id),
  payout_id uuid references payouts(id),
  entry_type varchar(50) not null check (entry_type in (
    'earning_created',
    'earning_available',
    'payout_locked',
    'payout_paid',
    'payout_released',
    'refund_reversal',
    'refund_debt'
  )),
  amount int not null,
  description text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists idx_earning_ledger_tutor_created
  on earning_ledger(tutor_id, created_at desc);

create index if not exists idx_earning_ledger_payment
  on earning_ledger(payment_id);

create index if not exists idx_earning_ledger_payout
  on earning_ledger(payout_id);

alter table payout_earning_items
  add column if not exists released_at timestamptz;

create unique index if not exists ux_payout_earning_items_earning
  on payout_earning_items(earning_id)
  where released_at is null;
