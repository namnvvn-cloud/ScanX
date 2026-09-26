import Foundation
import FirebaseAuth
import GoogleSignIn
import UIKit

/// Tương đương AuthViewModel.kt bên Android: đăng nhập Email/Password + Google,
/// gọi POST /auth/login để đăng ký hồ sơ trên backend, và sao lưu tài liệu lên cloud (một chiều).
@MainActor
final class AuthViewModel: ObservableObject {
    @Published var currentEmail: String?
    @Published var isBusy = false
    @Published var errorMessage: String?
    @Published var backupStatus: String?

    private var authHandle: AuthStateDidChangeListenerHandle?

    init() {
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            self?.currentEmail = user?.email
        }
    }

    deinit {
        if let authHandle {
            Auth.auth().removeStateDidChangeListener(authHandle)
        }
    }

    func signIn(email: String, password: String) {
        performAuth {
            try await Auth.auth().signIn(withEmail: email, password: password)
        }
    }

    func register(email: String, password: String) {
        performAuth {
            try await Auth.auth().createUser(withEmail: email, password: password)
        }
    }

    func signInWithGoogle(presenting: UIViewController) {
        isBusy = true
        errorMessage = nil
        GIDSignIn.sharedInstance.signIn(withPresenting: presenting) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.isBusy = false
                    self.errorMessage = error.localizedDescription
                    return
                }
                guard let idToken = result?.user.idToken?.tokenString else {
                    self.isBusy = false
                    self.errorMessage = "Không lấy được token Google"
                    return
                }
                let accessToken = result?.user.accessToken.tokenString ?? ""
                let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
                await self.finishAuth {
                    try await Auth.auth().signIn(with: credential)
                }
            }
        }
    }

    func signOut() {
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        currentEmail = nil
        backupStatus = nil
    }

    /// documents: danh sách tài liệu cục bộ cần sao lưu — id, tiêu đề, số trang, đường dẫn file PDF.
    func backupAll(documents: [(id: String, title: String, pageCount: Int, fileURL: URL)]) {
        Task {
            guard let user = Auth.auth().currentUser else {
                errorMessage = "Chưa đăng nhập"
                return
            }
            isBusy = true
            backupStatus = nil
            errorMessage = nil
            do {
                let token = try await user.getIDToken()
                let api = BackendAPI(idToken: token)
                _ = try await api.login()
                var okCount = 0
                var failCount = 0
                for doc in documents {
                    do {
                        let created = try await api.createDocument(title: doc.title, pageCount: doc.pageCount)
                        guard let uploadURL = created["uploadUrl"] as? String else {
                            failCount += 1
                            continue
                        }
                        try await api.uploadFile(uploadURL: uploadURL, fileURL: doc.fileURL)
                        okCount += 1
                    } catch {
                        failCount += 1
                    }
                }
                backupStatus = "Đã sao lưu \(okCount) tài liệu, lỗi \(failCount)"
            } catch {
                errorMessage = error.localizedDescription
            }
            isBusy = false
        }
    }

    private func performAuth(_ action: @escaping () async throws -> AuthDataResult) {
        Task { await finishAuth(action) }
    }

    private func finishAuth(_ action: () async throws -> AuthDataResult) async {
        isBusy = true
        errorMessage = nil
        do {
            _ = try await action()
            await registerWithBackend()
        } catch {
            errorMessage = error.localizedDescription
        }
        isBusy = false
    }

    /// Gọi POST /auth/login để backend tự tạo hồ sơ user (upsert theo firebaseUid) — không chặn
    /// đăng nhập nếu backend tạm thời lỗi (ví dụ Render đang "cold start").
    private func registerWithBackend() async {
        guard let user = Auth.auth().currentUser else { return }
        do {
            let token = try await user.getIDToken()
            _ = try await BackendAPI(idToken: token).login()
        } catch {
            // Bỏ qua: đăng nhập Firebase vẫn thành công dù backend lỗi tạm thời.
        }
    }
}
