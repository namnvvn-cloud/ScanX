import SwiftUI
import UIKit

struct LoginView: View {
    @ObservedObject var viewModel: AuthViewModel

    @State private var email = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("Đăng nhập ScanX")
                        .font(.title2.bold())
                        .padding(.top, 24)

                    Text("Đăng nhập (tuỳ chọn) để sao lưu tài liệu lên đám mây.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)

                    TextField("Email", text: $email)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    SecureField("Mật khẩu", text: $password)
                        .textFieldStyle(.roundedBorder)

                    if let error = viewModel.errorMessage {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }

                    HStack(spacing: 12) {
                        Button("Đăng nhập") {
                            viewModel.signIn(email: email, password: password)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.isBusy || email.isEmpty || password.isEmpty)

                        Button("Đăng ký") {
                            viewModel.register(email: email, password: password)
                        }
                        .buttonStyle(.bordered)
                        .disabled(viewModel.isBusy || email.isEmpty || password.isEmpty)
                    }

                    Button {
                        if let top = Self.topViewController() {
                            viewModel.signInWithGoogle(presenting: top)
                        }
                    } label: {
                        Text("Đăng nhập bằng Google")
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
            .navigationTitle("ScanX")
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

#Preview {
    LoginView(viewModel: AuthViewModel())
}
