import UIKit

/// Dựng PDF từ ảnh trang (bản cơ bản = màu, giữ nguyên ảnh). Khổ trang theo đúng quy tắc Android
/// (ScanPdfWriter): gần A4 → 595×842 pt, gần Letter → 612×792 pt, khác → cạnh dài 842 pt.
/// 4 chế độ A1/A2/B1/B2 + lớp chữ OCR ẩn sẽ thêm ở bước 3.
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

    static func build(pageURLs: [URL], to outputURL: URL) throws {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: a4))
        try renderer.writePDF(to: outputURL) { context in
            for url in pageURLs {
                autoreleasepool {
                    guard let image = UIImage(contentsOfFile: url.path) else { return }
                    let bounds = CGRect(origin: .zero, size: pageSize(for: image.size))
                    context.beginPage(withBounds: bounds, pageInfo: [:])
                    image.draw(in: bounds)
                }
            }
        }
    }
}
