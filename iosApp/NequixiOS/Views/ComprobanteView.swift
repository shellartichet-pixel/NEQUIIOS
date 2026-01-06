import SwiftUI
import FirebaseFirestore

struct ComprobanteView: View {
    let amount: Double
    let recipientName: String
    let recipientPhone: String
    let transactionType: TransactionType
    let transactionId: String
    
    @Environment(\.dismiss) var dismiss
    @State private var currentDate = Date()
    
    enum TransactionType {
        case send
        case receive
        case qrPayment
        case bancolombia
    }
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        ZStack {
            nequiPurple.ignoresSafeArea()
            
            VStack(spacing: 0) {
                headerSection
                
                ScrollView {
                    VStack(spacing: 20) {
                        checkmarkIcon
                        transactionInfo
                        detailsCard
                        actionButtons
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 30)
                }
            }
        }
        .navigationBarHidden(true)
    }
    
    private var headerSection: some View {
        HStack {
            Button(action: { dismiss() }) {
                Image(systemName: "xmark")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(.white)
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }
    
    private var checkmarkIcon: some View {
        ZStack {
            Circle()
                .fill(Color.green)
                .frame(width: 80, height: 80)
            
            Image(systemName: "checkmark")
                .font(.system(size: 40, weight: .bold))
                .foregroundColor(.white)
        }
    }
    
    private var transactionInfo: some View {
        VStack(spacing: 8) {
            Text(transactionTitle)
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(.white)
            
            Text(formattedAmount)
                .font(.system(size: 36, weight: .bold))
                .foregroundColor(.white)
            
            Text(recipientName)
                .font(.system(size: 16))
                .foregroundColor(.white.opacity(0.8))
        }
    }
    
    private var detailsCard: some View {
        VStack(spacing: 16) {
            DetailRow(label: "Fecha", value: formattedDate)
            DetailRow(label: "Hora", value: formattedTime)
            DetailRow(label: "No. de transacción", value: transactionId)
            
            if !recipientPhone.isEmpty {
                DetailRow(label: "Teléfono", value: formatPhone(recipientPhone))
            }
        }
        .padding(20)
        .background(Color.white.opacity(0.1))
        .cornerRadius(12)
    }
    
    private var actionButtons: some View {
        VStack(spacing: 12) {
            Button(action: shareReceipt) {
                HStack {
                    Image(systemName: "square.and.arrow.up")
                    Text("Compartir")
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(nequiPurple)
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color.white)
                .cornerRadius(12)
            }
            
            Button(action: { dismiss() }) {
                Text("Listo")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(nequiPink)
                    .cornerRadius(12)
            }
        }
        .padding(.top, 20)
    }
    
    private var transactionTitle: String {
        switch transactionType {
        case .send: return "Enviaste"
        case .receive: return "Recibiste"
        case .qrPayment: return "Pagaste"
        case .bancolombia: return "Enviaste a Bancolombia"
        }
    }
    
    private var formattedAmount: String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale(identifier: "es_CO")
        formatter.currencySymbol = "$"
        return formatter.string(from: NSNumber(value: amount)) ?? "$0"
    }
    
    private var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd/MM/yyyy"
        return formatter.string(from: currentDate)
    }
    
    private var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: currentDate)
    }
    
    private func formatPhone(_ phone: String) -> String {
        let digits = phone.filter { $0.isNumber }
        if digits.count == 10 {
            return "\(digits.prefix(3)) \(digits.dropFirst(3).prefix(3)) \(digits.dropFirst(6))"
        }
        return phone
    }
    
    private func shareReceipt() {
        let text = """
        Comprobante Nequi
        \(transactionTitle): \(formattedAmount)
        Para: \(recipientName)
        Fecha: \(formattedDate) \(formattedTime)
        No. transacción: \(transactionId)
        """
        
        let activityVC = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let window = windowScene.windows.first,
           let rootVC = window.rootViewController {
            rootVC.present(activityVC, animated: true)
        }
    }
}

struct DetailRow: View {
    let label: String
    let value: String
    
    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(.white.opacity(0.7))
            Spacer()
            Text(value)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.white)
        }
    }
}
