import SwiftUI

/// "Chuyển đổi / Dịch" — tương đương hộp thoại xuất Word/Excel/PowerPoint + "Dịch sang tiếng Việt"
/// (TranslateDialog) bên Android: chọn định dạng, bật dịch (chọn máy dịch, song ngữ), bật AI Cloud.
struct OfficeExportSheet: View {
    @ObservedObject var library: LibraryViewModel
    let documentID: String
    let onFinished: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var format: ConvertFormat = .docx
    @State private var translate = false
    @State private var engine: TranslationChoice = TranslationChoice.preferred
    @State private var bilingual = false
    @State private var useCloud = false
    @State private var running = false

    private var cloudAvailable: Bool { SecretStore.has(SecretStore.claudeKey) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Định dạng") {
                    Picker("Định dạng", selection: $format) {
                        ForEach(ConvertFormat.allCases) { f in
                            Label(f.title, systemImage: f.icon).tag(f)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section {
                    Toggle("Dịch sang tiếng Việt", isOn: $translate)
                    if translate {
                        Picker("Máy dịch", selection: $engine) {
                            ForEach(TranslationChoice.allCases) { choice in
                                Text(choice.isAvailable ? choice.title : "\(choice.title) — \(choice.unavailableReason)")
                                    .tag(choice)
                            }
                        }
                        Toggle("Song ngữ — giữ bản gốc, chèn bản dịch (in nghiêng) ngay bên dưới", isOn: $bilingual)
                    }
                } footer: {
                    if translate {
                        Text("Giữ nguyên bố cục (bảng, ô gộp, cỡ chữ); chỉ thay chữ. Đoạn đã là tiếng Việt, số liệu, mã viết tắt giữ nguyên.")
                    }
                }

                Section {
                    Toggle("Đọc chữ bằng AI Cloud (Claude)", isOn: $useCloud)
                        .disabled(!cloudAvailable)
                } footer: {
                    Text(cloudAvailable
                         ? "Dùng cho chữ viết tay/khó đọc: ảnh trang gửi thẳng tới Claude bằng API key của anh (có tính phí theo Anthropic)."
                         : "Nhập API key Claude trong Cài đặt → AI Cloud để bật.")
                }

                if running {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(library.progressText ?? "Đang xử lý…")
                                .font(.footnote)
                        }
                    }
                }
            }
            .navigationTitle("Chuyển đổi / Dịch")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Huỷ") { dismiss() }
                        .disabled(running)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Bắt đầu") { start() }
                        .disabled(running || (translate && !engine.isAvailable))
                }
            }
            .interactiveDismissDisabled(running)
        }
    }

    private func start() {
        running = true
        let job = ConvertExporter.Job(
            format: format,
            translate: translate,
            engine: engine,
            bilingual: bilingual,
            useCloud: useCloud && cloudAvailable
        )
        Task {
            let url = await library.runOfficeJob(id: documentID, job: job)
            running = false
            dismiss()
            if let url { onFinished(url) }
        }
    }
}
