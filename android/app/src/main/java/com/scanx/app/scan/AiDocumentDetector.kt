package com.scanx.app.scan

import android.content.Context
import android.graphics.PointF
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs

/**
 * 4 góc tài liệu, thứ tự top-left / top-right / bottom-right / bottom-left, chuẩn hoá [0,1] theo
 * khung ảnh đã xoay đứng (upright). [frameWidth]/[frameHeight] là kích thước khung upright gốc để
 * overlay map đúng tỉ lệ ra PreviewView (FILL_CENTER). [confidence] = độ tin cậy thấp nhất trong
 * 4 góc (0..1) — thấp khi ảnh mờ/thiếu sáng nên dùng luôn làm cổng chất lượng trước khi tự chụp.
 */
data class DetectedQuad(
    val points: List<PointF>,
    val confidence: Float,
    val areaRatio: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * Bộ phát hiện tài liệu bằng AI: model DocAligner (DocsaidLab, Apache-2.0) — backbone PP-LCNet
 * 1.0 + BiFPN, dự đoán 4 heatmap góc (input 256×256 BGR, output 4×128×128). Đạt JI 0.989 trên
 * SmartDoc 2015. Model đã được rút gọn đồ thị (thay Einsum của BiFPN bằng Mul/Add tương đương)
 * để chạy thẳng bằng module DNN có sẵn trong OpenCV 4.11 — không cần thêm ONNX Runtime/TFLite.
 *
 * Đã kiểm thử trên ảnh thật: không có tài liệu → confidence ≈ 0; có tài liệu → 0.85–0.96.
 */
object AiDocumentDetector {

    private const val TAG = "AiDocumentDetector"
    private const val ASSET_NAME = "docaligner_lcnet100_cv.onnx"
    private const val INPUT_SIZE = 256.0
    private const val MIN_CORNER_CONFIDENCE = 0.35
    private const val MIN_AREA_RATIO = 0.08f

    private val lock = Any()
    @Volatile private var net: Net? = null
    @Volatile private var loadFailed = false

    val isReady: Boolean get() = net != null

    /** Nạp model (copy từ assets ra filesDir vì OpenCV DNN đọc theo đường dẫn file). Gọi trên luồng nền. */
    fun ensureLoaded(context: Context): Boolean {
        if (net != null) return true
        if (loadFailed) return false
        synchronized(lock) {
            if (net != null) return true
            return try {
                val file = File(context.filesDir, ASSET_NAME)
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open(ASSET_NAME).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val loaded = Dnn.readNetFromONNX(file.absolutePath)
                loaded.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                loaded.setPreferableTarget(Dnn.DNN_TARGET_CPU)
                net = loaded
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Không nạp được model DocAligner", e)
                loadFailed = true
                false
            }
        }
    }

    /**
     * Phát hiện tài liệu trên ảnh BGR (CV_8UC3) đã xoay đứng. Ảnh bất kỳ kích thước — sẽ được co
     * về 256×256 (co méo, đúng cách model được huấn luyện). Trả về null nếu không thấy tài liệu.
     */
    fun detect(bgrUpright: Mat): DetectedQuad? {
        val model = net ?: return null
        val frameW = bgrUpright.cols()
        val frameH = bgrUpright.rows()

        val heatmaps: List<Mat> = synchronized(lock) {
            val blob = Dnn.blobFromImage(
                bgrUpright, 1.0 / 255.0, Size(INPUT_SIZE, INPUT_SIZE), Scalar(0.0, 0.0, 0.0), false, false,
            )
            model.setInput(blob)
            val out = model.forward()
            blob.release()
            val images = ArrayList<Mat>()
            Dnn.imagesFromBlob(out, images)
            out.release()
            val channels = ArrayList<Mat>()
            Core.split(images[0], channels)
            images.forEach { it.release() }
            channels
        }
        if (heatmaps.size < 4) return null

        val corners = ArrayList<PointF>(4)
        var minConfidence = 1.0
        for (hm in heatmaps) {
            val peak = refinePeak(hm)
            hm.release()
            if (peak == null) {
                heatmaps.forEach { it.release() }
                return null
            }
            corners.add(peak.first)
            if (peak.second < minConfidence) minConfidence = peak.second
        }
        if (minConfidence < MIN_CORNER_CONFIDENCE) return null

        val ordered = orderCorners(corners)
        val area = polygonArea(ordered)
        if (area < MIN_AREA_RATIO || !isConvex(ordered)) return null

        return DetectedQuad(ordered, minConfidence.toFloat(), area, frameW, frameH)
    }

    /**
     * Đỉnh heatmap + trọng tâm có trọng số trong cửa sổ 7×7 quanh đỉnh (chỉ lấy điểm ≥ 50% đỉnh)
     * cho toạ độ dưới-pixel, ổn định hơn nhiều so với lấy argmax thô → khung ít rung, tự chụp nhanh hơn.
     */
    private fun refinePeak(hm: Mat): Pair<PointF, Double>? {
        val mm = Core.minMaxLoc(hm)
        val maxVal = mm.maxVal
        if (maxVal < MIN_CORNER_CONFIDENCE) return null
        val w = hm.cols()
        val h = hm.rows()
        val px = mm.maxLoc.x.toInt()
        val py = mm.maxLoc.y.toInt()
        val r = 3
        val x0 = (px - r).coerceAtLeast(0)
        val x1 = (px + r).coerceAtMost(w - 1)
        val y0 = (py - r).coerceAtLeast(0)
        val y1 = (py + r).coerceAtMost(h - 1)
        val cols = x1 - x0 + 1
        val buf = FloatArray(cols)
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        val cutoff = maxVal * 0.5
        for (y in y0..y1) {
            hm.get(y, x0, buf)
            for (i in 0 until cols) {
                val v = buf[i].toDouble()
                if (v >= cutoff) {
                    sw += v
                    sx += v * (x0 + i)
                    sy += v * y
                }
            }
        }
        if (sw <= 0.0) return null
        val cx = (sx / sw + 0.5) / w
        val cy = (sy / sw + 0.5) / h
        return PointF(cx.toFloat().coerceIn(0f, 1f), cy.toFloat().coerceIn(0f, 1f)) to maxVal
    }

    /** Sắp xếp lại theo hình học (không phụ thuộc thứ tự kênh của model khi điện thoại xoay ngang/dọc). */
    fun orderCorners(pts: List<PointF>): List<PointF> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val rest = pts.filter { it !== tl && it !== br }
        if (rest.size != 2) {
            val tr = pts.minByOrNull { it.y - it.x }!!
            val bl = pts.maxByOrNull { it.y - it.x }!!
            return listOf(tl, tr, br, bl)
        }
        val tr = rest.minByOrNull { it.y - it.x }!!
        val bl = rest.first { it !== tr }
        return listOf(tl, tr, br, bl)
    }

    private fun polygonArea(p: List<PointF>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private fun isConvex(p: List<PointF>): Boolean {
        val mat = MatOfPoint(*p.map { Point(it.x * 1000.0, it.y * 1000.0) }.toTypedArray())
        val convex = Imgproc.isContourConvex(mat)
        mat.release()
        return convex
    }

    /**
     * "Chữ ký" nội dung trang: vùng tài liệu làm phẳng về 24×32 ảnh xám, chuẩn hoá trừ trung bình.
     * Dùng để nhận biết đã lật sang trang mới (khác chữ ký trang vừa chụp) và chống chụp trùng.
     */
    fun pageSignature(bgrUpright: Mat, quad: DetectedQuad): FloatArray {
        val w = bgrUpright.cols().toDouble()
        val h = bgrUpright.rows().toDouble()
        val src = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(23.0, 0.0), Point(23.0, 31.0), Point(0.0, 31.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(bgrUpright, warped, m, Size(24.0, 32.0))
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_BGR2GRAY)
        val bytes = ByteArray(24 * 32)
        gray.get(0, 0, bytes)
        src.release(); dst.release(); m.release(); warped.release(); gray.release()
        val values = FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
        val mean = values.average().toFloat()
        for (i in values.indices) values[i] -= mean
        return values
    }

    fun signatureDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 1f
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }
}
