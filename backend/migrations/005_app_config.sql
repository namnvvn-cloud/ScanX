-- Cấu hình app sửa được trên Web Admin, không cần phát hành lại app:
--   app_version  — chính sách cập nhật (bản mới nhất, bản tối thiểu, ép cập nhật) cho Android/iOS
--   product_info — màn "Thông tin sản phẩm" (mô tả, chức năng, nhà phát hành, liên hệ)
create table if not exists app_config (
  key        text primary key,
  value      jsonb not null,
  updated_at timestamptz not null default now()
);
alter table app_config enable row level security;

insert into app_config (key, value) values
('app_version', '{
  "android": {
    "autoFromGithub": true,
    "forceLatest": true,
    "minVersionCode": 0,
    "latestVersionCode": 0,
    "latestVersionName": "",
    "downloadUrl": "",
    "releaseNotes": ""
  },
  "ios": {
    "forceLatest": false,
    "minBuild": 0,
    "latestBuild": 0,
    "latestVersion": "",
    "downloadUrl": "",
    "releaseNotes": ""
  }
}'::jsonb),
('product_info', '{
  "appName": "ScanX",
  "tagline": "Quét tài liệu thông minh — OCR tiếng Việt, chuyển Word/Excel/PowerPoint, dịch tài liệu",
  "description": "ScanX biến điện thoại thành máy quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu, nhận dạng chữ tiếng Việt và xuất PDF tìm kiếm được. Gói Business chuyển bản chụp sang Word/Excel/PowerPoint giữ nguyên bố cục, dịch cả tài liệu và sao lưu đám mây.",
  "features": [
    "Quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu / đen trắng, xoá bóng",
    "Chỉnh sửa trang: cắt, xoay, làm sạch vết bẩn",
    "OCR tiếng Việt và nhiều ngôn ngữ, PDF có lớp chữ tìm kiếm được (4 chế độ)",
    "Xuất ảnh JPG, văn bản TXT, chia sẻ nhanh",
    "Chụp để dịch và dịch trực tiếp khi soi camera",
    "Business: chuyển Word / Excel / PowerPoint giữ bố cục bảng biểu",
    "Business: dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)",
    "Business: AI Cloud đọc chữ viết tay, sao lưu tài liệu lên đám mây"
  ],
  "publisher": "",
  "website": "",
  "email": "",
  "phone": "",
  "address": "",
  "privacyUrl": "",
  "termsUrl": ""
}'::jsonb)
on conflict (key) do nothing;
