package com.scanx.app.scan

import android.graphics.Bitmap
import java.io.File

/**
 * 1 trang vừa chụp trong phiên quét: ảnh master màu đầy đủ độ phân giải nằm trên đĩa ([masterFile],
 * JPEG q92), trong RAM chỉ giữ ảnh xem trước nhỏ đã áp bộ lọc mặc định ([preview]) → chụp hàng chục
 * trang không bị tràn bộ nhớ như khi giữ nguyên bitmap full-res.
 */
class CapturedPage(val masterFile: File, val preview: Bitmap)
