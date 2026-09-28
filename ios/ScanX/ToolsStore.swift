import Foundation
import UIKit

/// Thư mục đám mây đã kết nối qua Files (iCloud Drive, Google Drive, OneDrive, Dropbox… — cần cài app của
/// dịch vụ đó và bật trong Files). Lưu bookmark bảo mật để ghi lại được sau khi mở lại app.
struct CloudFolder: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var bookmark: Data
}

/// Mẫu email khi gửi tài liệu. Biến: {ten} {ngay} {gio} {so_trang}.
struct EmailTemplate: Codable, Equatable {
    var to: String
    var subject: String
    var body: String

    static let `default` = EmailTemplate(
        to: "",
        subject: "Gửi tài liệu: {ten}",
        body: "Chào anh/chị,\n\nGửi kèm tài liệu «{ten}» ({so_trang} trang), quét ngày {ngay}.\n\nTrân trọng."
    )
}

/// Quy trình tự động — giống Workflow bên Android.
struct Workflow: Codable, Identifiable, Hashable {
    var id: String = UUID().uuidString
    var name: String
    var renamePattern: String = ""
    var folderId: String? = nil
    var saveToCloudFolder = false
    var backupScanX = false
    var sendEmail = false
    var runAfterScan = false

    var stepCount: Int {
        [!renamePattern.isEmpty, saveToCloudFolder, backupScanX, sendEmail].filter { $0 }.count
    }
}

/// Cấu hình công cụ (UserDefaults) + ảnh chữ ký (PNG trong Application Support, chỉ trên máy này).
final class ToolsStore: ObservableObject {
    static let shared = ToolsStore()

    private let defaults = UserDefaults.standard
    private enum Key {
        static let folders = "tools.cloudFolders"
        static let defaultFolder = "tools.defaultCloudFolder"
        static let autoUpload = "tools.autoUpload"
        static let autoFolder = "tools.autoUploadFolder"
        static let autoScanX = "tools.autoUploadScanX"
        static let email = "tools.emailTemplate"
        static let workflows = "tools.workflows"
        static let qr = "tools.qrHistory"
    }

    @Published private(set) var cloudFolders: [CloudFolder] = []
    @Published private(set) var workflows: [Workflow] = []
    @Published private(set) var qrHistory: [String] = []
    @Published private(set) var signatures: [URL] = []

    @Published var defaultCloudFolderID: String? {
        didSet { defaults.set(defaultCloudFolderID, forKey: Key.defaultFolder) }
    }
    @Published var autoUploadEnabled: Bool {
        didSet { defaults.set(autoUploadEnabled, forKey: Key.autoUpload) }
    }
    @Published var autoUploadToFolder: Bool {
        didSet { defaults.set(autoUploadToFolder, forKey: Key.autoFolder) }
    }
    @Published var autoUploadToScanX: Bool {
        didSet { defaults.set(autoUploadToScanX, forKey: Key.autoScanX) }
    }
    @Published var emailTemplate: EmailTemplate {
        didSet { save(emailTemplate, Key.email) }
    }

    private init() {
        let d = UserDefaults.standard
        defaultCloudFolderID = d.string(forKey: Key.defaultFolder)
        autoUploadEnabled = d.bool(forKey: Key.autoUpload)
        autoUploadToFolder = d.object(forKey: Key.autoFolder) as? Bool ?? true
        autoUploadToScanX = d.bool(forKey: Key.autoScanX)
        emailTemplate = Self.load(EmailTemplate.self, Key.email) ?? .default
        cloudFolders = Self.load([CloudFolder].self, Key.folders) ?? []
        workflows = Self.load([Workflow].self, Key.workflows) ?? []
        qrHistory = d.stringArray(forKey: Key.qr) ?? []
        reloadSignatures()
    }

    // MARK: Thư mục đám mây

    var defaultCloudFolder: CloudFolder? {
        cloudFolders.first { $0.id == defaultCloudFolderID } ?? cloudFolders.first
    }

    /// [url] = thư mục người dùng vừa chọn trong UIDocumentPicker (đang có quyền truy cập bảo mật).
    func addCloudFolder(url: URL) throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let folder = CloudFolder(id: UUID().uuidString, name: url.lastPathComponent, bookmark: bookmark)
        cloudFolders.append(folder)
        save(cloudFolders, Key.folders)
        if defaultCloudFolderID == nil { defaultCloudFolderID = folder.id }
    }

    func removeCloudFolder(id: String) {
        cloudFolders.removeAll { $0.id == id }
        save(cloudFolders, Key.folders)
        if defaultCloudFolderID == id { defaultCloudFolderID = cloudFolders.first?.id }
    }

    /// Ghi [file] vào thư mục đã kết nối với tên [name] (trùng tên thì thêm hậu tố). Chạy được ở luồng nền.
    static func export(file: URL, to folder: CloudFolder, name: String) throws {
        var stale = false
        let dir = try URL(resolvingBookmarkData: folder.bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        let access = dir.startAccessingSecurityScopedResource()
        defer { if access { dir.stopAccessingSecurityScopedResource() } }
        let fm = FileManager.default
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var target = dir.appendingPathComponent(name)
        var n = 2
        while fm.fileExists(atPath: target.path) {
            target = dir.appendingPathComponent("\(base) (\(n)).\(ext)")
            n += 1
        }
        var coordError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(writingItemAt: target, options: .forReplacing, error: &coordError) { dest in
            do { try fm.copyItem(at: file, to: dest) } catch { copyError = error }
        }
        if let coordError { throw coordError }
        if let copyError { throw copyError }
    }

    /// Tên dịch vụ đoán từ đường dẫn thư mục (File Provider của từng app).
    static func providerName(_ folder: CloudFolder) -> String {
        var stale = false
        let path = ((try? URL(resolvingBookmarkData: folder.bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale))?.path ?? "").lowercased()
        if path.contains("mobile documents") || path.contains("icloud") { return "iCloud Drive" }
        if path.contains("google") { return "Google Drive" }
        if path.contains("onedrive") || path.contains("skydrive") { return "OneDrive" }
        if path.contains("dropbox") { return "Dropbox" }
        if path.contains("box") { return "Box" }
        return "Tệp"
    }

    // MARK: Quy trình

    func saveWorkflow(_ w: Workflow) {
        var list = workflows.filter { $0.id != w.id } + [w]
        if w.runAfterScan {
            list = list.map { item in
                var copy = item
                if copy.id != w.id { copy.runAfterScan = false }
                return copy
            }
        }
        workflows = list
        save(workflows, Key.workflows)
    }

    func deleteWorkflow(id: String) {
        workflows.removeAll { $0.id == id }
        save(workflows, Key.workflows)
    }

    var autoWorkflow: Workflow? { workflows.first { $0.runAfterScan } }

    // MARK: Chữ ký

    private var signaturesDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("signatures", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func reloadSignatures() {
        let files = (try? FileManager.default.contentsOfDirectory(at: signaturesDir, includingPropertiesForKeys: nil)) ?? []
        signatures = files.filter { $0.pathExtension == "png" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    func saveSignature(_ image: UIImage) {
        guard let data = image.pngData() else { return }
        let url = signaturesDir.appendingPathComponent("sig_\(Int(Date().timeIntervalSince1970 * 1000)).png")
        try? data.write(to: url, options: .atomic)
        reloadSignatures()
    }

    func deleteSignature(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        reloadSignatures()
    }

    // MARK: Lịch sử QR

    func addQR(_ value: String) {
        qrHistory = Array(([value] + qrHistory.filter { $0 != value }).prefix(50))
        defaults.set(qrHistory, forKey: Key.qr)
    }

    func clearQR() {
        qrHistory = []
        defaults.removeObject(forKey: Key.qr)
    }

    // MARK: Tiện ích

    /// Thay biến {ten} {ngay} {gio} {so_trang} trong mẫu.
    static func render(_ template: String, meta: DocumentMeta) -> String {
        let d = DateFormatter()
        d.locale = Locale(identifier: "vi_VN")
        d.dateFormat = "dd/MM/yyyy"
        let t = DateFormatter()
        t.locale = Locale(identifier: "vi_VN")
        t.dateFormat = "HH:mm"
        return template
            .replacingOccurrences(of: "{ten}", with: meta.title)
            .replacingOccurrences(of: "{ngay}", with: d.string(from: meta.createdAt))
            .replacingOccurrences(of: "{gio}", with: t.string(from: meta.createdAt))
            .replacingOccurrences(of: "{so_trang}", with: String(meta.pageCount))
    }

    static func safeFileName(_ name: String) -> String {
        let cleaned = name.components(separatedBy: CharacterSet(charactersIn: "/\\:*?\"<>|")).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String((cleaned.isEmpty ? "ScanX" : cleaned).prefix(120))
    }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }

    private static func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
