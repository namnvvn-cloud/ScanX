-- Mô tả gói khớp danh sách tính năng Business thật trong app (src/users/entitlements.ts).
-- Chỉ ghi đè khi mô tả vẫn là bản seed cũ — không đè nội dung admin đã tự sửa trên web.
update plans set description = 'Mở khoá: chuyển Word/Excel/PowerPoint, dịch cả tài liệu, AI Cloud đọc chữ viết tay, sao lưu đám mây', updated_at = now()
where id = 'business_1m' and description = 'Sao lưu đám mây không giới hạn, OCR Cloud độ chính xác cao, hỗ trợ ưu tiên';
update plans set description = 'Toàn bộ tính năng Business như gói tháng, tiết kiệm ~17%', updated_at = now()
where id = 'business_12m' and description = 'Như gói tháng, tiết kiệm ~17%';
