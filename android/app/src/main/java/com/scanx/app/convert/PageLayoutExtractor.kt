package com.scanx.app.convert

import android.graphics.Bitmap
import android.graphics.Rect
import com.scanx.app.scan.ScanFilters
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Phần phụ thuộc Android của engine chuyển đổi: từ ảnh 1 trang (màu, đúng chiều) → [PageInput].
 *  - Chuẩn hoá ánh sáng trước (bản đen trắng A2 của [ScanFilters]) → OCR, đường kẻ, độ dày nét
 *    không bị bóng đổ/nền ố làm sai.
 *  - OCR đa ngôn ngữ ([MultiScriptOcr]: Latin + Hàn/Nhật/Trung, gán ngôn ngữ từng dòng) → dòng + từ
 *    kèm toạ độ, độ tin cậy, ngôn ngữ.
 *  - Đường kẻ bảng: nhị phân thích nghi + morphology mở với phần tử cấu trúc dài ngang/dọc.
 *  - Màu chữ: trung bình màu các điểm mực của từng dòng trên ảnh màu đã cân bằng trắng → quy về
 *    đen/xanh/đỏ/xanh lá/tím ([InkColor]).
 *  - Hình giữ nguyên dạng ảnh: CHỈ con dấu đỏ và vùng màu không chứa chữ (logo, hình minh hoạ);
 *    chữ viết bằng mực màu luôn được xuất thành chữ.
 */
class PageLayoutExtractor(private val ocr: MultiScriptOcr) {

    suspend fun extract(page: Bitmap): PageInput {
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val gray = ScanFilters.bwHq(rgba)
        val color = ScanFilters.colorNormalized(rgba)
        val ocrBmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val shown = Mat()
        Imgproc.cvtColor(gray, shown, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(shown, ocrBmp)
        shown.release()
        try {
            val lines = ocr.recognize(ocrBmp).map { l ->
                val r = Rect(l.box.left.toInt(), l.box.top.toInt(), l.box.right.toInt(), l.box.bottom.toInt())
                val (stroke, ink) = strokeAndInk(gray, color, r)
                l.copy(strokeWidth = stroke, color = ink, words = l.words.map { it.copy(color = ink) })
            }
            val rules = detectRules(gray)
            val figures = detectFigures(rgba, page)
            return PageInput(page.width, page.height, lines, rules, figures)
        } finally {
            ocrBmp.recycle()
            rgba.release()
            gray.release()
            color.release()
        }
    }

    /**
     * Độ dày nét trung bình (px) = 2 × trung bình distance transform trên điểm mực, và màu mực của dòng.
     */
    private fun strokeAndInk(gray: Mat, color: Mat, r: Rect): Pair<Float, Int> {
        val x = r.left.coerceIn(0, gray.cols() - 1)
        val y = r.top.coerceIn(0, gray.rows() - 1)
        val w = (r.right.coerceAtMost(gray.cols()) - x)
        val h = (r.bottom.coerceAtMost(gray.rows()) - y)
        if (w < 4 || h < 4) return 0f to InkColor.BLACK
        val crop = gray.submat(y, y + h, x, x + w)
        val bw = Mat()
        Imgproc.threshold(crop, bw, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val dt = Mat()
        Imgproc.distanceTransform(bw, dt, Imgproc.DIST_L2, 3)
        val stroke = (2.0 * Core.mean(dt, bw).`val`[0]).toFloat()
        val cc = color.submat(y, y + h, x, x + w)
        val m = Core.mean(cc, bw).`val`
        val ink = if (Core.countNonZero(bw) < 10) InkColor.BLACK else InkColor.classify(m[0].toInt(), m[1].toInt(), m[2].toInt())
        crop.release(); bw.release(); dt.release(); cc.release()
        return stroke to ink
    }

    private fun detectRules(gray: Mat): List<RuleSegment> {
        val w = gray.cols()
        val h = gray.rows()
        val bw = Mat()
        Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
        val out = ArrayList<RuleSegment>()
        fun collect(kernel: Size, grow: Size, horizontal: Boolean) {
            val m = Mat()
            Imgproc.morphologyEx(bw, m, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kernel))
            Imgproc.dilate(m, m, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, grow))
            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(m, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (c in contours) {
                val r = Imgproc.boundingRect(c)
                if (horizontal && r.width > w / 15) {
                    val yc = r.y + r.height / 2f
                    out.add(RuleSegment(r.x.toFloat(), yc, (r.x + r.width).toFloat(), yc))
                } else if (!horizontal && r.height > h / 50) {
                    val xc = r.x + r.width / 2f
                    out.add(RuleSegment(xc, r.y.toFloat(), xc, (r.y + r.height).toFloat()))
                }
                c.release()
            }
            hier.release(); m.release()
        }
        collect(Size((w / 30).coerceAtLeast(10).toDouble(), 1.0), Size(9.0, 3.0), horizontal = true)
        collect(Size(1.0, (h / 45).coerceAtLeast(10).toDouble()), Size(3.0, 9.0), horizontal = false)
        bw.release()
        return out
    }

    /** Vùng mực màu gọn: con dấu đỏ (luôn giữ ảnh) và hình/logo màu (LayoutAnalyzer loại vùng có chữ). */
    private fun detectFigures(rgba: Mat, page: Bitmap): List<Figure> {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 90.0, 0.0), Scalar(180.0, 255.0, 235.0), mask)
        val red1 = Mat()
        val red2 = Mat()
        Core.inRange(hsv, Scalar(0.0, 90.0, 40.0), Scalar(10.0, 255.0, 255.0), red1)
        Core.inRange(hsv, Scalar(160.0, 90.0, 40.0), Scalar(180.0, 255.0, 255.0), red2)
        Core.bitwise_or(red1, red2, red1)
        red2.release(); hsv.release()
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Mat.ones(3, 3, CvType.CV_8U))
        Imgproc.dilate(mask, mask, Mat.ones(21, 21, CvType.CV_8U))
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(mask, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release(); hier.release()
        val area = page.width.toDouble() * page.height
        val out = ArrayList<Figure>()
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            c.release()
            if (r.width.toDouble() * r.height < area * 0.003 || out.size >= 12) continue
            val x = r.x.coerceIn(0, page.width - 1)
            val y = r.y.coerceIn(0, page.height - 1)
            val cw = r.width.coerceAtMost(page.width - x)
            val ch = r.height.coerceAtMost(page.height - y)
            if (cw < 8 || ch < 8) continue
            // Con dấu: phần lớn điểm màu là đỏ, khung gần vuông/tròn.
            val sub = red1.submat(y, y + ch, x, x + cw)
            val redRatio = Core.countNonZero(sub).toDouble() / max(1, cw * ch)
            sub.release()
            val aspect = cw.toDouble() / ch
            val isStamp = redRatio > 0.08 && aspect in 0.6..1.7 && cw * ch > area * 0.006
            val crop = Bitmap.createBitmap(page, x, y, cw, ch)
            val bos = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            crop.recycle()
            out.add(Figure(Box(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), bos.toByteArray(), isStamp))
        }
        red1.release()
        return out
    }
}
