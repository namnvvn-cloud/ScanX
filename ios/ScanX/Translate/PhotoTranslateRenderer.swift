import UIKit

/// Vẽ bản dịch ĐÈ lên ảnh kiểu Google Dịch — port từ Android PhotoTranslateRenderer.kt: phủ khối bằng màu nền
/// đo quanh khối, viết bản dịch vào đúng khung (tự xuống dòng, tự thu nhỏ, không to hơn chữ gốc), màu chữ
/// theo mực gốc nếu đủ tương phản; vẽ theo góc nghiêng của chữ (bản 1.1).
enum PhotoTranslateRenderer {
    struct Colors {
        let background: UIColor
        let ink: UIColor
    }

    /// translations cùng thứ tự với blocks; nil = giữ nguyên chữ gốc ở khối đó.
    static func render(_ image: CGImage, blocks: [PhotoTranslation.TextBlock], translations: [String?]) -> UIImage? {
        guard let pixels = ScanFilters.rgbxBuffer(from: image) else { return nil }
        let size = CGSize(width: image.width, height: image.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIImage(cgImage: image).draw(in: CGRect(origin: .zero, size: size))
            for (i, block) in blocks.enumerated() {
                guard i < translations.count,
                      let text = translations[i]?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !text.isEmpty
                else { continue }
                drawBlock(ctx.cgContext, block: block, text: text, colors: blockColors(pixels, block))
            }
        }
    }

    private static func pad(_ block: PhotoTranslation.TextBlock) -> Double {
        max(2, block.lineHeight * 0.12)
    }

    private static func rect(_ block: PhotoTranslation.TextBlock) -> CGRect {
        let p = pad(block)
        return CGRect(
            x: block.box.left - p,
            y: block.box.top - p,
            width: block.box.width + 2 * p,
            height: block.box.height + 2 * p
        )
    }

    /// Phủ khối và viết bản dịch (toạ độ ảnh; ngữ cảnh UIKit trục y hướng xuống).
    static func drawBlock(_ cg: CGContext, block: PhotoTranslation.TextBlock, text: String, colors: Colors) {
        let r = rect(block)
        if r.width < 4 || r.height < 4 { return }
        let p = CGFloat(pad(block))
        UIGraphicsPushContext(cg)
        cg.saveGState()
        if block.angle != 0 { cg.rotate(by: CGFloat(block.angle * .pi / 180)) }
        colors.background.setFill()
        UIBezierPath(roundedRect: r, cornerRadius: p).fill()
        drawFitted(cg, text: text, rect: r, color: colors.ink, lineHeight: block.lineHeight)
        cg.restoreGState()
        UIGraphicsPopContext()
    }

    private static func drawFitted(_ cg: CGContext, text: String, rect: CGRect, color: UIColor, lineHeight: Double) {
        // Cỡ chữ gốc ≈ 0,8 × chiều cao dòng thật; bản dịch không to hơn, chỉ thu nhỏ cho vừa khung.
        var size = CGFloat(min(max(lineHeight * 0.8, 6), 160))
        func attributed(_ s: CGFloat) -> NSAttributedString {
            let style = NSMutableParagraphStyle()
            style.lineBreakMode = .byWordWrapping
            return NSAttributedString(string: text, attributes: [
                .font: UIFont.systemFont(ofSize: s),
                .foregroundColor: color,
                .paragraphStyle: style,
            ])
        }
        func height(_ a: NSAttributedString) -> CGFloat {
            a.boundingRect(
                with: CGSize(width: rect.width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                context: nil
            ).height.rounded(.up)
        }
        var attr = attributed(size)
        var h = height(attr)
        while h > rect.height && size > 6 {
            size *= 0.92
            attr = attributed(size)
            h = height(attr)
        }
        cg.saveGState()
        cg.clip(to: rect)
        let dy = max(0, (rect.height - h) / 2)
        attr.draw(with: CGRect(x: rect.minX, y: rect.minY + dy, width: rect.width, height: h),
                  options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        cg.restoreGState()
    }

    // MARK: - Màu nền / màu mực

    static func blockColors(_ pixels: ScanFilters.RGBX, _ block: PhotoTranslation.TextBlock) -> Colors {
        let r = rect(block)
        let bg = borderColor(pixels, block, r)
        return Colors(background: uiColor(bg), ink: uiColor(inkColor(pixels, block, r, bg)))
    }

    private typealias RGB = (r: Int, g: Int, b: Int)

    private static func pixel(_ pixels: ScanFilters.RGBX, _ block: PhotoTranslation.TextBlock, _ x: Double, _ y: Double) -> RGB {
        let (ix, iy) = block.toImage(x, y)
        let px = clampInt(Int(ix.rounded()), 0, pixels.width - 1)
        let py = clampInt(Int(iy.rounded()), 0, pixels.height - 1)
        let i = (py * pixels.width + px) * 4
        return (Int(pixels.pixels[i]), Int(pixels.pixels[i + 1]), Int(pixels.pixels[i + 2]))
    }

    private static func borderColor(_ pixels: ScanFilters.RGBX, _ block: PhotoTranslation.TextBlock, _ r: CGRect) -> RGB {
        var samples: [RGB] = []
        let n = 24
        for k in 0...n {
            let x = Double(r.minX + r.width * CGFloat(k) / CGFloat(n))
            let y = Double(r.minY + r.height * CGFloat(k) / CGFloat(n))
            samples.append(pixel(pixels, block, x, Double(r.minY) - 2))
            samples.append(pixel(pixels, block, x, Double(r.maxY) + 2))
            samples.append(pixel(pixels, block, Double(r.minX) - 2, y))
            samples.append(pixel(pixels, block, Double(r.maxX) + 2, y))
        }
        return medianRGB(samples)
    }

    private static func inkColor(_ pixels: ScanFilters.RGBX, _ block: PhotoTranslation.TextBlock, _ r: CGRect, _ bg: RGB) -> RGB {
        let bgL = luminance(bg)
        let step = max(1, Double(min(r.width, r.height)) / 20)
        var samples: [RGB] = []
        var y = Double(r.minY)
        while y < Double(r.maxY) {
            var x = Double(r.minX)
            while x < Double(r.maxX) {
                let c = pixel(pixels, block, x, y)
                if abs(luminance(c) - bgL) > 0.35 { samples.append(c) }
                x += step
            }
            y += step
        }
        let fallback: RGB = bgL > 0.5 ? (20, 20, 20) : (255, 255, 255)
        if samples.count < 8 { return fallback }
        let ink = medianRGB(samples)
        return abs(luminance(ink) - bgL) > 0.4 ? ink : fallback
    }

    private static func luminance(_ c: RGB) -> Double {
        (0.299 * Double(c.r) + 0.587 * Double(c.g) + 0.114 * Double(c.b)) / 255
    }

    private static func medianRGB(_ v: [RGB]) -> RGB {
        if v.isEmpty { return (255, 255, 255) }
        let rs = v.map { $0.r }.sorted()
        let gs = v.map { $0.g }.sorted()
        let bs = v.map { $0.b }.sorted()
        return (rs[rs.count / 2], gs[gs.count / 2], bs[bs.count / 2])
    }

    private static func uiColor(_ c: RGB) -> UIColor {
        UIColor(red: CGFloat(c.r) / 255, green: CGFloat(c.g) / 255, blue: CGFloat(c.b) / 255, alpha: 1)
    }
}
