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

### Bước 3 — Storage lưu file

**Đã đổi từ Cloudflare R2 sang Supabase Storage**: từ 2024 Cloudflare bắt buộc phải gắn thẻ tín dụng (dù free tier $0) mới bật được R2 — không phù hợp nếu chưa có thẻ. Supabase Storage free tier (1GB, đủ dùng giai đoạn đầu) **không cần thẻ**, và dùng chung luôn account Supabase đã tạo ở Bước 1. Code backend không đổi gì (vẫn dùng `@aws-sdk/client-s3`, chỉ đổi biến môi trường) — sau này muốn chuyển sang R2/Backblaze B2 cũng chỉ cần sửa `.env`, không sửa code.

1. Vào lại project Supabase (Bước 1) → menu trái → **Storage** → **New bucket**.
2. Đặt tên `scanx-documents` → để **Public bucket = OFF** (tài liệu người dùng phải riêng tư) → có thể set **File size limit** và **Allowed MIME types** = `application/pdf` → **Save/Create bucket**.
3. Vào **Project Settings** (icon bánh răng) → **Storage** → tìm mục **S3 Connection** trên trang đó.
4. Bấm **New access key** (hoặc "Generate new key pair") → đặt tên bất kỳ → xác nhận.
5. Copy ngay **Access Key ID** và **Secret Access Key** — Secret chỉ hiện **1 lần**, mất phải tạo lại.
6. Cũng trên mục **S3 Connection** đó, copy đúng **Endpoint URL** và **Region** hiển thị cho project của mình (region là chuỗi kiểu `ap-southeast-1`/`us-east-1` tuỳ project, **không phải** `auto`).
7. Điền vào `.env`:
   ```
   STORAGE_ENDPOINT="<Endpoint URL copy ở bước 6>"
   STORAGE_REGION="<Region copy ở bước 6>"
   STORAGE_BUCKET="scanx-documents"
   STORAGE_ACCESS_KEY_ID="<Access Key ID>"
   STORAGE_SECRET_ACCESS_KEY="<Secret Access Key>"
   ```
8. Free tier Supabase Storage: 1GB dung lượng, 50MB/file. Khoá S3 này có quyền trên **toàn bộ bucket của project** (không riêng từng bucket) — giữ bí mật, chỉ dùng ở backend, không đưa vào code Android.

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
2. Vào https://render.com → **+ New → Web Service** → chọn repo `ScanX` (Render đã kết nối GitHub từ trước thì bấm luôn vào tên repo trong danh sách).
3. Điền ngay trong form tạo service (Render gộp hết vào 1 form, không qua nhiều bước như trước):
   - **Root Directory**: gõ `backend`, chọn gợi ý `backend/` hiện ra.
   - **Build Command**: xoá placeholder `yarn`, gõ `npm install && npm run build`.
   - **Start Command**: xoá placeholder `yarn start`, gõ `npm run start`.
   - **Compute**: **QUAN TRỌNG — Render mặc định chọn sẵn gói trả phí $7/tháng (0.5 CPU/512MB), KHÔNG phải Free.** Phải tự bấm chọn dòng **"$0 / month · 0.1 CPU · 512 MB RAM · Free"** trong danh sách, nếu không sẽ bị tính phí ngay khi deploy.
   - **Environment Variables**: bấm nút **"Add from .env"** (tính năng mới) → dán nguyên nội dung file `.env` (Ctrl+A, Ctrl+C từ file, Ctrl+V vào ô) → Render tự tách thành từng biến, không cần gõ tay từng dòng.
4. Bấm **Deploy web service** → theo dõi tiến trình ở tab **Deploys** của service.
5. Sau khi deploy xong, chạy migrate 1 lần: vào tab **Shell** của service trên Render, gõ `npm run db:migrate` (hoặc chạy từ máy local, trỏ `DATABASE_URL` production trong `.env` tạm thời).
6. Muốn sửa lại Root Directory/env var sau này: **Settings → Build & Deploy** (Root Directory) hoặc **Environment** (biến môi trường) ở sidebar trái của service.

## 4. Việc còn lại (chưa làm trong bản này)

- Android: thêm màn hình đăng nhập (Firebase Auth UI) + nút "Sao lưu/Đồng bộ" gọi các endpoint trên — **chưa đụng vào flow local-only hiện tại**, đây là tính năng cộng thêm.
- Web Admin (Phase 2): dashboard quản lý user/document, duyệt thanh toán Business (VNPay/MoMo) → gọi `UsersService.setBusinessStatus`.
- Giới hạn dung lượng/số trang theo gói Free vs Business (chưa enforce ở backend, mới enforce phía UI Android).
