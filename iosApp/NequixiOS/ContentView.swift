import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        RootView()
            .environmentObject(appState)
    }
}

