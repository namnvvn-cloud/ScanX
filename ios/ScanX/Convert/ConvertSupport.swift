import Foundation

/// Font mặc định cho văn bản tiếng Việt (chuẩn văn bản hành chính theo Nghị định 30/2020).
let defaultFont = "Times New Roman"
let xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

func xmlEscape(_ s: String) -> String {
    var out = ""
    out.reserveCapacity(s.count + 16)
    for scalar in s.unicodeScalars {
        switch scalar {
        case "&": out += "&amp;"
        case "<": out += "&lt;"
        case ">": out += "&gt;"
        case "\"": out += "&quot;"
        case "'": out += "&apos;"
        default:
            if scalar.value >= 0x20 || scalar == "\t" || scalar == "\n" || scalar == "\r" {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    return out
}

func hexColor(_ c: Int) -> String {
    String(format: "%06X", c & 0xFFFFFF)
}

/// Ngôn ngữ / hệ chữ từng dòng — port từ Android convert/Lang.kt.
enum Lang {
    enum Script { case latin, hangul, kana, han, other }

    struct ScriptCount {
        let latin: Int
        let hangul: Int
        let kana: Int
        let han: Int
        let other: Int

        var letters: Int { latin + hangul + kana + han + other }

        var dominant: Script {
            let m = max(latin, hangul, kana, han, other)
            if m == 0 { return .other }
            if hangul == m { return .hangul }
            if kana > 0 && kana + han >= m { return .kana }
            if han == m { return .han }
            if latin == m { return .latin }
            return .other
        }
    }

    static func count(_ text: String) -> ScriptCount {
        var latin = 0, hangul = 0, kana = 0, han = 0, other = 0
        for scalar in text.unicodeScalars {
            let c = scalar.value
            let isLetter = scalar.properties.isAlphabetic
            if (0xAC00...0xD7AF).contains(c) || (0x1100...0x11FF).contains(c) || (0x3130...0x318F).contains(c) {
                hangul += 1
            } else if (0x3040...0x30FF).contains(c) || (0x31F0...0x31FF).contains(c) || (0xFF66...0xFF9F).contains(c) {
                kana += 1
            } else if (0x4E00...0x9FFF).contains(c) || (0x3400...0x4DBF).contains(c) || (0xF900...0xFAFF).contains(c) {
                han += 1
            } else if isLetter && (c < 0x250 || (0x1E00...0x1EFF).contains(c)) {
                latin += 1
            } else if isLetter {
                other += 1
            }
        }
        return ScriptCount(latin: latin, hangul: hangul, kana: kana, han: han, other: other)
    }

    private static let viMarks: Set<Character> = Set(
        [
            "ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ",
            "ÀÁẢÃẠẰẮẲẴẶẦẤẨẪẬÈÉẺẼẸỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌỒỐỔỖỘỜỚỞỠỢÙÚỦŨỤỪỨỬỮỰỲÝỶỸỴ",
        ].joined()
    )

    /// hint = mã ngôn ngữ Vision/NaturalLanguage nếu có.
    static func detect(_ text: String, hint: String? = nil) -> String {
        let c = count(text)
        if c.letters == 0 { return hint ?? "" }
        switch c.dominant {
        case .hangul: return "ko"
        case .kana: return "ja"
        case .han: return hint == "ja" ? "ja" : "zh"
        case .latin:
            let h: String? = (hint?.isEmpty == false && hint != "und") ? hint : nil
            let marks = text.filter { viMarks.contains($0) }.count
            if h == "vi" { return "vi" }
            if marks >= 2 || (marks >= 1 && c.latin < 12) { return "vi" }
            return h ?? "en"
        case .other:
            return hint ?? ""
        }
    }

    static func isEastAsian(_ lang: String) -> Bool { lang == "ko" || lang == "ja" || lang == "zh" }

    static func fontFor(_ lang: String) -> String {
        switch lang {
        case "ko": return "Malgun Gothic"
        case "ja": return "Yu Mincho"
        case "zh": return "SimSun"
        default: return defaultFont
        }
    }

    static func ooxml(_ lang: String) -> String {
        switch lang {
        case "vi", "": return "vi-VN"
        case "en": return "en-US"
        case "ko": return "ko-KR"
        case "ja": return "ja-JP"
        case "zh": return "zh-CN"
        case "de": return "de-DE"
        case "fr": return "fr-FR"
        case "es": return "es-ES"
        case "ru": return "ru-RU"
        case "th": return "th-TH"
        default: return lang
        }
    }
}

/// Quy màu mực đo được về bảng màu chữ chuẩn — port từ Android convert/InkColor.kt.
enum InkColor {
    static let black = 0
    static let blue = 0x1F3A93
    static let red = 0xC0392B
    static let green = 0x1E7B34
    static let purple = 0x6A1B9A

    static func classify(r: Int, g: Int, b: Int) -> Int {
        let mx = max(r, g, b)
        let mn = min(r, g, b)
        let chroma = mx - mn
        if chroma < 28 || mx == 0 || Double(chroma) / Double(mx) < 0.22 { return black }
        var hue: Double
        if mx == r {
            hue = Double(g - b) / Double(chroma)
            if hue < 0 { hue += 6 }
            hue *= 60
        } else if mx == g {
            hue = (Double(b - r) / Double(chroma) + 2) * 60
        } else {
            hue = (Double(r - g) / Double(chroma) + 4) * 60
        }
        if hue < 20 || hue >= 330 { return red }
        if hue < 70 { return black }  // vàng/cam: thường là bút dạ quang tô nền
        if hue < 170 { return green }
        if hue < 255 { return blue }
        return purple
    }
}

/// Quy tắc trình bày bảng dùng chung Word/Excel/PowerPoint — port từ TableStyle.kt.
enum TableStyle {
    static let headerFill = "F2F2F2"

    static func headerRows(_ t: TableBlock) -> Int {
        if !t.bordered || t.rowCount < 3 { return 0 }
        var n = 0
        for r in 0..<min(3, t.rowCount - 1) {
            let cells = t.cells.filter { $0.row == r && !$0.paragraphs.isEmpty }
            if cells.isEmpty || cells.contains(where: { c in c.paragraphs.contains { !$0.bold } }) { break }
            n = r + 1
        }
        return n
    }

    static func isHeaderRow(_ t: TableBlock, _ cell: TableCell) -> Bool {
        cell.row < headerRows(t)
    }
}

func median(_ values: [Double]) -> Double? {
    if values.isEmpty { return nil }
    let s = values.sorted()
    return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
}

/// Sắp xếp ỔN ĐỊNH theo khoá (giống sortedBy của Kotlin — sorted() của Swift không ổn định).
func stableSorted<T>(_ items: [T], by key: (T) -> Double) -> [T] {
    items.enumerated()
        .sorted { a, b in
            let ka = key(a.element)
            let kb = key(b.element)
            return ka != kb ? ka < kb : a.offset < b.offset
        }
        .map { $0.element }
}

/// Khoá chiếm đa số theo trọng số (giữ thứ tự xuất hiện đầu tiên khi hoà — như groupBy + maxByOrNull).
func dominantKey<K: Hashable>(_ items: [(K, Int)]) -> K? {
    var totals: [K: Int] = [:]
    var order: [K] = []
    for (key, weight) in items {
        if totals[key] == nil { order.append(key) }
        totals[key, default: 0] += weight
    }
    var best: K?
    var bestWeight = Int.min
    for key in order {
        let weight = totals[key] ?? 0
        if weight > bestWeight {
            best = key
            bestWeight = weight
        }
    }
    return best
}

/// Làm tròn về Int an toàn (NaN/vô cực → 0) — tương đương roundToInt() của Kotlin.
func roundInt(_ v: Double) -> Int {
    guard v.isFinite else { return 0 }
    return Int(v.rounded())
}

func clampInt(_ v: Int, _ lo: Int, _ hi: Int) -> Int {
    min(max(v, lo), hi)
}

func clampDouble(_ v: Double, _ lo: Double, _ hi: Double) -> Double {
    min(max(v, lo), hi)
}

func corePropsXml(_ title: String) -> String {
    [
        xmlHeader,
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" ",
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" ",
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
        "<dc:title>\(xmlEscape(title))</dc:title><dc:creator>ScanX</dc:creator></cp:coreProperties>",
    ].joined()
}
