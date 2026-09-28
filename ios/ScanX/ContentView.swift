import SwiftUI

struct ContentView: View {
    @StateObject private var library = LibraryViewModel()
    @StateObject private var auth = AuthViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        HomeView(library: library, auth: auth)
            .onChange(of: scenePhase) { phase in
                // Quay lại app (có thể vừa mua gói trên web) → cập nhật trạng thái Business.
                if phase == .active { EntitlementsManager.shared.refreshIfStale() }
            }
    }
}
