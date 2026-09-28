-- Yêu cầu hỗ trợ gửi từ app (Cài đặt / menu → Hỗ trợ). Tài khoản Business còn hạn = ưu tiên.
create table if not exists support_tickets (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid references users(id) on delete set null,
  install_id  text,
  email       text,
  subject     text not null,
  message     text not null,
  platform    text,
  app_version text,
  device      text,
  priority    boolean not null default false,
  status      text not null default 'open' check (status in ('open', 'closed')),
  admin_note  text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists support_tickets_status_idx on support_tickets(status, priority desc, created_at desc);
alter table support_tickets enable row level security;
