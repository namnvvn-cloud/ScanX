import UIKit
import CoreText

/// Dựng PDF từ ảnh trang theo 1 trong 4 chế độ (A1/A2/B1/B2) + lớp chữ OCR ẩn (tìm kiếm/copy được).
/// Khổ trang theo đúng quy tắc Android (ScanPdfWriter): gần A4 → 595×842 pt, gần Letter → 612×792 pt,
/// khác → cạnh dài 842 pt.
enum PDFBuilder {
    static let a4 = CGSize(width: 595, height: 842)

    static func pageSize(for imageSize: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0 else { return a4 }
        let landscape = imageSize.width > imageSize.height
        let ratio = max(imageSize.width, imageSize.height) / min(imageSize.width, imageSize.height)
        let a4Ratio: CGFloat = 842.0 / 595.0
        let letterRatio: CGFloat = 792.0 / 612.0
        let longSide: CGFloat
        let shortSide: CGFloat
        if abs(ratio - a4Ratio) / a4Ratio < 0.03 {
            longSide = 842
            shortSide = 595
        } else if abs(ratio - letterRatio) / letterRatio < 0.03 {
            longSide = 792
            shortSide = 612
        } else {
            longSide = 842
            shortSide = (842 / ratio).rounded()
        }
        return landscape ? CGSize(width: longSide, height: shortSide) : CGSize(width: shortSide, height: longSide)
    }

    static func build(pageURLs: [URL], mode: PDFMode, textLayers: [[TextLine]]?, to outputURL: URL) throws {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: a4))
        try renderer.writePDF(to: outputURL) { context in
            for (index, url) in pageURLs.enumerated() {
                autoreleasepool {
                    guard let image = ScanFilters.process(url: url, mode: mode) else { return }
                    let bounds = CGRect(
                        origin: .zero,
                        size: pageSize(for: CGSize(width: image.width, height: image.height))
                    )
                    context.beginPage(withBounds: bounds, pageInfo: [:])
                    UIImage(cgImage: image).draw(in: bounds)
                    if let textLayers, index < textLayers.count {
                        drawInvisibleText(textLayers[index], in: bounds, context: context.cgContext)
                    }
                }
            }
        }
    }

    /// Chữ vô hình (text rendering mode 3) — giống kỹ thuật Tesseract/OCRmyPDF bên Android, nhưng dùng
    /// CoreText + font hệ thống (tự thay font cho tiếng Việt/Hàn/Nhật/Trung). Mỗi dòng được co giãn
    /// ngang cho khớp đúng bề rộng khung OCR để bôi chọn/copy trùng vị trí chữ trên ảnh.
    static func drawInvisibleText(_ lines: [TextLine], in bounds: CGRect, context: CGContext) {
        context.saveGState()
        context.setTextDrawingMode(.invisible)
        context.textMatrix = .identity
        for line in lines {
            let rect = CGRect(
                x: bounds.minX + CGFloat(line.x) * bounds.width,
                y: bounds.minY + CGFloat(line.y) * bounds.height,
                width: CGFloat(line.w) * bounds.width,
                height: CGFloat(line.h) * bounds.height
            )
            guard rect.width > 1, rect.height > 1 else { continue }
            let font = UIFont.systemFont(ofSize: max(rect.height * 0.8, 1))
            let attributed = NSAttributedString(string: line.text, attributes: [.font: font])
            let ctLine = CTLineCreateWithAttributedString(attributed)
            let lineWidth = CGFloat(CTLineGetTypographicBounds(ctLine, nil, nil, nil))
            guard lineWidth > 0 else { continue }
            context.saveGState()
            // Ngữ cảnh PDF của UIKit có trục y hướng xuống → lật lại để CoreText vẽ đúng chiều.
            context.translateBy(x: rect.minX, y: rect.maxY - rect.height * 0.2)
            context.scaleBy(x: rect.width / lineWidth, y: -1)
            context.textPosition = .zero
            CTLineDraw(ctLine, context)
            context.restoreGState()
        }
        context.restoreGState()
    }
}
