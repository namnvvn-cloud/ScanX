import SwiftUI
import PhotosUI
import UIKit

/// Màn "Chụp để dịch" — tương đương CameraTranslateScreen bên Android (phần chụp/chọn ảnh + kết quả).
struct PhotoTranslateView: View {
    @StateObject private var model = PhotoTranslateModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showCamera = false
    @State private var showLive = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var showOriginal = false
    @State private var showText = false
    @State private var shareImage: ExportedImage?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Chụp để dịch")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button {
                            dismiss()
                        } label: {
                            Image(systemName: "xmark")
                        }
                    }
                }
        }
        .fullScreenCover(isPresented: $showLive) {
            LiveTranslateScreen { image in
                showLive = false
                model.process(image)
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                showCamera = false
                if let image { model.process(image) }
            }
            .ignoresSafeArea()
        }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                    model.process(image)
                }
                pickerItem = nil
            }
        }
        .sheet(item: $shareImage) { item in
            ActivityView(items: [item.image])
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .idle:
            sourcePicker
        case .working(let message):
            VStack(spacing: 16) {
                ProgressView()
                Text(message).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failure(let message):
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundStyle(.orange)
                Text(message)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                Button("Thử lại") { model.reset() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .result(let result):
            resultView(result)
        }
    }

    private var sourcePicker: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "character.bubble")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Chụp hoặc chọn ảnh có chữ — ScanX nhận dạng trên máy rồi vẽ bản dịch tiếng Việt đè đúng vị trí.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Spacer()
            VStack(spacing: 12) {
                Button {
                    showLive = true
                } label: {
                    Label("Dịch trực tiếp (soi camera)", systemImage: "camera.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

                Button {
                    showCamera = true
                } label: {
                    Label("Chụp ảnh", systemImage: "camera")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("Chọn ảnh từ thư viện", systemImage: "photo.on.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }

    private func resultView(_ result: PhotoTranslateModel.Result) -> some View {
        VStack(spacing: 0) {
            Picker("Hiển thị", selection: $showOriginal) {
                Text("Bản dịch").tag(false)
                Text("Bản gốc").tag(true)
            }
            .pickerStyle(.segmented)
            .padding()

            ScrollView([.vertical, .horizontal]) {
                Image(uiImage: showOriginal ? result.original : result.translated)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: UIScreen.main.bounds.width - 16)
            }

            VStack(spacing: 6) {
                if !result.engineLabel.isEmpty {
                    Text("Máy dịch: \(result.engineLabel)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let notice = result.notice {
                    Text(notice)
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)

            HStack(spacing: 12) {
                Button {
                    model.reset()
                } label: {
                    Label("Ảnh khác", systemImage: "camera.rotate")
                }
                Button {
                    showText = true
                } label: {
                    Label("Văn bản", systemImage: "text.alignleft")
                }
                .disabled(result.pairs.isEmpty)
                Button {
                    shareImage = ExportedImage(image: result.translated)
                } label: {
                    Label("Chia sẻ", systemImage: "square.and.arrow.up")
                }
            }
            .buttonStyle(.bordered)
            .padding()
        }
        .sheet(isPresented: $showText) {
            TranslatedTextSheet(pairs: result.pairs)
        }
    }
}

struct ExportedImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// Từng cặp bản gốc – bản dịch, sao chép được (nút "Văn bản" bên Android).
private struct TranslatedTextSheet: View {
    let pairs: [(original: String, translated: String)]
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationStack {
            List {
                ForEach(pairs.indices, id: \.self) { i in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(pairs[i].original)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text(pairs[i].translated)
                            .textSelection(.enabled)
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Văn bản")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Đóng") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(copied ? "Đã chép" : "Sao chép") {
                        UIPasteboard.general.string = pairs.map { $0.translated }.joined(separator: "\n\n")
                        copied = true
                    }
                }
            }
        }
    }
}

/// Máy ảnh hệ thống (UIImagePickerController) để chụp 1 ảnh.
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (UIImage?) -> Void

        init(onFinish: @escaping (UIImage?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onFinish(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
