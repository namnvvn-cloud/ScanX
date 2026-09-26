import Foundation

/// "Chụp để dịch" / "Dịch trực tiếp" — port 1:1 từ Android convert/PhotoTranslation.kt: gom dòng OCR thành
/// KHỐI chữ để dịch theo ngữ cảnh cả khối và vẽ bản dịch đè đúng vùng khối, kiểu Google Dịch. Hình học XOAY
/// (bản 1.1): đo góc nghiêng trang từ 4 góc thật của dòng, xoay mọi dòng về "khung thẳng", gom khối trong
/// khung thẳng, rồi vẽ lại theo đúng góc nghiêng.
enum PhotoTranslation {
    /// box nằm trong KHUNG THẲNG (ảnh xoay −angle độ quanh gốc (0,0)); toImage đổi về toạ độ ảnh gốc.
    struct TextBlock {
        var box: Box
        var lines: [OcrLine]
        var text: String
        var lang: String
        /// Chiều cao dòng tiêu biểu (trung vị, theo 4 góc thật) — chọn cỡ chữ khi vẽ bản dịch.
        var lineHeight: Double
        /// Góc nghiêng (độ, chiều kim đồng hồ trên màn hình).
        var angle: Double = 0
        var confidence: Double = 0

        func toImage(_ x: Double, _ y: Double) -> (Double, Double) {
            PhotoTranslation.rotate(x, y, angle)
        }
    }

    /// Xoay điểm quanh gốc toạ độ deg độ (trục y hướng xuống — như Canvas.rotate / CGContext của UIKit).
    static func rotate(_ x: Double, _ y: Double, _ deg: Double) -> (Double, Double) {
        if deg == 0 { return (x, y) }
        let r = deg * .pi / 180
        let c = cos(r)
        let s = sin(r)
        return (x * c - y * s, x * s + y * c)
    }

    static func lineAngle(_ l: OcrLine) -> Double? {
        guard let q = l.quad, q.count >= 8 else { return nil }
        let a1 = atan2(q[3] - q[1], q[2] - q[0])
        let a2 = atan2(q[5] - q[7], q[4] - q[6])
        return (a1 + a2) / 2 * 180 / .pi
    }

    static func trueSize(_ l: OcrLine) -> (Double, Double)? {
        guard let q = l.quad, q.count >= 8 else { return nil }
        let w = (hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[4] - q[6], q[5] - q[7])) / 2
        let h = (hypot(q[6] - q[0], q[7] - q[1]) + hypot(q[4] - q[2], q[5] - q[3])) / 2
        return (w > 0 && h > 0) ? (w, h) : nil
    }

    /// Góc nghiêng chung: trung vị có trọng số (theo bề dài) góc các dòng dài ≥ 3 lần cao; |góc| < 0,3° hoặc > 45° → 0.
    static func pageAngle(_ lines: [OcrLine]) -> Double {
        let samples: [(Double, Double)] = lines.compactMap { l in
            guard let a = lineAngle(l), let (w, h) = trueSize(l), w >= 3 * h else { return nil }
            return (a, w)
        }.sorted { $0.0 < $1.0 }
        guard let lastSample = samples.last else { return 0 }
        let total = samples.reduce(0.0) { $0 + $1.1 }
        var acc = 0.0
        var median = lastSample.0
        for (a, w) in samples {
            acc += w
            if acc >= total / 2 {
                median = a
                break
            }
        }
        return (abs(median) < 0.3 || abs(median) > 45) ? 0 : median
    }

    /// Dòng trong khung thẳng: tâm xoay đi −angle, kích thước = kích thước thật (không phình).
    static func deskew(_ l: OcrLine, _ angle: Double) -> OcrLine {
        var out = l
        guard let (w, h) = trueSize(l), let q = l.quad else {
            if angle == 0 { return l }
            let pts = [
                rotate(l.box.left, l.box.top, -angle), rotate(l.box.right, l.box.top, -angle),
                rotate(l.box.right, l.box.bottom, -angle), rotate(l.box.left, l.box.bottom, -angle),
            ]
            out.box = Box(
                left: pts.map { $0.0 }.min()!, top: pts.map { $0.1 }.min()!,
                right: pts.map { $0.0 }.max()!, bottom: pts.map { $0.1 }.max()!
            )
            return out
        }
        let cx = (q[0] + q[2] + q[4] + q[6]) / 4
        let cy = (q[1] + q[3] + q[5] + q[7]) / 4
        let (x, y) = rotate(cx, cy, -angle)
        out.box = Box(left: x - w / 2, top: y - h / 2, right: x + w / 2, bottom: y + h / 2)
        return out
    }

    private static func sortedTopLeft<T>(_ items: [T], box: (T) -> Box) -> [T] {
        items.enumerated().sorted { a, b in
            let ba = box(a.element), bb = box(b.element)
            if ba.top != bb.top { return ba.top < bb.top }
            if ba.left != bb.left { return ba.left < bb.left }
            return a.offset < b.offset
        }.map { $0.element }
    }

    /// Gom dòng thành khối (trong khung thẳng).
    static func groupBlocks(_ lines: [OcrLine]) -> [TextBlock] {
        let valid = lines.filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty && $0.box.height > 0 && $0.box.width > 0 }
        let angle = pageAngle(valid)
        let sorted = sortedTopLeft(valid.map { deskew($0, angle) }) { $0.box }
        var groups: [[OcrLine]] = []
        for line in sorted {
            var best: Int?
            var bestGap = Double.greatestFiniteMagnitude
            for (gi, g) in groups.enumerated() {
                guard let last = g.last, joinable(last, line) else { continue }
                let gap = line.box.top - last.box.bottom
                if gap < bestGap {
                    bestGap = gap
                    best = gi
                }
            }
            if let best {
                groups[best].append(line)
            } else {
                groups.append([line])
            }
        }
        let blocks: [TextBlock] = groups.map { g in
            let box = Box.unionOf(g.map { $0.box })!
            let heights = g.map { $0.box.height }.sorted()
            let conf = g.map { $0.confidence }.filter { $0 > 0 }
            return TextBlock(
                box: box,
                lines: g,
                text: joinLines(g),
                lang: dominantLang(g),
                lineHeight: heights[heights.count / 2],
                angle: angle,
                confidence: conf.isEmpty ? 0 : conf.reduce(0, +) / Double(conf.count)
            )
        }
        return sortedTopLeft(blocks) { $0.box }
    }

    private static func joinable(_ a: OcrLine, _ b: OcrLine) -> Bool {
        if a.blockId >= 0 && b.blockId >= 0 && a.blockId != b.blockId { return false }
        if Lang.isEastAsian(a.lang) != Lang.isEastAsian(b.lang) { return false }
        let ha = a.box.height
        let hb = b.box.height
        let ratio = hb / ha
        if ratio < 0.7 || ratio > 1.4 { return false }
        let h = max(ha, hb)
        let gap = b.box.top - a.box.bottom
        if gap > 0.8 * h || gap < -0.5 * h { return false }
        let overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
        let narrow = min(a.box.width, b.box.width)
        let aligned = abs(a.box.left - b.box.left) <= 1.5 * h
        return overlap >= 0.3 * narrow || (aligned && overlap > 0)
    }

    /// Nối các dòng 1 khối: câu bị ngắt nối bằng dấu cách (bỏ gạch nối cuối dòng), CJK nối liền; hết đoạn/mục giữ "\n".
    static func joinLines(_ lines: [OcrLine]) -> String {
        let widest = lines.map { $0.box.width }.max() ?? 0
        var sb = ""
        for (i, l) in lines.enumerated() {
            let t = l.text.trimmingCharacters(in: .whitespaces)
            if i == 0 {
                sb += t
                continue
            }
            let prev = lines[i - 1]
            let prevText = prev.text.trimmingCharacters(in: .whitespaces)
            let prevCjk = Lang.isEastAsian(prev.lang)
            let first = t.first
            let startsItem = first.map { $0.isUppercase || $0.isNumber || "-•*–+·".contains($0) } ?? false
            let endsSentence = prevText.last.map { ":.;!?。：；！？".contains($0) } ?? false
            let shortPrev = prev.box.width < 0.75 * widest
            let labelPair = isLabelLine(prevText) && isLabelLine(t)
            var endsWithHyphen = false
            if sb.hasSuffix("-") && sb.count >= 2 {
                endsWithHyphen = sb[sb.index(sb.endIndex, offsetBy: -2)].isLetter
            }
            if endsWithHyphen && first?.isLowercase == true {
                sb.removeLast()
                sb += t
            } else if labelPair || (shortPrev && (startsItem || prevCjk)) || (endsSentence && startsItem) {
                sb += "\n" + t
            } else if prevCjk && Lang.isEastAsian(l.lang) {
                sb += t
            } else {
                sb += " " + t
            }
        }
        return sb
    }

    private static func isLabelLine(_ t: String) -> Bool {
        let chars = Array(t)
        guard let i = chars.firstIndex(where: { $0 == ":" || $0 == "：" }) else { return false }
        return i >= 1 && i <= chars.count * 6 / 10
    }

    private static func dominantLang(_ lines: [OcrLine]) -> String {
        dominantKey(lines.map { ($0.lang, 1) }) ?? ""
    }

    /// Nhãn tên người nói: "Jeff:", "Alyssa:", "A:" → giữ nguyên, không dịch.
    private static let speaker = try! NSRegularExpression(pattern: "^[\\p{Lu}][\\p{L}'.-]{0,14}\\s?[:：]$")

    private static func isSpeaker(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespaces)
        return speaker.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }

    /// Khối cần dịch: bỏ khối đã là tiếng Việt, khối chỉ số/ký hiệu, khối toàn nhãn người nói, khối OCR đọc kém.
    /// (Vision báo độ tin cậy theo nấc 0,3/0,5/1,0 — khác ML Kit — nên ngưỡng loại là < 0,25.)
    static func needsTranslation(_ block: TextBlock, target: String = DocTranslation.target) -> Bool {
        if block.lang == target { return false }
        if !block.text.contains(where: { $0.isLetter }) { return false }
        if block.lines.allSatisfy({ isSpeaker($0.text) }) { return false }
        if block.confidence > 0.001 && block.confidence < 0.25 { return false }
        return true
    }

    /// Tách văn bản các khối thành từng đoạn theo "\n" để dịch; trả danh sách phẳng + số đoạn mỗi khối.
    static func splitSegments(_ texts: [String]) -> (segments: [String], counts: [Int]) {
        var flat: [String] = []
        var counts: [Int] = []
        for t in texts {
            let parts = t.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let use = parts.isEmpty ? [t] : parts
            flat += use
            counts.append(use.count)
        }
        return (flat, counts)
    }

    /// Ghép bản dịch từng đoạn về theo khối; khối thiếu bản dịch mọi đoạn → nil.
    static func joinSegments(_ translated: [String?], counts: [Int], originals: [String]? = nil) -> [String?] {
        var out: [String?] = []
        var k = 0
        for (bi, n) in counts.enumerated() {
            let part: [String?] = (0..<n).map { k + $0 < translated.count ? translated[k + $0] : nil }
            k += n
            if part.allSatisfy({ ($0 ?? "").trimmingCharacters(in: .whitespaces).isEmpty }) {
                out.append(nil)
                continue
            }
            let origParts: [String]? = (originals.map { bi < $0.count ? $0[bi] : "" })?
                .components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let joined = part.enumerated().map { i, s -> String in
                if let s, !s.trimmingCharacters(in: .whitespaces).isEmpty { return s }
                if let origParts, i < origParts.count { return origParts[i] }
                return ""
            }.joined(separator: "\n")
            out.append(joined)
        }
        return out
    }
}
