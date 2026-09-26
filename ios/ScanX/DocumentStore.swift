import UIKit

struct DocumentMeta: Codable, Identifiable, Hashable {
    let id: String
    var title: String
    let createdAt: Date
    var modifiedAt: Date
    var pageCount: Int
}

enum DocumentStoreError: LocalizedError {
    case noPages
    case notFound

    var errorDescription: String? {
        switch self {
        case .noPages: return "Không có trang nào để lưu"
        case .notFound: return "Không tìm thấy tài liệu"
        }
    }
}

/// Lưu tài liệu trên máy, cùng cấu trúc với Android (DocumentRepository):
///   documents/<id>/pages/page_NNN.jpg  (ảnh gốc màu)
///   documents/<id>/meta.json
///   documents/<id>/document.pdf
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
    func create(title: String, pageFiles: [URL]) throws -> DocumentMeta {
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
        let meta = DocumentMeta(id: id, title: title, createdAt: now, modifiedAt: now, pageCount: pageFiles.count)
        try saveMeta(meta)
        try PDFBuilder.build(pageURLs: pageURLs(for: meta), to: pdfURL(for: id))
        makeThumbnail(id: id)
        return meta
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
