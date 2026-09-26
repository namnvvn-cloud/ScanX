import Foundation

/// Chế độ "AI Cloud" cho Word/Excel/PowerPoint — port 1:1 từ Android convert/CloudTranscription.kt:
/// BỐ CỤC do máy dựng (bảng, ô gộp, vị trí), CHỮ do Claude đọc lại từ ảnh trang (kể cả chữ viết tay tiếng
/// Việt). Mỗi ô chữ có mã + khung 0–1000 + bản nháp OCR; AI trả nội dung theo mã → ghép lại bố cục.
enum CloudTranscription {
    struct Slot {
        let id: String
        let box: Box
        let draft: String
        let inTable: Bool
        let lang: String
    }

    struct ExtraText {
        let text: String
        let box: Box
    }

    static func slots(_ page: DocPage) -> [Slot] {
        var out: [Slot] = []
        for (bi, block) in page.blocks.enumerated() {
            switch block {
            case .paragraph(let p):
                out.append(Slot(id: "p\(bi)", box: p.box, draft: p.text, inTable: false, lang: p.lang))
            case .table(let t):
                for cell in t.cells {
                    let r1 = min(t.rowCount, cell.row + cell.rowSpan)
                    let c1 = min(t.colCount, cell.col + cell.colSpan)
                    let box = Box(left: t.colEdges[cell.col], top: t.rowEdges[cell.row], right: t.colEdges[c1], bottom: t.rowEdges[r1])
                    out.append(Slot(
                        id: "t\(bi)r\(cell.row)c\(cell.col)",
                        box: box,
                        draft: cell.paragraphs.map { $0.text }.joined(separator: "\n"),
                        inTable: t.bordered,
                        lang: cell.paragraphs.first?.lang ?? ""
                    ))
                }
            case .image:
                break
            }
        }
        return out
    }

    static func prompt(_ page: DocPage) -> String {
        [
            "Bạn là hệ thống nhận dạng tài liệu. Ảnh đính kèm là 1 trang tài liệu đã scan (có thể có chữ in, ",
            "chữ viết tay tiếng Việt, số tiền, bảng biểu). Bố cục đã được máy phân tích sẵn thành các Ô CHỮ ",
            "dưới đây, mỗi ô có mã, khung toạ độ [trái, trên, phải, dưới] theo thang 0–1000 của chiều rộng/chiều cao ",
            "trang, và bản nháp OCR (thường sai với chữ viết tay).\n\n",
            taskRules(),
            "Chỉ trả về 1 đối tượng JSON, không kèm chữ nào khác, dạng:\n",
            "{\"slots\": {\"<mã>\": \"<nội dung>\", ...}, \"extra\": [{\"text\": \"...\", \"box\": [l, t, r, b]}]}\n\n",
            "Các ô chữ:\n",
            slotListing(page),
        ].joined()
    }

    static func batchPrompt(_ pages: [DocPage]) -> String {
        let n = pages.count
        var sb = [
            "Bạn là hệ thống nhận dạng tài liệu. \(n) ảnh đính kèm theo đúng thứ tự là \(n) TRANG ",
            "tài liệu đã scan (Trang 1, Trang 2, …) — MỖI ẢNH LÀ 1 TRANG RIÊNG, không liên quan nội dung nhau. ",
            "Có thể có chữ in, chữ viết tay tiếng Việt, số tiền, bảng biểu. Bố cục mỗi trang đã được máy phân tích ",
            "sẵn thành các Ô CHỮ dưới đây, mỗi ô có mã, khung toạ độ [trái, trên, phải, dưới] theo thang 0–1000 của ",
            "chiều rộng/chiều cao TRANG ĐÓ, và bản nháp OCR (thường sai với chữ viết tay).\n\n",
            taskRules(),
            "Chỉ trả về 1 đối tượng JSON, không kèm chữ nào khác, dạng:\n",
            "{\"pages\": [ {\"slots\": {\"<mã>\": \"<nội dung>\", ...}, \"extra\": [{\"text\": \"...\", \"box\": [l, t, r, b]}]}, ... ]}\n",
            "Mảng \"pages\" phải có đúng \(n) phần tử, theo đúng thứ tự Trang 1 → Trang \(n).\n\n",
        ].joined()
        for (i, page) in pages.enumerated() {
            sb += "=== Trang \(i + 1) (ảnh thứ \(i + 1)) — các ô chữ ===\n"
            sb += slotListing(page)
        }
        return sb
    }

    private static func taskRules() -> String {
        [
            "Nhiệm vụ: đọc chính xác chữ nằm TRONG TỪNG KHUNG trên ảnh tương ứng và trả về đúng nội dung đó.\n",
            "Quy tắc:\n",
            "- Giữ nguyên ngôn ngữ và hệ chữ gốc của từng ô: tiếng Việt có dấu đầy đủ, chính xác; tiếng Hàn bằng Hangul; ",
            "tiếng Nhật bằng Kanji/Kana; tiếng Trung bằng Hán tự; tiếng Anh/Đức/Pháp… đúng chính tả và dấu (ä ö ü ß é è ç…). ",
            "Mục \"ngôn ngữ\" chỉ là gợi ý của máy, ảnh mới là chuẩn. Giữ nguyên số, ",
            "ký hiệu tiền (k, tr, đ, M…), dấu ngoặc, dấu +, /, ngày tháng như trên giấy. Không dịch, không giải thích, không sửa nội dung.\n",
            "- Ô không có chữ → chuỗi rỗng \"\". Chữ bị gạch xoá → bỏ qua.\n",
            "- Nhiều dòng trong 1 ô → nối bằng \\n.\n",
            "- Chữ nằm ngoài mọi khung (ghi chú lề, chữ OCR bỏ sót) → đưa vào \"extra\" kèm khung 0–1000.\n",
        ].joined()
    }

    private static func slotListing(_ page: DocPage) -> String {
        let w = max(1, Double(page.width))
        let h = max(1, Double(page.height))
        func n(_ v: Double, _ d: Double) -> Int { clampInt(roundInt(v / d * 1000), 0, 1000) }
        var sb = ""
        for s in slots(page) {
            let draft = String(s.draft.replacingOccurrences(of: "\"", with: "'").replacingOccurrences(of: "\n", with: " ").prefix(120))
            sb += s.id
            sb += s.inTable ? " (ô bảng)" : ""
            sb += " [\(n(s.box.left, w)), \(n(s.box.top, h)), \(n(s.box.right, w)), \(n(s.box.bottom, h))]"
            sb += s.lang.isEmpty ? "" : " ngôn ngữ: \(s.lang)"
            sb += " nháp: \"\(draft)\"\n"
        }
        return sb
    }

    static func parseSlotsAndExtras(_ json: [String: Any]) -> ([String: String], [ExtraText]) {
        var answers: [String: String] = [:]
        if let slots = json["slots"] as? [String: Any] {
            for (k, v) in slots {
                answers[k] = (v as? String) ?? ""
            }
        }
        var extras: [ExtraText] = []
        for item in json["extra"] as? [[String: Any]] ?? [] {
            guard let b = item["box"] as? [Any], b.count >= 4 else { continue }
            let nums = b.prefix(4).map { ($0 as? NSNumber)?.doubleValue ?? 0 }
            extras.append(ExtraText(
                text: (item["text"] as? String) ?? "",
                box: Box(left: nums[0], top: nums[1], right: nums[2], bottom: nums[3])
            ))
        }
        return (answers, extras)
    }

    /// Ghép kết quả AI vào bố cục (khung extras theo thang 0–1000).
    static func apply(_ page: DocPage, answers: [String: String], extras: [ExtraText]) -> DocPage {
        let cellParas = page.blocks.compactMap { $0.table }.flatMap { t in t.cells.flatMap { $0.paragraphs } }
        let allParas = page.blocks.compactMap { $0.paragraph } + cellParas
        let domFont = dominantSize(cellParas) ?? dominantSize(allParas) ?? 12
        let domColor = dominantKey(allParas.map { ($0.color, $0.text.count) }) ?? 0

        var blocks: [Block] = []
        for (bi, block) in page.blocks.enumerated() {
            switch block {
            case .paragraph(let p):
                guard let ans = answers["p\(bi)"] else {
                    blocks.append(block)
                    continue
                }
                let text = clean(ans)
                if text.isEmpty { continue }
                var q = p
                q.text = text
                q.lang = Lang.detect(text, hint: p.lang)
                blocks.append(.paragraph(q))
            case .table(var t):
                t.cells = t.cells.map { cell in
                    guard let ans = answers["t\(bi)r\(cell.row)c\(cell.col)"] else { return cell }
                    let lines = clean(ans).components(separatedBy: "\n")
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                    var updated = cell
                    if lines.isEmpty {
                        updated.paragraphs = []
                        return updated
                    }
                    let r1 = min(t.rowCount, cell.row + cell.rowSpan)
                    let c1 = min(t.colCount, cell.col + cell.colSpan)
                    let cellBox = Box(left: t.colEdges[cell.col], top: t.rowEdges[cell.row], right: t.colEdges[c1], bottom: t.rowEdges[r1])
                    let template = cell.paragraphs.first
                    let rowPt = cellBox.height * page.ptPerPx
                    let font = template?.fontPt ?? min(domFont, max(8, snap(rowPt * 0.5 / Double(lines.count))))
                    updated.paragraphs = lines.enumerated().map { i, text in
                        let base = i < cell.paragraphs.count ? cell.paragraphs[i] : template
                        if var q = base {
                            q.text = text
                            q.fontPt = font
                            q.lang = Lang.detect(text, hint: q.lang)
                            return q
                        }
                        return Paragraph(
                            text: text, align: .center, fontPt: font, bold: false, indentPx: 0,
                            spaceBeforePx: 0, box: cellBox, color: domColor, lang: Lang.detect(text)
                        )
                    }
                    if cell.paragraphs.isEmpty { updated.vAlign = .center }
                    return updated
                }
                blocks.append(.table(t))
            case .image:
                blocks.append(block)
            }
        }
        for e in extras {
            let text = clean(e.text).replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }
            let box = Box(
                left: e.box.left / 1000 * Double(page.width),
                top: e.box.top / 1000 * Double(page.height),
                right: e.box.right / 1000 * Double(page.width),
                bottom: e.box.bottom / 1000 * Double(page.height)
            )
            if box.width <= 0 || box.height <= 0 { continue }
            blocks.append(.paragraph(Paragraph(
                text: text, align: .left, fontPt: domFont, bold: false, indentPx: 0, spaceBeforePx: 0,
                box: box, lineBoxes: [box], color: domColor, floating: true, lang: Lang.detect(text)
            )))
        }
        var out = page
        out.blocks = blocks
        return out
    }

    private static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\\n", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let sizes: [Double] = [8, 9, 10, 10.5, 11, 12, 13, 14]

    private static func snap(_ v: Double) -> Double {
        sizes.min { abs($0 - v) < abs($1 - v) } ?? 12
    }

    private static func dominantSize(_ paras: [Paragraph]) -> Double? {
        dominantKey(paras.filter { !$0.text.isEmpty }.map { ($0.fontPt, $0.text.count) })
    }
}
