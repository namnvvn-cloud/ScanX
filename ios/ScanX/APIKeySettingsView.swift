import SwiftUI

/// Nhập API key của dịch vụ đám mây (Claude / Gemini / Google Dịch) — tương đương CloudSettingsDialog,
/// GeminiSettingsDialog, GoogleTranslateSettingsDialog bên Android. Key lưu trong Keychain.
struct APIKeySettingsView: View {
    enum Service {
        case claude
        case gemini
        case google

        var title: String {
            switch self {
            case .claude: return "AI Cloud (Claude)"
            case .gemini: return "Gemini (dịch miễn phí)"
            case .google: return "Google Dịch (Cloud Translation)"
            }
        }

        var account: String {
            switch self {
            case .claude: return SecretStore.claudeKey
            case .gemini: return SecretStore.geminiKey
            case .google: return SecretStore.googleTranslateKey
            }
        }

        var help: String {
            switch self {
            case .claude:
                return "Dùng để đọc chữ viết tay khi chuyển đổi và dịch chính xác nhất. Tạo key tại console.anthropic.com (trả phí theo lượng dùng)."
            case .gemini:
                return "Dịch MIỄN PHÍ ở hạn mức cá nhân. Lấy key tại aistudio.google.com/apikey, không cần thẻ."
            case .google:
                return "Máy dịch Google Dịch: tạo key trong Google Cloud Console, bật Cloud Translation API + tài khoản thanh toán. Miễn phí 500.000 ký tự/tháng."
            }
        }
    }

    let service: Service

    @State private var key = ""
    @State private var saved = false
    @AppStorage(AppSettings.claudeModelKey) private var claudeModel: String = ClaudeClient.defaultModel
    @AppStorage(AppSettings.geminiModelKey) private var geminiModel: String = GeminiClient.defaultModel

    var body: some View {
        Form {
            Section {
                SecureField("API key", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                HStack {
                    Button("Lưu") {
                        SecretStore.set(service.account, key)
                        saved = true
                    }
                    .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
                    Spacer()
                    Button("Xoá key", role: .destructive) {
                        SecretStore.set(service.account, nil)
                        key = ""
                        saved = false
                    }
                }
                .buttonStyle(.borderless)
                if saved {
                    Label("Đã lưu (mã hoá trong Keychain)", systemImage: "checkmark.seal")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            } footer: {
                Text(service.help)
            }

            switch service {
            case .claude:
                Section("Mô hình") {
                    Picker("Mô hình", selection: $claudeModel) {
                        ForEach(ClaudeClient.models.indices, id: \.self) { i in
                            Text(ClaudeClient.models[i].title).tag(ClaudeClient.models[i].id)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
            case .gemini:
                Section {
                    TextField("Tên mô hình", text: $geminiModel)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    ForEach(GeminiClient.suggestedModels.indices, id: \.self) { i in
                        Button(GeminiClient.suggestedModels[i].title) { geminiModel = GeminiClient.suggestedModels[i].id }
                    }
                } header: {
                    Text("Mô hình")
                } footer: {
                    Text("Google đổi tên mô hình khá thường xuyên — gõ tự do nếu cần.")
                }
            case .google:
                EmptyView()
            }
        }
        .navigationTitle(service.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            let existing = SecretStore.get(service.account)
            key = existing ?? ""
            saved = existing != nil
        }
    }
}
