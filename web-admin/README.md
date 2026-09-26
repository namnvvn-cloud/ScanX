# ScanX Web (Phase 2)

Next.js 16 (App Router) + Tailwind v4 + Firebase Web Auth. Toàn bộ trang chạy phía trình duyệt, gọi backend NestJS (`NEXT_PUBLIC_API_URL`).

| Trang | Ai dùng | Chức năng |
|-------|---------|-----------|
| `/` | Khách | Giới thiệu app |
| `/pricing` | Khách | Chọn gói Business → đăng nhập → thanh toán VNPay / MoMo |
| `/billing/result` | Khách | Trang VNPay/MoMo trả về — xác nhận chữ ký, báo kết quả |
| `/account` | Khách | Gói hiện tại, ngày hết hạn, lịch sử đơn |
| `/admin` | Quản trị | Số liệu tổng + biểu đồ 30 ngày (người dùng mới, doanh thu) |
| `/admin/users`, `/admin/users/[id]` | Quản trị | Tìm/lọc user, bật-tắt Business + hạn, tải/xoá tài liệu, xem đơn |
| `/admin/orders` | Quản trị | Lọc đơn, xác nhận tay / huỷ đơn chờ |
| `/admin/plans` | Quản trị | Sửa tên, giá, thời hạn, ẩn/hiện gói |

Quyền quản trị do **backend** quyết định (biến `ADMIN_EMAILS` trên Render) — web chỉ ẩn giao diện.

## Chạy thử máy local

```bash
cd web-admin
npm install
cp .env.example .env.local   # điền NEXT_PUBLIC_FIREBASE_API_KEY, NEXT_PUBLIC_FIREBASE_APP_ID
npm run dev                  # http://localhost:3000
```

## Triển khai lần đầu

### 1. Firebase — tạo Web App (lấy cấu hình, không phải bí mật)
Firebase Console → project **scanx-app** → ⚙ Project settings → **Your apps → Add app → Web (</>)** → tên `ScanX Web` (không tick Hosting) → copy `apiKey` và `appId`.

### 2. Vercel
1. https://vercel.com → đăng nhập bằng GitHub → **Add New → Project** → chọn repo `ScanX`.
2. **Root Directory**: `web-admin` (Framework tự nhận Next.js).
3. **Environment Variables**: `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_FIREBASE_API_KEY`, `NEXT_PUBLIC_FIREBASE_APP_ID` (2 biến còn lại đã có mặc định).
4. **Deploy** → nhận tên miền dạng `scanx-xxx.vercel.app`.

### 3. Firebase — cho phép tên miền đăng nhập
Authentication → **Settings → Authorized domains → Add domain** → dán tên miền Vercel (không có `https://`). Thiếu bước này đăng nhập Google báo `auth/unauthorized-domain`.

### 4. Render (backend) — thêm biến môi trường
`ADMIN_EMAILS`, `PUBLIC_API_URL`, `VNPAY_*`, `MOMO_*` — xem `backend/.env.example`. Cổng nào để trống thì nút thanh toán tương ứng tự ẩn.

### 5. VNPay — khai báo IPN URL
Trong trang merchant sandbox VNPay: IPN URL = `https://scanx-450n.onrender.com/payments/vnpay/ipn`.

## Kiểm thử thanh toán sandbox
- **VNPay**: thẻ test NCB trong email đăng ký sandbox (số thẻ, tên, ngày phát hành, OTP).
- **MoMo**: trang test hiện QR/thẻ ATM test theo tài liệu developers.momo.vn.
- Sau khi trả tiền: `/billing/result` báo thành công → `/account` và `/admin/users` hiện Business + hạn.
- Lưu ý: app Android/iOS **chưa đọc** trạng thái Business từ backend (chưa khoá/mở tính năng theo gói) — cần làm thêm ở bước sau.
