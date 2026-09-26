import SwiftUI

struct DocumentDetailView: View {
    @ObservedObject var library: LibraryViewModel
    let documentID: String
    let fallback: DocumentMeta

    @Environment(\.dismiss) private var dismiss
    @State private var currentPage = 0
    @State private var showRename = false
    @State private var newTitle = ""
    @State private var confirmDelete = false
    @State private var isExporting = false
    @State private var exported: ExportedFile?
    @State private var editRequest: PageEditRequest?

    private var meta: DocumentMeta {
        library.document(id: documentID) ?? fallback
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(0..<meta.pageCount, id: \.self) { index in
                    PageImageView(
                        url: library.store.pageURL(for: documentID, index: index),
                        filter: index < meta.filters.count ? meta.filters[index] : nil,
                        version: meta.modifiedAt.timeIntervalSince1970
                    )
                    .padding(12)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editRequest = PageEditRequest(pageIndex: index, tool: .filter)
                    }
                    .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .background(Color(.secondarySystemBackground))

            HStack(spacing: 8) {
                Text("Trang \(currentPage + 1)/\(meta.pageCount)")
                Text("·")
                Text("PDF \(meta.mode.rawValue)")
                if meta.hasTextLayer == true {
                    Text("·")
                    Label("Có lớp chữ", systemImage: "text.viewfinder")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.top, 8)

            // Thanh dưới cố định như Android (bản 0.9): Bộ lọc | Cắt xoay | Làm sạch.
            HStack {
                ForEach(PageEditTool.allCases) { tool in
                    Button {
                        editRequest = PageEditRequest(pageIndex: currentPage, tool: tool)
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: tool.icon)
                                .font(.title3)
                            Text(tool.title)
                                .font(.caption)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(.vertical, 10)
            .background(.bar)
        }
        .fullScreenCover(item: $editRequest) { request in
            PageEditView(library: library, documentID: documentID, request: request)
        }
        .navigationTitle(meta.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Section("Chia sẻ PDF") {
                        ForEach(PDFMode.allCases) { mode in
                            Button {
                                export(mode: mode)
                            } label: {
                                Text(mode == meta.mode ? "\(mode.title) (đang lưu)" : mode.title)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(isExporting)
                .accessibilityLabel("Chia sẻ PDF")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        newTitle = meta.title
                        showRename = true
                    } label: {
                        Label("Đổi tên", systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        confirmDelete = true
                    } label: {
                        Label("Xoá", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .alert("Đổi tên tài liệu", isPresented: $showRename) {
            TextField("Tên tài liệu", text: $newTitle)
            Button("Lưu") {
                library.rename(id: documentID, to: newTitle)
            }
            Button("Huỷ", role: .cancel) {}
        }
        .overlay {
            if isExporting {
                ProgressView("Đang dựng PDF…")
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .sheet(item: $exported) { file in
            ActivityView(items: [file.url])
        }
        .confirmationDialog("Xoá tài liệu này?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Xoá", role: .destructive) {
                library.delete(id: documentID)
                dismiss()
            }
        }
    }
}

extension DocumentDetailView {
    private func export(mode: PDFMode) {
        isExporting = true
        Task {
            let url = await library.exportPDF(id: documentID, mode: mode)
            isExporting = false
            if let url {
                exported = ExportedFile(url: url)
            }
        }
    }
}

private struct PageImageView: View {
    let url: URL
    let filter: PageFilter?
    let version: Double
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .shadow(radius: 2)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: "\(url.path)#\(filter?.rawValue ?? "-")#\(version)") {
            let target = url
            let pageFilter = filter
            image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let base = ImageLoader.downsampled(at: target, maxPixel: 1600) else { return nil }
                guard let pageFilter, let source = base.cgImage,
                      let filtered = ScanFilters.process(image: source, filter: pageFilter)
                else { return base }
                return UIImage(cgImage: filtered)
            }.value
        }
    }
}
