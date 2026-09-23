package com.scanx.app.data

/**
 * Bộ lọc xem/xuất RIÊNG TỪNG TRANG (bản 0.8, màn hình "Chỉnh sửa trang" → tab "Bộ lọc") — độc lập
 * với cỡ file (nhỏ gọn/chất lượng cao) chọn lúc xuất cả tài liệu ([PdfExportMode]). Trang không đặt
 * (null) → dùng đúng theo [PdfExportMode] của tài liệu như trước bản 0.8 (không đổi hành vi cũ);
 * trang có đặt → override phần "màu hay đen trắng/xám" của riêng trang đó, không ảnh hưởng trang khác.
 */
enum class PageFilter(val code: String, val label: String) {
    ORIGINAL("ORIGINAL", "Ban đầu"),
    AUTO("AUTO", "Tự động"),
    COLOR("COLOR", "Màu"),
    BW("BW", "Đen trắng"),
    GRAY("GRAY", "Xám"),
    SHADOW("SHADOW", "Bóng"),
    ;

    companion object {
        fun fromCode(code: String?): PageFilter? = values().firstOrNull { it.code == code }
    }
}
