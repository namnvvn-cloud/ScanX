import UIKit

struct DocumentMeta: Codable, Identifiable, Hashable {
    let id: String
    var title: String
    let createdAt: Date
    var modifiedAt: Date
    var pageCount: Int
    /// Mã chế độ PDF đang lưu (A1/A2/B1/B2) — nil với tài liệu tạo trước bước 3 (coi như A2).
    var pdfMode: String?
    var hasTextLayer: Bool?
    /// Bộ lọc riêng từng trang (mã PageFilter), nil = trang theo chế độ PDF.
    var pageFilters: [String?]?

    var mode: PDFMode { PDFMode(rawValue: pdfMode ?? "") ?? .a2 }

    var filters: [PageFilter?] {
        (0..<pageCount).map { index in
            guard let codes = pageFilters, index < codes.count, let code = codes[index] else { return nil }
            return PageFilter(rawValue: code)
        }
    }
}

/// 1 thao tác sửa trang — lưu nguyên khối (ảnh + metadata + PDF) như commitPageEdit bên Android.
enum PageEdit {
    case filter(PageFilter?)
    case geometry(quarterTurns: Int, tilt: Double, quad: Quad)
    case cleanup([CleanupStroke])
}

enum DocumentStoreError: LocalizedError {
    case noPages
    case notFound
    case editFailed

    var errorDescription: String? {
        switch self {
        case .noPages: return "Không có trang nào để lưu"
        case .notFound: return "Không tìm thấy tài liệu"
        case .editFailed: return "Không xử lý được ảnh trang"
        }
    }
}

/// Lưu tài liệu trên máy, cùng cấu trúc với Android (DocumentRepository):
///   documents/<id>/pages/page_NNN.jpg  (ảnh gốc màu — bộ lọc chỉ áp lúc dựng PDF, không phá pixel gốc)
///   documents/<id>/meta.json
///   documents/<id>/text_layers.json    (lớp chữ OCR, toạ độ chuẩn hoá)
///   documents/<id>/document.pdf        (PDF theo chế độ lưu)
///   documents/<id>/thumbnail.jpg
final class DocumentStore: @unchecked Sendable {
    static let shared = DocumentStore()

    private let fm = FileManager.default
    let rootURL: URL

    init() {
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        rootURL = base.appendingPathComponent("documents", isDirectory: true)
        try? fm.createDirectory(at: rootURL, withIntermediateDirectories: true)
    }

    func directory(for id: String) -> URL { rootURL.appendingPathComponent(id, isDirectory: true) }
    func pagesDirectory(for id: String) -> URL { directory(for: id).appendingPathComponent("pages", isDirectory: true) }
    func metaURL(for id: String) -> URL { directory(for: id).appendingPathComponent("meta.json") }
    func textLayersURL(for id: String) -> URL { directory(for: id).appendingPathComponent("text_layers.json") }
    func pdfURL(for id: String) -> URL { directory(for: id).appendingPathComponent("document.pdf") }
    func thumbnailURL(for id: String) -> URL { directory(for: id).appendingPathComponent("thumbnail.jpg") }

    func pageURL(for id: String, index: Int) -> URL {
        pagesDirectory(for: id).appendingPathComponent(String(format: "page_%03d.jpg", index + 1))
    }

    func pageURLs(for meta: DocumentMeta) -> [URL] {
        (0..<meta.pageCount).map { pageURL(for: meta.id, index: $0) }
    }

    func loadAll() -> [DocumentMeta] {
        guard let dirs = try? fm.contentsOfDirectory(at: rootURL, includingPropertiesForKeys: nil) else { return [] }
        return dirs.compactMap { dir in
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("meta.json")) else { return nil }
            return try? Self.decoder.decode(DocumentMeta.self, from: data)
        }
        .sorted { $0.modifiedAt > $1.modifiedAt }
    }

    /// pageFiles: ảnh JPEG tạm (đã ghi ra đĩa ngay lúc quét để không giữ ảnh 12 MP trong RAM).
    func create(title: String, pageFiles: [URL], mode: PDFMode, runOCR: Bool) throws -> DocumentMeta {
        guard !pageFiles.isEmpty else { throw DocumentStoreError.noPages }
        let id = UUID().uuidString
        try fm.createDirectory(at: pagesDirectory(for: id), withIntermediateDirectories: true)
        for (index, file) in pageFiles.enumerated() {
            let target = pageURL(for: id, index: index)
            if (try? fm.moveItem(at: file, to: target)) == nil {
                try fm.copyItem(at: file, to: target)
            }
        }
        let now = Date()
        var meta = DocumentMeta(
            id: id,
            title: title,
            createdAt: now,
            modifiedAt: now,
            pageCount: pageFiles.count,
            pdfMode: mode.rawValue,
            hasTextLayer: false,
            pageFilters: nil
        )
        var layers: [[TextLine]]?
        if runOCR {
            let recognized = pageURLs(for: meta).map { url in
                autoreleasepool { PageOCR.recognize(url: url) }
            }
            try Self.encoder.encode(recognized).write(to: textLayersURL(for: id), options: .atomic)
            layers = recognized
            meta.hasTextLayer = true
        }
        try saveMeta(meta)
        try PDFBuilder.build(
            pageURLs: pageURLs(for: meta), mode: mode, pageFilters: nil, textLayers: layers, to: pdfURL(for: id)
        )
        makeThumbnail(id: id)
        return meta
    }

    func commitPageEdit(id: String, pageIndex: Int, edit: PageEdit) throws -> DocumentMeta {
        guard var meta = load(id: id), pageIndex >= 0, pageIndex < meta.pageCount else {
            throw DocumentStoreError.notFound
        }
        let masterURL = pageURL(for: id, index: pageIndex)
        var masterChanged = false
        switch edit {
        case .filter(let filter):
            var codes = meta.pageFilters ?? []
            if codes.count < meta.pageCount {
                codes += [String?](repeating: nil, count: meta.pageCount - codes.count)
            }
            codes[pageIndex] = filter?.rawValue
            meta.pageFilters = codes
        case .geometry(let quarterTurns, let tilt, let quad):
            guard let master = PageTransforms.loadMaster(masterURL),
                  let output = PageTransforms.render(
                    PageTransforms.apply(to: master, quarterTurns: quarterTurns, tiltDegrees: tilt, quad: quad)
                  )
            else { throw DocumentStoreError.editFailed }
            try writeMaster(output, to: masterURL)
            masterChanged = true
        case .cleanup(let strokes):
            guard let source = ImageLoader.downsampled(at: masterURL, maxPixel: 20000)?.cgImage,
                  let rgbx = ScanFilters.rgbxBuffer(from: source),
                  let output = ScanFilters.colorImage(PageCleanup.heal(rgbx, strokes: strokes))
            else { throw DocumentStoreError.editFailed }
            try writeMaster(output, to: masterURL)
            masterChanged = true
        }

        var layers = textLayers(for: id)
        if masterChanged, meta.hasTextLayer == true {
            // Ảnh đổi hình học → lớp chữ cũ lệch vị trí. Android chỉ xoá lớp chữ của trang;
            // iOS chạy lại OCR cho đúng trang đó để PDF vẫn tìm kiếm/copy được.
            var updated = layers ?? []
            if updated.count < meta.pageCount {
                updated += [[TextLine]](repeating: [], count: meta.pageCount - updated.count)
            }
            updated[pageIndex] = PageOCR.recognize(url: masterURL)
            try Self.encoder.encode(updated).write(to: textLayersURL(for: id), options: .atomic)
            layers = updated
        }

        meta.modifiedAt = Date()
        try saveMeta(meta)
        try PDFBuilder.build(
            pageURLs: pageURLs(for: meta),
            mode: meta.mode,
            pageFilters: meta.filters,
            textLayers: meta.hasTextLayer == true ? layers : nil,
            to: pdfURL(for: id)
        )
        if masterChanged, pageIndex == 0 {
            makeThumbnail(id: id)
        }
        return meta
    }

    private func writeMaster(_ image: CGImage, to url: URL) throws {
        guard let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.92) else {
            throw DocumentStoreError.editFailed
        }
        try data.write(to: url, options: .atomic)
    }

    func textLayers(for id: String) -> [[TextLine]]? {
        guard let data = try? Data(contentsOf: textLayersURL(for: id)) else { return nil }
        return try? Self.decoder.decode([[TextLine]].self, from: data)
    }

    /// Xuất PDF theo chế độ người dùng chọn lúc chia sẻ (giống Android: tên "<tài liệu>_A1.pdf").
    func exportPDF(id: String, mode: PDFMode) throws -> URL {
        guard let meta = load(id: id) else { throw DocumentStoreError.notFound }
        let exportDir = fm.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        try fm.createDirectory(at: exportDir, withIntermediateDirectories: true)
        let safeTitle = meta.title
            .components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>"))
            .joined(separator: "_")
        let output = exportDir.appendingPathComponent("\(safeTitle)_\(mode.rawValue).pdf")
        if fm.fileExists(atPath: output.path) {
            try fm.removeItem(at: output)
        }
        // Giống Android: bộ lọc riêng trang chỉ áp khi xuất ĐÚNG chế độ đang lưu của tài liệu;
        // chọn chế độ khác lúc xuất thì áp chế độ đó cho mọi trang.
        try PDFBuilder.build(
            pageURLs: pageURLs(for: meta),
            mode: mode,
            pageFilters: mode == meta.mode ? meta.filters : nil,
            textLayers: textLayers(for: id),
            to: output
        )
        return output
    }

    /// Chuyển đổi bố cục sang Word/Excel/PowerPoint/TXT (chữ + bảng thật, không dán ảnh).
    func exportConverted(id: String, format: ConvertFormat) throws -> ConvertExporter.Result {
        guard let meta = load(id: id) else { throw DocumentStoreError.notFound }
        let exportDir = fm.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        try fm.createDirectory(at: exportDir, withIntermediateDirectories: true)
        return try ConvertExporter.export(title: meta.title, pageURLs: pageURLs(for: meta), format: format, to: exportDir)
    }

    func rename(id: String, to title: String) throws -> DocumentMeta {
        guard var meta = load(id: id) else { throw DocumentStoreError.notFound }
        meta.title = title
        meta.modifiedAt = Date()
        try saveMeta(meta)
        return meta
    }

    func delete(id: String) throws {
        try fm.removeItem(at: directory(for: id))
    }

    func load(id: String) -> DocumentMeta? {
        guard let data = try? Data(contentsOf: metaURL(for: id)) else { return nil }
        return try? Self.decoder.decode(DocumentMeta.self, from: data)
    }

    private func saveMeta(_ meta: DocumentMeta) throws {
        let data = try Self.encoder.encode(meta)
        try data.write(to: metaURL(for: meta.id), options: .atomic)
    }

    private func makeThumbnail(id: String) {
        guard let image = ImageLoader.downsampled(at: pageURL(for: id, index: 0), maxPixel: 360) else { return }
        try? image.jpegData(compressionQuality: 0.8)?.write(to: thumbnailURL(for: id), options: .atomic)
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
}
