package com.scanx.app.convert

import kotlin.math.min

/**
 * Quy tắc trình bày bảng dùng chung cho Word/Excel/PowerPoint (Kotlin thuần).
 * Hàng tiêu đề = các hàng đầu bảng mà mọi ô có chữ đều in đậm (tối đa 3 hàng) → tô nền xám nhạt
 * F2F2F2 + chữ đậm, như bảng soạn chuẩn trong văn bản hành chính. Không đoán khi bản gốc không đậm.
 */
object TableStyle {
    const val HEADER_FILL = "F2F2F2"

    fun headerRows(t: TableBlock): Int {
        if (!t.bordered || t.rowCount < 3) return 0
        var n = 0
        for (r in 0 until min(3, t.rowCount - 1)) {
            val cells = t.cells.filter { it.row == r && it.paragraphs.isNotEmpty() }
            if (cells.isEmpty() || cells.any { c -> c.paragraphs.any { !it.bold } }) break
            n = r + 1
        }
        return n
    }
}

/** Ô thuộc vùng tiêu đề của bảng. */
fun isHeaderRow(t: TableBlock, cell: TableCell): Boolean = cell.row < TableStyle.headerRows(t)
