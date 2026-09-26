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

    private var meta: DocumentMeta {
        library.document(id: documentID) ?? fallback
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(0..<meta.pageCount, id: \.self) { index in
                    PageImageView(url: library.store.pageURL(for: documentID, index: index))
                        .padding(12)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .background(Color(.secondarySystemBackground))

            Text("Trang \(currentPage + 1)/\(meta.pageCount)")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.vertical, 8)
        }
        .navigationTitle(meta.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                ShareLink(item: library.store.pdfURL(for: documentID)) {
                    Image(systemName: "square.and.arrow.up")
                }
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
        .confirmationDialog("Xoá tài liệu này?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Xoá", role: .destructive) {
                library.delete(id: documentID)
                dismiss()
            }
        }
    }
}

private struct PageImageView: View {
    let url: URL
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
        .task(id: url) {
            let target = url
            image = await Task.detached(priority: .userInitiated) {
                ImageLoader.downsampled(at: target, maxPixel: 1600)
            }.value
        }
    }
}
