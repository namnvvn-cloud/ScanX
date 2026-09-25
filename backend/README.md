# ScanX Backend (Phase 1 — MVP API)

NestJS + PostgreSQL (Supabase) + Cloudflare R2 (lưu file) + Firebase Auth (đăng nhập).
Không dùng ORM (Prisma) để tránh phải tải binary native lúc build/deploy — dùng thẳng SQL qua `pg`.

## 1. API hiện có

| Method | Endpoint       | Auth | Mô tả |
|--------|----------------|------|-------|
| POST   | `/auth/login`  | Bearer Firebase ID token | Xác thực token, tự tạo user nếu là lần đầu, trả hồ sơ |
| GET    | `/users/me`    | ✓ | Hồ sơ user hiện tại (gồm cờ `is_business`) |
| GET    | `/documents`   | ✓ | Danh sách tài liệu của user (để đồng bộ đa thiết bị) |
| POST   | `/documents`   | ✓ | Tạo metadata + trả `uploadUrl` (presigned) để Android PUT file thẳng lên R2 |
| GET    | `/documents/:id` | ✓ | Metadata + `downloadUrl` (presigned) |
| PATCH  | `/documents/:id` | ✓ | Sửa tiêu đề / số trang |
| DELETE | `/documents/:id` | ✓ | Xoá metadata + xoá file trên R2 |

Mọi request có `Auth: ✓` cần header `Authorization: Bearer <firebase-id-token>` (token lấy từ Firebase Auth SDK bên Android sau khi đăng nhập Google/Email).

Backend **không** nhận file upload trực tiếp — chỉ cấp presigned URL, Android tự PUT/GET thẳng với R2 (nhanh hơn, đỡ tải server).

## 2. Cài đặt lần đầu

### Bước 1 — Supabase (Postgres free tier)
1. Vào https://supabase.com → New project (chọn region Singapore cho gần VN).
2. Project Settings → Database → Connection string → chọn **URI**, copy → dán vào `DATABASE_URL` trong `.env`.

### Bước 2 — Firebase Auth
1. Vào https://console.firebase.google.com → tạo project (hoặc dùng project sẵn có).
2. Authentication → bật phương thức đăng nhập (Google, Email/Password...).
3. Project Settings (bánh răng) → Service accounts → **Generate new private key** → tải file `.json`.
4. Mở file `.json`, copy nguyên nội dung, dán vào `FIREBASE_SERVICE_ACCOUNT_JSON` trong `.env` (1 dòng, để trong dấu nháy đơn).
5. Bên Android: dùng Firebase Auth SDK để đăng nhập, lấy ID token bằng `FirebaseAuth.getInstance().currentUser.getIdToken(false)`.

### Bước 3 — Cloudflare R2 (lưu file, free tier 10GB)
1. Vào Cloudflare Dashboard → R2 → Create bucket (ví dụ `scanx-documents`).
2. R2 → Manage API Tokens → Create API Token → quyền **Object Read & Write**, giới hạn vào bucket vừa tạo.
3. Điền `R2_ENDPOINT` (dạng `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`), `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` vào `.env`.

### Bước 4 — Chạy máy local
```bash
cd backend
cp .env.example .env
# ... điền các giá trị ở bước 1-3 vào .env ...
npm install
npm run db:migrate      # tạo bảng users, documents trên Supabase
npm run start:dev       # chạy dev, tự reload khi sửa code — http://localhost:3000
```

Test nhanh (không cần token thật, chỉ để chắc server sống):
```bash
curl -i http://localhost:3000/documents
# → 401 Unauthorized (đúng — vì chưa gửi token) là server đang chạy tốt
```

## 3. Deploy free tier (Render)

1. Push code lên GitHub (đã có sẵn trong repo `ScanX`, thư mục `backend/`).
2. Vào https://render.com → New → Web Service → chọn repo `ScanX`, **Root Directory** = `backend`.
3. Build Command: `npm install && npm run build`
   Start Command: `npm run start`
4. Environment → Add tất cả biến trong `.env` (không commit `.env` thật lên git — đã có trong `.gitignore`).
5. Sau khi deploy xong, chạy migrate 1 lần bằng Render Shell: `npm run db:migrate` (hoặc chạy từ máy local, trỏ `DATABASE_URL` production).

(Fly.io tương tự — dùng `fly launch` trong thư mục `backend/`, set secrets bằng `fly secrets set KEY=value`.)

## 4. Việc còn lại (chưa làm trong bản này)

- Android: thêm màn hình đăng nhập (Firebase Auth UI) + nút "Sao lưu/Đồng bộ" gọi các endpoint trên — **chưa đụng vào flow local-only hiện tại**, đây là tính năng cộng thêm.
- Web Admin (Phase 2): dashboard quản lý user/document, duyệt thanh toán Business (VNPay/MoMo) → gọi `UsersService.setBusinessStatus`.
- Giới hạn dung lượng/số trang theo gói Free vs Business (chưa enforce ở backend, mới enforce phía UI Android).
