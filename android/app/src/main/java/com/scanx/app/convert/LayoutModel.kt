package com.scanx.app.convert

import kotlin.math.max
import kotlin.math.min

/*
 * Mô hình dữ liệu của engine chuyển đổi bố cục (Kotlin thuần, KHÔNG phụ thuộc Android) để có thể
 * biên dịch + kiểm thử độc lập trên JVM. Phía Android chỉ lo: OCR (ML Kit) → [OcrLine]/[OcrWord],
 * OpenCV tách đường kẻ bảng → [RuleSegment], cắt vùng ảnh con dấu/chữ ký/logo → [Figure].
 * Toạ độ tất cả theo pixel của ảnh trang (đã làm phẳng).
 */

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun containsPoint(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    fun verticalOverlap(o: Box): Float = max(0f, min(bottom, o.bottom) - max(top, o.top))
    fun horizontalOverlap(o: Box): Float = max(0f, min(right, o.right) - max(left, o.left))

    companion object {
        fun unionOf(boxes: Collection<Box>): Box? = boxes.reduceOrNull { a, b -> a.union(b) }
    }
}

/** [color] = màu mực RGB (0 = đen/không rõ), [confidence] = độ tin cậy OCR 0..1. */
data class OcrWord(val text: String, val box: Box, val color: Int = 0, val confidence: Float = 1f, val lang: String = "")

/**
 * [strokeWidth] = độ dày nét chữ trung bình (px, đo bằng distance transform trên ảnh nhị phân của dòng).
 * Chữ đậm có nét dày hơn rõ rệt so với mặt bằng chung của trang → dùng suy ra định dạng in đậm.
 */
data class OcrLine(
    val text: String,
    val box: Box,
    val words: List<OcrWord>,
    val strokeWidth: Float = 0f,
    val color: Int = 0,
    val confidence: Float = 1f,
    /** Ngôn ngữ của dòng ("vi", "ko", "ja", "zh", "en"…; "" = chưa rõ) — xem [Lang]. */
    val lang: String = "",
    /** Chữ nghiêng (italic) — suy từ độ xiên nét chữ (bản 0.7), xem [PageLayoutExtractor]. */
    val italic: Boolean = false,
    /** Bản 1.0: số khối chữ (TextBlock) ML Kit gán cho dòng — dùng gom đoạn khi "Chụp để dịch"; -1 = không rõ. */
    val blockId: Int = -1,
    /**
     * Bản 1.1: 4 góc thật của dòng (x0,y0 trên-trái, x1,y1 trên-phải, x2,y2 dưới-phải, x3,y3 dưới-trái) theo
     * ML Kit — chữ nghiêng theo trang chụp xiên thì khung chữ nhật [box] bị phình cao, dùng 4 góc này để
     * tính đúng góc xoay và chiều cao dòng khi vẽ bản dịch đè. null = không có.
     */
    val quad: List<Float>? = null,
)

/** Đoạn đường kẻ (ngang hoặc dọc) tách được từ ảnh bằng morphology. */
data class RuleSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val isHorizontal: Boolean get() = kotlin.math.abs(x2 - x1) >= kotlin.math.abs(y2 - y1)
}

/**
 * Vùng ảnh giữ nguyên (con dấu, logo, hình minh hoạ) đã cắt sẵn thành JPEG. [isStamp] = vùng mực đỏ
 * dạng tròn/vuông gọn (con dấu) → luôn giữ dạng ảnh dù bên trong có chữ.
 */
class Figure(val box: Box, val jpeg: ByteArray, val isStamp: Boolean = false)

class PageInput(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
    val rules: List<RuleSegment>,
    val figures: List<Figure> = emptyList(),
    /** Ảnh cả trang (JPEG) — dùng cho xuất JPG và làm nền tuỳ chọn. */
    val pageJpeg: ByteArray? = null,
)

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }
enum class VAlign { TOP, CENTER, BOTTOM }

data class Paragraph(
    val text: String,
    val align: Align,
    val fontPt: Float,
    val bold: Boolean,
    /** Chữ nghiêng (italic) — suy từ độ xiên nét chữ, xem [PageLayoutExtractor] (bản 0.7). */
    val italic: Boolean = false,
    /** Thụt lề trái tính theo pixel so với mép trái vùng chứa (vùng nội dung trang hoặc ô bảng). */
    val indentPx: Float,
    val spaceBeforePx: Float,
    val box: Box,
    /** Thụt đầu dòng (dòng đầu thụt vào so với các dòng sau), pixel. */
    val firstLineIndentPx: Float = 0f,
    /** Các dòng gốc trên ảnh (giữ nguyên vị trí từng dòng khi xuất PowerPoint / PDF có lớp chữ). */
    val lineBoxes: List<Box> = emptyList(),
    /** Màu chữ RGB (0 = đen). Lấy mẫu từ màu mực thật: mực xanh/đỏ/tím giữ đúng màu. */
    val color: Int = 0,
    /**
     * Ghi chú nằm cạnh bảng (lề phải/trái, cùng độ cao với bảng) → đặt tuyệt đối đúng vị trí
     * (Word: khung định vị theo trang; Excel: ô cùng hàng bên cạnh bảng) thay vì xếp chồng lên trên bảng.
     */
    val floating: Boolean = false,
    /** Ngôn ngữ của đoạn → font + thuộc tính ngôn ngữ trong Word/Excel/PowerPoint. */
    val lang: String = "",
)

sealed class Block {
    abstract val box: Box
}

class ParagraphBlock(val paragraph: Paragraph) : Block() {
    override val box: Box get() = paragraph.box
}

data class TableCell(
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
    val paragraphs: List<Paragraph>,
    val vAlign: VAlign,
)

class TableBlock(
    override val box: Box,
    /** Toạ độ x các đường dọc (n cột → n+1 phần tử). */
    val colEdges: List<Float>,
    /** Toạ độ y các đường ngang (m hàng → m+1 phần tử). */
    val rowEdges: List<Float>,
    /** Chỉ các ô "gốc" (ô bị gộp vào ô khác không có mặt). */
    val cells: List<TableCell>,
    /** false = bảng không viền (dùng cho header 2 cột kiểu văn bản hành chính). */
    val bordered: Boolean,
    val spaceBeforePx: Float,
) : Block() {
    val rowCount: Int get() = rowEdges.size - 1
    val colCount: Int get() = colEdges.size - 1
}

class ImageBlock(override val box: Box, val figure: Figure, val align: Align, val spaceBeforePx: Float) : Block()

class DocPage(
    val width: Int,
    val height: Int,
    val blocks: List<Block>,
    val content: Box,
    val pageJpeg: ByteArray?,
    /** Số điểm (pt) ứng với 1 pixel ảnh, giả định trang giấy A4. */
    val ptPerPx: Float,
    /** Độ tin cậy OCR trung bình (theo số ký tự) của trang; thấp (< 0,75) → thường là chữ viết tay. */
    val ocrConfidence: Float = 1f,
) {
    val isLandscape: Boolean get() = width > height
}

class DocModel(val title: String, val pages: List<DocPage>)
