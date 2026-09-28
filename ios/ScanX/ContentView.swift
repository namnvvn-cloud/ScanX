import SwiftUI

struct ContentView: View {
    @StateObject private var library = LibraryViewModel()
    @StateObject private var auth = AuthViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var appConfig = AppConfigManager.shared

    var body: some View {
        HomeView(library: library, auth: auth)
            .onChange(of: scenePhase) { phase in
                // Quay lại app (có thể vừa mua gói trên web) → cập nhật trạng thái Business.
                if phase == .active {
                    EntitlementsManager.shared.refreshIfStale()
                    appConfig.checkIfStale()
                }
            }
            // Bắt buộc cập nhật: màn chặn toàn app, không đóng được.
            .fullScreenCover(item: Binding(
                get: { appConfig.pendingUpdate?.forceUpdate == true ? appConfig.pendingUpdate : nil },
                set: { _ in }
            )) { info in
                ForcedUpdateView(info: info)
            }
            // Có bản mới (không bắt buộc): nhắc, cho phép "Để sau".
            .alert(
                "Đã có phiên bản mới",
                isPresented: Binding(
                    get: { appConfig.pendingUpdate.map { !$0.forceUpdate } ?? false },
                    set: { shown in if !shown && appConfig.pendingUpdate?.forceUpdate == false { appConfig.snooze() } }
                ),
                presenting: appConfig.pendingUpdate
            ) { _ in
                Button("Cập nhật ngay") { appConfig.openUpdate(); appConfig.snooze() }
                Button("Để sau", role: .cancel) { appConfig.snooze() }
            } message: { info in
                Text("Bản \(info.latestVersionName) đã sẵn sàng. \(info.releaseNotes)")
            }
    }
}
