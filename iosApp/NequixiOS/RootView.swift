import SwiftUI

struct RootView: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        ZStack {
            switch appState.currentView {
            case .splash:
                SplashView()
                    .transition(.opacity)
                
            case .login:
                LoginView()
                    .transition(.opacity)
                
            case .pin:
                PinView(phone: appState.userPhone)
                    .transition(.opacity)
                
            case .home:
                MainTabView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: appState.currentView)
    }
}
