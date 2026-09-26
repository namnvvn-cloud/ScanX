import SwiftUI
import UIKit

/// Bảng chia sẻ hệ thống (UIActivityViewController) cho file đã dựng xong.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct ExportedFile: Identifiable {
    let id = UUID()
    let url: URL
}
