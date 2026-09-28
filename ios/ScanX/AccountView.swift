import SwiftUI

/// "Tài khoản & Sao lưu đám mây" — tương đương AccountScreen.kt bên Android.
struct AccountView: View {
    @ObservedObject var auth: AuthViewModel
    @ObservedObject var library: LibraryViewModel
    @ObservedObject private var ent = EntitlementsManager.shared
    @State private var paywall: BusinessFeature?

    var body: some View {
        Group {
            if let email = auth.currentEmail {
                signedIn(email: email)
            } else {
                LoginView(viewModel: auth)
            }
        }
        .sheet(item: $paywall) { f in BusinessPaywallView(feature: f) }
        .navigationTitle("Tài khoản & Sao lưu")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func signedIn(email: String) -> some View {
        List {
            Section("Đã đăng nhập") {
                Text(email)
                Text("\(library.documents.count) tài liệu trên máy")
                    .foregroundStyle(.secondary)
            }

            PlanSection()

            Section {
                Button {
                    // Sao lưu = tính năng Business (backend cũng chặn POST /documents khi chưa có gói).
                    if ent.has(.cloudBackup) {
                        auth.backupAll(documents: library.backupItems())
                    } else {
                        paywall = .cloudBackup
                    }
                } label: {
                    Label(ent.has(.cloudBackup) ? "Sao lưu lên đám mây" : "Sao lưu lên đám mây (Business)",
                          systemImage: ent.has(.cloudBackup) ? "icloud.and.arrow.up" : "lock")
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
