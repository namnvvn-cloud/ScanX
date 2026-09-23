package com.scanx.app.scan

import android.graphics.Bitmap
import com.scanx.app.data.PageFilter
import com.scanx.app.data.PdfExportMode
import com.scanx.app.data.PdfImage
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bộ lọc "scan" chạy trên ảnh gốc màu đã làm phẳng (master). App luôn giữ master màu nên đổi chế
 * độ xuất bất cứ lúc nào mà không phải chụp lại.
 *
 * Bước chung — chuẩn hoá ánh sáng: ước lượng nền giấy bằng phép giãn (dilate) trên ảnh thu nhỏ
 * (xoá nét chữ) + median + Gauss, rồi chia ảnh cho nền → khử bóng tay/điện thoại, nền ố, sáng không
 * đều; giấy về trắng đều (kỹ thuật "background division" dùng trong các app scan thương mại).
 *  - Đen trắng chất lượng cao (A2): xám chuẩn hoá + kéo điểm đen/trắng + gamma → nét mịn, nền trắng.
 *  - Đen trắng nhỏ gọn (A1): nhị phân hoá thích nghi trên ảnh đã chuẩn hoá (ngưỡng = 86% trung bình
 *    cục bộ 31×31, trần 205) → 1-bit, nhẹ nhất.
 *  - Màu (B1/B2): chuẩn hoá từng kênh (cân bằng trắng + khử bóng), tăng bão hoà 15% cho mực/dấu rõ.
 */
object ScanFilters {

    /** Ước lượng nền (giấy) của 1 kênh 8-bit, cùng kích thước. */
    private fun background(ch: Mat): Mat {
        val w = ch.cols()
        val h = ch.rows()
        val s = max(1, (max(w, h) / 400.0).roundToInt())
        val small = Mat()
        Imgproc.resize(ch, small, Size((w / s).toDouble(), (h / s).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        var k = max(3, min(small.cols(), small.rows()) / 40)
        if (k % 2 == 0) k++
        Imgproc.dilate(small, small, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        Imgproc.medianBlur(small, small, min(k, 255))
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), k / 2.0)
        val bg = Mat()
        Imgproc.resize(small, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return bg
    }

    /** Ảnh xám đã chuẩn hoá ánh sáng (8-bit). [rgba] CV_8UC4. */
    fun normalizedGray(rgba: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val bg = background(gray)
        val out = Mat()
        Core.divide(gray, bg, out, 255.0)
        gray.release(); bg.release()
        return out
    }

    /**
     * Đen trắng chất lượng cao (xám mịn). Có unsharp mask nhẹ (bán kính 2 px, 60%) để nét chữ đều
     * nhau giữa giữa trang và mép trang — mép trang luôn hơi mờ hơn do ống kính và độ cong giấy.
     */
    fun bwHq(rgba: Mat): Mat {
        val n = normalizedGray(rgba)
        Imgproc.medianBlur(n, n, 3)
        val blur = Mat()
        Imgproc.GaussianBlur(n, blur, Size(0.0, 0.0), 2.0)
        Core.addWeighted(n, 1.6, blur, -0.6, 0.0, n)
        blur.release()
        val bytes = ByteArray(n.cols() * n.rows())
        n.get(0, 0, bytes)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        // Điểm đen = phân vị 5% của các điểm tối (< 200), tối đa 120.
        val darkTotal = (0 until 200).sumOf { hist[it] }
        var lo = 40
        if (darkTotal > 100) {
            var acc = 0
            val target = darkTotal * 0.05
            for (v in 0 until 200) { acc += hist[v]; if (acc >= target) { lo = v; break } }
        }
        lo = min(lo, 120)
        val hi = 0.86 * 255
        val lut = Mat(1, 256, CvType.CV_8U)
        val table = ByteArray(256) { v ->
            val t = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            (t.pow(1.5) * 255).roundToInt().toByte()
        }
        lut.put(0, 0, table)
        val out = Mat()
        Core.LUT(n, lut, out)
        n.release(); lut.release()
        return out
    }

    /** Nhị phân 1-bit: trả về Mat 8-bit 0 (mực) / 255 (giấy). */
    fun bwBinary(rgba: Mat): Mat {
        val n = normalizedGray(rgba)
        val mean = Mat()
        Imgproc.boxFilter(n, mean, -1, Size(31.0, 31.0))
        Core.multiply(mean, Scalar(0.86), mean)
        Core.min(mean, Scalar(205.0), mean)
        val out = Mat()
        Core.compare(n, mean, out, Core.CMP_GT)
        n.release(); mean.release()
        return out
    }

    /** Màu đã chuẩn hoá (RGB 8-bit, CV_8UC3). */
    fun colorNormalized(rgba: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val chs = ArrayList<Mat>()
        Core.split(rgb, chs)
        for (c in chs) {
            val bg = background(c)
            Core.divide(c, bg, c, 255.0 / 0.94)
            bg.release()
        }
        Core.merge(chs, rgb)
        chs.forEach { it.release() }
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        Core.multiply(hsvCh[1], Scalar(1.15), hsvCh[1])
        Core.merge(hsvCh, hsv)
        hsvCh.forEach { it.release() }
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return rgb
    }

    /**
     * Tăng tương phản cục bộ (CLAHE trên kênh L của Lab) sau khi đã chuẩn hoá màu — bản 0.8, chế độ
     * "Bóng" trong màn Chỉnh sửa trang: khử bóng/ánh sáng không đều mạnh hơn [colorNormalized] một
     * bậc, dùng cho ảnh chụp bảng trắng/giấy bóng/ánh sáng chéo mà chế độ Màu thường vẫn còn ám bóng.
     */
    fun shadowEnhanced(rgba: Mat): Mat {
        val rgb = colorNormalized(rgba)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val chs = ArrayList<Mat>()
        Core.split(lab, chs)
        val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
        clahe.apply(chs[0], chs[0])
        Core.merge(chs, lab)
        chs.forEach { it.release() }
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return rgb
    }

    /**
     * Chế độ "Tự động" (bản 0.8): tự chọn Màu hay Đen trắng theo nội dung — đo độ bão hoà trung bình
     * (kênh S của HSV) trên ảnh đã chuẩn hoá màu; trang gần như không màu (chữ đen/nền trắng) → Đen
     * trắng cho nhẹ và nét; trang có màu thật (ảnh, biểu đồ màu, dấu đỏ nổi bật…) → giữ Màu.
     */
    private fun autoPick(rgba: Mat): Mat {
        val rgb = colorNormalized(rgba)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val chs = ArrayList<Mat>()
        Core.split(hsv, chs)
        val meanSat = Core.mean(chs[1]).`val`[0]
        chs.forEach { it.release() }
        hsv.release()
        return if (meanSat > AUTO_COLOR_SAT_THRESHOLD) {
            rgb
        } else {
            rgb.release()
            bwHq(rgba)
        }
    }

    /** Dựng ảnh theo 1 trong 6 chế độ lọc riêng trang (bản 0.8). Trả về Mat 1 kênh (xám) hoặc 3 kênh (RGB). */
    private fun renderPageFilterMat(rgba: Mat, filter: PageFilter): Mat = when (filter) {
        PageFilter.ORIGINAL -> {
            val rgb = Mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgb
        }
        PageFilter.AUTO -> autoPick(rgba)
        PageFilter.COLOR -> colorNormalized(rgba)
        PageFilter.BW -> bwHq(rgba)
        PageFilter.GRAY -> normalizedGray(rgba)
        PageFilter.SHADOW -> shadowEnhanced(rgba)
    }

    /** Ảnh xem trước theo bộ lọc riêng trang (bản 0.8), cạnh dài ≤ [maxSide]. Dùng cho màn Chỉnh sửa trang. */
    fun renderPageFilter(master: Bitmap, filter: PageFilter, maxSide: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(master, rgba)
        val k = maxSide.toDouble() / max(rgba.cols(), rgba.rows())
        if (k < 1.0) Imgproc.resize(rgba, rgba, Size(rgba.cols() * k, rgba.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        val out = renderPageFilterMat(rgba, filter)
        val shown = Mat()
        Imgproc.cvtColor(out, shown, if (out.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(shown.cols(), shown.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(shown, bmp)
        rgba.release(); out.release(); shown.release()
        return bmp
    }

    /** Mã hoá theo bộ lọc riêng trang (bản 0.8) — dùng khi trang có override, chất lượng cố định (không
     *  phân biệt nhỏ gọn/chất lượng cao như [encodeForPdf], vì đây là lựa chọn riêng của người dùng). */
    fun encodePageOverride(master: Bitmap, filter: PageFilter): PdfImage {
        val rgba = Mat()
        Utils.bitmapToMat(master, rgba)
        try {
            val out = renderPageFilterMat(rgba, filter)
            return if (out.channels() == 1) {
                val img = PdfImage(out.cols(), out.rows(), PdfImage.Kind.JPEG_GRAY, jpeg(out, 82))
                out.release()
                img
            } else {
                val bgr = Mat()
                Imgproc.cvtColor(out, bgr, Imgproc.COLOR_RGB2BGR)
                val img = PdfImage(bgr.cols(), bgr.rows(), PdfImage.Kind.JPEG_RGB, jpeg(bgr, 85))
                out.release(); bgr.release()
                img
            }
        } finally {
            rgba.release()
        }
    }

    private const val AUTO_COLOR_SAT_THRESHOLD = 18.0

    /** Mã hoá ảnh master theo chế độ xuất PDF. */
    fun encodeForPdf(master: Bitmap, mode: PdfExportMode): PdfImage {
        val rgba = Mat()
        Utils.bitmapToMat(master, rgba)
        try {
            return when (mode) {
                PdfExportMode.BW_SMALL -> {
                    val bin = bwBinary(rgba)
                    val img = PdfImage(bin.cols(), bin.rows(), PdfImage.Kind.BITMAP_1BIT, packBits(bin))
                    bin.release()
                    img
                }
                PdfExportMode.BW_HQ -> {
                    val g = bwHq(rgba)
                    val img = PdfImage(g.cols(), g.rows(), PdfImage.Kind.JPEG_GRAY, jpeg(g, 75))
                    g.release()
                    img
                }
                PdfExportMode.COLOR_SMALL, PdfExportMode.COLOR_HQ -> {
                    val rgb = colorNormalized(rgba)
                    if (mode == PdfExportMode.COLOR_SMALL) {
                        val k = 1600.0 / max(rgb.cols(), rgb.rows())
                        if (k < 1.0) Imgproc.resize(rgb, rgb, Size(rgb.cols() * k, rgb.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
                    }
                    val bgr = Mat()
                    Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR)
                    val img = PdfImage(bgr.cols(), bgr.rows(), PdfImage.Kind.JPEG_RGB, jpeg(bgr, if (mode == PdfExportMode.COLOR_SMALL) 60 else 88))
                    rgb.release(); bgr.release()
                    img
                }
            }
        } finally {
            rgba.release()
        }
    }

    /** Ảnh hiển thị/xem trước theo chế độ (Bitmap ARGB), cạnh dài ≤ [maxSide]. */
    fun renderBitmap(master: Bitmap, mode: PdfExportMode, maxSide: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(master, rgba)
        val k = maxSide.toDouble() / max(rgba.cols(), rgba.rows())
        if (k < 1.0) Imgproc.resize(rgba, rgba, Size(rgba.cols() * k, rgba.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        val out = when (mode) {
            PdfExportMode.BW_SMALL -> bwBinary(rgba)
            PdfExportMode.BW_HQ -> bwHq(rgba)
            else -> colorNormalized(rgba)
        }
        val shown = Mat()
        Imgproc.cvtColor(out, shown, if (out.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(shown.cols(), shown.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(shown, bmp)
        rgba.release(); out.release(); shown.release()
        return bmp
    }

    private fun jpeg(m: Mat, quality: Int): ByteArray {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
        val bytes = buf.toArray()
        buf.release()
        return bytes
    }

    /** Mat 0/255 → hàng bit (MSB trước, 1 = trắng), mỗi hàng đệm đủ byte. */
    private fun packBits(bin: Mat): ByteArray {
        val w = bin.cols()
        val h = bin.rows()
        val src = ByteArray(w * h)
        bin.get(0, 0, src)
        val rowBytes = (w + 7) / 8
        val out = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            val so = y * w
            val o = y * rowBytes
            for (x in 0 until w) {
                if (src[so + x].toInt() != 0) {
                    val idx = o + (x ushr 3)
                    out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }
}
