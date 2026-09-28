package com.scanx.app.data

/** Bộ lọc thông minh ở màn hình chính (chip ngang dưới ô tìm kiếm). */
enum class SmartFilter(val label: String) {
    ALL("Tất cả"),
    RECENT("7 ngày qua"),
    HAS_TEXT("Có văn bản"),
    RECEIPTS("Hoá đơn, biên lai"),
    MULTI_PAGE("Nhiều trang"),
    ;

    fun matches(doc: DocumentMeta, now: Long = System.currentTimeMillis()): Boolean = when (this) {
        ALL -> true
        RECENT -> now - doc.createdAtEpochMillis <= 7L * 24 * 3600 * 1000
        HAS_TEXT -> doc.ocrText.isNotBlank()
        RECEIPTS -> ReceiptParser.looksLikeReceipt(doc.ocrText)
        MULTI_PAGE -> doc.pageCount >= 3
    }
}
