import Foundation

/*
 * Mô hình dữ liệu engine chuyển đổi bố cục — port 1:1 từ Android convert/LayoutModel.kt.
 * Toạ độ theo pixel của ảnh trang (đã làm phẳng).
 */

struct Box: Hashable {
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double

    var width: Double { right - left }
    var height: Double { bottom - top }
    var cx: Double { (left + right) / 2 }
    var cy: Double { (top + bottom) / 2 }

    func union(_ o: Box) -> Box {
        Box(left: min(left, o.left), top: min(top, o.top), right: max(right, o.right), bottom: max(bottom, o.bottom))
    }

    func containsPoint(x: Double, y: Double) -> Bool {
        x >= left && x <= right && y >= top && y <= bottom
    }

    func verticalOverlap(_ o: Box) -> Double { max(0, min(bottom, o.bottom) - max(top, o.top)) }
    func horizontalOverlap(_ o: Box) -> Double { max(0, min(right, o.right) - max(left, o.left)) }

    static func unionOf(_ boxes: [Box]) -> Box? {
        guard var result = boxes.first else { return nil }
        for b in boxes.dropFirst() { result = result.union(b) }
        return result
    }
}

/// color = màu mực RGB (0 = đen/không rõ), confidence = độ tin cậy OCR 0…1.
struct OcrWord: Hashable {
    var text: String
    var box: Box
    var color: Int = 0
    var confidence: Double = 1
    var lang: String = ""
}

struct OcrLine: Hashable {
    var text: String
    var box: Box
    var words: [OcrWord]
    /// Độ dày nét chữ trung bình (tương đối) — dùng suy chữ đậm so với mặt bằng trang.
    var strokeWidth: Double = 0
    var color: Int = 0
    var confidence: Double = 1
    var lang: String = ""
    var italic: Bool = false
}

struct RuleSegment {
    var x1: Double
    var y1: Double
    var x2: Double
    var y2: Double
    var isHorizontal: Bool { abs(x2 - x1) >= abs(y2 - y1) }
}

/// Vùng ảnh giữ nguyên (con dấu, logo, hình minh hoạ) đã cắt thành JPEG.
struct Figure {
    var box: Box
    var jpeg: Data
    var isStamp: Bool = false
}

struct PageInput {
    var width: Int
    var height: Int
    var lines: [OcrLine]
    var rules: [RuleSegment]
    var figures: [Figure] = []
}

enum Align: Hashable { case left, center, right, justify }
enum VAlign: Hashable { case top, center, bottom }

struct Paragraph: Hashable {
    var text: String
    var align: Align
    var fontPt: Double
    var bold: Bool
    var italic: Bool = false
    /// Thụt lề trái (px) so với mép trái vùng chứa.
    var indentPx: Double
    var spaceBeforePx: Double
    var box: Box
    var firstLineIndentPx: Double = 0
    var lineBoxes: [Box] = []
    /// Màu chữ RGB (0 = đen).
    var color: Int = 0
    /// Ghi chú cạnh bảng → đặt tuyệt đối đúng vị trí.
    var floating: Bool = false
    var lang: String = ""
}

struct TableCell {
    var row: Int
    var col: Int
    var rowSpan: Int
    var colSpan: Int
    var paragraphs: [Paragraph]
    var vAlign: VAlign
}

struct TableBlock {
    var box: Box
    /// Toạ độ x các đường dọc (n cột → n+1 phần tử).
    var colEdges: [Double]
    /// Toạ độ y các đường ngang (m hàng → m+1 phần tử).
    var rowEdges: [Double]
    /// Chỉ các ô "gốc".
    var cells: [TableCell]
    /// false = bảng không viền (header 2 cột kiểu văn bản hành chính).
    var bordered: Bool
    var spaceBeforePx: Double

    var rowCount: Int { rowEdges.count - 1 }
    var colCount: Int { colEdges.count - 1 }
}

struct ImageBlock {
    var box: Box
    var figure: Figure
    var align: Align
    var spaceBeforePx: Double
}

enum Block {
    case paragraph(Paragraph)
    case table(TableBlock)
    case image(ImageBlock)

    var box: Box {
        switch self {
        case .paragraph(let p): return p.box
        case .table(let t): return t.box
        case .image(let i): return i.box
        }
    }

    var paragraph: Paragraph? {
        if case .paragraph(let p) = self { return p }
        return nil
    }

    var table: TableBlock? {
        if case .table(let t) = self { return t }
        return nil
    }
}

struct DocPage {
    var width: Int
    var height: Int
    var blocks: [Block]
    var content: Box
    /// Số điểm (pt) ứng với 1 pixel ảnh, giả định trang A4.
    var ptPerPx: Double
    /// Độ tin cậy OCR trung bình theo ký tự; thấp (< 0,75) → thường là chữ viết tay.
    var ocrConfidence: Double = 1

    var isLandscape: Bool { width > height }
}

struct DocModel {
    var title: String
    var pages: [DocPage]
}
