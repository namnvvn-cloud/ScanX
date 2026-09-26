-- ScanX backend — Phase 2: Web Admin + gói Business + thanh toán VNPay/MoMo.
-- Idempotent (chạy lại nhiều lần không lỗi) — backend tự chạy mọi file migrations/*.sql lúc khởi động.

alter table users add column if not exists last_login_at timestamptz;

create table if not exists plans (
  id            text primary key,
  name          text not null,
  description   text,
  price_vnd     integer not null check (price_vnd >= 0),
  duration_days integer not null check (duration_days > 0),
  active        boolean not null default true,
  sort_order    integer not null default 0,
  updated_at    timestamptz not null default now()
);

-- Giá khởi tạo — sửa trực tiếp trong Web Admin (mục Gói dịch vụ).
insert into plans (id, name, description, price_vnd, duration_days, sort_order) values
  ('business_1m',  'ScanX Business — 1 tháng',  'Sao lưu đám mây không giới hạn, OCR Cloud độ chính xác cao, hỗ trợ ưu tiên', 99000,  30,  1),
  ('business_12m', 'ScanX Business — 12 tháng', 'Như gói tháng, tiết kiệm ~17%', 990000, 365, 2)
on conflict (id) do nothing;

create table if not exists orders (
  id                uuid primary key default gen_random_uuid(),
  code              text not null unique,
  user_id           uuid not null references users(id) on delete cascade,
  plan_id           text not null references plans(id),
  amount_vnd        integer not null,
  duration_days     integer not null,
  provider          text not null check (provider in ('vnpay', 'momo', 'manual')),
  status            text not null default 'pending' check (status in ('pending', 'paid', 'failed', 'cancelled')),
  provider_txn_id   text,
  provider_response jsonb,
  paid_at           timestamptz,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create index if not exists orders_user_id_idx on orders(user_id);
create index if not exists orders_status_idx on orders(status);
create index if not exists orders_created_at_idx on orders(created_at);
create index if not exists users_created_at_idx on users(created_at);
