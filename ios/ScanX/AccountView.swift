import SwiftUI

/// "Tài khoản & Sao lưu đám mây" — tương đương AccountScreen.kt bên Android.
struct AccountView: View {
    @ObservedObject var auth: AuthViewModel
    @ObservedObject var library: LibraryViewModel

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let email = auth.currentEmail {
                    signedIn(email: email)
                } else {
                    LoginView(viewModel: auth)
                }
            }
            .navigationTitle("Tài khoản")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Xong") { dismiss() }
                }
            }
        }
    }

    private func signedIn(email: String) -> some View {
        List {
            Section("Đã đăng nhập") {
                Text(email)
                Text("\(library.documents.count) tài liệu trên máy")
                    .foregroundStyle(.secondary)
            }

            Section {
                Button {
                    auth.backupAll(documents: library.backupItems())
                } label: {
                    Label("Sao lưu lên đám mây", systemImage: "icloud.and.arrow.up")
                }
                .disabled(auth.isBusy || library.documents.isEmpty)

                if auth.isBusy {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Đang sao lưu… (máy chủ có thể mất ~1 phút để khởi động)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                if let status = auth.backupStatus {
                    Text(status)
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
                if let error = auth.errorMessage {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            } footer: {
                Text("Sao lưu 1 chiều: tải bản PDF của từng tài liệu lên cloud. Không tự động, không đồng bộ ngược về máy.")
            }

            Section {
                Button("Đăng xuất", role: .destructive) {
                    auth.signOut()
                }
            }
        }
    }
}
