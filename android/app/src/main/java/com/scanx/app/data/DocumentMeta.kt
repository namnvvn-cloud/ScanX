package com.scanx.app.data

/**
 * Metadata của một tài liệu đã scan, lưu dưới dạng JSON cạnh file PDF trong bộ nhớ trong của app.
 * Phase 1 dùng lưu trữ file JSON đơn giản, không cần Room — đủ dùng khi chưa có nhiều tài liệu
 * và chưa cần query phức tạp. Sẽ chuyển sang Room + đồng bộ backend ở Phase 2.
 */
data class DocumentMeta(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val pageCount: Int,
    val ocrText: String
)
