package com.scanx.app.scan

import android.graphics.Bitmap
import com.scanx.app.data.PageFilter
import java.io.File

/**
 * 1 trang vừa chụp trong phiên quét: ảnh master màu đầy đủ độ phân giải nằm trên đĩa ([masterFile],
 * JPEG q92), trong RAM chỉ giữ ảnh xem trước nhỏ đã áp bộ lọc ([preview]) → chụp hàng chục trang
 * không bị tràn bộ nhớ như khi giữ nguyên bitmap full-res.
 *
 * [pageFilter] (bản 0.8, màn "Chỉnh sửa trang" → tab "Bộ lọc", chỉnh TRƯỚC khi lưu tài liệu): null =
 * dùng đúng chế độ PDF mặc định của tài liệu (như trước bản 0.8); có chọn → mang theo khi lưu
 * ([DocumentRepository.saveDocument]) làm override riêng trang đó ngay từ đầu.
 */
data class CapturedPage(val masterFile: File, val preview: Bitmap, val pageFilter: PageFilter? = null)
