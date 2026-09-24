package com.scanx.app.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vẽ bản dịch ĐÈ lên ảnh, kiểu Google Dịch: mỗi khối chữ được phủ bằng màu nền đo quanh khối, rồi viết
 * bản dịch vào đúng khung đó (tự xuống dòng, tự thu nhỏ cỡ chữ cho vừa, không bao giờ to hơn chữ gốc),
 * màu chữ lấy theo màu mực gốc nếu đủ tương phản, không thì đen/trắng tuỳ nền.
 *
 * Bản 1.1: vẽ theo GÓC NGHIÊNG của chữ ([PhotoTranslation.TextBlock.angle]) — khung phủ và dòng chữ dịch
 * nghiêng đúng như dòng gốc trên trang chụp xiên. [drawBlock]/[blockColors] dùng chung cho ảnh chụp và
 * lớp phủ dịch trực tiếp trên camera.
 */
object PhotoTranslateRenderer {

    class Colors(val background: Int, val ink: Int)

    /** [translations] cùng thứ tự với [blocks]; phần tử null = giữ nguyên chữ gốc ở khối đó. */
    fun render(source: Bitmap, blocks: List<PhotoTranslation.TextBlock>, translations: List<String?>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for ((i, block) in blocks.withIndex()) {
            val text = translations.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val colors = blockColors(source, block)
            drawBlock(canvas, block, text, colors)
        }
        return out
    }

    private fun padOf(block: PhotoTranslation.TextBlock) = max(2f, block.lineHeight * 0.12f)

    private fun rectOf(block: PhotoTranslation.TextBlock): RectF {
        val pad = padOf(block)
        return RectF(block.box.left - pad, block.box.top - pad, block.box.right + pad, block.box.bottom + pad)
    }

    /** Phủ khối và viết bản dịch lên [canvas] (toạ độ ảnh của khối). */
    fun drawBlock(canvas: Canvas, block: PhotoTranslation.TextBlock, text: String, colors: Colors) {
        val rect = rectOf(block)
        if (rect.width() < 4f || rect.height() < 4f) return
        val pad = padOf(block)
        canvas.save()
        if (block.angle != 0f) canvas.rotate(block.angle)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colors.background }
        canvas.drawRoundRect(rect, pad, pad, fill)
        drawFitted(canvas, text, rect, colors.ink, block.lineHeight)
        canvas.restore()
    }

    private fun drawFitted(canvas: Canvas, text: String, rect: RectF, color: Int, lineHeight: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.DEFAULT
        }
        val width = rect.width().roundToInt().coerceAtLeast(1)
        // Cỡ chữ gốc ≈ 0,8 × chiều cao dòng thật; bản dịch không to hơn, chỉ thu nhỏ cho vừa khung.
        var size = (lineHeight * 0.8f).coerceIn(6f, 160f)
        var layout = build(text, paint, width, size)
        while (layout.height > rect.height() && size > 6f) {
            size *= 0.92f
            layout = build(text, paint, width, size)
        }
        canvas.save()
        canvas.clipRect(rect)
        val dy = ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)
        canvas.translate(rect.left, rect.top + dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun build(text: String, paint: TextPaint, width: Int, size: Float): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    /** Màu nền (trung vị viền ngoài khối) và màu mực (trung vị điểm trong khối khác nền rõ rệt). */
    fun blockColors(bmp: Bitmap, block: PhotoTranslation.TextBlock): Colors {
        val r = rectOf(block)
        val bg = borderColor(bmp, block, r)
        return Colors(bg, inkColor(bmp, block, r, bg))
    }

    private fun pixel(bmp: Bitmap, block: PhotoTranslation.TextBlock, x: Float, y: Float): Int {
        val (ix, iy) = block.toImage(x, y)
        return bmp.getPixel(ix.roundToInt().coerceIn(0, bmp.width - 1), iy.roundToInt().coerceIn(0, bmp.height - 1))
    }

    private fun borderColor(bmp: Bitmap, block: PhotoTranslation.TextBlock, r: RectF): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun sample(x: Float, y: Float) {
            val c = pixel(bmp, block, x, y)
            rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c))
        }
        val n = 24
        for (k in 0..n) {
            val x = r.left + r.width() * k / n
            val y = r.top + r.height() * k / n
            sample(x, r.top - 2f); sample(x, r.bottom + 2f)
            sample(r.left - 2f, y); sample(r.right + 2f, y)
        }
        return Color.rgb(median(rs), median(gs), median(bs))
    }

    private fun inkColor(bmp: Bitmap, block: PhotoTranslation.TextBlock, r: RectF, bg: Int): Int {
        val bgL = luminance(bg)
        val step = max(1f, min(r.width(), r.height()) / 20f)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = pixel(bmp, block, x, y)
                if (abs(luminance(c) - bgL) > 0.35f) { rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c)) }
                x += step
            }
            y += step
        }
        val fallback = if (bgL > 0.5f) Color.rgb(20, 20, 20) else Color.WHITE
        if (rs.size < 8) return fallback
        val ink = Color.rgb(median(rs), median(gs), median(bs))
        return if (abs(luminance(ink) - bgL) > 0.4f) ink else fallback
    }

    private fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun median(v: MutableList<Int>): Int {
        if (v.isEmpty()) return 255
        v.sort()
        return v[v.size / 2]
    }
}
