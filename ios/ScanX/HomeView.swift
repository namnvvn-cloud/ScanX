import SwiftUI
import VisionKit

/// Mục đích của 1 lần mở bộ quét: tài liệu / lấy văn bản / sách 2 trang.
enum ScanPurpose { case document, text, book }

/// Màn chính: danh sách tài liệu + nút Quét. Đăng nhập là TUỲ CHỌN (giống Android) —
/// vào qua Cài đặt → Tài khoản & Sao lưu đám mây.
struct HomeView: View {
    @ObservedObject var library: LibraryViewModel
    @ObservedObject var auth: AuthViewModel

    @State private var path: [DocumentMeta] = []
    @State private var showScanner = false
    @State private var showSettings = false
    @State private var showPhotoTranslate = false
    @State private var showUnsupported = false
    @State private var paywall: BusinessFeature?
    @State private var scanPurpose: ScanPurpose = .document
    @State private var textResult: TextResult?
    @State private var qrValue: QRValue?
    @State private var showQRScanner = false
    @State private var showExpense = false
    @State private var showSupport = false
    @State private var showQRHistory = false
    @ObservedObject var ent: EntitlementsManager = .shared

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if library.documents.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
                        smartFilterRow
                        if library.visibleDocuments.isEmpty {
                            Text("Không có tài liệu khớp bộ lọc «\(library.smartFilter.label)».")
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            documentList
                        }
                    }
                }
            }
            .navigationTitle("ScanX")
            .navigationDestination(isPresented: $showExpense) { ExpenseReportView(library: library) }
            .navigationDestination(isPresented: $showSupport) { SupportView(library: library) }
            .navigationDestination(isPresented: $showQRHistory) { QRHistoryView(onScan: { startQR() }) }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        Button {
                            use(.expenseReport) { showExpense = true }
                        } label: { Label("Báo cáo chi phí", systemImage: "list.bullet.rectangle.portrait") }
                        Button {
                            showQRHistory = true
                        } label: { Label("Lịch sử quét mã", systemImage: "qrcode") }
                        Button {
                            showSupport = true
                        } label: { Label(ent.businessActive ? "Hỗ trợ ưu tiên" : "Hỗ trợ", systemImage: "questionmark.bubble") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("Thêm")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Cài đặt")
                }
            }
            .safeAreaInset(edge: .bottom) {
                scanButton
            }
            .navigationDestination(for: DocumentMeta.self) { meta in
                DocumentDetailView(library: library, documentID: meta.id, fallback: meta)
            }
            .overlay {
                if library.isSaving {
                    ProgressView("Đang lưu tài liệu…")
                        .padding(24)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView(
                onFinish: { files in
                    showScanner = false
                    let purpose = scanPurpose
                    scanPurpose = .document
                    Task {
                        switch purpose {
                        case .text:
                            let text = await library.recognizeText(files: files)
                            textResult = TextResult(text: text)
                        case .book:
                            if let meta = await library.saveBookScan(files: files) { path.append(meta) }
                        case .document:
                            if let meta = await library.saveScan(pageFiles: files) { path.append(meta) }
                        }
                    }
                },
                onCancel: { showScanner = false },
                onError: { error in
                    showScanner = false
                    library.errorMessage = error.localizedDescription
                }
            )
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(auth: auth, library: library)
        }
        .sheet(item: $textResult) { r in TextResultView(text: r.text) }
        .sheet(item: $qrValue) { v in
            QRResultView(value: v.value, onHistory: { qrValue = nil; showQRHistory = true })
        }
        .sheet(item: $library.pendingEmail) { p in EmailComposer(payload: p) }
        .fullScreenCover(isPresented: $showQRScanner) {
            QRScannerView(
                onFound: { value in
                    ToolsStore.shared.addQR(value)
                    showQRScanner = false
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { qrValue = QRValue(value: value) }
                },
                onCancel: { showQRScanner = false }
            )
            .ignoresSafeArea()
        }
        .overlay {
            if let text = library.progressText, !library.isSaving {
                ProgressView(text)
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .alert("Thiết bị không hỗ trợ quét", isPresented: $showUnsupported) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Máy này không có bộ quét tài liệu của Apple (VisionKit), ví dụ khi chạy trên Simulator.")
        }
        .alert(
            "Lưu ý",
            isPresented: Binding(
                get: { library.notice != nil },
                set: { if !$0 { library.notice = nil } }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(library.notice ?? "")
        }
        .alert(
            "Lỗi",
            isPresented: Binding(
                get: { library.errorMessage != nil },
                set: { if !$0 { library.errorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(library.errorMessage ?? "")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "doc.viewfinder")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Chưa có tài liệu")
                .font(.headline)
            Text("Bấm \"Quét tài liệu\" để bắt đầu.")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var documentList: some View {
        List {
            let docs = library.visibleDocuments
            ForEach(docs) { meta in
                NavigationLink(value: meta) {
                    DocumentRow(meta: meta, thumbnailURL: library.store.thumbnailURL(for: meta.id))
                }
            }
            .onDelete { offsets in
                let ids = offsets.map { docs[$0].id }
                ids.forEach { library.delete(id: $0) }
            }
        }
        .listStyle(.plain)
    }

    /// Bộ lọc thông minh: 7 ngày qua / Có văn bản / Hoá đơn / Nhiều trang.
    private var smartFilterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SmartFilter.allCases) { f in
                    let selected = library.smartFilter == f
                    Button {
                        library.smartFilter = selected ? .all : f
                    } label: {
                        HStack(spacing: 4) {
                            if selected && f != .all { Image(systemName: "checkmark") }
                            Text(f.label)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(selected ? Color.accentColor.opacity(0.15) : Color(.secondarySystemBackground), in: Capsule())
                        .overlay(Capsule().stroke(selected ? Color.accentColor : Color.clear))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    /// Chạy [action] nếu được phép (Business / còn lượt thử), ngược lại mở màn Business.
    private func use(_ feature: BusinessFeature, _ action: () -> Void) {
        switch ent.tryUse([feature]) {
        case .locked(let f): paywall = f
        case .business, .trial: action()
        }
    }

    private func startScan(_ purpose: ScanPurpose) {
        guard VNDocumentCameraViewController.isSupported else {
            showUnsupported = true
            return
        }
        scanPurpose = purpose
        showScanner = true
    }

    private func startQR() {
        use(.qrScan) { showQRScanner = true }
    }

    private var scanButton: some View {
        HStack(spacing: 12) {
            Menu {
                Button { use(.textScan) { startScan(.text) } } label: { Label("Văn bản", systemImage: "text.viewfinder") }
                Button { use(.bookScan) { startScan(.book) } } label: { Label("Sách (tách 2 trang)", systemImage: "book") }
                Button { startQR() } label: { Label("Mã QR", systemImage: "qrcode.viewfinder") }
            } label: {
                Image(systemName: "square.grid.2x2")
                    .font(.headline)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Công cụ")

            Button {
                startScan(.document)
            } label: {
                Label("Quét tài liệu", systemImage: "camera.viewfinder")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(library.isSaving)

            Button {
                // "Chụp để dịch / dịch trực tiếp" có thể bị admin chuyển sang Business hoặc tạm tắt.
                switch EntitlementsManager.shared.tryUse([.cameraTranslate]) {
                case .locked(let f): paywall = f
                case .business, .trial: showPhotoTranslate = true
                }
            } label: {
                Label("Dịch", systemImage: "character.bubble")
                    .font(.headline)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Chụp để dịch")
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .fullScreenCover(isPresented: $showPhotoTranslate) {
            PhotoTranslateView()
        }
        .sheet(item: $paywall) { f in BusinessPaywallView(feature: f) }
    }
}

/// Cache ảnh thu nhỏ danh sách tài liệu (tự giải phóng khi thiếu bộ nhớ).
enum ThumbnailCache {
    static let shared: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 300
        return c
    }()
}

private struct DocumentRow: View {
    let meta: DocumentMeta
    let thumbnailURL: URL
    @State private var thumbnail: UIImage?

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let image = thumbnail {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Color.secondary.opacity(0.2)
                }
            }
            .frame(width: 48, height: 64)
            // Đọc ảnh thu nhỏ trên luồng nền + cache → cuộn danh sách không giật.
            .task(id: "\(meta.id)#\(meta.modifiedAt.timeIntervalSince1970)") {
                let key = "\(meta.id)#\(meta.modifiedAt.timeIntervalSince1970)" as NSString
                if let cached = ThumbnailCache.shared.object(forKey: key) {
                    thumbnail = cached
                    return
                }
                let url = thumbnailURL
                let image = await Task.detached(priority: .utility) { ImageLoader.downsampled(at: url, maxPixel: 192) }.value
                if let image { ThumbnailCache.shared.setObject(image, forKey: key) }
                thumbnail = image
            }
            .clipShape(RoundedRectangle(cornerRadius: 4))

            VStack(alignment: .leading, spacing: 4) {
                Text(meta.title)
                    .font(.body)
                    .lineLimit(1)
                Text("\(meta.pageCount) trang · \(meta.modifiedAt.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
