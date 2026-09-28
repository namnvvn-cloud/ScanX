import SwiftUI
import UIKit
import UniformTypeIdentifiers

// MARK: - Dịch vụ lưu trữ (thư mục đám mây) + Tự động tải lên

/// Chọn 1 thư mục trong Files (iCloud Drive, Google Drive, OneDrive, Dropbox… nếu đã cài app và bật trong Files).
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        vc.delegate = context.coordinator
        vc.allowsMultipleSelection = false
        return vc
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}

struct CloudFoldersView: View {
    @ObservedObject var tools: ToolsStore = .shared
    @ObservedObject var ent: EntitlementsManager = .shared
    @State private var showPicker = false
    @State private var error: String?
    @State private var paywall: BusinessFeature?

    var body: some View {
        List {
            Section {
                ForEach(tools.cloudFolders) { f in
                    Button {
                        tools.defaultCloudFolderID = f.id
                    } label: {
                        HStack {
                            Image(systemName: f.id == tools.defaultCloudFolder?.id ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(Color.accentColor)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(f.name).foregroundStyle(.primary)
                                Text(ToolsStore.providerName(f) + (f.id == tools.defaultCloudFolder?.id ? " · mặc định" : ""))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .onDelete { idx in idx.map { tools.cloudFolders[$0].id }.forEach(tools.removeCloudFolder) }
                Button {
                    switch ent.tryUse([.cloudFolders]) {
                    case .locked(let f): paywall = f
                    default: showPicker = true
                    }
                } label: {
                    Label("Kết nối thư mục", systemImage: "folder.badge.plus")
                }
                if let error { Text(error).font(.footnote).foregroundStyle(.red) }
            } header: {
                Text("Thư mục đám mây")
            } footer: {
                Text("Chọn 1 thư mục trong Tệp: iCloud Drive, Google Drive, OneDrive, Dropbox… (cài app của dịch vụ và bật trong Tệp → Duyệt → ⋯ → Sửa). ScanX lưu PDF vào đó khi bạn chọn «Lưu vào thư mục đám mây», khi Tự động tải lên, hoặc trong Quy trình. Vuốt trái để gỡ.")
            }

            Section {
                Toggle(isOn: Binding(
                    get: { tools.autoUploadEnabled && ent.has(.autoUpload) },
                    set: { on in
                        if !on { tools.autoUploadEnabled = false }
                        else if ent.has(.autoUpload) { tools.autoUploadEnabled = true }
                        else { paywall = .autoUpload }
                    }
                )) {
                    Label(ent.has(.autoUpload) ? "Tự động tải lên" : "Tự động tải lên (Business)", systemImage: "icloud.and.arrow.up")
                }
                Toggle("Thư mục đám mây mặc định" + (tools.cloudFolders.isEmpty ? " (chưa kết nối)" : ""), isOn: $tools.autoUploadToFolder)
                    .disabled(tools.cloudFolders.isEmpty)
                Toggle("ScanX Cloud (sao lưu tài khoản)" + (ent.email != nil && ent.has(.cloudBackup) ? "" : " — cần đăng nhập Business"), isOn: $tools.autoUploadToScanX)
            } header: {
                Text("Tự động tải lên")
            } footer: {
                Text("Mỗi tài liệu mới quét xong được tải lên các đích đã chọn.")
            }
        }
        .navigationTitle("Dịch vụ lưu trữ")
        .sheet(isPresented: $showPicker) {
            FolderPicker { url in
                do { try tools.addCloudFolder(url: url); error = nil } catch { self.error = "Không kết nối được thư mục: \(error.localizedDescription)" }
            }
        }
        .sheet(item: $paywall) { f in BusinessPaywallView(feature: f) }
    }
}

// MARK: - Mẫu email

struct EmailTemplateView: View {
    @ObservedObject var tools: ToolsStore = .shared
    @State private var draft = EmailTemplate.default
    @State private var saved = false

    var body: some View {
        Form {
            Section {
                TextField("Gửi tới (nhiều email cách nhau dấu phẩy)", text: $draft.to)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Tiêu đề", text: $draft.subject)
                TextField("Nội dung", text: $draft.body, axis: .vertical).lineLimit(6...14)
            } footer: {
                Text("Dùng khi chọn «Gửi email» ở màn tài liệu và trong Quy trình. Biến tự thay: {ten} tên tài liệu, {ngay} ngày quét, {gio} giờ, {so_trang} số trang.")
            }
            Section {
                Button("Lưu mẫu") { tools.emailTemplate = draft; saved = true }
                Button("Khôi phục mẫu mặc định") { draft = .default; saved = false }
                if saved { Text("✓ Đã lưu").foregroundStyle(.green) }
            }
        }
        .navigationTitle("Mẫu email")
        .onAppear { draft = tools.emailTemplate }
        .onChange(of: draft) { _ in saved = false }
    }
}

// MARK: - Quy trình tự động

struct WorkflowsView: View {
    @ObservedObject var tools: ToolsStore = .shared
    @ObservedObject var ent: EntitlementsManager = .shared
    @State private var editing: Workflow?

    var body: some View {
        List {
            Section {
                ForEach(tools.workflows) { w in
                    Button { editing = w } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(w.name).foregroundStyle(.primary)
                            Text("\(w.stepCount) bước" + (w.runAfterScan ? " · tự chạy sau khi quét" : ""))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { idx in idx.map { tools.workflows[$0].id }.forEach(tools.deleteWorkflow) }
                Button {
                    editing = Workflow(name: "Quy trình \(tools.workflows.count + 1)")
                } label: {
                    Label("Tạo quy trình", systemImage: "plus")
                }
            } footer: {
                Text("Gộp nhiều thao tác thành 1 lần bấm: đặt tên theo mẫu, lưu vào thư mục đám mây, sao lưu ScanX Cloud, gửi email theo mẫu. Chạy ở màn tài liệu (⋯ → Chạy quy trình) hoặc tự chạy sau mỗi lần quét."
                     + (ent.has(.workflows) ? "" : " Quy trình là tính năng Business — khi chạy sẽ dùng lượt thử (nếu có)."))
            }
        }
        .navigationTitle("Quy trình tự động")
        .sheet(item: $editing) { w in
            WorkflowEditor(workflow: w, hasCloudFolder: !tools.cloudFolders.isEmpty) { tools.saveWorkflow($0) }
        }
    }
}

private struct WorkflowEditor: View {
    @State var workflow: Workflow
    let hasCloudFolder: Bool
    let onSave: (Workflow) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Tên quy trình", text: $workflow.name)
                    TextField("Đặt tên theo mẫu (trống = giữ tên)", text: $workflow.renamePattern, prompt: Text("VD: Hoá đơn {ngay} {gio}"))
                }
                Section("Các bước") {
                    Toggle("Lưu PDF vào thư mục đám mây" + (hasCloudFolder ? "" : " (chưa kết nối)"), isOn: $workflow.saveToCloudFolder)
                    Toggle("Sao lưu lên ScanX Cloud (Business)", isOn: $workflow.backupScanX)
                    Toggle("Gửi email theo Mẫu email", isOn: $workflow.sendEmail)
                }
                Section {
                    Toggle("Tự chạy sau mỗi lần quét", isOn: $workflow.runAfterScan)
                }
            }
            .navigationTitle("Quy trình")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Huỷ") { dismiss() } }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Lưu") {
                        var w = workflow
                        if w.name.trimmingCharacters(in: .whitespaces).isEmpty { w.name = "Quy trình" }
                        onSave(w)
                        dismiss()
                    }
                    .disabled(workflow.stepCount == 0)
                }
            }
        }
    }
}

// MARK: - Chữ ký

struct SignaturesView: View {
    @ObservedObject var tools: ToolsStore = .shared
    @State private var drawing = false

    var body: some View {
        List {
            Section {
                ForEach(tools.signatures, id: \.self) { url in
                    if let image = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 64)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(6)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 6))
                    }
                }
                .onDelete { idx in idx.map { tools.signatures[$0] }.forEach(tools.deleteSignature) }
                Button { drawing = true } label: { Label("Vẽ chữ ký mới", systemImage: "signature") }
            } footer: {
                Text("Vẽ chữ ký 1 lần, sau đó chèn vào tài liệu ở màn tài liệu (⋯ → Ký tên). Chữ ký chỉ lưu trên máy này. Vuốt trái để xoá.")
            }
        }
        .navigationTitle("Chữ ký")
        .sheet(isPresented: $drawing) {
            SignaturePadView { image in tools.saveSignature(image) }
        }
    }
}

/// Bảng vẽ chữ ký bằng ngón tay → ảnh PNG nền trong suốt, cắt sát nét.
struct SignaturePadView: View {
    let onDone: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var strokes: [[CGPoint]] = []
    @State private var current: [CGPoint] = []
    @State private var canvasSize: CGSize = .zero

    private static let ink = Color(red: 0.05, green: 0.16, blue: 0.42)

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                GeometryReader { geo in
                    Canvas { ctx, _ in
                        for s in strokes + [current] where s.count > 1 {
                            var p = Path()
                            p.addLines(s)
                            ctx.stroke(p, with: .color(Self.ink), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                        }
                    }
                    .background(Color.white)
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { v in current.append(v.location) }
                            .onEnded { _ in
                                if current.count > 1 { strokes.append(current) }
                                current = []
                            }
                    )
                    .onAppear { canvasSize = geo.size }
                    .onChange(of: geo.size) { canvasSize = $0 }
                }
                .frame(height: 240)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.4)))
                Text("Ký bằng ngón tay bên trong khung.").font(.footnote).foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
            .navigationTitle("Vẽ chữ ký")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Huỷ") { dismiss() } }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button("Xoá nét") { strokes = []; current = [] }
                    Button("Lưu") {
                        if let image = render() { onDone(image) }
                        dismiss()
                    }
                    .disabled(strokes.isEmpty)
                }
            }
        }
    }

    private func render() -> UIImage? {
        let pts = strokes.flatMap { $0 }
        guard !pts.isEmpty else { return nil }
        let scale: CGFloat = 3
        let pad: CGFloat = 8
        let minX = max(0, (pts.map(\.x).min() ?? 0) - pad)
        let minY = max(0, (pts.map(\.y).min() ?? 0) - pad)
        let maxX = min(canvasSize.width, (pts.map(\.x).max() ?? 0) + pad)
        let maxY = min(canvasSize.height, (pts.map(\.y).max() ?? 0) + pad)
        let size = CGSize(width: max(1, maxX - minX), height: max(1, maxY - minY))
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            c.setStrokeColor(UIColor(red: 0.05, green: 0.16, blue: 0.42, alpha: 1).cgColor)
            c.setLineWidth(4)
            c.setLineCap(.round)
            c.setLineJoin(.round)
            for s in strokes where s.count > 1 {
                c.move(to: CGPoint(x: s[0].x - minX, y: s[0].y - minY))
                for p in s.dropFirst() { c.addLine(to: CGPoint(x: p.x - minX, y: p.y - minY)) }
                c.strokePath()
            }
        }
    }
}

/// Đặt chữ ký lên trang: kéo để di chuyển, +/− đổi cỡ. Toạ độ chuẩn hoá 0…1 theo ảnh trang.
struct SignPlacementView: View {
    @ObservedObject var library: LibraryViewModel
    let documentID: String
    let pageIndex: Int
    @ObservedObject var tools: ToolsStore = .shared
    @Environment(\.dismiss) private var dismiss
    @State private var page: UIImage?
    @State private var signatureURL: URL?
    @State private var nx: CGFloat = 0.55
    @State private var ny: CGFloat = 0.78
    @State private var nw: CGFloat = 0.32
    @State private var dragStart: CGPoint?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if tools.signatures.isEmpty {
                    VStack(spacing: 12) {
                        Text("Chưa có chữ ký nào.").font(.headline)
                        NavigationLink("Vẽ chữ ký") { SignaturesView() }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let page, let sigURL = signatureURL ?? tools.signatures.first, let sig = UIImage(contentsOfFile: sigURL.path) {
                    GeometryReader { geo in
                        let fit = fitRect(page.size, in: geo.size)
                        let sw = nw * fit.width
                        let sh = sw * sig.size.height / max(1, sig.size.width)
                        ZStack(alignment: .topLeading) {
                            Image(uiImage: page).resizable().frame(width: fit.width, height: fit.height).offset(x: fit.minX, y: fit.minY)
                            Image(uiImage: sig).resizable()
                                .frame(width: sw, height: sh)
                                .border(Color.accentColor, width: 2)
                                .offset(x: fit.minX + nx * fit.width, y: fit.minY + ny * fit.height)
                                .gesture(
                                    DragGesture()
                                        .onChanged { v in
                                            let start = dragStart ?? CGPoint(x: nx, y: ny)
                                            if dragStart == nil { dragStart = start }
                                            nx = min(max(0, start.x + v.translation.width / fit.width), 1 - nw)
                                            ny = min(max(0, start.y + v.translation.height / fit.height), max(0, 1 - sh / fit.height))
                                        }
                                        .onEnded { _ in dragStart = nil }
                                )
                        }
                    }
                    .background(Color(.darkGray))
                    if tools.signatures.count > 1 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(tools.signatures, id: \.self) { url in
                                    if let img = UIImage(contentsOfFile: url.path) {
                                        Image(uiImage: img).resizable().scaledToFit().frame(height: 36).padding(4)
                                            .background(Color.white, in: RoundedRectangle(cornerRadius: 4))
                                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(url == sigURL ? Color.accentColor : .clear, lineWidth: 2))
                                            .onTapGesture { signatureURL = url }
                                    }
                                }
                            }
                            .padding(8)
                        }
                    }
                    HStack {
                        Button("−") { nw = max(0.1, nw - 0.05) }.buttonStyle(.bordered)
                        Button("+") { nw = min(0.9, nw + 0.05); nx = min(nx, 1 - nw) }.buttonStyle(.bordered)
                        Text("Kéo chữ ký để đặt vị trí").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Chèn") {
                            saving = true
                            Task {
                                let ok = await library.applySignature(id: documentID, pageIndex: pageIndex, signature: sigURL, x: nx, y: ny, w: nw)
                                saving = false
                                if ok { dismiss() }
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(saving)
                    }
                    .padding()
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Ký — Trang \(pageIndex + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("Huỷ") { dismiss() } } }
            .overlay { if saving { ProgressView("Đang chèn chữ ký…").padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
            .task {
                let url = library.store.pageURL(for: documentID, index: pageIndex)
                page = await Task.detached { ImageLoader.downsampled(at: url, maxPixel: 1600) }.value
            }
        }
    }

    private func fitRect(_ image: CGSize, in box: CGSize) -> CGRect {
        guard image.width > 0, image.height > 0, box.width > 0, box.height > 0 else { return .zero }
        let pad: CGFloat = 12
        let w = box.width - pad * 2, h = box.height - pad * 2
        let scale = min(w / image.width, h / image.height)
        let size = CGSize(width: image.width * scale, height: image.height * scale)
        return CGRect(x: (box.width - size.width) / 2, y: (box.height - size.height) / 2, width: size.width, height: size.height)
    }
}

// MARK: - Biểu tượng ứng dụng

struct AppIconView: View {
    private struct Option: Identifiable {
        let id: String?
        let label: String
        let color: Color
    }

    private let options: [Option] = [
        Option(id: nil, label: "Xanh dương (mặc định)", color: Color(red: 0.18, green: 0.44, blue: 0.93)),
        Option(id: "AppIconDark", label: "Tối", color: Color(red: 0.11, green: 0.11, blue: 0.12)),
        Option(id: "AppIconGreen", label: "Xanh lá", color: Color(red: 0.12, green: 0.56, blue: 0.35)),
        Option(id: "AppIconOrange", label: "Cam", color: Color(red: 0.91, green: 0.44, blue: 0.04)),
    ]
    @State private var current: String?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                ForEach(options) { o in
                    Button {
                        guard UIApplication.shared.supportsAlternateIcons else {
                            error = "Máy này không hỗ trợ đổi biểu tượng."
                            return
                        }
                        UIApplication.shared.setAlternateIconName(o.id) { err in
                            DispatchQueue.main.async {
                                if let err { error = err.localizedDescription } else { current = o.id; error = nil }
                            }
                        }
                    } label: {
                        HStack(spacing: 16) {
                            RoundedRectangle(cornerRadius: 10).fill(o.color).frame(width: 44, height: 44)
                                .overlay(RoundedRectangle(cornerRadius: 2).fill(Color.white).frame(width: 20, height: 26))
                            Text(o.label).foregroundStyle(.primary)
                            Spacer()
                            if o.id == current { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor) }
                        }
                    }
                }
                if let error { Text(error).font(.footnote).foregroundStyle(.red) }
            } footer: {
                Text("iOS sẽ hiện thông báo xác nhận khi đổi biểu tượng.")
            }
        }
        .navigationTitle("Biểu tượng ứng dụng")
        .onAppear { current = UIApplication.shared.alternateIconName }
    }
}
