import Foundation
import SwiftUI
import UIKit

/// Kết quả kiểm tra phiên bản — tương đương UpdateInfo.kt.
struct AppUpdateInfo: Identifiable, Equatable {
    var id: Int { latestVersionCode }
    let latestVersionCode: Int
    let latestVersionName: String
    let updateAvailable: Bool
    let forceUpdate: Bool
    let downloadURL: String
    let releaseNotes: String

    init(_ o: [String: Any]) {
        latestVersionCode = o["latestVersionCode"] as? Int ?? 0
        latestVersionName = o["latestVersionName"] as? String ?? ""
        updateAvailable = o["updateAvailable"] as? Bool ?? false
        forceUpdate = o["forceUpdate"] as? Bool ?? false
        downloadURL = o["downloadUrl"] as? String ?? ""
        releaseNotes = o["releaseNotes"] as? String ?? ""
    }
}

/// Nội dung "Thông tin sản phẩm" — tương đương ProductInfo.kt (anh Nam sửa trên Web Admin).
struct ProductInfo {
    var appName = "ScanX"
    var tagline = "Quét tài liệu thông minh — OCR tiếng Việt, chuyển Word/Excel/PowerPoint, dịch tài liệu"
    var description = "ScanX biến điện thoại thành máy quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu, nhận dạng chữ tiếng Việt và xuất PDF tìm kiếm được."
    var features: [String] = [
        "Quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu / đen trắng",
        "OCR tiếng Việt, PDF có lớp chữ tìm kiếm được (4 chế độ)",
        "Chụp để dịch, dịch trực tiếp khi soi camera",
        "Business: chuyển Word / Excel / PowerPoint, dịch tài liệu, AI Cloud, sao lưu đám mây",
    ]
    var publisher = ""
    var website = ""
    var email = ""
    var phone = ""
    var address = ""
    var privacyURL = ""
    var termsURL = ""

    init() {}

    init(_ o: [String: Any]) {
        func s(_ k: String) -> String { (o[k] as? String) ?? "" }
        appName = s("appName").isEmpty ? "ScanX" : s("appName")
        tagline = s("tagline")
        description = s("description")
        features = ((o["features"] as? [String]) ?? []).filter { !$0.isEmpty }
        publisher = s("publisher")
        website = s("website")
        email = s("email")
        phone = s("phone")
        address = s("address")
        privacyURL = s("privacyUrl")
        termsURL = s("termsUrl")
    }
}

/// Nhắc / ép cập nhật + thông tin sản phẩm (tương đương AppConfigViewModel.kt). Gọi GET /app/config (công khai).
@MainActor
final class AppConfigManager: ObservableObject {
    static let shared = AppConfigManager()

    @Published private(set) var pendingUpdate: AppUpdateInfo?
    @Published private(set) var productInfo = ProductInfo()
    @Published private(set) var checking = false
    @Published private(set) var manualResult: String?

    private let defaults = UserDefaults.standard
    private var lastCheck = Date.distantPast

    static var buildNumber: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0 }
    static var versionName: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?" }

    private init() {
        if let data = defaults.data(forKey: "appcfg.product"),
           let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            productInfo = ProductInfo(obj)
        }
        check(manual: false)
    }

    func checkIfStale() {
        let interval: TimeInterval = pendingUpdate?.forceUpdate == true ? 0 : 600
        if Date().timeIntervalSince(lastCheck) > interval { check(manual: false) }
    }

    func check(manual: Bool) {
        guard !checking else { return }
        lastCheck = Date()
        checking = true
        if manual { manualResult = nil }
        Task {
            do {
                let json = try await BackendAPI.appConfig(platform: "ios", versionCode: Self.buildNumber)
                if let product = json["productInfo"] as? [String: Any] {
                    productInfo = ProductInfo(product)
                    if let data = try? JSONSerialization.data(withJSONObject: product) {
                        defaults.set(data, forKey: "appcfg.product")
                    }
                }
                let info = AppUpdateInfo(json["update"] as? [String: Any] ?? [:])
                let snoozedUntil = defaults.integer(forKey: "appcfg.snoozeVersion") == info.latestVersionCode
                    ? defaults.double(forKey: "appcfg.snoozeUntil") : 0
                let snoozed = !info.forceUpdate && snoozedUntil > Date().timeIntervalSince1970
                if info.forceUpdate {
                    pendingUpdate = info
                } else if info.updateAvailable && (manual || !snoozed) {
                    pendingUpdate = info
                } else {
                    pendingUpdate = nil
                }
                if manual {
                    manualResult = info.updateAvailable ? "Có bản mới \(info.latestVersionName)" : "Bạn đang dùng bản mới nhất (\(Self.versionName))"
                }
            } catch {
                if manual { manualResult = "Không kiểm tra được: \(error.localizedDescription)" }
            }
            checking = false
        }
    }

    func snooze() {
        guard let info = pendingUpdate, !info.forceUpdate else { return }
        defaults.set(info.latestVersionCode, forKey: "appcfg.snoozeVersion")
        defaults.set(Date().timeIntervalSince1970 + 86_400, forKey: "appcfg.snoozeUntil")
        pendingUpdate = nil
    }

    func openUpdate() {
        guard let info = pendingUpdate, let url = URL(string: info.downloadURL) else { return }
        UIApplication.shared.open(url)
    }
}

/// Màn chặn khi BẮT BUỘC cập nhật — không có nút đóng.
struct ForcedUpdateView: View {
    let info: AppUpdateInfo
    @ObservedObject var cfg: AppConfigManager = .shared

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "arrow.down.app.fill")
                .font(.system(size: 56))
                .foregroundStyle(Color.accentColor)
            Text("Cần cập nhật ScanX")
                .font(.title2.bold())
            Text("Bản mới \(info.latestVersionName) — bạn đang dùng \(AppConfigManager.versionName). Phiên bản hiện tại không còn được hỗ trợ, vui lòng cập nhật để tiếp tục.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            if !info.releaseNotes.isEmpty {
                Text(info.releaseNotes)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Button {
                cfg.openUpdate()
            } label: {
                Text("Cập nhật ngay").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding(24)
        .interactiveDismissDisabled(true)
    }
}

/// "Thông tin sản phẩm" — tương đương AboutScreen.kt.
struct AboutView: View {
    @ObservedObject var cfg: AppConfigManager = .shared

    var body: some View {
        let info = cfg.productInfo
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(info.appName).font(.title2.bold())
                    if !info.tagline.isEmpty {
                        Text(info.tagline).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            Section("Phiên bản") {
                Text("\(AppConfigManager.versionName) (build \(AppConfigManager.buildNumber))")
                Button {
                    cfg.check(manual: true)
                } label: {
                    HStack {
                        Text("Kiểm tra cập nhật")
                        if cfg.checking { Spacer(); ProgressView() }
                    }
                }
                .disabled(cfg.checking)
                if let r = cfg.manualResult {
                    Text(r).font(.footnote).foregroundStyle(.secondary)
                }
            }
            if !info.description.isEmpty {
                Section("Giới thiệu") { Text(info.description) }
            }
            if !info.features.isEmpty {
                Section("Chức năng chính") {
                    ForEach(Array(info.features.enumerated()), id: \.offset) { item in
                        Label(item.element, systemImage: "checkmark.circle.fill")
                    }
                }
            }
            if hasContact(info) {
                Section("Nhà phát hành & liên hệ") {
                    if !info.publisher.isEmpty { Label("Nhà phát hành: \(info.publisher)", systemImage: "building.2") }
                    if let url = webURL(info.website) { Link(destination: url) { Label(info.website, systemImage: "globe") } }
                    if let url = URL(string: "mailto:\(info.email)"), !info.email.isEmpty { Link(destination: url) { Label(info.email, systemImage: "envelope") } }
                    if let url = URL(string: "tel:\(info.phone.filter { !$0.isWhitespace })"), !info.phone.isEmpty { Link(destination: url) { Label(info.phone, systemImage: "phone") } }
                    if !info.address.isEmpty { Label(info.address, systemImage: "mappin.and.ellipse") }
                    if let url = webURL(info.privacyURL) { Link(destination: url) { Label("Chính sách quyền riêng tư", systemImage: "hand.raised") } }
                    if let url = webURL(info.termsURL) { Link(destination: url) { Label("Điều khoản sử dụng", systemImage: "doc.text") } }
                }
            }
        }
        .navigationTitle("Thông tin sản phẩm")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func hasContact(_ i: ProductInfo) -> Bool {
        ![i.publisher, i.website, i.email, i.phone, i.address, i.privacyURL, i.termsURL].allSatisfy { $0.isEmpty }
    }

    private func webURL(_ s: String) -> URL? {
        guard !s.isEmpty else { return nil }
        return URL(string: s.hasPrefix("http") ? s : "https://\(s)")
    }
}
