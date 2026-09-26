import UIKit
import FirebaseCore

/// Khởi tạo Firebase khi app chạy. Cần AppDelegate riêng vì FirebaseApp.configure()
/// phải gọi trước khi bất kỳ API Firebase nào (Auth, ...) được dùng.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()
        return true
    }
}
