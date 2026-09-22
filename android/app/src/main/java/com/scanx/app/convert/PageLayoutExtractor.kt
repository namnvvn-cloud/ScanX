package com.scanx.app.convert

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.scanx.app.util.awaitTask
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/**
 * Phần phụ thuộc Android của engine chuyển đổi: từ ảnh 1 trang → [PageInput] cho [LayoutAnalyzer].
 *  - OCR: ML Kit Text Recognition v2 (on-device, tiếng Việt hỗ trợ chính thức) → dòng + từ kèm toạ độ.
 *  - Đường kẻ bảng: nhị phân hoá thích nghi + morphology mở với phần tử cấu trúc dài ngang/dọc.
 *  - Độ dày nét từng dòng (distance transform) → suy chữ đậm.
 *  - Vùng mực màu (con dấu đỏ, chữ ký xanh) → cắt nguyên ảnh để chèn lại đúng vị trí.
 */
class PageLayoutExtractor(private val recognizer: TextRecognizer) {

    suspend fun extract(page: Bitmap): PageInput {
        val text = recognizer.process(InputImage.fromBitmap(page, 0)).awaitTask()

        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        try {
            val lines = ocrLines(text, gray)
            val rules = detectRules(gray)
            val figures = detectColorFigures(rgba, page)
            return PageInput(page.width, page.height, lines, rules, figures)
        } finally {
            rgba.release()
            gray.release()
        }
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun ocrLines(text: Text, gray: Mat): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox ?: continue
                val words = line.elements.mapNotNull { el -> el.boundingBox?.let { OcrWord(el.text, it.toBox()) } }
                out.add(OcrLine(line.text, rect.toBox(), words, strokeWidth(gray, rect)))
            }
        }
        return out
    }

    /** Độ dày nét trung bình trong khung dòng (px): 2 × trung bình distance transform trên điểm mực. */
    private fun strokeWidth(gray: Mat, r: Rect): Float {
        val x = r.left.coerceIn(0, gray.cols() - 1)
        val y = r.top.coerceIn(0, gray.rows() - 1)
        val w = (r.right.coerceAtMost(gray.cols()) - x)
        val h = (r.bottom.coerceAtMost(gray.rows()) - y)
        if (w < 4 || h < 4) return 0f
        val crop = gray.submat(y, y + h, x, x + w)
        val bw = Mat()
        Imgproc.threshold(crop, bw, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val dt = Mat()
        Imgproc.distanceTransform(bw, dt, Imgproc.DIST_L2, 3)
        val mean = Core.mean(dt, bw).`val`[0]
        crop.release(); bw.release(); dt.release()
        return (2.0 * mean).toFloat()
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
        collect(Size(1.0, (h / 60).coerceAtLeast(10).toDouble()), Size(3.0, 9.0), horizontal = false)
        bw.release()
        return out
    }

    /** Vùng mực có màu (đỏ/xanh): con dấu, chữ ký, logo màu → cắt JPEG giữ nguyên. */
    private fun detectColorFigures(rgba: Mat, page: Bitmap): List<Figure> {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 90.0, 0.0), Scalar(180.0, 255.0, 235.0), mask)
        hsv.release()
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
            val crop = Bitmap.createBitmap(page, x, y, cw, ch)
            val bos = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            crop.recycle()
            out.add(Figure(Box(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), bos.toByteArray()))
        }
        return out
    }
}
