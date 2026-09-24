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

    private val lockMain = Any()
    private val lockRefine = Any()
    @Volatile private var net: Net? = null
    /** Bản 0.8: instance riêng cho bước tinh chỉnh góc sau khi chụp ([detectRefine]) — dùng
     *  [Net] và khoá riêng với luồng xem trước ([detect]) để 2 việc không khoá chờ lẫn nhau (trước
     *  đây dùng chung 1 Net + 1 khoá nên khi đang xử lý trang vừa chụp, khung hình xem trước có thể
     *  bị khựng vài chục ms chờ tới lượt). Cả 2 chạy trên cùng model, chỉ tốn thêm vài MB RAM. */
    @Volatile private var netRefine: Net? = null
    @Volatile private var loadFailed = false

    val isReady: Boolean get() = net != null && netRefine != null

    /** Nạp model (copy từ assets ra filesDir vì OpenCV DNN đọc theo đường dẫn file). Gọi trên luồng nền. */
    fun ensureLoaded(context: Context): Boolean {
        if (net != null && netRefine != null) return true
        if (loadFailed) return false
        synchronized(lockMain) {
            if (net != null && netRefine != null) return true
            return try {
                val file = File(context.filesDir, ASSET_NAME)
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open(ASSET_NAME).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (net == null) {
                    val loaded = Dnn.readNetFromONNX(file.absolutePath)
                    loaded.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                    loaded.setPreferableTarget(Dnn.DNN_TARGET_CPU)
                    net = loaded
                }
                if (netRefine == null) {
                    val loaded2 = Dnn.readNetFromONNX(file.absolutePath)
                    loaded2.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                    loaded2.setPreferableTarget(Dnn.DNN_TARGET_CPU)
                    netRefine = loaded2
                }
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Không nạp được model DocAligner", e)
                loadFailed = true
                false
            }
        }
    }

    /**
     * Phát hiện tài liệu trên ảnh BGR (CV_8UC3) đã xoay đứng — dùng cho luồng xem trước real-time.
     * Ảnh bất kỳ kích thước — sẽ được co về 256×256 (co méo, đúng cách model được huấn luyện). Trả
     * về null nếu không thấy tài liệu.
     */
    fun detect(bgrUpright: Mat): DetectedQuad? = runDetect(net, lockMain, bgrUpright)

    /**
     * Phát hiện lại trên ảnh vừa chụp (độ phân giải đầy đủ, đã thu nhỏ) để tinh chỉnh góc — dùng
     * [Net] + khoá RIÊNG với [detect] (bản 0.8) để không làm khựng khung hình xem trước của trang
     * tiếp theo trong lúc trang vừa chụp còn đang được xử lý.
     */
    fun detectRefine(bgrUpright: Mat): DetectedQuad? = runDetect(netRefine, lockRefine, bgrUpright)

    /** 4 heatmap góc (mỗi cái [width]×[height], thường 128×128) dạng mảng float — bản 0.9. */
    class Heatmaps(val maps: List<FloatArray>, val width: Int, val height: Int)

    /**
     * Bản 0.9: chạy model cho luồng xem trước và trả về heatmap THÔ (không tự chọn góc) để
     * [HeatmapPeaks] chọn đỉnh có xét khung đang theo dõi + [DocumentTracker] làm mượt/giữ khung.
     * Dùng [Net]/khoá của luồng xem trước (như [detect]). Null nếu model chưa nạp/lỗi.
     */
    fun detectHeatmaps(bgrUpright: Mat): Heatmaps? {
        val m = net ?: return null
        val channels: List<Mat> = synchronized(lockMain) {
            val blob = Dnn.blobFromImage(
                bgrUpright, 1.0 / 255.0, Size(INPUT_SIZE, INPUT_SIZE), Scalar(0.0, 0.0, 0.0), false, false,
            )
            m.setInput(blob)
            val out = m.forward()
            blob.release()
            val images = ArrayList<Mat>()
            Dnn.imagesFromBlob(out, images)
            out.release()
            val list = ArrayList<Mat>()
            Core.split(images[0], list)
            images.forEach { it.release() }
            list
        }
        try {
            if (channels.size < 4) return null
            val w = channels[0].cols()
            val h = channels[0].rows()
            val maps = channels.take(4).map { ch ->
                val c = if (ch.isContinuous) ch else ch.clone()
                val arr = FloatArray(w * h)
                c.get(0, 0, arr)
                if (c !== ch) c.release()
                arr
            }
            return Heatmaps(maps, w, h)
        } finally {
            channels.forEach { it.release() }
        }
    }

    private fun runDetect(model: Net?, lock: Any, bgrUpright: Mat): DetectedQuad? {
        val m = model ?: return null
        val frameW = bgrUpright.cols()
        val frameH = bgrUpright.rows()

        val heatmaps: List<Mat> = synchronized(lock) {
            val blob = Dnn.blobFromImage(
                bgrUpright, 1.0 / 255.0, Size(INPUT_SIZE, INPUT_SIZE), Scalar(0.0, 0.0, 0.0), false, false,
            )
            m.setInput(blob)
            val out = m.forward()
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
     * "Chữ ký" nội dung trang: vùng tài liệu làm phẳng về 48×64 ảnh xám rồi lọc thông cao (trừ nền
     * mờ Gauss σ=6) → chỉ còn nét chữ/đường kẻ, không phụ thuộc ánh sáng/bóng đổ. So 2 chữ ký bằng
     * tương quan Pearson ([pageSimilarity]). Kiểm thử trên 9 ảnh chụp trùng 1 trang thật (có tay che,
     * lệch khung): tương quan 0,36–0,98; giữa các trang khác nhau: ≤ 0,15.
     */
    fun pageSignature(bgrUpright: Mat, quad: DetectedQuad): FloatArray {
        val w = bgrUpright.cols().toDouble()
        val h = bgrUpright.rows().toDouble()
        val src = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(SIG_W - 1.0, 0.0), Point(SIG_W - 1.0, SIG_H - 1.0), Point(0.0, SIG_H - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(bgrUpright, warped, m, Size(SIG_W.toDouble(), SIG_H.toDouble()), Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_BGR2GRAY)
        val sig = signatureFromGray(gray)
        src.release(); dst.release(); m.release(); warped.release(); gray.release()
        return sig
    }

    /**
     * Bản 0.9: chữ ký CHỐNG RĂNG CƯA — làm phẳng vùng trang ra ảnh gấp 4 lần (192×256, nội suy tuyến
     * tính) rồi mới thu về 48×64 bằng INTER_AREA (lấy trung bình). [pageSignature] warp thẳng về
     * 48×64 (warpPerspective không hỗ trợ INTER_AREA → thực chất lấy mẫu điểm, bị răng cưa): lệch khung
     * 1 px là chữ ký đổi hẳn. Đo trên cùng 1 trang dưới rung tay nhẹ: tương quan 2 khung liền nhau
     * thấp nhất 0,33 → 0,74 (trang tổng hợp chữ dày), 0,53 → 0,70 (cảnh thật). Dùng cho đo độ nét và
     * phát hiện nội dung đổi mạnh; KHÔNG thay [pageSignature] trong chống trùng trang (ngưỡng cũ đã
     * đo trên chữ ký đó).
     */
    fun pageSignatureSharp(bgrUpright: Mat, quad: DetectedQuad): FloatArray {
        val w = bgrUpright.cols().toDouble()
        val h = bgrUpright.rows().toDouble()
        val k = 4.0
        val src = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(SIG_W * k, 0.0), Point(SIG_W * k, SIG_H * k), Point(0.0, SIG_H * k),
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(bgrUpright, warped, m, Size(SIG_W * k, SIG_H * k), Imgproc.INTER_LINEAR)
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_BGR2GRAY)
        val sig = signatureFromGray(gray)
        src.release(); dst.release(); m.release(); warped.release(); gray.release()
        return sig
    }

    /** Chữ ký của 1 ảnh trang đã làm phẳng (xám, kích thước bất kỳ) — dùng kiểm tra trùng sau khi chụp. */
    fun signatureFromGray(gray: Mat): FloatArray {
        val small = Mat()
        if (gray.cols() != SIG_W || gray.rows() != SIG_H) {
            Imgproc.resize(gray, small, Size(SIG_W.toDouble(), SIG_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            gray.copyTo(small)
        }
        val f = Mat()
        small.convertTo(f, org.opencv.core.CvType.CV_32F, 1.0 / 255.0)
        val bg = Mat()
        Imgproc.GaussianBlur(f, bg, Size(0.0, 0.0), 6.0)
        Core.subtract(f, bg, f)
        val out = FloatArray(SIG_W * SIG_H)
        f.get(0, 0, out)
        small.release(); f.release(); bg.release()
        return out
    }

    /** Độ "có nội dung" của trang — xem [PageSignature.texture]. */
    fun signatureTexture(sig: FloatArray): Float = PageSignature.texture(sig)

    /** Độ giống nhau của 2 trang, -1..1 — xem [PageSignature.similarity]. */
    fun pageSimilarity(a: FloatArray, b: FloatArray): Float = PageSignature.similarity(a, b)

    const val SIG_W = PageSignature.SIG_W
    const val SIG_H = PageSignature.SIG_H
    const val BLANK_TEXTURE = PageSignature.BLANK_TEXTURE
}
