import SwiftUI
import UIKit

/// Form đăng nhập (Email/Password + Google), hiển thị trong AccountView.
struct LoginView: View {
    @ObservedObject var viewModel: AuthViewModel

    @State private var email = ""
    @State private var password = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("Đăng nhập (tuỳ chọn) để sao lưu tài liệu lên đám mây. Không đăng nhập vẫn dùng đầy đủ tính năng quét.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 16)

                TextField("Email", text: $email)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                SecureField("Mật khẩu", text: $password)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.password)

                if let error = viewModel.errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }

                HStack(spacing: 12) {
                    Button {
                        viewModel.signIn(email: email, password: password)
                    } label: {
                        Text("Đăng nhập").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        viewModel.register(email: email, password: password)
                    } label: {
                        Text("Đăng ký").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .disabled(viewModel.isBusy || email.isEmpty || password.isEmpty)

                Button {
                    if let top = Self.topViewController() {
                        viewModel.signInWithGoogle(presenting: top)
                    }
                } label: {
                    Label("Đăng nhập bằng Google", systemImage: "g.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isBusy)

                if viewModel.isBusy {
                    ProgressView()
                }
            }
            .padding(.horizontal, 24)
        }
    }

    private static func topViewController() -> UIViewController? {
        guard let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
            let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
        else { return nil }
        var top = root
        while let presented = top.presentedViewController {
            top = presented
        }
        return top
    }
}
