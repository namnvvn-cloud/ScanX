import SwiftUI

struct ContentView: View {
    @StateObject private var library = LibraryViewModel()
    @StateObject private var auth = AuthViewModel()

    var body: some View {
        HomeView(library: library, auth: auth)
    }
}
