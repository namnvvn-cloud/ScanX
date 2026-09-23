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
    val modifiedAtEpochMillis: Long = createdAtEpochMillis,
    val pageCount: Int,
    val ocrText: String,
    val folderId: String? = null,
    val isTrashed: Boolean = false,
    val trashedAtEpochMillis: Long? = null,
    /** Chế độ PDF đang lưu (A1/A2/B1/B2); null = tài liệu tạo từ bản cũ. */
    val pdfMode: String? = null,
    /** Bộ lọc riêng từng trang (bản 0.8, màn "Chỉnh sửa trang" → tab "Bộ lọc"), theo [PageFilter.code].
     *  Rỗng hoặc phần tử null = trang đó dùng đúng [pdfMode] của cả tài liệu như trước bản 0.8. */
    val pageFilters: List<String?> = emptyList(),
)
