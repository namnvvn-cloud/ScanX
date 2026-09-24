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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vẽ bản dịch ĐÈ lên ảnh gốc, kiểu Google Dịch (bản 1.0): mỗi khối chữ được phủ bằng màu nền đo quanh
 * khối, rồi viết bản dịch vào đúng khung đó (tự xuống dòng, tự thu nhỏ cỡ chữ cho vừa), màu chữ lấy
 * theo màu mực gốc nếu đủ tương phản, không thì đen/trắng tuỳ nền.
 */
object PhotoTranslateRenderer {

    /** [translations] cùng thứ tự với [blocks]; phần tử null = giữ nguyên chữ gốc ở khối đó. */
    fun render(source: Bitmap, blocks: List<PhotoTranslation.TextBlock>, translations: List<String?>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for ((i, block) in blocks.withIndex()) {
            val text = translations.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val pad = max(2f, block.lineHeight * 0.12f)
            val rect = RectF(
                (block.box.left - pad).coerceAtLeast(0f),
                (block.box.top - pad).coerceAtLeast(0f),
                (block.box.right + pad).coerceAtMost(out.width.toFloat()),
                (block.box.bottom + pad).coerceAtMost(out.height.toFloat()),
            )
            if (rect.width() < 4f || rect.height() < 4f) continue
            val bg = borderColor(source, rect)
            val ink = inkColor(source, rect, bg)
            fill.color = bg
            canvas.drawRoundRect(rect, pad, pad, fill)
            drawFitted(canvas, text, rect, ink, block.lineHeight)
        }
        return out
    }

    private fun drawFitted(canvas: Canvas, text: String, rect: RectF, color: Int, lineHeight: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.DEFAULT
        }
        val width = rect.width().roundToInt().coerceAtLeast(1)
        var size = (lineHeight * 0.78f).coerceIn(8f, 160f)
        var layout = build(text, paint, width, size)
        while (layout.height > rect.height() && size > 8f) {
            size *= 0.9f
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

    /** Màu nền = trung vị từng kênh của các điểm trên viền ngoài khối. */
    private fun borderColor(bmp: Bitmap, r: RectF): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun sample(x: Float, y: Float) {
            val xi = x.roundToInt().coerceIn(0, bmp.width - 1)
            val yi = y.roundToInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(xi, yi)
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

    /** Màu mực: trung vị các điểm trong khối khác nền rõ rệt; không đủ tương phản → đen/trắng theo nền. */
    private fun inkColor(bmp: Bitmap, r: RectF, bg: Int): Int {
        val bgL = luminance(bg)
        val step = max(1f, min(r.width(), r.height()) / 20f)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = bmp.getPixel(x.roundToInt().coerceIn(0, bmp.width - 1), y.roundToInt().coerceIn(0, bmp.height - 1))
                if (kotlin.math.abs(luminance(c) - bgL) > 0.35f) { rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c)) }
                x += step
            }
            y += step
        }
        val fallback = if (bgL > 0.5f) Color.rgb(20, 20, 20) else Color.WHITE
        if (rs.size < 8) return fallback
        val ink = Color.rgb(median(rs), median(gs), median(bs))
        return if (kotlin.math.abs(luminance(ink) - bgL) > 0.4f) ink else fallback
    }

    private fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun median(v: MutableList<Int>): Int {
        if (v.isEmpty()) return 255
        v.sort()
        return v[v.size / 2]
    }
}
