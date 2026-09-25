-- ScanX backend — schema khởi tạo. Chạy 1 lần: npm run db:migrate (đọc DATABASE_URL từ .env)
create extension if not exists pgcrypto;

create table if not exists users (
  id                  uuid primary key default gen_random_uuid(),
  firebase_uid        text not null unique,
  email               text,
  display_name        text,
  is_business         boolean not null default false,
  business_expires_at timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);

create table if not exists documents (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users(id) on delete cascade,
  title       text not null,
  page_count  integer not null default 1,
  file_key    text not null,
  file_size   integer,
  mime_type   text default 'application/pdf',
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists documents_user_id_idx on documents(user_id);
