-- Supabase tự mở REST API (PostgREST) cho mọi bảng trong schema public. Bật RLS mà KHÔNG tạo policy
-- = chặn hết truy cập qua anon/authenticated key. Backend kết nối bằng role postgres (chủ bảng) nên
-- không bị ảnh hưởng. Idempotent: chạy lại nhiều lần không sao.
alter table if exists users enable row level security;
alter table if exists documents enable row level security;
alter table if exists plans enable row level security;
alter table if exists orders enable row level security;
