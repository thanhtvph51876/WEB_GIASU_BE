update system_settings
set value = '"sandbox"'::jsonb,
    description = 'sandbox | production',
    updated_at = now()
where key = 'paymentMode'
  and value = '"mock"'::jsonb;

update system_settings
set value = '["bank_qr","vnpay","momo","payos","stripe"]'::jsonb,
    updated_at = now()
where key = 'enabledGateways'
  and value::text like '%mock%';

update system_settings
set value = '"bank_qr"'::jsonb,
    updated_at = now()
where key = 'defaultGateway'
  and value = '"mock"'::jsonb;
