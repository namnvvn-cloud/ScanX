import SwiftUI
import VisionKit

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

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if library.documents.isEmpty {
                    emptyState
                } else {
                    documentList
                }
            }
            .navigationTitle("ScanX")
            .toolbar {
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
                    Task {
                        if let meta = await library.saveScan(pageFiles: files) {
                            path.append(meta)
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
            ForEach(library.documents) { meta in
                NavigationLink(value: meta) {
                    DocumentRow(meta: meta, thumbnailURL: library.store.thumbnailURL(for: meta.id))
                }
            }
            .onDelete { offsets in
                for index in offsets {
                    library.delete(id: library.documents[index].id)
                }
            }
        }
        .listStyle(.plain)
    }

    private var scanButton: some View {
        HStack(spacing: 12) {
            Button {
                if VNDocumentCameraViewController.isSupported {
                    showScanner = true
                } else {
                    showUnsupported = true
                }
            } label: {
                Label("Quét tài liệu", systemImage: "camera.viewfinder")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(library.isSaving)

            Button {
                showPhotoTranslate = true
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
    }
}

private struct DocumentRow: View {
    let meta: DocumentMeta
    let thumbnailURL: URL

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let image = UIImage(contentsOfFile: thumbnailURL.path) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Color.secondary.opacity(0.2)
                }
            }
            .frame(width: 48, height: 64)
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
