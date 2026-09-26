import SwiftUI

/// Cài đặt — tương đương SettingsScreen.kt bên Android (mục Tài khoản ở đầu).
struct SettingsView: View {
    @ObservedObject var auth: AuthViewModel
    @ObservedObject var library: LibraryViewModel

    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppSettings.pdfModeKey) private var pdfMode: String = PDFMode.a2.rawValue
    @AppStorage(AppSettings.ocrEnabledKey) private var ocrEnabled = true

    var body: some View {
        NavigationStack {
            List {
                Section("Tài khoản") {
                    NavigationLink {
                        AccountView(auth: auth, library: library)
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Tài khoản & Sao lưu đám mây")
                                Text(auth.currentEmail ?? "Đăng nhập (tuỳ chọn) để sao lưu tài liệu lên cloud")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "person.crop.circle")
                        }
                    }
                }

                Section {
                    Picker("Chế độ PDF khi lưu", selection: $pdfMode) {
                        ForEach(PDFMode.allCases) { mode in
                            VStack(alignment: .leading) {
                                Text(mode.title)
                                Text(mode.detail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .tag(mode.rawValue)
                        }
                    }
                    .pickerStyle(.inline)
                } header: {
                    Text("PDF")
                } footer: {
                    Text("Ảnh gốc luôn được giữ nguyên; lúc chia sẻ vẫn chọn lại được chế độ khác.")
                }

                Section {
                    Toggle("Nhận dạng chữ (OCR) khi lưu", isOn: $ocrEnabled)
                } footer: {
                    Text("Thêm lớp chữ ẩn vào PDF để tìm kiếm và copy được chữ (Việt, Anh, Hàn, Nhật, Trung). Chạy trên máy, không cần mạng.")
                }
            }
            .navigationTitle("Cài đặt")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Xong") { dismiss() }
                }
            }
        }
    }
}
