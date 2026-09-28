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
    @State private var paywall: BusinessFeature?
    @State private var trialNote: String?
    @ObservedObject private var ent = EntitlementsManager.shared

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

                if !ent.businessActive {
                    Section {
                        Label("Word/Excel/PowerPoint, Dịch tài liệu, AI Cloud là tính năng Business", systemImage: "crown")
                            .font(.footnote)
                    } footer: {
                        Text("Còn \(ent.trialsRemaining(.officeExport)) lượt thử chuyển Office, \(ent.trialsRemaining(.docTranslate)) lượt thử dịch. Xuất TXT luôn miễn phí.")
                    }
                }

                if running {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(library.progressText ?? "Đang xử lý…")
                                .font(.footnote)
                        }
                        if let trialNote {
                            Text(trialNote)
                                .font(.caption)
                                .foregroundStyle(.secondary)
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
            .sheet(item: $paywall) { f in BusinessPaywallView(feature: f) }
        }
    }

    /// Tính năng Business mà thao tác này cần (xuất TXT không dịch, không AI = miễn phí).
    private var neededFeatures: [BusinessFeature] {
        var needed: [BusinessFeature] = []
        if format != .txt { needed.append(.officeExport) }
        if translate { needed.append(.docTranslate) }
        if useCloud && cloudAvailable { needed.append(.aiHandwriting) }
        return needed
    }

    private func start() {
        switch ent.tryUse(neededFeatures) {
        case .locked(let feature):
            paywall = feature
            return
        case .trial(let remaining):
            trialNote = "Dùng thử tính năng Business — còn \(remaining) lượt"
        case .business:
            trialNote = nil
        }
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
