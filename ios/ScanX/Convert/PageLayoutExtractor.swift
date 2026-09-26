import UIKit
import Vision

/// Từ ảnh 1 trang (màu, đúng chiều) → PageInput — tương đương Android convert/PageLayoutExtractor.kt nhưng
/// KHÔNG dùng OpenCV (iOS không có): viết thuần Swift trên bộ đệm điểm ảnh của ScanFilters.
///  - Chuẩn hoá ánh sáng (đen trắng chất lượng cao, như A2) trước → OCR, đường kẻ, độ dày nét không bị bóng/nền ố.
///  - OCR Vision → dòng + TỪ (toạ độ từng từ) + độ tin cậy + ngôn ngữ.
///  - Độ dày nét (suy chữ đậm) = 2 × số điểm mực / số điểm biên; màu mực → InkColor; chữ nghiêng bằng
///    "projection profile" y như Android.
///  - Đường kẻ bảng: nhị phân thích nghi + giữ các đoạn mực chạy liền ≥ ngưỡng (tương đương morphology mở
///    với phần tử cấu trúc dài), nới rộng rồi gom vùng liên thông.
///  - Hình giữ dạng ảnh: con dấu đỏ + vùng màu không chứa chữ.
enum PageLayoutExtractor {
    static let workingMaxSide: CGFloat = 2400

    static func extract(url: URL) -> PageInput? {
        guard let source = ImageLoader.downsampled(at: url, maxPixel: workingMaxSide)?.cgImage,
              let rgbx = ScanFilters.rgbxBuffer(from: source)
        else { return nil }
        let w = rgbx.width
        let h = rgbx.height
        let gray = ScanFilters.grayscale(rgbx)
        let background = ScanFilters.estimateBackground(gray)
        var bw = ScanFilters.normalize(gray, background: background)
        bw = ScanFilters.unsharp(bw)
        ScanFilters.applyToneCurve(&bw)
        let color = ScanFilters.normalizeColor(rgbx, background: background)

        var lines: [OcrLine] = []
        if let ocrImage = ScanFilters.grayImage(bw) {
            lines = recognize(ocrImage, width: w, height: h).map { line in
                var l = line
                let (stroke, ink, italic) = strokeInkItalic(gray: bw, color: color, box: line.box)
                l.strokeWidth = stroke
                l.color = ink
                l.italic = italic
                l.words = line.words.map { word in
                    var copy = word
                    copy.color = ink
                    return copy
                }
                return l
            }
        }
        let rules = detectRules(bw)
        let figures = detectFigures(rgbx, source: source)
        return PageInput(width: w, height: h, lines: lines, rules: rules, figures: figures)
    }

    // MARK: - OCR (Vision) → dòng + từ

    /// OCR Vision → dòng (có TỪ, độ tin cậy, ngôn ngữ, 4 góc thật). Dùng chung cho Chuyển đổi và Chụp để dịch.
    static func ocrLines(_ image: CGImage) -> [OcrLine] {
        recognize(image, width: image.width, height: image.height)
    }

    private static func recognize(_ image: CGImage, width: Int, height: Int) -> [OcrLine] {
        let languages = PageOCR.preferredLanguages()
        if let lines = try? performOCR(image, width: width, height: height, languages: languages) {
            return lines
        }
        return (try? performOCR(image, width: width, height: height, languages: nil)) ?? []
    }

    private static func performOCR(_ image: CGImage, width: Int, height: Int, languages: [String]?) throws -> [OcrLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        if let languages, !languages.isEmpty {
            request.recognitionLanguages = languages
        }
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
        try handler.perform([request])
        let W = Double(width)
        let H = Double(height)
        func pixelBox(_ r: CGRect) -> Box {
            Box(
                left: Double(r.minX) * W,
                top: (1 - Double(r.maxY)) * H,
                right: Double(r.maxX) * W,
                bottom: (1 - Double(r.minY)) * H
            )
        }
        var out: [OcrLine] = []
        for observation in request.results ?? [] {
            guard let candidate = observation.topCandidates(1).first else { continue }
            let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { continue }
            let lineBox = pixelBox(observation.boundingBox)
            let lang = Lang.detect(text)
            let confidence = Double(candidate.confidence)
            var words: [OcrWord] = []
            for range in wordRanges(candidate.string) {
                let wordText = String(candidate.string[range])
                if let rect = try? candidate.boundingBox(for: range)?.boundingBox {
                    words.append(OcrWord(text: wordText, box: pixelBox(rect), confidence: confidence, lang: lang))
                }
            }
            if words.isEmpty {
                words = [OcrWord(text: text, box: lineBox, confidence: confidence, lang: lang)]
            }
            func px(_ p: CGPoint) -> [Double] { [Double(p.x) * W, (1 - Double(p.y)) * H] }
            var line = OcrLine(text: text, box: lineBox, words: words, confidence: confidence, lang: lang)
            line.quad = px(observation.topLeft) + px(observation.topRight) + px(observation.bottomRight) + px(observation.bottomLeft)
            out.append(line)
        }
        return out
    }

    private static func wordRanges(_ s: String) -> [Range<String.Index>] {
        var ranges: [Range<String.Index>] = []
        var i = s.startIndex
        while i < s.endIndex {
            while i < s.endIndex, s[i].isWhitespace { i = s.index(after: i) }
            guard i < s.endIndex else { break }
            let start = i
            while i < s.endIndex, !s[i].isWhitespace { i = s.index(after: i) }
            ranges.append(start..<i)
        }
        return ranges
    }

    // MARK: - Độ dày nét, màu mực, chữ nghiêng

    private static func strokeInkItalic(gray: ScanFilters.Gray, color: ScanFilters.RGBX, box: Box) -> (Double, Int, Bool) {
        let w = gray.width
        let h = gray.height
        let x0 = clampInt(Int(box.left), 0, w - 1)
        let y0 = clampInt(Int(box.top), 0, h - 1)
        let x1 = min(Int(box.right), w)
        let y1 = min(Int(box.bottom), h)
        let cw = x1 - x0
        let ch = y1 - y0
        if cw < 4 || ch < 4 { return (0, InkColor.black, false) }

        var histogram = [Int](repeating: 0, count: 256)
        for y in y0..<y1 {
            let row = y * w
            for x in x0..<x1 { histogram[Int(gray.pixels[row + x])] += 1 }
        }
        let threshold = otsu(histogram, total: cw * ch)
        var ink = [Bool](repeating: false, count: cw * ch)
        var inkCount = 0
        var sumR = 0, sumG = 0, sumB = 0
        var points: [(Int, Int)] = []
        for y in 0..<ch {
            let row = (y + y0) * w
            for x in 0..<cw where Int(gray.pixels[row + x + x0]) <= threshold {
                ink[y * cw + x] = true
                inkCount += 1
                let ci = (row + x + x0) * 4
                sumR += Int(color.pixels[ci])
                sumG += Int(color.pixels[ci + 1])
                sumB += Int(color.pixels[ci + 2])
                points.append((x, y))
            }
        }
        if inkCount < 10 { return (0, InkColor.black, false) }
        var boundary = 0
        for y in 0..<ch {
            for x in 0..<cw where ink[y * cw + x] {
                let left = x > 0 && !ink[y * cw + x - 1]
                let right = x < cw - 1 && !ink[y * cw + x + 1]
                let up = y > 0 && !ink[(y - 1) * cw + x]
                let down = y < ch - 1 && !ink[(y + 1) * cw + x]
                if left || right || up || down { boundary += 1 }
            }
        }
        let stroke = boundary > 0 ? 2 * Double(inkCount) / Double(boundary) : 0
        let inkColor = InkColor.classify(r: sumR / inkCount, g: sumG / inkCount, b: sumB / inkCount)
        return (stroke, inkColor, estimateItalic(points, width: cw, height: ch))
    }

    private static func otsu(_ histogram: [Int], total: Int) -> Int {
        var sumAll = 0.0
        for i in 0..<256 { sumAll += Double(i * histogram[i]) }
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for t in 0..<256 {
            wB += histogram[t]
            if wB == 0 { continue }
            let wF = total - wB
            if wF == 0 { break }
            sumB += Double(t * histogram[t])
            let mB = sumB / Double(wB)
            let mF = (sumAll - sumB) / Double(wF)
            let between = Double(wB) * Double(wF) * (mB - mF) * (mB - mF)
            if between > best {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /// Chữ nghiêng: cắt xiên điểm mực theo các góc ứng viên, góc làm nét chữ thẳng cột nhất (tổng bình
    /// phương số điểm mỗi cột lớn nhất) lệch hẳn khỏi 0° và nhọn hơn rõ so với 0° → nghiêng (như Android).
    private static func estimateItalic(_ points: [(Int, Int)], width: Int, height: Int) -> Bool {
        if width < 24 || height < 10 || points.count < 40 { return false }
        let stride = max(1, points.count / 4000)
        let binW = max(1.0, Double(height) * 0.12)
        let nBins = max(Int(Double(width) / binW), 4) + 4
        var bestScore = -1.0
        var bestAngle = 0
        var baseScore = 0.0
        for deg in [-24, -20, -16, -12, -8, -4, 0, 4, 8, 12, 16, 20, 24] {
            let t = tan(Double(deg) * .pi / 180)
            var bins = [Int](repeating: 0, count: nBins + 2)
            var k = 0
            while k < points.count {
                let (px, py) = points[k]
                let sx = Double(px) - t * (Double(py) - Double(height) / 2)
                let bi = Int(sx / binW) + 1
                if bi >= 0 && bi < bins.count { bins[bi] += 1 }
                k += stride
            }
            var score = 0.0
            for c in bins { score += Double(c) * Double(c) }
            if deg == 0 { baseScore = score }
            if score > bestScore {
                bestScore = score
                bestAngle = deg
            }
        }
        return bestAngle != 0 && baseScore > 0 && bestScore > baseScore * 1.12
    }

    // MARK: - Đường kẻ bảng

    private static func detectRules(_ gray: ScanFilters.Gray) -> [RuleSegment] {
        let w = gray.width
        let h = gray.height
        // Nhị phân thích nghi (mean C, cửa sổ 31, C = 15) qua ảnh tích phân.
        var integral = [Int](repeating: 0, count: (w + 1) * (h + 1))
        for y in 0..<h {
            var rowSum = 0
            for x in 0..<w {
                rowSum += Int(gray.pixels[y * w + x])
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        let r = 15
        var ink = [Bool](repeating: false, count: w * h)
        for y in 0..<h {
            let ya = max(0, y - r), yb = min(h, y + r + 1)
            for x in 0..<w {
                let xa = max(0, x - r), xb = min(w, x + r + 1)
                let sum = integral[yb * (w + 1) + xb] - integral[ya * (w + 1) + xb] - integral[yb * (w + 1) + xa] + integral[ya * (w + 1) + xa]
                let mean = Double(sum) / Double((yb - ya) * (xb - xa))
                ink[y * w + x] = Double(gray.pixels[y * w + x]) < mean - 15
            }
        }
        var out: [RuleSegment] = []

        // Ngang: đoạn mực liền ≥ w/30 (tương đương MORPH_OPEN với phần tử 1 × w/30), nới 9×3.
        let kh = max(w / 30, 10)
        var hMask = [Bool](repeating: false, count: w * h)
        for y in 0..<h {
            var x = 0
            while x < w {
                if !ink[y * w + x] { x += 1; continue }
                let start = x
                while x < w && ink[y * w + x] { x += 1 }
                if x - start >= kh {
                    for k in start..<x { hMask[y * w + k] = true }
                }
            }
        }
        hMask = dilate(hMask, width: w, height: h, rx: 4, ry: 1)
        for c in components(hMask, width: w, height: h) where c.maxX - c.minX + 1 > w / 15 {
            let yc = Double(c.minY + c.maxY + 1) / 2
            out.append(RuleSegment(x1: Double(c.minX), y1: yc, x2: Double(c.maxX + 1), y2: yc))
        }

        // Dọc: đoạn mực liền ≥ h/45, nới 3×9.
        let kv = max(h / 45, 10)
        var vMask = [Bool](repeating: false, count: w * h)
        for x in 0..<w {
            var y = 0
            while y < h {
                if !ink[y * w + x] { y += 1; continue }
                let start = y
                while y < h && ink[y * w + x] { y += 1 }
                if y - start >= kv {
                    for k in start..<y { vMask[k * w + x] = true }
                }
            }
        }
        vMask = dilate(vMask, width: w, height: h, rx: 1, ry: 4)
        for c in components(vMask, width: w, height: h) where c.maxY - c.minY + 1 > h / 50 {
            let xc = Double(c.minX + c.maxX + 1) / 2
            out.append(RuleSegment(x1: xc, y1: Double(c.minY), x2: xc, y2: Double(c.maxY + 1)))
        }
        return out
    }

    private static func dilate(_ mask: [Bool], width w: Int, height h: Int, rx: Int, ry: Int) -> [Bool] {
        var tmp = [Bool](repeating: false, count: mask.count)
        if rx > 0 {
            for y in 0..<h {
                var count = 0
                for k in 0...min(rx, w - 1) where mask[y * w + k] { count += 1 }
                for x in 0..<w {
                    tmp[y * w + x] = count > 0
                    let add = x + rx + 1
                    let sub = x - rx
                    if add < w && mask[y * w + add] { count += 1 }
                    if sub >= 0 && mask[y * w + sub] { count -= 1 }
                }
            }
        } else {
            tmp = mask
        }
        var out = [Bool](repeating: false, count: mask.count)
        if ry > 0 {
            for x in 0..<w {
                var count = 0
                for k in 0...min(ry, h - 1) where tmp[k * w + x] { count += 1 }
                for y in 0..<h {
                    out[y * w + x] = count > 0
                    let add = y + ry + 1
                    let sub = y - ry
                    if add < h && tmp[add * w + x] { count += 1 }
                    if sub >= 0 && tmp[sub * w + x] { count -= 1 }
                }
            }
        } else {
            out = tmp
        }
        return out
    }

    private struct Component {
        var minX: Int
        var minY: Int
        var maxX: Int
        var maxY: Int
    }

    /// Vùng liên thông 8 hướng → khung bao (tương đương findContours RETR_EXTERNAL + boundingRect).
    private static func components(_ mask: [Bool], width w: Int, height h: Int) -> [Component] {
        var visited = [Bool](repeating: false, count: mask.count)
        var result: [Component] = []
        var stack: [Int] = []
        for start in 0..<mask.count where mask[start] && !visited[start] {
            var comp = Component(minX: start % w, minY: start / w, maxX: start % w, maxY: start / w)
            visited[start] = true
            stack.append(start)
            while let i = stack.popLast() {
                let x = i % w
                let y = i / w
                comp.minX = min(comp.minX, x)
                comp.maxX = max(comp.maxX, x)
                comp.minY = min(comp.minY, y)
                comp.maxY = max(comp.maxY, y)
                for dy in -1...1 {
                    let ny = y + dy
                    if ny < 0 || ny >= h { continue }
                    for dx in -1...1 {
                        let nx = x + dx
                        if nx < 0 || nx >= w || (dx == 0 && dy == 0) { continue }
                        let j = ny * w + nx
                        if mask[j] && !visited[j] {
                            visited[j] = true
                            stack.append(j)
                        }
                    }
                }
            }
            result.append(comp)
        }
        return result
    }

    // MARK: - Hình / con dấu

    private static func detectFigures(_ rgbx: ScanFilters.RGBX, source: CGImage) -> [Figure] {
        let w = rgbx.width
        let h = rgbx.height
        // Làm trên ảnh thu nhỏ (~800 px) cho nhanh, rồi quy toạ độ về ảnh làm việc.
        let f = max(1, Int((Double(max(w, h)) / 800).rounded(.up)))
        let sw = w / f
        let sh = h / f
        if sw < 8 || sh < 8 { return [] }
        var mask = [Bool](repeating: false, count: sw * sh)
        var red = [Bool](repeating: false, count: sw * sh)
        for y in 0..<sh {
            for x in 0..<sw {
                let i = ((y * f) * w + x * f) * 4
                let r = Int(rgbx.pixels[i]), g = Int(rgbx.pixels[i + 1]), b = Int(rgbx.pixels[i + 2])
                let v = max(r, g, b)
                let mn = min(r, g, b)
                let diff = v - mn
                let s = v == 0 ? 0 : 255 * diff / v
                var hue = 0.0
                if diff > 0 {
                    if v == r {
                        hue = 60 * Double(g - b) / Double(diff)
                    } else if v == g {
                        hue = 120 + 60 * Double(b - r) / Double(diff)
                    } else {
                        hue = 240 + 60 * Double(r - g) / Double(diff)
                    }
                    if hue < 0 { hue += 360 }
                }
                let hcv = hue / 2  // thang OpenCV 0…180
                mask[y * sw + x] = s >= 90 && v <= 235
                red[y * sw + x] = (hcv <= 10 || hcv >= 160) && s >= 90 && v >= 40
            }
        }
        // Mở 3×3 (bỏ chấm lẻ) rồi nới rộng ≈ 21 px ảnh gốc.
        mask = erode3(mask, width: sw, height: sh)
        mask = dilate(mask, width: sw, height: sh, rx: 1, ry: 1)
        let grow = max(1, 10 / f)
        mask = dilate(mask, width: sw, height: sh, rx: grow, ry: grow)

        let pageArea = Double(w) * Double(h)
        var out: [Figure] = []
        for c in components(mask, width: sw, height: sh) {
            if out.count >= 12 { break }
            let x = clampInt(c.minX * f, 0, w - 1)
            let y = clampInt(c.minY * f, 0, h - 1)
            let cw = min((c.maxX - c.minX + 1) * f, w - x)
            let ch = min((c.maxY - c.minY + 1) * f, h - y)
            if Double(cw) * Double(ch) < pageArea * 0.003 || cw < 8 || ch < 8 { continue }
            var redCount = 0
            for yy in c.minY...c.maxY {
                for xx in c.minX...c.maxX where red[yy * sw + xx] { redCount += 1 }
            }
            let redRatio = Double(redCount) / Double(max(1, (c.maxX - c.minX + 1) * (c.maxY - c.minY + 1)))
            let aspect = Double(cw) / Double(ch)
            let isStamp = redRatio > 0.08 && aspect >= 0.6 && aspect <= 1.7 && Double(cw * ch) > pageArea * 0.006
            guard let crop = source.cropping(to: CGRect(x: x, y: y, width: cw, height: ch)),
                  let jpeg = UIImage(cgImage: crop).jpegData(compressionQuality: 0.9)
            else { continue }
            out.append(Figure(
                box: Box(left: Double(x), top: Double(y), right: Double(x + cw), bottom: Double(y + ch)),
                jpeg: jpeg,
                isStamp: isStamp
            ))
        }
        return out
    }

    private static func erode3(_ mask: [Bool], width w: Int, height h: Int) -> [Bool] {
        var out = [Bool](repeating: false, count: mask.count)
        if w < 3 || h < 3 { return out }
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                var all = true
                for dy in -1...1 where all {
                    for dx in -1...1 where !mask[(y + dy) * w + x + dx] {
                        all = false
                    }
                }
                out[y * w + x] = all
            }
        }
        return out
    }
}
