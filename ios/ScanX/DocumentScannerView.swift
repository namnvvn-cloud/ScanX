import SwiftUI
import VisionKit

/// Bọc bộ quét tài liệu gốc của Apple (VisionKit) — tương đương Google ML Kit Document Scanner
/// bên Android: tự bắt cạnh, tự chụp, nhiều trang, cắt/xoay trong UI của hệ thống.
struct DocumentScannerView: UIViewControllerRepresentable {
    let onFinish: ([URL]) -> Void
    let onCancel: () -> Void
    let onError: (Error) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let parent: DocumentScannerView

        init(parent: DocumentScannerView) {
            self.parent = parent
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFinishWith scan: VNDocumentCameraScan
        ) {
            // Ghi từng trang ra file JPEG tạm ngay lập tức — không giữ nhiều ảnh 12 MP trong RAM.
            let tempDir = FileManager.default.temporaryDirectory
                .appendingPathComponent("scan_\(UUID().uuidString)", isDirectory: true)
            try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
            var files: [URL] = []
            for index in 0..<scan.pageCount {
                autoreleasepool {
                    let image = scan.imageOfPage(at: index)
                    guard let data = image.jpegData(compressionQuality: 0.92) else { return }
                    let url = tempDir.appendingPathComponent(String(format: "page_%03d.jpg", index + 1))
                    if (try? data.write(to: url)) != nil {
                        files.append(url)
                    }
                }
            }
            parent.onFinish(files)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.onCancel()
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFailWithError error: Error
        ) {
            parent.onError(error)
        }
    }
}
