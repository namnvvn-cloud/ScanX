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
1. Vào https://supabase.com → **New project** (chọn region Singapore cho gần VN, đặt mật khẩu DB — lưu lại, không xem lại được).
2. Trong project vừa tạo, bấm nút **Connect** (góc trên bên phải màn hình, cạnh chỗ chọn nhánh `main`) — Supabase đã bỏ đường cũ "Settings → Database → Connection string", giờ dồn hết vào đây.
3. Trong hộp thoại "Connect to your project", chọn tab **Direct** (không chọn ORM — mình không dùng Prisma). Bấm icon copy nhỏ **ngay cạnh ô chuỗi kết nối** (dòng `postgresql://postgres:[YOUR-PASSWORD]@...`) — không bấm nút "Copy all" ở khối "Connection parameters" bên dưới (khối đó copy riêng từng dòng host/port/database/user, không phải chuỗi URI đầy đủ).
4. Thay `[YOUR-PASSWORD]` trong chuỗi vừa copy bằng mật khẩu DB đã đặt ở bước 1 (quên thì bấm **Reset database password** ngay trong hộp thoại đó để đặt lại) → dán nguyên chuỗi vào `DATABASE_URL` trong `.env`. Cổng mặc định ở tab Direct là `5432` (kết nối trực tiếp) — dùng được luôn cho backend chạy dạng server thường trực (Render/Fly), không cần đổi sang pooler.

### Bước 2 — Firebase Auth
1. Vào https://console.firebase.google.com → đăng nhập bằng tài khoản Google → bấm nút tạo project mới (ở màn danh sách project) → đặt tên (vd `scanx-app`) → **Continue**.
2. Màn tiếp theo hỏi có bật **Gemini in Firebase** và **Google Analytics** không — cả 2 đều **không cần** cho ScanX, bỏ qua/tắt được → bấm **Create project**, đợi vài giây.
3. Vào project vừa tạo, menu bên trái tìm mục **Authentication** — Google mới gộp nó vào nhóm **Security** (không còn là mục ngang hàng riêng như trước, nên nếu không thấy ngay thì mở nhóm "Security" ra) → **Get started** (nếu lần đầu) → tab **Sign-in method** → bật **Email/Password** và/hoặc **Google** → **Save**.
4. Bấm icon bánh răng cạnh "Project Overview" → **Project settings** → tab **Service accounts** → **Generate new private key** → xác nhận **Generate key** → 1 file `.json` tự tải về.
   - **Lưu ý riêng cho tài khoản Google Workspace/tổ chức**: nếu Google báo lỗi kiểu "tổ chức của bạn chặn tạo khoá service account" (chính sách bảo mật mới Google áp cho tài khoản doanh nghiệp), cần nhờ admin Google Workspace của tổ chức gỡ policy `iam.disableServiceAccountKeyCreation`, hoặc đơn giản nhất là tạo project Firebase bằng **tài khoản Gmail cá nhân** (không phải email công ty) — tài khoản cá nhân không bị chặn.
5. Mở file `.json`, copy nguyên nội dung, dán vào `FIREBASE_SERVICE_ACCOUNT_JSON` trong `.env` (1 dòng, để trong dấu nháy đơn).
6. Miễn phí hoàn toàn: gói Spark (mặc định) không cần thẻ, Email/Password và Google Sign-in miễn phí tới 50.000 người dùng/tháng — không cần nâng gói Blaze (gói Blaze chỉ bắt buộc nếu dùng Phone Authentication bằng SMS, ScanX không dùng).
7. Bên Android: dùng Firebase Auth SDK để đăng nhập, lấy ID token bằng `FirebaseAuth.getInstance().currentUser.getIdToken(false)`.

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

Render vẫn có gói Web Service miễn phí thật (không cần thẻ) tính đến 2026: 512 MB RAM, tự **ngủ sau 15 phút không có request** (lần gọi tiếp theo chờ ~1 phút để dậy), giới hạn **750 giờ chạy/tháng cho cả workspace** (đủ dùng cho 1 service chạy cả tháng nếu chỉ có mình nó). **Fly.io không còn gói miễn phí** (từ khoảng 2025 đã bắt buộc thẻ tín dụng, tính phí theo giây) — nên dùng Render cho Phase 1, không dùng Fly.io nữa.

1. Push code lên GitHub (đã có sẵn trong repo `ScanX`, thư mục `backend/`).
2. Vào https://render.com → **+ New → Web Service** → chọn/kết nối repo `ScanX`.
3. Điền ngay trong form tạo service (Render gộp hết vào 1 form, không qua nhiều bước như trước):
   - **Root Directory**: `backend` — Render sẽ tự hiểu mọi lệnh dưới đây (Build/Start Command) là chạy tính từ thư mục `backend/`, không cần gõ `cd backend &&`.
   - **Build Command**: `npm install && npm run build`
   - **Start Command**: `npm run start`
   - **Instance Type**: chọn **Free**.
   - **Environment Variables**: có sẵn khung nhập key/value ngay trong form này — dán từng biến trong `.env` vào đây (không commit file `.env` thật lên git — đã có trong `.gitignore`).
4. Bấm **Create Web Service** / **Deploy** → theo dõi tiến trình ở tab **Deploys** của service.
5. Sau khi deploy xong, chạy migrate 1 lần: vào tab **Shell** của service trên Render, gõ `npm run db:migrate` (hoặc chạy từ máy local, trỏ `DATABASE_URL` production trong `.env` tạm thời).
6. Muốn sửa lại Root Directory/env var sau này: **Settings → Build & Deploy** (Root Directory) hoặc **Environment** (biến môi trường) ở sidebar trái của service.

## 4. Việc còn lại (chưa làm trong bản này)

- Android: thêm màn hình đăng nhập (Firebase Auth UI) + nút "Sao lưu/Đồng bộ" gọi các endpoint trên — **chưa đụng vào flow local-only hiện tại**, đây là tính năng cộng thêm.
- Web Admin (Phase 2): dashboard quản lý user/document, duyệt thanh toán Business (VNPay/MoMo) → gọi `UsersService.setBusinessStatus`.
- Giới hạn dung lượng/số trang theo gói Free vs Business (chưa enforce ở backend, mới enforce phía UI Android).
