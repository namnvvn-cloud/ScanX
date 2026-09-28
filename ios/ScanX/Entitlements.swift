import Foundation
import FirebaseAuth
import SwiftUI

/// Tính năng Business — tương đương BusinessFeature bên Android. Danh sách thật do backend quyết định
/// (backend/src/users/entitlements.ts, GET /users/me); nhãn + lượt thử ở đây chỉ là giá trị dự phòng.
enum BusinessFeature: String, CaseIterable, Identifiable {
    case officeExport = "office_export"
    case docTranslate = "doc_translate"
    case aiHandwriting = "ai_handwriting"
    case cloudBackup = "cloud_backup"

    var id: String { rawValue }

    var fallbackLabel: String {
        switch self {
        case .officeExport: return "Chuyển sang Word / Excel / PowerPoint giữ bố cục"
        case .docTranslate: return "Dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)"
        case .aiHandwriting: return "AI Cloud đọc chữ viết tay, bản chụp khó"
        case .cloudBackup: return "Sao lưu tài liệu lên đám mây"
        }
    }

    var fallbackTrials: Int {
        switch self {
        case .officeExport, .docTranslate: return 3
        case .aiHandwriting, .cloudBackup: return 0
        }
    }
}

enum FeatureAccess {
    case business
    case trial(remainingAfter: Int)
    case locked(BusinessFeature)
}

/// Trạng thái gói Business của tài khoản đang đăng nhập (tương đương EntitlementsViewModel.kt).
/// Singleton để mọi màn (chuyển đổi, tài khoản) dùng chung, không phải truyền qua nhiều tầng view.
/// KHÔNG có nút mua trong app iOS (App Store Guideline 3.1.1) — chỉ đăng nhập tài khoản đã có gói.
@MainActor
final class EntitlementsManager: ObservableObject {
    static let shared = EntitlementsManager()

    @Published private(set) var email: String?
    @Published private(set) var serverBusinessActive = false
    @Published private(set) var businessExpiresAt: Date?
    @Published private(set) var features: [String: Bool] = [:]
    @Published private(set) var labels: [String: String] = [:]
    @Published private(set) var freeTrials: [String: Int] = [:]
    @Published private(set) var refreshing = false
    @Published private(set) var refreshError: String?
    /// Tăng mỗi lần trừ lượt thử để SwiftUI vẽ lại số lượt còn lại.
    @Published private(set) var trialTick = 0

    private let defaults = UserDefaults.standard
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var lastUID: String?
    private var lastRefresh = Date.distantPast

    private enum Key {
        static let email = "ent.email"
        static let userJSON = "ent.userJSON"
        static func trialUsed(_ f: BusinessFeature) -> String { "ent.trialUsed.\(f.rawValue)" }
    }

    private init() {
        let user = Auth.auth().currentUser
        lastUID = user?.uid
        loadCache(email: user?.email)
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            Task { @MainActor in self?.authChanged(user) }
        }
        refresh()
    }

    var businessActive: Bool {
        guard serverBusinessActive else { return false }
        if let exp = businessExpiresAt { return exp > Date() }
        return true
    }

    func has(_ f: BusinessFeature) -> Bool { businessActive && (features[f.rawValue] ?? true) }
    func label(_ f: BusinessFeature) -> String { labels[f.rawValue] ?? f.fallbackLabel }
    func trialLimit(_ f: BusinessFeature) -> Int { freeTrials[f.rawValue] ?? f.fallbackTrials }
    func trialsRemaining(_ f: BusinessFeature) -> Int {
        max(0, trialLimit(f) - defaults.integer(forKey: Key.trialUsed(f)))
    }

    /// Kiểm tra quyền cho 1 thao tác cần [features]; dùng thử thì trừ lượt (chỉ khi mọi tính năng đều được phép).
    func tryUse(_ needed: [BusinessFeature]) -> FeatureAccess {
        let missing = needed.filter { !has($0) }
        if missing.isEmpty { return .business }
        if let locked = missing.first(where: { trialsRemaining($0) <= 0 }) { return .locked(locked) }
        for f in missing {
            defaults.set(defaults.integer(forKey: Key.trialUsed(f)) + 1, forKey: Key.trialUsed(f))
        }
        trialTick += 1
        return .trial(remainingAfter: missing.map { trialsRemaining($0) }.min() ?? 0)
    }

    func refreshIfStale() {
        if Date().timeIntervalSince(lastRefresh) > 30 { refresh() }
    }

    func refresh() {
        guard let user = Auth.auth().currentUser else {
            apply(email: nil, user: [:])
            return
        }
        lastRefresh = Date()
        refreshing = true
        refreshError = nil
        Task {
            do {
                let token = try await user.getIDToken()
                let me = try await BackendAPI(idToken: token).me()
                let mail = user.email ?? (me["email"] as? String)
                if let mail, let data = try? JSONSerialization.data(withJSONObject: me) {
                    defaults.set(mail, forKey: Key.email)
                    defaults.set(data, forKey: Key.userJSON)
                }
                apply(email: mail, user: me)
            } catch {
                // Giữ trạng thái đã lưu (offline / server đang khởi động).
                refreshError = error.localizedDescription
            }
            refreshing = false
        }
    }

    private func authChanged(_ user: User?) {
        guard user?.uid != lastUID else { return }
        lastUID = user?.uid
        if user == nil {
            defaults.removeObject(forKey: Key.email)
            defaults.removeObject(forKey: Key.userJSON)
            apply(email: nil, user: [:])
        } else {
            loadCache(email: user?.email)
            refresh()
        }
    }

    private func loadCache(email: String?) {
        guard let email,
              defaults.string(forKey: Key.email) == email,
              let data = defaults.data(forKey: Key.userJSON),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else {
            apply(email: email, user: [:])
            return
        }
        apply(email: email, user: obj)
    }

    private func apply(email: String?, user: [String: Any]) {
        self.email = email
        let ent = user["entitlements"] as? [String: Any] ?? [:]
        serverBusinessActive = (ent["businessActive"] as? Bool) ?? (user["business_active"] as? Bool) ?? false
        let expRaw = (ent["businessExpiresAt"] as? String) ?? (user["business_expires_at"] as? String)
        businessExpiresAt = expRaw.flatMap(Self.parseDate)
        features = ent["features"] as? [String: Bool] ?? [:]
        labels = ent["featureLabels"] as? [String: String] ?? [:]
        freeTrials = ent["freeTrials"] as? [String: Int] ?? [:]
    }

    private static func parseDate(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

/// Danh sách tính năng Business có ✓ / ổ khoá (luôn kèm chữ).
struct BusinessFeatureList: View {
    @ObservedObject var ent: EntitlementsManager
    var highlight: BusinessFeature? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(BusinessFeature.allCases) { f in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: ent.has(f) ? "checkmark.circle.fill" : "lock.fill")
                        .foregroundStyle(ent.has(f) ? Color.accentColor : Color.secondary)
                        .accessibilityLabel(ent.has(f) ? "Đã mở" : "Đang khoá")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ent.label(f))
                            .fontWeight(f == highlight ? .semibold : .regular)
                        if !ent.has(f) && ent.trialLimit(f) > 0 {
                            Text("Dùng thử: còn \(ent.trialsRemaining(f))/\(ent.trialLimit(f)) lượt")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .id(ent.trialTick)
    }
}

/// Màn hiện khi tính năng bị khoá (tương đương BusinessPaywallDialog.kt, không có nút mua — App Store).
struct BusinessPaywallView: View {
    let feature: BusinessFeature
    @ObservedObject var ent: EntitlementsManager = .shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Tính năng ScanX Business", systemImage: "crown.fill")
                            .font(.headline)
                        Text("«\(ent.label(feature))» dành cho tài khoản Business."
                             + (ent.trialLimit(feature) > 0 ? " Bạn đã dùng hết lượt dùng thử miễn phí." : ""))
                    }
                }
                Section("Business mở khoá") {
                    BusinessFeatureList(ent: ent, highlight: feature)
                }
                Section {
                    if ent.email != nil {
                        Button {
                            ent.refresh()
                        } label: {
                            HStack {
                                Text("Làm mới trạng thái gói")
                                if ent.refreshing { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(ent.refreshing)
                    }
                    if let err = ent.refreshError {
                        Text(err).font(.footnote).foregroundStyle(.red)
                    }
                } footer: {
                    Text(ent.email != nil
                         ? "Đã có gói cho tài khoản \(ent.email ?? "")? Bấm làm mới để cập nhật."
                         : "Đã có gói Business? Đăng nhập đúng tài khoản trong Cài đặt → Tài khoản để mở khoá.")
                }
            }
            .navigationTitle("Business")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Đóng") { dismiss() }
                }
            }
            .onChange(of: ent.businessActive) { active in
                if active { dismiss() }
            }
        }
    }
}

/// Thẻ "Gói hiện tại" trong màn Tài khoản.
struct PlanSection: View {
    @ObservedObject var ent: EntitlementsManager = .shared

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "vi_VN")
        f.dateFormat = "dd/MM/yyyy"
        return f
    }()

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Label(ent.businessActive ? "Gói Business ✓ đang hoạt động" : "Gói Miễn phí",
                      systemImage: ent.businessActive ? "crown.fill" : "lock")
                    .font(.headline)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            BusinessFeatureList(ent: ent)
            Button {
                ent.refresh()
            } label: {
                HStack {
                    Text("Làm mới trạng thái gói")
                    if ent.refreshing { Spacer(); ProgressView() }
                }
            }
            .disabled(ent.refreshing)
            if let err = ent.refreshError {
                Text(err).font(.footnote).foregroundStyle(.red)
            }
        } header: {
            Text("Gói hiện tại")
        }
    }

    private var subtitle: String {
        if ent.businessActive {
            if let exp = ent.businessExpiresAt { return "Hết hạn \(Self.dateFormatter.string(from: exp))" }
            return "Không thời hạn"
        }
        if let exp = ent.businessExpiresAt { return "Business đã hết hạn \(Self.dateFormatter.string(from: exp))" }
        return "Quét, OCR, PDF, chụp để dịch — miễn phí"
    }
}
