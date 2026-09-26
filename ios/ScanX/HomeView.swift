import SwiftUI

/// Màn Trang chủ rỗng (placeholder) — bước 1 của kế hoạch build iOS.
/// Các bước sau sẽ thêm: quét tài liệu (VisionKit), OCR (Vision), xuất PDF/Word/Excel/PowerPoint, dịch.
struct HomeView: View {
    @ObservedObject var viewModel: AuthViewModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()

                Text("Trang chủ ScanX")
                    .font(.title2.bold())

                if let email = viewModel.currentEmail {
                    Text(email)
                        .foregroundStyle(.secondary)
                }

                Text("Tính năng quét tài liệu, OCR, xuất PDF/Word/Excel/PowerPoint và dịch sẽ được thêm ở các bước tiếp theo.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 24)

                if let status = viewModel.backupStatus {
                    Text(status)
                        .font(.footnote)
                        .foregroundStyle(.green)
                }

                if let error = viewModel.errorMessage {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                Spacer()

                Button("Đăng xuất") {
                    viewModel.signOut()
                }
                .buttonStyle(.bordered)
            }
            .padding()
            .navigationTitle("ScanX")
        }
    }
}

#Preview {
    HomeView(viewModel: AuthViewModel())
}
