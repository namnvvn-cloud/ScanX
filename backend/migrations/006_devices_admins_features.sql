-- Thiết bị đã cài app (kể cả CHƯA đăng ký tài khoản) — app gửi POST /devices/ping lúc mở.
create table if not exists devices (
  install_id    text primary key,
  platform      text not null default 'android',
  version_code  integer,
  version_name  text,
  model         text,
  os_version    text,
  locale        text,
  user_id       uuid references users(id) on delete set null,
  open_count    integer not null default 1,
  first_seen    timestamptz not null default now(),
  last_seen     timestamptz not null default now()
);
create index if not exists devices_user_idx on devices(user_id);
create index if not exists devices_last_seen_idx on devices(last_seen desc);
alter table devices enable row level security;

-- Quản trị viên phụ (ngoài ADMIN_EMAILS trên Render = quản trị viên chính, không xoá được từ web).
create table if not exists admins (
  email      text primary key,
  added_by   text,
  created_at timestamptz not null default now()
);
alter table admins enable row level security;

-- Chính sách tính năng Miễn phí / Business (Web Admin → Tính năng).
insert into app_config (key, value) values ('feature_policy', '{
  "export_image_text": {"access": "free", "trials": 0},
  "camera_translate":  {"access": "free", "trials": 0},
  "office_export":     {"access": "business", "trials": 3},
  "doc_translate":     {"access": "business", "trials": 3},
  "ai_handwriting":    {"access": "business", "trials": 0},
  "cloud_backup":      {"access": "business", "trials": 0}
}'::jsonb)
on conflict (key) do nothing;
