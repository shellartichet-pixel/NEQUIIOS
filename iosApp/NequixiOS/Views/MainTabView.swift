import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    @State private var selectedTab = 0
    @State private var showSendMoney = false
    @State private var showQRScanner = false
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        ZStack {
            TabView(selection: $selectedTab) {
                HomeView()
                    .tabItem {
                        Image(systemName: "house.fill")
                        Text("Inicio")
                    }
                    .tag(0)
                
                MovementsView()
                    .tabItem {
                        Image(systemName: "clock.fill")
                        Text("Movimientos")
                    }
                    .tag(1)
                
                Color.clear
                    .tabItem {
                        Image(systemName: "plus.circle.fill")
                        Text("")
                    }
                    .tag(2)
                
                ServiciosView()
                    .tabItem {
                        Image(systemName: "square.grid.2x2.fill")
                        Text("Servicios")
                    }
                    .tag(3)
                
                ProfileView()
                    .tabItem {
                        Image(systemName: "person.fill")
                        Text("Perfil")
                    }
                    .tag(4)
            }
            .accentColor(nequiPink)
            
            VStack {
                Spacer()
                
                HStack {
                    Spacer()
                    
                    Button(action: {
                        showActionMenu()
                    }) {
                        Image(systemName: "plus")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 56, height: 56)
                            .background(
                                LinearGradient(
                                    gradient: Gradient(colors: [nequiPink, Color(hex: "ff0081")]),
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .clipShape(Circle())
                            .shadow(color: nequiPurple.opacity(0.3), radius: 4, x: 0, y: 2)
                    }
                    
                    Spacer()
                }
                .padding(.bottom, 60)
            }
        }
        .sheet(isPresented: $showSendMoney) {
            SendMoneyView()
                .environmentObject(appState)
        }
        .fullScreenCover(isPresented: $showQRScanner) {
            QRScannerView()
                .environmentObject(appState)
        }
    }
    
    private func showActionMenu() {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        
        alert.addAction(UIAlertAction(title: "Enviar dinero", style: .default) { _ in
            showSendMoney = true
        })
        
        alert.addAction(UIAlertAction(title: "Escanear QR", style: .default) { _ in
            showQRScanner = true
        })
        
        alert.addAction(UIAlertAction(title: "Pedir dinero", style: .default) { _ in
        })
        
        alert.addAction(UIAlertAction(title: "Recargar", style: .default) { _ in
        })
        
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel))
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let window = windowScene.windows.first,
           let rootVC = window.rootViewController {
            rootVC.present(alert, animated: true)
        }
    }
}

struct ProfileView: View {
    @EnvironmentObject var appState: AppState
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        VStack(spacing: 0) {
            headerView
            
            ScrollView {
                VStack(spacing: 16) {
                    profileCard
                    menuItems
                    logoutButton
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
            }
        }
    }
    
    private var headerView: some View {
        HStack {
            Text("Mi perfil")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(nequiPurple)
            
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }
    
    private var profileCard: some View {
        HStack(spacing: 16) {
            Circle()
                .fill(nequiPurple.opacity(0.2))
                .frame(width: 60, height: 60)
                .overlay(
                    Image(systemName: "person.fill")
                        .font(.system(size: 28))
                        .foregroundColor(nequiPurple)
                )
            
            VStack(alignment: .leading, spacing: 4) {
                Text("Usuario Nequi")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(nequiPurple)
                
                Text(formatPhone(appState.userPhone))
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            
            Spacer()
            
            Image(systemName: "chevron.right")
                .foregroundColor(.gray)
        }
        .padding()
        .background(Color.white)
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
    }
    
    private var menuItems: some View {
        VStack(spacing: 0) {
            ProfileMenuItem(icon: "bell.fill", title: "Notificaciones")
            ProfileMenuItem(icon: "lock.fill", title: "Seguridad")
            ProfileMenuItem(icon: "questionmark.circle.fill", title: "Ayuda")
            ProfileMenuItem(icon: "doc.text.fill", title: "Términos y condiciones")
        }
        .background(Color.white)
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
    }
    
    private var logoutButton: some View {
        Button(action: {
            appState.logout()
        }) {
            HStack {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                Text("Cerrar sesión")
            }
            .font(.system(size: 16, weight: .medium))
            .foregroundColor(.red)
            .frame(maxWidth: .infinity)
            .padding()
            .background(Color.white)
            .cornerRadius(12)
            .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
        }
        .padding(.top, 20)
    }
    
    private func formatPhone(_ phone: String) -> String {
        let digits = phone.filter { $0.isNumber }
        if digits.count == 10 {
            return "\(digits.prefix(3)) \(digits.dropFirst(3).prefix(3)) \(digits.dropFirst(6))"
        }
        return phone
    }
}

struct ProfileMenuItem: View {
    let icon: String
    let title: String
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        Button(action: {}) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(nequiPurple)
                    .frame(width: 30)
                
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(nequiPurple)
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            .padding()
        }
        
        Divider()
            .padding(.leading, 60)
    }
}
