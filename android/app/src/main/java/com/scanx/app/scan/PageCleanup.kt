package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * "Làm sạch" (bản 0.8, màn "Chỉnh sửa trang" → tab "Làm sạch"): xoá vết đen/ố/dấu bẩn trên ảnh master
 * bằng bút xoá — mỗi nét vẽ tay đánh dấu 1 vùng cần xoá, rồi dùng OpenCV inpaint (thuật toán Telea)
 * để "vá" lại vùng đó bằng màu/vân giấy lấy từ xung quanh — không phải tô trắng phẳng, nên chỗ vừa
 * xoá khớp cả với giấy hơi ố/vàng hoặc có vân, không bị lộ mảng trắng như tẩy thô.
 */
object PageCleanup {

    /** 1 nét bút xoá: các điểm liên tiếp (chuẩn hoá [0,1] theo ảnh) + bán kính nét (chuẩn hoá theo
     *  cạnh dài ảnh) — lưu chuẩn hoá để áp lại đúng tỉ lệ dù xem trước ở ảnh thu nhỏ hay xuất ở ảnh gốc. */
    data class Stroke(val points: List<PointF>, val radiusNormalized: Float)

    /** Vá lại [master] theo danh sách nét xoá [strokes]. Trả về bitmap mới, không sửa [master]. */
    fun heal(master: Bitmap, strokes: List<Stroke>): Bitmap {
        if (strokes.isEmpty()) return master.copy(master.config ?: Bitmap.Config.ARGB_8888, false)
        val w = master.width; val h = master.height
        val longSide = maxOf(w, h)
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        try {
            for (stroke in strokes) {
                val radiusPx = (stroke.radiusNormalized * longSide).toInt().coerceAtLeast(2)
                val pts = stroke.points
                for (i in pts.indices) {
                    val p = Point((pts[i].x * w).toDouble(), (pts[i].y * h).toDouble())
                    Imgproc.circle(mask, p, radiusPx, Scalar(255.0), -1)
                    if (i > 0) {
                        val prev = Point((pts[i - 1].x * w).toDouble(), (pts[i - 1].y * h).toDouble())
                        Imgproc.line(mask, prev, p, Scalar(255.0), radiusPx * 2)
                    }
                }
            }
            val src = Mat()
            Utils.bitmapToMat(master, src)
            try {
                val rgb = Mat()
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
                try {
                    // Nới rộng mask thêm vài px để vá hết cả viền mờ quanh vết bẩn (không để sót viền cũ).
                    val maskDilated = Mat()
                    Imgproc.dilate(mask, maskDilated, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
                    try {
                        val healed = Mat()
                        Photo.inpaint(rgb, maskDilated, healed, 9.0, Photo.INPAINT_TELEA)
                        try {
                            val rgba = Mat()
                            Imgproc.cvtColor(healed, rgba, Imgproc.COLOR_RGB2RGBA)
                            try {
                                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                Utils.matToBitmap(rgba, out)
                                return out
                            } finally {
                                rgba.release()
                            }
                        } finally {
                            healed.release()
                        }
                    } finally {
                        maskDilated.release()
                    }
                } finally {
                    rgb.release()
                }
            } finally {
                src.release()
            }
        } finally {
            mask.release()
        }
    }
}
