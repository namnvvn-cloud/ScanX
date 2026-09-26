import UIKit

/// Định dạng chuyển đổi bố cục — cùng danh sách với Android (ExportFormat DOCX/XLSX/PPTX/TXT).
enum ConvertFormat: String, CaseIterable, Identifiable {
    case docx
    case xlsx
    case pptx
    case txt

    var id: String { rawValue }

    var title: String {
        switch self {
        case .docx: return "Word (.docx)"
        case .xlsx: return "Excel (.xlsx)"
        case .pptx: return "PowerPoint (.pptx)"
        case .txt: return "Văn bản (.txt)"
        }
    }

    var icon: String {
        switch self {
        case .docx: return "doc.richtext"
        case .xlsx: return "tablecells"
        case .pptx: return "rectangle.on.rectangle"
        case .txt: return "doc.plaintext"
        }
    }
}

/// Điều phối chuyển đổi (tương đương phần Word/Excel/PowerPoint của ExportManager.kt):
/// ảnh gốc từng trang → PageLayoutExtractor (OCR + đường kẻ + hình) → LayoutAnalyzer → Docx/Xlsx/PptxWriter.
/// Kết quả 100% chữ + bảng thật (không dán ảnh chụp). Xử lý tuần tự từng trang, giải phóng ảnh ngay.
enum ConvertExporter {
    struct Result {
        let url: URL
        /// Thông báo cho người dùng (vd trang chữ viết tay khó đọc, đoạn chưa dịch được), nil nếu không có.
        let notice: String?
    }

    /// 1 lần xuất: định dạng + (tuỳ chọn) dịch sang tiếng Việt + (tuỳ chọn) AI Cloud đọc lại chữ.
    struct Job {
        var format: ConvertFormat
        var translate: Bool
        var engine: TranslationChoice
        var bilingual: Bool
        var useCloud: Bool
    }

    /// Số trang gộp mỗi lần gọi Claude đọc chữ (như BATCH_SIZE bên Android bản 0.7).
    static let cloudBatchSize = 3

    static func buildDoc(title: String, pageURLs: [URL], status: (String) -> Void) -> DocModel {
        var pages: [DocPage] = []
        for (i, url) in pageURLs.enumerated() {
            status("Đang nhận dạng chữ trang \(i + 1)/\(pageURLs.count)…")
            autoreleasepool {
                if let input = PageLayoutExtractor.extract(url: url) {
                    pages.append(LayoutAnalyzer.analyze(input))
                }
            }
        }
        return DocModel(title: title, pages: pages)
    }

    static func run(
        title: String,
        pageURLs: [URL],
        job: Job,
        to directory: URL,
        status: @escaping (String) -> Void
    ) async throws -> Result {
        var doc = buildDoc(title: title, pageURLs: pageURLs, status: status)
        guard !doc.pages.isEmpty else { throw DocumentStoreError.noPages }
        var notices: [String] = []

        // --- AI Cloud (Claude) đọc lại chữ, gộp mỗi lô 3 trang ---
        if job.useCloud {
            guard let key = SecretStore.get(SecretStore.claudeKey) else {
                throw AIClientError(message: "Chưa nhập API key Claude (Cài đặt → AI Cloud)")
            }
            let client = ClaudeClient(apiKey: key, model: AppSettings.claudeModel)
            var result: [DocPage] = []
            var start = 0
            while start < doc.pages.count {
                let end = min(start + cloudBatchSize, doc.pages.count)
                status("AI Cloud đang đọc chữ trang \(start + 1)–\(end)/\(doc.pages.count)…")
                let batch = Array(doc.pages[start..<end])
                let jpegs = (start..<end).map { cloudJPEG(pageURLs[$0]) }
                result += try await client.transcribeBatch(pages: batch, jpegs: jpegs)
                start = end
            }
            doc.pages = result
        } else {
            let low = doc.pages.filter { $0.ocrConfidence < 0.75 }.count
            if low > 0 {
                notices.append("Có \(low) trang chữ viết tay/khó đọc — bật \"AI Cloud\" khi xuất để nhận dạng chính xác hơn.")
            }
        }

        // --- Dịch sang tiếng Việt ---
        var suffix = ""
        if job.translate {
            let engine = try job.engine.makeEngine()
            let items = DocTranslation.collect(doc)
            var translated: [String: String] = [:]
            if items.isEmpty {
                notices.append("Tài liệu đã là tiếng Việt (hoặc không có chữ cần dịch) — xuất nguyên văn.")
            } else {
                let langs = dominantLanguages(items)
                translated = try await engine.translate(items, context: "ngôn ngữ nguồn: \(langs); tiêu đề: \(doc.title)") { i, n in
                    status("\(engine.label) đang dịch (\(langs) → Tiếng Việt) \(i)/\(n)…")
                }
                let missing = items.filter { (translated[$0.id] ?? "").isEmpty }.count
                if missing > 0 { notices.append("Còn \(missing) đoạn chưa dịch được (giữ nguyên văn gốc).") }
            }
            doc = job.bilingual ? DocTranslation.applyBilingual(doc, translated) : DocTranslation.apply(doc, translated)
            suffix = job.bilingual ? "_SongNgu" : "_TiengViet"
        }

        status("Đang ghi file…")
        let data: Data
        switch job.format {
        case .docx: data = DocxWriter.write(doc)
        case .xlsx: data = XlsxWriter.write(doc)
        case .pptx: data = PptxWriter.write(doc)
        case .txt: data = Data(plainText(doc).utf8)
        }
        let output = directory.appendingPathComponent("\(safeFileName(title))\(suffix).\(job.format.rawValue)")
        if FileManager.default.fileExists(atPath: output.path) {
            try FileManager.default.removeItem(at: output)
        }
        try data.write(to: output, options: .atomic)
        return Result(url: output, notice: notices.isEmpty ? nil : notices.joined(separator: "\n"))
    }

    /// Ảnh trang gửi AI: cạnh dài ≤ 2000 px, JPEG q85 (như CloudAiClient.encode bên Android).
    private static func cloudJPEG(_ url: URL) -> Data {
        ImageLoader.downsampled(at: url, maxPixel: 2000)?.jpegData(compressionQuality: 0.85) ?? Data()
    }

    private static func dominantLanguages(_ items: [DocTranslation.Item]) -> String {
        var totals: [String: Int] = [:]
        for it in items { totals[it.lang, default: 0] += it.text.count }
        return totals.sorted { $0.value > $1.value }.map { Lang.displayName($0.key) }.joined(separator: ", ")
    }

    /// Văn bản thuần: đoạn theo thứ tự đọc, bảng thành các cột cách nhau bằng Tab (như DocTranslation.plainText).
    static func plainText(_ doc: DocModel) -> String {
        var sb = ""
        for (pi, page) in doc.pages.enumerated() {
            if doc.pages.count > 1 { sb += "--- Trang \(pi + 1) ---\n" }
            for block in page.blocks {
                switch block {
                case .paragraph(let p):
                    sb += p.text + "\n"
                case .table(let t):
                    for r in 0..<t.rowCount {
                        let row = t.cells.filter { $0.row == r }.sorted { $0.col < $1.col }
                        if row.isEmpty { continue }
                        sb += row.map { c in c.paragraphs.map { $0.text }.joined(separator: " ") }.joined(separator: "\t") + "\n"
                    }
                case .image:
                    break
                }
            }
            sb += "\n"
        }
        return sb.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func safeFileName(_ title: String) -> String {
        title.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined(separator: "_")
    }
}
