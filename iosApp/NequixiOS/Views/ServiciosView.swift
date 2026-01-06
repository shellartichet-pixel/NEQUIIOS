import SwiftUI

struct ServiciosView: View {
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        VStack(spacing: 0) {
            headerView
            
            ScrollView {
                VStack(spacing: 24) {
                    quickActions
                    allServices
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
            }
        }
    }
    
    private var headerView: some View {
        HStack {
            Text("Servicios")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(nequiPurple)
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }
    
    private var quickActions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Acciones rápidas")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(nequiPurple)
            
            LazyVGrid(columns: [
                GridItem(.flexible()),
                GridItem(.flexible()),
                GridItem(.flexible()),
                GridItem(.flexible())
            ], spacing: 16) {
                ServiceItem(icon: "phone.fill", title: "Recargas")
                ServiceItem(icon: "bolt.fill", title: "Servicios")
                ServiceItem(icon: "building.2.fill", title: "Bancos")
                ServiceItem(icon: "qrcode", title: "QR")
            }
        }
    }
    
    private var allServices: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Todos los servicios")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(nequiPurple)
            
            VStack(spacing: 0) {
                ServiceRow(icon: "phone.fill", title: "Recarga de celular", subtitle: "Claro, Movistar, Tigo, WOM")
                ServiceRow(icon: "bolt.fill", title: "Pago de servicios", subtitle: "Luz, agua, gas, internet")
                ServiceRow(icon: "building.2.fill", title: "Enviar a otros bancos", subtitle: "Bancolombia, Davivienda, BBVA")
                ServiceRow(icon: "bed.double.fill", title: "Colchón", subtitle: "Ahorra para tus metas")
                ServiceRow(icon: "bag.fill", title: "Bolsillos", subtitle: "Organiza tu plata")
                ServiceRow(icon: "creditcard.fill", title: "Tarjeta Nequi", subtitle: "Pide tu tarjeta física")
                ServiceRow(icon: "dollarsign.circle.fill", title: "Créditos", subtitle: "Préstamos rápidos")
            }
            .background(Color.white)
            .cornerRadius(12)
            .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
        }
    }
}

struct ServiceItem: View {
    let icon: String
    let title: String
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(nequiPurple.opacity(0.1))
                    .frame(width: 50, height: 50)
                
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundColor(nequiPurple)
            }
            
            Text(title)
                .font(.system(size: 12))
                .foregroundColor(nequiPurple)
                .multilineTextAlignment(.center)
        }
    }
}

struct ServiceRow: View {
    let icon: String
    let title: String
    let subtitle: String
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        Button(action: {}) {
            HStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(nequiPurple.opacity(0.1))
                        .frame(width: 44, height: 44)
                    
                    Image(systemName: icon)
                        .font(.system(size: 20))
                        .foregroundColor(nequiPurple)
                }
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(nequiPurple)
                    
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundColor(.gray)
                }
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            .padding()
        }
        
        Divider()
            .padding(.leading, 76)
    }
}
