import Foundation

@MainActor
final class LibraryViewModel: ObservableObject {
    @Published private(set) var documents: [DocumentMeta] = []
    @Published var isSaving = false
    @Published var errorMessage: String?

    let store = DocumentStore.shared

    init() {
        reload()
    }

    func reload() {
        documents = store.loadAll()
    }

    func document(id: String) -> DocumentMeta? {
        documents.first { $0.id == id }
    }

    func saveScan(pageFiles: [URL]) async -> DocumentMeta? {
        guard !pageFiles.isEmpty else { return nil }
        isSaving = true
        defer { isSaving = false }
        let title = Self.defaultTitle()
        let store = self.store
        let mode = AppSettings.pdfMode
        let runOCR = AppSettings.ocrEnabled
        do {
            let meta = try await Task.detached(priority: .userInitiated) {
                try store.create(title: title, pageFiles: pageFiles, mode: mode, runOCR: runOCR)
            }.value
            reload()
            return meta
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    /// Dựng PDF theo chế độ chọn lúc xuất (chạy nền), trả URL file tạm để chia sẻ.
    func exportPDF(id: String, mode: PDFMode) async -> URL? {
        let store = self.store
        do {
            return try await Task.detached(priority: .userInitiated) {
                try store.exportPDF(id: id, mode: mode)
            }.value
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    @Published var notice: String?

    @Published var progressText: String?

    /// Chuyển đổi Word/Excel/PowerPoint/TXT, tuỳ chọn dịch + AI Cloud — chạy nền, báo tiến độ.
    func runOfficeJob(id: String, job: ConvertExporter.Job) async -> URL? {
        let store = self.store
        progressText = "Đang chuẩn bị…"
        defer { progressText = nil }
        do {
            let source = try store.conversionSource(id: id)
            let result = try await Task.detached(priority: .userInitiated) { [weak self] in
                try await ConvertExporter.run(
                    title: source.title,
                    pageURLs: source.pages,
                    job: job,
                    to: source.directory,
                    status: { text in
                        Task { @MainActor in self?.progressText = text }
                    }
                )
            }.value
            notice = result.notice
            return result.url
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func commitPageEdit(id: String, pageIndex: Int, edit: PageEdit) async -> Bool {
        let store = self.store
        do {
            _ = try await Task.detached(priority: .userInitiated) {
                try store.commitPageEdit(id: id, pageIndex: pageIndex, edit: edit)
            }.value
            reload()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func rename(id: String, to title: String) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            _ = try store.rename(id: id, to: trimmed)
            reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func delete(id: String) {
        do {
            try store.delete(id: id)
            reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Dữ liệu cho AuthViewModel.backupAll (sao lưu 1 chiều lên cloud).
    func backupItems() -> [(id: String, title: String, pageCount: Int, fileURL: URL)] {
        documents.map { (id: $0.id, title: $0.title, pageCount: $0.pageCount, fileURL: store.pdfURL(for: $0.id)) }
    }

    /// Cùng kiểu tên với Android: "Scan 22-09-2026 19_59".
    static func defaultTitle(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "dd-MM-yyyy HH_mm"
        return "Scan " + formatter.string(from: date)
    }
}
