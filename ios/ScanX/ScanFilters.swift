import UIKit
import ImageIO
import UniformTypeIdentifiers

/// Xử lý ảnh trang theo 4 chế độ PDF — cùng hướng thuật toán với Android (scan/ScanFilters):
/// chuẩn hoá ánh sáng bằng CHIA NỀN (nền ước lượng trên ảnh thu nhỏ ~400 px: dilate + làm mờ),
/// A1 = ngưỡng Otsu → 1-bit, A2 = xám + unsharp + đường cong tông → JPEG q75,
/// B1 = màu chuẩn hoá, cạnh dài 1600 px, JPEG q60, B2 = màu chuẩn hoá, JPEG q88.
enum ScanFilters {
    struct Gray {
        let width: Int
        let height: Int
        var pixels: [UInt8]
    }

    struct RGBX {
        let width: Int
        let height: Int
        var pixels: [UInt8]  // R, G, B, X
    }

    struct Background {
        let small: Gray
        let block: Int
    }

    static func process(url: URL, mode: PDFMode) -> CGImage? {
        let maxSide: CGFloat = mode == .b1 ? 1600 : 3000
        guard let source = ImageLoader.downsampled(at: url, maxPixel: maxSide)?.cgImage,
              let rgbx = rgbxBuffer(from: source)
        else { return nil }
        let gray = grayscale(rgbx)
        let background = estimateBackground(gray)
        switch mode {
        case .a1:
            let normalized = normalize(gray, background: background)
            return bilevelImage(normalized, threshold: otsuThreshold(normalized))
        case .a2:
            var normalized = normalize(gray, background: background)
            normalized = unsharp(normalized)
            applyToneCurve(&normalized)
            return jpegImage(grayImage(normalized), quality: 0.75)
        case .b1:
            return jpegImage(colorImage(normalizeColor(rgbx, background: background)), quality: 0.6)
        case .b2:
            return jpegImage(colorImage(normalizeColor(rgbx, background: background)), quality: 0.88)
        }
    }

    // MARK: - Đọc ảnh

    static func rgbxBuffer(from image: CGImage) -> RGBX? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let ok: Bool = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(
                data: raw.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return ok ? RGBX(width: width, height: height, pixels: pixels) : nil
    }

    static func grayscale(_ image: RGBX) -> Gray {
        let count = image.width * image.height
        var out = [UInt8](repeating: 0, count: count)
        image.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                for i in 0..<count {
                    let r = Int(src[i * 4])
                    let g = Int(src[i * 4 + 1])
                    let b = Int(src[i * 4 + 2])
                    dst[i] = UInt8((77 * r + 150 * g + 29 * b) >> 8)
                }
            }
        }
        return Gray(width: image.width, height: image.height, pixels: out)
    }

    // MARK: - Ước lượng nền & chuẩn hoá ánh sáng

    static func estimateBackground(_ gray: Gray) -> Background {
        let block = max(1, Int((Double(max(gray.width, gray.height)) / 400.0).rounded(.up)))
        let sw = (gray.width + block - 1) / block
        let sh = (gray.height + block - 1) / block
        var small = [UInt8](repeating: 255, count: sw * sh)
        gray.pixels.withUnsafeBufferPointer { src in
            for by in 0..<sh {
                for bx in 0..<sw {
                    var sum = 0
                    var n = 0
                    let y0 = by * block
                    let x0 = bx * block
                    for y in y0..<min(y0 + block, gray.height) {
                        let row = y * gray.width
                        for x in x0..<min(x0 + block, gray.width) {
                            sum += Int(src[row + x])
                            n += 1
                        }
                    }
                    small[by * sw + bx] = UInt8(n > 0 ? sum / n : 255)
                }
            }
        }
        // Dilate (lấy max) xoá nét chữ tối, rồi làm mờ 3 lượt hộp ≈ Gauss.
        var bg = maxFilter(small, width: sw, height: sh, radius: 3)
        for _ in 0..<3 {
            bg = boxBlur(bg, width: sw, height: sh, radius: 3)
        }
        return Background(small: Gray(width: sw, height: sh, pixels: bg), block: block)
    }

    /// Nội suy song tuyến tính ảnh nền nhỏ cho từng hàng của ảnh lớn, gọi `body(y, rowValues)`.
    private static func forEachBackgroundRow(
        _ background: Background,
        width: Int,
        height: Int,
        _ body: (Int, UnsafeBufferPointer<Float>) -> Void
    ) {
        let small = background.small
        let block = Float(background.block)
        var x0s = [Int](repeating: 0, count: width)
        var x1s = [Int](repeating: 0, count: width)
        var fxs = [Float](repeating: 0, count: width)
        for x in 0..<width {
            let sx = min(max((Float(x) + 0.5) / block - 0.5, 0), Float(small.width - 1))
            let x0 = Int(sx)
            x0s[x] = x0
            x1s[x] = min(x0 + 1, small.width - 1)
            fxs[x] = sx - Float(x0)
        }
        var rowValues = [Float](repeating: 255, count: width)
        small.pixels.withUnsafeBufferPointer { s in
            for y in 0..<height {
                let sy = min(max((Float(y) + 0.5) / block - 0.5, 0), Float(small.height - 1))
                let y0 = Int(sy)
                let y1 = min(y0 + 1, small.height - 1)
                let fy = sy - Float(y0)
                let r0 = y0 * small.width
                let r1 = y1 * small.width
                for x in 0..<width {
                    let a = Float(s[r0 + x0s[x]]) * (1 - fxs[x]) + Float(s[r0 + x1s[x]]) * fxs[x]
                    let b = Float(s[r1 + x0s[x]]) * (1 - fxs[x]) + Float(s[r1 + x1s[x]]) * fxs[x]
                    rowValues[x] = max(a * (1 - fy) + b * fy, 16)
                }
                rowValues.withUnsafeBufferPointer { body(y, $0) }
            }
        }
    }

    static func normalize(_ gray: Gray, background: Background) -> Gray {
        var out = [UInt8](repeating: 255, count: gray.pixels.count)
        gray.pixels.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                forEachBackgroundRow(background, width: gray.width, height: gray.height) { y, bg in
                    let row = y * gray.width
                    for x in 0..<gray.width {
                        let v = Float(src[row + x]) * 255 / bg[x]
                        dst[row + x] = UInt8(min(v, 255))
                    }
                }
            }
        }
        return Gray(width: gray.width, height: gray.height, pixels: out)
    }

    static func normalizeColor(_ image: RGBX, background: Background) -> RGBX {
        var out = image.pixels
        out.withUnsafeMutableBufferPointer { px in
            forEachBackgroundRow(background, width: image.width, height: image.height) { y, bg in
                let row = y * image.width
                for x in 0..<image.width {
                    let k = 255 / bg[x]
                    let i = (row + x) * 4
                    px[i] = UInt8(min(Float(px[i]) * k, 255))
                    px[i + 1] = UInt8(min(Float(px[i + 1]) * k, 255))
                    px[i + 2] = UInt8(min(Float(px[i + 2]) * k, 255))
                }
            }
        }
        return RGBX(width: image.width, height: image.height, pixels: out)
    }

    // MARK: - Làm nét, tông, ngưỡng

    static func unsharp(_ gray: Gray) -> Gray {
        var blurred = gray.pixels
        for _ in 0..<3 {
            blurred = boxBlur(blurred, width: gray.width, height: gray.height, radius: 2)
        }
        var out = gray.pixels
        for i in 0..<out.count {
            let v = 1.6 * Float(gray.pixels[i]) - 0.6 * Float(blurred[i])
            out[i] = UInt8(min(max(v, 0), 255))
        }
        return Gray(width: gray.width, height: gray.height, pixels: out)
    }

    static func applyToneCurve(_ gray: inout Gray) {
        let lo: Float = 30
        let hi: Float = 225
        var lut = [UInt8](repeating: 0, count: 256)
        for v in 0..<256 {
            let t = min(max((Float(v) - lo) / (hi - lo), 0), 1)
            lut[v] = UInt8((pow(t, 1.25) * 255).rounded())
        }
        for i in 0..<gray.pixels.count {
            gray.pixels[i] = lut[Int(gray.pixels[i])]
        }
    }

    static func otsuThreshold(_ gray: Gray) -> UInt8 {
        var histogram = [Int](repeating: 0, count: 256)
        for v in gray.pixels {
            histogram[Int(v)] += 1
        }
        let total = gray.pixels.count
        var sumAll = 0.0
        for i in 0..<256 {
            sumAll += Double(i * histogram[i])
        }
        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var threshold = 128
        for t in 0..<256 {
            weightBackground += histogram[t]
            if weightBackground == 0 { continue }
            let weightForeground = total - weightBackground
            if weightForeground == 0 { break }
            sumBackground += Double(t * histogram[t])
            let meanB = sumBackground / Double(weightBackground)
            let meanF = (sumAll - sumBackground) / Double(weightForeground)
            let between = Double(weightBackground) * Double(weightForeground) * (meanB - meanF) * (meanB - meanF)
            if between > best {
                best = between
                threshold = t
            }
        }
        // Trang đã chuẩn hoá nền ≈ 255: giữ ngưỡng trong khoảng hợp lý để không mất nét mảnh.
        return UInt8(min(max(threshold, 100), 220))
    }

    // MARK: - Lọc 1 kênh

    static func boxBlur(_ src: [UInt8], width: Int, height: Int, radius: Int) -> [UInt8] {
        guard radius > 0, width > 0, height > 0 else { return src }
        let div = 2 * radius + 1
        var tmp = [UInt8](repeating: 0, count: src.count)
        var out = [UInt8](repeating: 0, count: src.count)
        src.withUnsafeBufferPointer { s in
            tmp.withUnsafeMutableBufferPointer { t in
                for y in 0..<height {
                    let row = y * width
                    var sum = 0
                    for k in -radius...radius {
                        sum += Int(s[row + min(max(k, 0), width - 1)])
                    }
                    for x in 0..<width {
                        t[row + x] = UInt8(sum / div)
                        sum += Int(s[row + min(x + radius + 1, width - 1)]) - Int(s[row + max(x - radius, 0)])
                    }
                }
            }
        }
        tmp.withUnsafeBufferPointer { t in
            out.withUnsafeMutableBufferPointer { o in
                for x in 0..<width {
                    var sum = 0
                    for k in -radius...radius {
                        sum += Int(t[min(max(k, 0), height - 1) * width + x])
                    }
                    for y in 0..<height {
                        o[y * width + x] = UInt8(sum / div)
                        sum += Int(t[min(y + radius + 1, height - 1) * width + x])
                            - Int(t[max(y - radius, 0) * width + x])
                    }
                }
            }
        }
        return out
    }

    static func maxFilter(_ src: [UInt8], width: Int, height: Int, radius: Int) -> [UInt8] {
        guard radius > 0, width > 0, height > 0 else { return src }
        var tmp = src
        for y in 0..<height {
            for x in 0..<width {
                var m: UInt8 = 0
                for k in max(x - radius, 0)...min(x + radius, width - 1) {
                    m = max(m, src[y * width + k])
                }
                tmp[y * width + x] = m
            }
        }
        var out = tmp
        for y in 0..<height {
            for x in 0..<width {
                var m: UInt8 = 0
                for k in max(y - radius, 0)...min(y + radius, height - 1) {
                    m = max(m, tmp[k * width + x])
                }
                out[y * width + x] = m
            }
        }
        return out
    }

    // MARK: - Tạo CGImage

    static func grayImage(_ gray: Gray) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(gray.pixels) as CFData) else { return nil }
        return CGImage(
            width: gray.width,
            height: gray.height,
            bitsPerComponent: 8,
            bitsPerPixel: 8,
            bytesPerRow: gray.width,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    static func colorImage(_ image: RGBX) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(image.pixels) as CFData) else { return nil }
        return CGImage(
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: image.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    /// 1-bit DeviceGray: bit 1 = trắng, 0 = đen (MSB trước).
    static func bilevelImage(_ gray: Gray, threshold: UInt8) -> CGImage? {
        let bytesPerRow = (gray.width + 7) / 8
        var bits = [UInt8](repeating: 0, count: bytesPerRow * gray.height)
        for y in 0..<gray.height {
            let src = y * gray.width
            let dst = y * bytesPerRow
            for x in 0..<gray.width where gray.pixels[src + x] >= threshold {
                bits[dst + x / 8] |= UInt8(0x80 >> (x % 8))
            }
        }
        guard let provider = CGDataProvider(data: Data(bits) as CFData) else { return nil }
        return CGImage(
            width: gray.width,
            height: gray.height,
            bitsPerComponent: 1,
            bitsPerPixel: 1,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        )
    }

    /// Nén JPEG với chất lượng cố định rồi dựng lại CGImage từ dữ liệu JPEG — PDF nhúng thẳng
    /// luồng DCT, dung lượng đúng theo chất lượng đã chọn.
    static func jpegImage(_ image: CGImage?, quality: CGFloat) -> CGImage? {
        guard let image else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil
        ) else { return nil }
        let properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination),
              let provider = CGDataProvider(data: data as CFData)
        else { return nil }
        return CGImage(jpegDataProviderSource: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
