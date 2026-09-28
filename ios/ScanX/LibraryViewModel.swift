import Foundation
import FirebaseAuth
import UIKit

/// Bộ lọc thông minh ở màn chính.
enum SmartFilter: String, CaseIterable, Identifiable {
    case all, recent, hasText, receipts, multiPage
    var id: String { rawValue }
    var label: String {
        switch self {
        case .all: return "Tất cả"
        case .recent: return "7 ngày qua"
        case .hasText: return "Có văn bản"
        case .receipts: return "Hoá đơn, biên lai"
        case .multiPage: return "Nhiều trang"
        }
    }
}

/// Nội dung email chờ gửi (Mẫu email + PDF đính kèm).
struct EmailPayload: Identifiable {
    let id = UUID()
    let to: [String]
    let subject: String
    let body: String
    let attachment: URL
}

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

    func saveScan(pageFiles: [URL], title customTitle: String? = nil) async -> DocumentMeta? {
        guard !pageFiles.isEmpty else { return nil }
        isSaving = true
        defer { isSaving = false }
        let title = customTitle ?? Self.defaultTitle()
        let store = self.store
        let mode = AppSettings.pdfMode
        let runOCR = AppSettings.ocrEnabled
        do {
            let meta = try await Task.detached(priority: .userInitiated) {
                try store.create(title: title, pageFiles: pageFiles, mode: mode, runOCR: runOCR)
            }.value
            reload()
            afterSave(meta)
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

    // MARK: - Bộ lọc thông minh

    @Published var smartFilter: SmartFilter = .all
    private var ocrCache: [String: (Date, String)] = [:]

    func ocrText(_ meta: DocumentMeta) -> String {
        if let cached = ocrCache[meta.id], cached.0 == meta.modifiedAt { return cached.1 }
        let text = store.ocrText(for: meta.id)
        ocrCache[meta.id] = (meta.modifiedAt, text)
        return text
    }

    var visibleDocuments: [DocumentMeta] {
        let now = Date()
        return documents.filter { meta in
            switch smartFilter {
            case .all: return true
            case .recent: return now.timeIntervalSince(meta.createdAt) <= 7 * 24 * 3600
            case .hasText: return !ocrText(meta).isEmpty
            case .receipts: return ReceiptParser.looksLikeReceipt(ocrText(meta))
            case .multiPage: return meta.pageCount >= 3
            }
        }
    }

    // MARK: - Công cụ Văn bản / Sách / Chữ ký

    /// OCR ảnh vừa quét → văn bản (không lưu tài liệu). Xoá file tạm sau khi đọc.
    func recognizeText(files: [URL]) async -> String {
        progressText = "Đang nhận dạng chữ…"
        defer { progressText = nil }
        let text = await Task.detached(priority: .userInitiated) { () -> String in
            files.map { url in
                autoreleasepool { PageOCR.recognize(url: url).map(\.text).joined(separator: "\n") }
            }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        }.value
        files.forEach { try? FileManager.default.removeItem(at: $0) }
        return text
    }

    /// Quét sách: tách ảnh 2 trang mở thành trang trái/phải rồi lưu thành 1 tài liệu.
    func saveBookScan(files: [URL]) async -> DocumentMeta? {
        let split = await Task.detached(priority: .userInitiated) { () -> [URL] in
            var out: [URL] = []
            for url in files {
                autoreleasepool {
                    guard let image = UIImage(contentsOfFile: url.path) else { return }
                    let parts = BookSplitter.split(image)
                    if parts.count == 1 { out.append(url); return }
                    for (j, part) in parts.enumerated() {
                        let dest = url.deletingPathExtension().appendingPathExtension("\(j).jpg")
                        if let data = part.jpegData(compressionQuality: 0.92), (try? data.write(to: dest)) != nil {
                            out.append(dest)
                        }
                    }
                    try? FileManager.default.removeItem(at: url)
                }
            }
            return out
        }.value
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy HH_mm"
        return await saveScan(pageFiles: split, title: "Sách " + f.string(from: Date()))
    }

    /// Chèn chữ ký lên trang tại toạ độ chuẩn hoá (x, y góc trên trái; w = bề rộng chữ ký / bề rộng trang).
    func applySignature(id: String, pageIndex: Int, signature: URL, x: CGFloat, y: CGFloat, w: CGFloat) async -> Bool {
        let store = self.store
        do {
            _ = try await Task.detached(priority: .userInitiated) { () throws -> DocumentMeta in
                let pageURL = store.pageURL(for: id, index: pageIndex)
                guard let page = UIImage(contentsOfFile: pageURL.path), let sig = UIImage(contentsOfFile: signature.path) else {
                    throw DocumentStoreError.editFailed
                }
                let size = page.size
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                format.opaque = true
                let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                    page.draw(in: CGRect(origin: .zero, size: size))
                    let sw = w * size.width
                    let sh = sw * sig.size.height / max(1, sig.size.width)
                    sig.draw(in: CGRect(x: x * size.width, y: y * size.height, width: sw, height: sh))
                }
                guard let cg = rendered.cgImage else { throw DocumentStoreError.editFailed }
                return try store.stampPage(id: id, pageIndex: pageIndex, image: cg)
            }.value
            reload()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    // MARK: - Thư mục đám mây / ScanX Cloud / Quy trình

    private func pdfName(_ meta: DocumentMeta) -> String { ToolsStore.safeFileName(meta.title) + ".pdf" }

    private func exportToCloudFolder(_ meta: DocumentMeta, folder: CloudFolder) async throws {
        let file = store.pdfURL(for: meta.id)
        let name = pdfName(meta)
        try await Task.detached(priority: .userInitiated) {
            try ToolsStore.export(file: file, to: folder, name: name)
        }.value
    }

    func saveToCloudFolder(id: String) async {
        guard let meta = document(id: id) else { return }
        guard let folder = ToolsStore.shared.defaultCloudFolder else {
            notice = "Chưa kết nối thư mục đám mây — vào Cài đặt → Dịch vụ lưu trữ."
            return
        }
        progressText = "Đang lưu vào \(folder.name)…"
        defer { progressText = nil }
        do {
            try await exportToCloudFolder(meta, folder: folder)
            notice = "Đã lưu «\(pdfName(meta))» vào \(folder.name) (\(ToolsStore.providerName(folder)))."
        } catch {
            errorMessage = "Không lưu được vào thư mục đám mây: \(error.localizedDescription)"
        }
    }

    private func backupToScanX(_ meta: DocumentMeta) async throws {
        guard let user = Auth.auth().currentUser else { throw BackendAPIError.http(401, "Chưa đăng nhập") }
        let token = try await user.getIDToken()
        let api = BackendAPI(idToken: token)
        let file = store.pdfURL(for: meta.id)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? nil
        let created = try await api.createDocument(title: meta.title, pageCount: meta.pageCount, fileSize: size)
        guard let uploadURL = created["uploadUrl"] as? String else { throw BackendAPIError.invalidResponse }
        try await api.uploadFile(uploadURL: uploadURL, fileURL: file)
    }

    /// Email chờ mở (quy trình có bước "Gửi email") — HomeView hiện màn soạn thư.
    @Published var pendingEmail: EmailPayload?

    func makeEmail(id: String) -> EmailPayload? {
        guard let meta = document(id: id) else { return nil }
        let src = store.pdfURL(for: meta.id)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(pdfName(meta))
        try? FileManager.default.removeItem(at: dest)
        let file = (try? FileManager.default.copyItem(at: src, to: dest)) != nil ? dest : src
        let t = ToolsStore.shared.emailTemplate
        let to = t.to.components(separatedBy: CharacterSet(charactersIn: ",;"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return EmailPayload(
            to: to,
            subject: ToolsStore.render(t.subject, meta: meta),
            body: ToolsStore.render(t.body, meta: meta),
            attachment: file
        )
    }

    /// Chạy quy trình: đặt tên → lưu thư mục đám mây → sao lưu ScanX Cloud → gửi email.
    func runWorkflow(id: String, workflow wf: Workflow) async {
        guard var meta = document(id: id) else { return }
        progressText = "Đang chạy quy trình «\(wf.name)»…"
        defer { progressText = nil }
        var log: [String] = []
        if !wf.renamePattern.isEmpty {
            let title = ToolsStore.render(wf.renamePattern, meta: meta).trimmingCharacters(in: .whitespaces)
            if !title.isEmpty {
                rename(id: id, to: title)
                meta = document(id: id) ?? meta
                log.append("đặt tên")
            }
        }
        if wf.saveToCloudFolder {
            if let folder = ToolsStore.shared.defaultCloudFolder, (try? await exportToCloudFolder(meta, folder: folder)) != nil {
                log.append("lưu thư mục đám mây")
            } else {
                log.append("lưu thư mục đám mây (LỖI)")
            }
        }
        if wf.backupScanX {
            let ent = EntitlementsManager.shared
            if ent.email == nil || !ent.has(.cloudBackup) {
                log.append("sao lưu ScanX (cần Business)")
            } else if (try? await backupToScanX(meta)) != nil {
                log.append("sao lưu ScanX")
            } else {
                log.append("sao lưu ScanX (LỖI)")
            }
        }
        if wf.sendEmail {
            pendingEmail = makeEmail(id: id)
            log.append("gửi email")
        }
        notice = "Quy trình «\(wf.name)»: " + (log.isEmpty ? "không có bước nào" : log.joined(separator: " → "))
    }

    /// Sau mỗi lần lưu tài liệu mới: Tự động tải lên + quy trình tự chạy.
    private func afterSave(_ meta: DocumentMeta) {
        let tools = ToolsStore.shared
        let ent = EntitlementsManager.shared
        Task {
            if tools.autoUploadEnabled, ent.has(.autoUpload) {
                var done: [String] = []
                var failed: [String] = []
                if tools.autoUploadToFolder, ent.has(.cloudFolders), let folder = tools.defaultCloudFolder {
                    if (try? await exportToCloudFolder(meta, folder: folder)) != nil { done.append(folder.name) } else { failed.append(folder.name) }
                }
                if tools.autoUploadToScanX, ent.email != nil, ent.has(.cloudBackup) {
                    if (try? await backupToScanX(meta)) != nil { done.append("ScanX Cloud") } else { failed.append("ScanX Cloud") }
                }
                if !done.isEmpty || !failed.isEmpty {
                    notice = (done.isEmpty ? "" : "Đã tự động tải lên: \(done.joined(separator: ", ")). ")
                        + (failed.isEmpty ? "" : "Lỗi: \(failed.joined(separator: ", ")).")
                }
            }
            if let wf = tools.autoWorkflow {
                switch ent.tryUse([.workflows]) {
                case .locked: notice = "Quy trình «\(wf.name)» không tự chạy: cần gói Business."
                case .business, .trial: await runWorkflow(id: meta.id, workflow: wf)
                }
            }
        }
    }

    // MARK: - Báo cáo chi phí

    func expenseCandidates() -> [ExpenseItem] {
        let withText = documents.filter { !ocrText($0).isEmpty }
        let receipts = withText.filter { ReceiptParser.looksLikeReceipt(ocrText($0)) }
        return (receipts.isEmpty ? withText : receipts)
            .sorted { $0.createdAt > $1.createdAt }
            .map { ReceiptParser.parse(documentID: $0.id, title: $0.title, text: ocrText($0)) }
    }

    func writeExpenseCSV(_ items: [ExpenseItem]) -> URL? {
        let titles = Dictionary(uniqueKeysWithValues: documents.map { ($0.id, $0.title) })
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HHmm"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(f.string(from: Date()))_bao-cao-chi-phi.csv")
        do {
            try ReceiptParser.csv(items, titles: titles).write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    // MARK: - Hỗ trợ

    /// Gửi yêu cầu hỗ trợ; trả thông báo kết quả (✓ … khi thành công).
    func sendSupport(subject: String, message: String, email: String) async -> String {
        var body: [String: Any] = [
            "subject": String(subject.trimmingCharacters(in: .whitespacesAndNewlines).prefix(150)),
            "message": String(message.trimmingCharacters(in: .whitespacesAndNewlines).prefix(5000)),
            "installId": DeviceRegistry.installId,
            "platform": "ios",
            "appVersion": AppConfigManager.versionName,
            "device": "\(UIDevice.current.model) · iOS \(UIDevice.current.systemVersion)",
        ]
        let mail = email.trimmingCharacters(in: .whitespaces)
        if mail.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil { body["email"] = mail }
        let token = try? await Auth.auth().currentUser?.getIDToken()
        do {
            let res = try await BackendAPI.createSupportTicket(body: body, idToken: token)
            return (res["priority"] as? Bool) == true
                ? "✓ Đã gửi yêu cầu (ưu tiên Business). Chúng tôi sẽ phản hồi qua email sớm nhất."
                : "✓ Đã gửi yêu cầu. Chúng tôi sẽ phản hồi qua email."
        } catch {
            return "Gửi thất bại: \(error.localizedDescription) — thử lại hoặc gửi email trực tiếp."
        }
    }

    /// Cùng kiểu tên với Android: "Scan 22-09-2026 19_59".
    static func defaultTitle(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "dd-MM-yyyy HH_mm"
        return "Scan " + formatter.string(from: date)
    }
}
