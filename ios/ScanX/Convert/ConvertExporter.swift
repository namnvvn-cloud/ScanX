import Foundation

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
        /// Thông báo cho người dùng (vd trang chữ viết tay khó đọc), nil nếu không có.
        let notice: String?
    }

    static func buildDoc(title: String, pageURLs: [URL]) -> DocModel {
        var pages: [DocPage] = []
        for url in pageURLs {
            autoreleasepool {
                if let input = PageLayoutExtractor.extract(url: url) {
                    pages.append(LayoutAnalyzer.analyze(input))
                }
            }
        }
        return DocModel(title: title, pages: pages)
    }

    static func export(title: String, pageURLs: [URL], format: ConvertFormat, to directory: URL) throws -> Result {
        let doc = buildDoc(title: title, pageURLs: pageURLs)
        guard !doc.pages.isEmpty else { throw DocumentStoreError.noPages }
        let data: Data
        switch format {
        case .docx: data = DocxWriter.write(doc)
        case .xlsx: data = XlsxWriter.write(doc)
        case .pptx: data = PptxWriter.write(doc)
        case .txt: data = Data(plainText(doc).utf8)
        }
        let output = directory.appendingPathComponent("\(safeFileName(title)).\(format.rawValue)")
        if FileManager.default.fileExists(atPath: output.path) {
            try FileManager.default.removeItem(at: output)
        }
        try data.write(to: output, options: .atomic)
        let lowConfidence = doc.pages.filter { $0.ocrConfidence < 0.75 }.count
        let notice = lowConfidence > 0
            ? "Có \(lowConfidence) trang chữ viết tay/khó đọc — kết quả nhận dạng có thể chưa chính xác, nên kiểm tra lại."
            : nil
        return Result(url: output, notice: notice)
    }

    /// Văn bản thuần: đoạn theo thứ tự đọc, bảng thành các cột cách nhau bằng Tab (như Translation.plainText).
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
