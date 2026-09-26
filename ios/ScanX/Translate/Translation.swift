import Foundation

/// "Dịch sang tiếng Việt" giữ nguyên bố cục — port 1:1 từ Android convert/DocTranslation.kt: chỉ thay CHỮ
/// của từng đoạn/ô bằng bản dịch; đoạn đã là tiếng Việt, số liệu, mã viết tắt ngắn giữ nguyên.
enum DocTranslation {
    struct Item {
        let id: String
        let text: String
        let lang: String
    }

    static let target = "vi"

    static func collect(_ doc: DocModel) -> [Item] {
        var out: [Item] = []
        for (pi, page) in doc.pages.enumerated() {
            for (bi, block) in page.blocks.enumerated() {
                switch block {
                case .paragraph(let p):
                    add(&out, "g\(pi)b\(bi)", p)
                case .table(let t):
                    for c in t.cells {
                        for (k, p) in c.paragraphs.enumerated() {
                            add(&out, "g\(pi)b\(bi)r\(c.row)c\(c.col)k\(k)", p)
                        }
                    }
                case .image:
                    break
                }
            }
        }
        return out
    }

    private static func add(_ out: inout [Item], _ id: String, _ p: Paragraph) {
        let text = p.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needsTranslation(text, lang: p.lang) else { return }
        out.append(Item(id: id, text: text, lang: p.lang.isEmpty ? Lang.detect(text) : p.lang))
    }

    /// Có chữ cái và không phải tiếng Việt → cần dịch.
    static func needsTranslation(_ text: String, lang: String) -> Bool {
        let c = Lang.count(text)
        if c.letters < 2 { return false }
        let l = lang.isEmpty ? Lang.detect(text) : lang
        // Dòng Latin ngắn kiểu mã hiệu/viết tắt ("GIS", "KPI 2026") giữ nguyên.
        if l != target && c.dominant == .latin && c.latin <= 4 && !text.contains(" ") { return false }
        return l != target || c.hangul + c.kana + c.han > 0
    }

    /// Thay chữ bằng bản dịch.
    static func apply(_ doc: DocModel, _ translated: [String: String], titleSuffix: String = " (tiếng Việt)") -> DocModel {
        func tr(_ id: String, _ p: Paragraph) -> Paragraph {
            guard let t = translated[id]?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return p }
            var q = p
            q.text = t
            q.lang = target
            return q
        }
        var pages: [DocPage] = []
        for (pi, page) in doc.pages.enumerated() {
            var newPage = page
            newPage.blocks = page.blocks.enumerated().map { (bi, block) -> Block in
                switch block {
                case .paragraph(let p):
                    return .paragraph(tr("g\(pi)b\(bi)", p))
                case .table(var t):
                    t.cells = t.cells.map { c in
                        var cell = c
                        cell.paragraphs = c.paragraphs.enumerated().map { k, p in
                            tr("g\(pi)b\(bi)r\(c.row)c\(c.col)k\(k)", p)
                        }
                        return cell
                    }
                    return .table(t)
                case .image:
                    return block
                }
            }
            pages.append(newPage)
        }
        return DocModel(title: doc.title + titleSuffix, pages: pages)
    }

    /// Song ngữ: GIỮ đoạn/ô gốc, chèn bản dịch (in nghiêng) ngay sau.
    static func applyBilingual(_ doc: DocModel, _ translated: [String: String], titleSuffix: String = " (song ngữ)") -> DocModel {
        func translatedOrNil(_ id: String, _ p: Paragraph) -> Paragraph? {
            guard let t = translated[id]?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty,
                  t != p.text.trimmingCharacters(in: .whitespacesAndNewlines)
            else { return nil }
            var q = p
            q.text = t
            q.lang = target
            q.italic = true
            q.lineBoxes = []
            return q
        }
        var pages: [DocPage] = []
        for (pi, page) in doc.pages.enumerated() {
            var blocks: [Block] = []
            for (bi, block) in page.blocks.enumerated() {
                switch block {
                case .paragraph(let p):
                    blocks.append(block)
                    if var t = translatedOrNil("g\(pi)b\(bi)", p) {
                        let ob = p.box
                        t.box = Box(left: ob.left, top: ob.bottom, right: ob.right, bottom: ob.bottom + ob.height)
                        t.spaceBeforePx = 0
                        blocks.append(.paragraph(t))
                    }
                case .table(var t):
                    t.cells = t.cells.map { c in
                        var extra: [Paragraph] = []
                        for (k, p) in c.paragraphs.enumerated() {
                            if let tp = translatedOrNil("g\(pi)b\(bi)r\(c.row)c\(c.col)k\(k)", p) { extra.append(tp) }
                        }
                        if extra.isEmpty { return c }
                        var cell = c
                        cell.paragraphs += extra
                        return cell
                    }
                    blocks.append(.table(t))
                case .image:
                    blocks.append(block)
                }
            }
            var newPage = page
            newPage.blocks = blocks
            pages.append(newPage)
        }
        return DocModel(title: doc.title + titleSuffix, pages: pages)
    }

    /// Chia lô: mỗi lô ≤ maxChars ký tự và ≤ maxItems đoạn.
    static func batches(_ items: [Item], maxChars: Int = 6000, maxItems: Int = 80) -> [[Item]] {
        var out: [[Item]] = []
        var cur: [Item] = []
        var chars = 0
        for it in items {
            if !cur.isEmpty && (chars + it.text.count > maxChars || cur.count >= maxItems) {
                out.append(cur)
                cur = []
                chars = 0
            }
            cur.append(it)
            chars += it.text.count
        }
        if !cur.isEmpty { out.append(cur) }
        return out
    }

    /// Chỉ dẫn dịch cho mô hình ngôn ngữ lớn (Claude/Gemini) — trả về JSON theo mã đoạn.
    static func llmPrompt(_ batch: [Item], context: String) -> String {
        var sb = [
            "Bạn là biên dịch viên chuyên nghiệp. Dịch các đoạn văn bản dưới đây (trích từ 1 tài liệu scan: \(context)) ",
            "sang TIẾNG VIỆT chuẩn mực, tự nhiên, đúng văn phong tài liệu hành chính/kỹ thuật Việt Nam.\n",
            "Quy tắc:\n",
            "- Dịch đúng nghĩa theo ngữ cảnh cả tài liệu; dùng thuật ngữ chuyên ngành chuẩn tiếng Việt (điện lực, viễn thông, CNTT, tài chính, pháp lý…); ",
            "tên riêng/tên hệ thống/viết tắt thông dụng (GIS, KPI, 5G…) giữ nguyên.\n",
            "- Giữ nguyên số, đơn vị, ngày tháng, ký hiệu tiền, mã hiệu, dấu đầu dòng, nhãn trong ngoặc như [KR], [VN].\n",
            "- Đoạn đã là tiếng Việt hoặc không cần dịch → trả lại nguyên văn.\n",
            "- Không thêm giải thích, không gộp/tách đoạn.\n",
            "Chỉ trả về 1 đối tượng JSON {\"<mã>\": \"<bản dịch>\", ...} cho đủ mọi mã, không kèm chữ nào khác.\n\n",
            "Các đoạn (mã | ngôn ngữ nguồn | nội dung):\n",
        ].joined()
        for it in batch {
            sb += "\(it.id) | \(it.lang.isEmpty ? "?" : it.lang) | \(it.text.replacingOccurrences(of: "\n", with: " "))\n"
        }
        return sb
    }

    /// Lấy đối tượng JSON đầu tiên trong câu trả lời của mô hình (bỏ chữ thừa quanh nó).
    static func extractJSONObject(_ raw: String) -> [String: Any]? {
        guard let start = raw.firstIndex(of: "{"), let end = raw.lastIndex(of: "}"), start < end else { return nil }
        let slice = String(raw[start...end])
        guard let data = slice.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

extension Lang {
    static func displayName(_ lang: String) -> String {
        switch lang {
        case "vi": return "Tiếng Việt"
        case "en": return "Tiếng Anh"
        case "ko": return "Tiếng Hàn"
        case "ja": return "Tiếng Nhật"
        case "zh": return "Tiếng Trung"
        case "de": return "Tiếng Đức"
        case "fr": return "Tiếng Pháp"
        case "es": return "Tiếng Tây Ban Nha"
        case "ru": return "Tiếng Nga"
        case "th": return "Tiếng Thái"
        default: return lang.isEmpty ? "Không rõ" : lang
        }
    }
}
