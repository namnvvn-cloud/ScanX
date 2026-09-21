# ScanX Android — Hướng dẫn build & cài đặt (Phase 1 MVP)

## Đã code xong trong bản này
- Quét tài liệu qua camera bằng Google ML Kit Document Scanner (tự phát hiện biên, crop, chọn nhiều trang, gallery import) — engine giống Scanner Pro/Adobe Scan, không phải tự viết OpenCV.
- OCR tiếng Việt trên máy (ML Kit Text Recognition), chạy ngầm sau khi quét xong.
- Xuất & lưu file PDF vào bộ nhớ trong máy (`/data/data/com.scanx.app/files/ScanX/documents/<id>/document.pdf`).
- Danh sách tài liệu đã quét, xem chi tiết + text OCR, chia sẻ PDF ra app khác, xoá tài liệu.
- **Chưa có**: đăng nhập, đồng bộ cloud, backend — theo đúng thứ tự đã chốt, các phần này làm sau khi bản Android chạy ổn.

## Vì sao Claude không tự build ra file APK được
Môi trường cloud Claude đang chạy bị chặn truy cập `dl.google.com` và `maven.google.com` (chính sách egress của tổ chức) — đây là 2 server bắt buộc để tải Android SDK và các thư viện AndroidX/ML Kit/Google Play Services. Không có 2 server này, Gradle không thể tải dependency nên không build được. Máy tính cá nhân của Anh Nam không bị chặn như vậy nên build bình thường.

## Cách 1 — Build trên Android Studio (khuyên dùng để test nhanh trên điện thoại)
1. Cài Android Studio (bản mới nhất, tải tại developer.android.com/studio) nếu chưa có.
2. Copy toàn bộ thư mục `android/` này vào máy, mở bằng Android Studio: **File → Open** → chọn thư mục `android/`.
3. Nếu Android Studio báo thiếu Gradle wrapper: chọn **OK/Fix** để nó tự tạo (thao tác chuẩn, chỉ cần internet bình thường).
4. Đợi Gradle sync xong (lần đầu có thể mất 3-5 phút để tải SDK + thư viện).
5. Cắm điện thoại Android qua cáp USB, bật **Chế độ nhà phát triển → gỡ lỗi USB (USB debugging)** trên điện thoại.
6. Bấm nút **Run ▶** (hoặc Shift+F10) trong Android Studio — app sẽ cài thẳng vào điện thoại và chạy luôn.
7. Muốn xuất file APK để gửi người khác cài: **Build → Build App Bundle(s) / APK(s) → Build APK(s)** → file nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Cách 2 — Để GitHub Actions tự build APK (không cần cài Android Studio)
Repo đã có sẵn file `.github/workflows/android-build.yml` ở thư mục gốc.
1. Đẩy (push) toàn bộ code này lên GitHub (repo `Scan Pro` đã có sẵn theo folder đang dùng).
2. Vào tab **Actions** trên GitHub → workflow "Build ScanX debug APK" sẽ tự chạy.
3. Sau khi chạy xong (khoảng 3-5 phút), vào run đó → mục **Artifacts** → tải file `scanx-debug-apk` về (đây chính là APK debug).
4. Copy file `.apk` vào điện thoại Android, mở lên để cài (cần bật "Cài đặt từ nguồn không xác định" cho ứng dụng dùng để mở file, ví dụ File Manager/Gmail).

## Lưu ý khi test
- Lần đầu mở app sẽ xin quyền Camera — bắt buộc phải cho phép.
- Máy ảo (emulator) không có camera thật nên nên test trên điện thoại thật để đúng trải nghiệm scan.
- Nếu Gradle báo lỗi version không tìm thấy (ví dụ ML Kit hoặc Compose BOM), khả năng cao là đã có bản mới hơn thay thế — mở file `app/build.gradle.kts`, để Android Studio gợi ý version mới nhất qua "Alt+Enter" tại dòng bị đỏ, hoặc báo lại cho Claude để cập nhật version cụ thể.

## Bước tiếp theo (theo lộ trình đã chốt)
Sau khi Anh Nam xác nhận bản Android này chạy được trên máy thật → làm Backend API (Phase 1 phần còn lại: đăng nhập, đồng bộ cloud) → rồi Web quản lý → rồi iOS.
