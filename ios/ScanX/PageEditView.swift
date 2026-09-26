import SwiftUI
import CoreImage

enum PageEditTool: String, CaseIterable, Identifiable {
    case filter
    case crop
    case cleanup

    var id: String { rawValue }

    var title: String {
        switch self {
        case .filter: return "Bộ lọc"
        case .crop: return "Cắt xoay"
        case .cleanup: return "Làm sạch"
        }
    }

    var icon: String {
        switch self {
        case .filter: return "camera.filters"
        case .crop: return "crop.rotate"
        case .cleanup: return "eraser"
        }
    }
}

struct PageEditRequest: Identifiable {
    let id = UUID()
    let pageIndex: Int
    let tool: PageEditTool
}

/// Chỉnh sửa trang sau scan — Bộ lọc / Cắt xoay / Làm sạch (tương đương PageEditScreen.kt, luồng bản 1.3:
/// mỗi công cụ 1 màn, "Áp dụng" = lưu & quay về, nút X = huỷ).
struct PageEditView: View {
    @ObservedObject var library: LibraryViewModel
    let documentID: String
    let request: PageEditRequest

    @Environment(\.dismiss) private var dismiss
    @State private var tool: PageEditTool
    @State private var preview: UIImage?
    @State private var saving = false

    init(library: LibraryViewModel, documentID: String, request: PageEditRequest) {
        self.library = library
        self.documentID = documentID
        self.request = request
        _tool = State(initialValue: request.tool)
    }

    private var meta: DocumentMeta? {
        library.document(id: documentID)
    }

    private var currentFilter: PageFilter? {
        guard let meta else { return nil }
        let filters = meta.filters
        return request.pageIndex < filters.count ? filters[request.pageIndex] : nil
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Công cụ", selection: $tool) {
                    ForEach(PageEditTool.allCases) { item in
                        Text(item.title).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .padding()

                if let preview {
                    switch tool {
                    case .filter:
                        FilterToolView(
                            preview: preview,
                            current: currentFilter,
                            documentMode: meta?.mode ?? .a2,
                            onApply: { filter in commit(.filter(filter)) }
                        )
                    case .crop:
                        CropToolView(
                            preview: preview,
                            onApply: { turns, tilt, quad in
                                commit(.geometry(quarterTurns: turns, tilt: tilt, quad: quad))
                            }
                        )
                    case .cleanup:
                        CleanupToolView(
                            preview: preview,
                            onApply: { strokes in commit(.cleanup(strokes)) }
                        )
                    }
                } else {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }
            .disabled(saving)
            .overlay {
                if saving {
                    ProgressView("Đang lưu…")
                        .padding(24)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .navigationTitle("Trang \(request.pageIndex + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Huỷ")
                }
            }
            .task {
                let url = library.store.pageURL(for: documentID, index: request.pageIndex)
                preview = await Task.detached(priority: .userInitiated) {
                    ImageLoader.downsampled(at: url, maxPixel: 1600)
                }.value
            }
        }
    }

    private func commit(_ edit: PageEdit) {
        saving = true
        Task {
            let ok = await library.commitPageEdit(id: documentID, pageIndex: request.pageIndex, edit: edit)
            saving = false
            if ok {
                dismiss()
            }
        }
    }
}

// MARK: - Bộ lọc

private struct FilterToolView: View {
    let preview: UIImage
    let documentMode: PDFMode
    let onApply: (PageFilter?) -> Void

    @State private var selected: PageFilter?
    @State private var rendered: UIImage?

    init(preview: UIImage, current: PageFilter?, documentMode: PDFMode, onApply: @escaping (PageFilter?) -> Void) {
        self.preview = preview
        self.documentMode = documentMode
        self.onApply = onApply
        _selected = State(initialValue: current)
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(uiImage: rendered ?? preview)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(title: "Theo PDF (\(documentMode.rawValue))", value: nil)
                    ForEach(PageFilter.allCases) { filter in
                        chip(title: filter.title, value: filter)
                    }
                }
                .padding(.horizontal)
            }

            Button {
                onApply(selected)
            } label: {
                Text("Áp dụng").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding([.horizontal, .bottom])
        }
        .task(id: selected?.rawValue ?? "mode") {
            guard let source = preview.cgImage else { return }
            let filter = selected
            let mode = documentMode
            let output = await Task.detached(priority: .userInitiated) { () -> CGImage? in
                if let filter {
                    return ScanFilters.process(image: source, filter: filter)
                }
                return ScanFilters.process(image: source, mode: mode)
            }.value
            if let output {
                rendered = UIImage(cgImage: output)
            }
        }
    }

    private func chip(title: String, value: PageFilter?) -> some View {
        let isSelected = selected == value
        return Button(title) {
            selected = value
        }
        .buttonStyle(.bordered)
        .tint(isSelected ? Color.accentColor : Color.gray)
    }
}

// MARK: - Cắt xoay

private struct CropToolView: View {
    let preview: UIImage
    let onApply: (Int, Double, Quad) -> Void

    @State private var quarterTurns = 0
    @State private var tilt: Double = 0
    @State private var committedTilt: Double = 0
    @State private var working: UIImage?
    @State private var quad = Quad.full

    var body: some View {
        VStack(spacing: 12) {
            GeometryReader { geo in
                let image = working ?? preview
                let rect = fittedRect(imageSize: image.size, in: geo.size)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image)
                        .resizable()
                        .frame(width: rect.width, height: rect.height)
                        .offset(x: rect.minX, y: rect.minY)
                    quadPath(in: rect)
                        .fill(Color.accentColor.opacity(0.12))
                    quadPath(in: rect)
                        .stroke(Color.accentColor, lineWidth: 2)
                    ForEach(0..<4, id: \.self) { corner in
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 28, height: 28)
                            .position(point(quad[corner], in: rect))
                            .gesture(
                                DragGesture(coordinateSpace: .named("crop"))
                                    .onChanged { value in
                                        guard rect.width > 0, rect.height > 0 else { return }
                                        quad[corner] = CGPoint(
                                            x: min(max((value.location.x - rect.minX) / rect.width, 0), 1),
                                            y: min(max((value.location.y - rect.minY) / rect.height, 0), 1)
                                        )
                                    }
                            )
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
                .coordinateSpace(name: "crop")
            }
            .padding(.horizontal)

            HStack(spacing: 12) {
                Button("Cắt tự động") { autoDetect() }
                Button("Không cắt") { quad = .full }
                Button {
                    quarterTurns = (quarterTurns + 1) % 4
                } label: {
                    Label("Xoay", systemImage: "rotate.right")
                }
            }
            .buttonStyle(.bordered)

            HStack {
                Text("Nghiêng")
                Slider(value: $tilt, in: -15...15, step: 0.5, onEditingChanged: { editing in
                    if !editing {
                        committedTilt = tilt
                    }
                })
                Text(String(format: "%.1f°", tilt))
                    .monospacedDigit()
                    .frame(width: 52, alignment: .trailing)
            }
            .padding(.horizontal)

            Button {
                onApply(quarterTurns, committedTilt, quad)
            } label: {
                Text("Áp dụng").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding([.horizontal, .bottom])
        }
        .task(id: "\(quarterTurns)|\(committedTilt)") {
            await refreshWorking()
        }
    }

    private func refreshWorking() async {
        guard let source = preview.cgImage else { return }
        let turns = quarterTurns
        let angle = committedTilt
        let output = await Task.detached(priority: .userInitiated) { () -> CGImage? in
            PageTransforms.render(
                PageTransforms.rotate(CIImage(cgImage: source), quarterTurns: turns, tiltDegrees: angle)
            )
        }.value
        if let output {
            working = UIImage(cgImage: output)
        }
        quad = .full
    }

    private func autoDetect() {
        guard let source = (working ?? preview).cgImage else { return }
        Task {
            let detected = await Task.detached(priority: .userInitiated) {
                PageTransforms.detectQuad(in: source)
            }.value
            quad = detected ?? .full
        }
    }

    private func point(_ p: CGPoint, in rect: CGRect) -> CGPoint {
        CGPoint(x: rect.minX + p.x * rect.width, y: rect.minY + p.y * rect.height)
    }

    private func quadPath(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: point(quad.topLeft, in: rect))
            path.addLine(to: point(quad.topRight, in: rect))
            path.addLine(to: point(quad.bottomRight, in: rect))
            path.addLine(to: point(quad.bottomLeft, in: rect))
            path.closeSubpath()
        }
    }
}

// MARK: - Làm sạch

private struct CleanupToolView: View {
    let preview: UIImage
    let onApply: ([CleanupStroke]) -> Void

    @State private var strokes: [CleanupStroke] = []
    @State private var current: [CGPoint] = []
    @State private var brush: Double = 0.03
    @State private var healed: UIImage?

    var body: some View {
        VStack(spacing: 12) {
            GeometryReader { geo in
                let rect = fittedRect(imageSize: preview.size, in: geo.size)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: healed ?? preview)
                        .resizable()
                        .frame(width: rect.width, height: rect.height)
                        .offset(x: rect.minX, y: rect.minY)
                    Path { path in
                        let r = CGFloat(brush) * rect.width
                        for p in current {
                            let c = CGPoint(x: rect.minX + p.x * rect.width, y: rect.minY + p.y * rect.height)
                            path.addEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
                        }
                    }
                    .fill(Color.red.opacity(0.35))
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            guard rect.width > 0, rect.height > 0 else { return }
                            current.append(CGPoint(
                                x: min(max((value.location.x - rect.minX) / rect.width, 0), 1),
                                y: min(max((value.location.y - rect.minY) / rect.height, 0), 1)
                            ))
                        }
                        .onEnded { _ in
                            if !current.isEmpty {
                                strokes.append(CleanupStroke(points: current, radius: CGFloat(brush)))
                            }
                            current = []
                            refreshHealed()
                        }
                )
            }
            .padding(.horizontal)

            HStack {
                Image(systemName: "paintbrush.pointed")
                Slider(value: $brush, in: 0.01...0.08)
            }
            .padding(.horizontal)

            HStack(spacing: 12) {
                Button("Hoàn tác") {
                    if !strokes.isEmpty {
                        strokes.removeLast()
                        refreshHealed()
                    }
                }
                .buttonStyle(.bordered)
                .disabled(strokes.isEmpty)

                Button("Đặt lại") {
                    strokes.removeAll()
                    healed = nil
                }
                .buttonStyle(.bordered)
                .disabled(strokes.isEmpty)

                Button {
                    onApply(strokes)
                } label: {
                    Text("Áp dụng").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(strokes.isEmpty)
            }
            .padding(.horizontal)

            Text("Tô lên vết bẩn, vết ngón tay cần xoá")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.bottom)
        }
    }

    private func refreshHealed() {
        guard !strokes.isEmpty, let source = preview.cgImage else {
            healed = nil
            return
        }
        let snapshot = strokes
        Task {
            let output = await Task.detached(priority: .userInitiated) { () -> CGImage? in
                guard let rgbx = ScanFilters.rgbxBuffer(from: source) else { return nil }
                return ScanFilters.colorImage(PageCleanup.heal(rgbx, strokes: snapshot))
            }.value
            if let output {
                healed = UIImage(cgImage: output)
            }
        }
    }
}

// MARK: - Tiện ích

private func fittedRect(imageSize: CGSize, in container: CGSize) -> CGRect {
    guard imageSize.width > 0, imageSize.height > 0, container.width > 0, container.height > 0 else {
        return .zero
    }
    let scale = min(container.width / imageSize.width, container.height / imageSize.height)
    let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
    return CGRect(
        x: (container.width - size.width) / 2,
        y: (container.height - size.height) / 2,
        width: size.width,
        height: size.height
    )
}
