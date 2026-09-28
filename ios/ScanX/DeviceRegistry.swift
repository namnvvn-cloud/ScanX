import Foundation
import UIKit

/// Báo thiết bị về backend (POST /devices/ping) — tương đương DeviceRegistry.kt. Chỉ gửi mã cài đặt ngẫu
/// nhiên (tạo lần đầu, gỡ app là mất), phiên bản app, dòng máy, phiên bản iOS, ngôn ngữ; kèm token nếu đã
/// đăng nhập để gắn thiết bị với tài khoản. Lỗi mạng bỏ qua.
enum DeviceRegistry {
    private static let key = "device.installId"

    static var installId: String {
        if let id = UserDefaults.standard.string(forKey: key) { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: key)
        return id
    }

    private static var modelIdentifier: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    @MainActor
    static func ping(idToken: String?) async {
        let body: [String: Any] = [
            "installId": installId,
            "platform": "ios",
            "versionCode": AppConfigManager.buildNumber,
            "versionName": AppConfigManager.versionName,
            "model": "Apple \(modelIdentifier)",
            "osVersion": UIDevice.current.systemVersion,
            "locale": String(Locale.current.identifier.prefix(20)),
        ]
        guard let url = URL(string: "\(BackendAPI.baseURL)/devices/ping"),
              let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let idToken { request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization") }
        request.httpBody = data
        _ = try? await URLSession.shared.data(for: request)
    }
}
