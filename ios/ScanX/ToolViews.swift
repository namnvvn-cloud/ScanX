import AVFoundation
import MessageUI
import SwiftUI
import UIKit

// MARK: - Mã QR

/// Máy quét mã QR / mã vạch bằng AVFoundation (chạy mọi iPhone iOS 16, không cần thư viện ngoài).
struct QRScannerView: UIViewControllerRepresentable {
    let onFound: (String) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let vc = QRScannerController()
        vc.onFound = onFound
        vc.onCancel = onCancel
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {}
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String) -> Void)?
    var onCancel: (() -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var reported = false
    private let message = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let close = UIButton(type: .system)
        close.setTitle("Đóng", for: .normal)
        close.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        close.tintColor = .white
        close.translatesAutoresizingMaskIntoConstraints = false
        close.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        message.text = "Đưa mã QR / mã vạch vào khung hình"
        message.textColor = .white
        message.numberOfLines = 0
        message.textAlignment = .center
        message.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(close)
        view.addSubview(message)
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            message.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            message.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            message.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                if granted { self.configure() } else { self.message.text = "Chưa cấp quyền camera. Vào Cài đặt → ScanX → Camera để bật." }
            }
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            message.text = "Không mở được camera trên máy này."
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        let wanted: [AVMetadataObject.ObjectType] = [.qr, .ean13, .ean8, .code128, .code39, .upce, .pdf417, .aztec, .dataMatrix]
        output.metadataObjectTypes = wanted.filter { output.availableMetadataObjectTypes.contains($0) }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() } }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !reported,
              let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
              let value = code.stringValue, !value.isEmpty else { return }
        reported = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onFound?(value)
    }

    @objc private func cancel() { onCancel?() }
}

/// Loại nội dung mã để hiện nút phù hợp.
private func qrKind(_ v: String) -> String {
    let l = v.lowercased()
    if l.hasPrefix("http://") || l.hasPrefix("https://") { return "link" }
    if l.hasPrefix("wifi:") { return "wifi" }
    if l.hasPrefix("tel:") { return "phone" }
    if l.hasPrefix("mailto:") { return "email" }
    if l.hasPrefix("begin:vcard") { return "contact" }
    return "text"
}

private func wifiField(_ v: String, _ key: String) -> String {
    guard let re = try? NSRegularExpression(pattern: "(?:^|;|:)\(key):((?:\\\\;|[^;])*)") else { return "" }
    let ns = v as NSString
    guard let m = re.firstMatch(in: v, range: NSRange(location: 0, length: ns.length)) else { return "" }
    return ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "\\;", with: ";")
}

struct QRValue: Identifiable {
    let id = UUID()
    let value: String
}

struct QRResultView: View {
    let value: String
    var onHistory: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                Section(title) {
                    if qrKind(value) == "wifi" {
                        LabeledContent("Tên mạng", value: wifiField(value, "S"))
                        LabeledContent("Mật khẩu", value: wifiField(value, "P").isEmpty ? "Không có" : wifiField(value, "P"))
                        LabeledContent("Bảo mật", value: wifiField(value, "T").isEmpty ? "—" : wifiField(value, "T"))
                    } else {
                        Text(value).textSelection(.enabled)
                    }
                }
                Section {
                    if ["link", "phone", "email"].contains(qrKind(value)), let url = URL(string: value) {
                        Button { openURL(url) } label: { Label("Mở", systemImage: "arrow.up.right.square") }
                    }
                    Button {
                        UIPasteboard.general.string = qrKind(value) == "wifi" ? wifiField(value, "P") : value
                        copied = true
                    } label: {
                        Label(copied ? "Đã sao chép" : (qrKind(value) == "wifi" ? "Chép mật khẩu" : "Sao chép"), systemImage: "doc.on.doc")
                    }
                    ShareLink(item: value) { Label("Chia sẻ", systemImage: "square.and.arrow.up") }
                    if let onHistory {
                        Button { onHistory() } label: { Label("Lịch sử quét mã", systemImage: "clock.arrow.circlepath") }
                    }
                }
            }
            .navigationTitle("Mã đã quét")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Đóng") { dismiss() } } }
        }
    }

    private var title: String {
        switch qrKind(value) {
        case "link": return "Liên kết"
        case "wifi": return "Mạng Wi-Fi"
        case "phone": return "Số điện thoại"
        case "email": return "Email"
        case "contact": return "Danh bạ"
        default: return "Nội dung mã"
        }
    }
}

struct QRHistoryView: View {
    @ObservedObject var tools: ToolsStore = .shared
    let onScan: () -> Void
    @State private var selected: QRValue?

    var body: some View {
        List {
            Section {
                Button { onScan() } label: { Label("Quét mã mới", systemImage: "qrcode.viewfinder") }
            }
            Section("Đã quét") {
                if tools.qrHistory.isEmpty {
                    Text("Chưa quét mã nào.").foregroundStyle(.secondary)
                }
                ForEach(tools.qrHistory, id: \.self) { v in
                    Button { selected = QRValue(value: v) } label: {
                        Text(v).lineLimit(2).foregroundStyle(.primary)
                    }
                }
            }
        }
        .navigationTitle("Lịch sử quét mã")
        .toolbar {
            if !tools.qrHistory.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Xoá", role: .destructive) { tools.clearQR() }
                }
            }
        }
        .sheet(item: $selected) { v in QRResultView(value: v.value) }
    }
}

// MARK: - Văn bản

struct TextResult: Identifiable {
    let id = UUID()
    let text: String
}

struct TextResultView: View {
    @State var text: String
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var shareFile: ExportedFile?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                Text(text.isEmpty
                     ? "Không nhận dạng được chữ nào — thử chụp rõ và đủ sáng hơn."
                     : "\(text.count) ký tự · sửa trực tiếp trước khi sao chép / chia sẻ")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextEditor(text: $text)
                    .font(.body)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.3)))
            }
            .padding()
            .navigationTitle("Văn bản")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Đóng") { dismiss() } }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        UIPasteboard.general.string = text
                        copied = true
                    } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc") }
                    .accessibilityLabel("Sao chép")
                    Menu {
                        ShareLink(item: text) { Label("Chia sẻ chữ", systemImage: "text.bubble") }
                        Button {
                            let f = DateFormatter()
                            f.locale = Locale(identifier: "en_US_POSIX")
                            f.dateFormat = "yyyy-MM-dd_HHmm"
                            let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(f.string(from: Date()))_van-ban.txt")
                            if (try? text.write(to: url, atomically: true, encoding: .utf8)) != nil { shareFile = ExportedFile(url: url) }
                        } label: { Label("Lưu file .txt", systemImage: "doc.text") }
                    } label: { Image(systemName: "square.and.arrow.up") }
                }
            }
            .sheet(item: $shareFile) { f in ActivityView(items: [f.url]) }
        }
    }
}

// MARK: - Báo cáo chi phí

struct ExpenseReportView: View {
    @ObservedObject var library: LibraryViewModel
    @State private var items: [ExpenseItem] = []
    @State private var loaded = false
    @State private var shareFile: ExportedFile?

    private static let vnd: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "vi_VN")
        return f
    }()

    private var total: Int64 { items.filter(\.include).reduce(0) { $0 + ($1.amount ?? 0) } }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Tổng \(items.filter(\.include).count) hoá đơn")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text("\(Self.vnd.string(from: NSNumber(value: total)) ?? "0") đ")
                        .font(.title2.bold()).monospacedDigit()
                }
            } footer: {
                Text("Tự đọc từ chữ OCR của tài liệu có dạng hoá đơn / biên lai. Kiểm tra, sửa lại rồi xuất CSV (mở bằng Excel / Numbers / Google Sheets).")
            }
            if loaded && items.isEmpty {
                Text("Chưa có tài liệu nào có chữ. Bật «Nhận dạng chữ (OCR) khi lưu» trong Cài đặt rồi quét hoá đơn.")
                    .foregroundStyle(.secondary)
            }
            ForEach($items) { $it in
                Section {
                    Toggle(isOn: $it.include) {
                        Text(library.document(id: it.documentID)?.title ?? "Tài liệu").font(.footnote).foregroundStyle(.secondary)
                    }
                    TextField("Đơn vị bán", text: $it.merchant)
                    HStack {
                        TextField("Ngày (dd/mm/yyyy)", text: $it.date)
                        TextField("Số tiền", text: Binding(
                            get: { it.amount.map { String($0) } ?? "" },
                            set: { it.amount = Int64($0.filter(\.isNumber)) }
                        ))
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                    }
                    HStack {
                        TextField("Nhóm (ăn uống, đi lại…)", text: $it.category)
                        TextField("Ghi chú", text: $it.note)
                    }
                }
            }
        }
        .navigationTitle("Báo cáo chi phí")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Xuất CSV") {
                    if let url = library.writeExpenseCSV(items.filter(\.include)) { shareFile = ExportedFile(url: url) }
                }
                .disabled(items.filter(\.include).isEmpty)
            }
        }
        .onAppear {
            if !loaded {
                items = library.expenseCandidates()
                loaded = true
            }
        }
        .sheet(item: $shareFile) { f in ActivityView(items: [f.url]) }
    }
}

// MARK: - Hỗ trợ

struct SupportView: View {
    @ObservedObject var library: LibraryViewModel
    @ObservedObject var ent: EntitlementsManager = .shared
    @ObservedObject var appConfig: AppConfigManager = .shared
    @Environment(\.openURL) private var openURL
    @State private var subject = ""
    @State private var message = ""
    @State private var email = ""
    @State private var sending = false
    @State private var result: String?

    var body: some View {
        Form {
            Section {
                Label(ent.businessActive
                      ? "Tài khoản Business: yêu cầu của bạn được xử lý ưu tiên."
                      : "Gói Business được hỗ trợ ưu tiên. Bạn vẫn gửi yêu cầu bình thường bên dưới.",
                      systemImage: "crown.fill")
                    .font(.subheadline)
            }
            Section {
                TextField("Chủ đề", text: $subject)
                TextField("Mô tả vấn đề / góp ý", text: $message, axis: .vertical)
                    .lineLimit(5...12)
                TextField("Email để nhận phản hồi", text: $email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } footer: {
                Text("Kèm theo tự động: phiên bản app, dòng máy — để hỗ trợ nhanh hơn.")
            }
            Section {
                Button {
                    sending = true
                    result = nil
                    Task {
                        result = await library.sendSupport(subject: subject, message: message, email: email)
                        sending = false
                        if result?.hasPrefix("✓") == true { subject = ""; message = "" }
                    }
                } label: {
                    HStack {
                        Text("Gửi yêu cầu")
                        if sending { Spacer(); ProgressView() }
                    }
                }
                .disabled(sending || subject.trimmingCharacters(in: .whitespaces).count < 3 || message.trimmingCharacters(in: .whitespaces).count < 5)
                if let result {
                    Text(result).font(.footnote).foregroundStyle(result.hasPrefix("✓") ? Color.green : Color.red)
                }
            }
            let info = appConfig.productInfo
            if !info.email.isEmpty || !info.phone.isEmpty {
                Section("Hoặc liên hệ trực tiếp") {
                    if !info.email.isEmpty, let url = URL(string: "mailto:\(info.email)") {
                        Button("Email: \(info.email)") { openURL(url) }
                    }
                    if !info.phone.isEmpty, let url = URL(string: "tel:\(info.phone.filter { !$0.isWhitespace })") {
                        Button("Gọi: \(info.phone)") { openURL(url) }
                    }
                }
            }
        }
        .navigationTitle(ent.businessActive ? "Hỗ trợ ưu tiên" : "Hỗ trợ")
        .onAppear { if email.isEmpty { email = ent.email ?? "" } }
    }
}

// MARK: - Email

/// Màn soạn thư hệ thống (Mail) kèm PDF; máy chưa cài tài khoản Mail → bảng chia sẻ (Gmail, Outlook…).
struct EmailComposer: View {
    let payload: EmailPayload

    var body: some View {
        if MFMailComposeViewController.canSendMail() {
            MailComposeView(payload: payload).ignoresSafeArea()
        } else {
            ActivityView(items: [payload.attachment, "\(payload.subject)\n\n\(payload.body)"])
        }
    }
}

private struct MailComposeView: UIViewControllerRepresentable {
    let payload: EmailPayload
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: { dismiss() }) }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        if !payload.to.isEmpty { vc.setToRecipients(payload.to) }
        vc.setSubject(payload.subject)
        vc.setMessageBody(payload.body, isHTML: false)
        if let data = try? Data(contentsOf: payload.attachment) {
            vc.addAttachmentData(data, mimeType: "application/pdf", fileName: payload.attachment.lastPathComponent)
        }
        return vc
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let dismiss: () -> Void
        init(dismiss: @escaping () -> Void) { self.dismiss = dismiss }
        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            dismiss()
        }
    }
}
