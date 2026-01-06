import SwiftUI
import FirebaseFirestore

struct SendMoneyView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var appState: AppState
    @State private var recipientPhone: String = ""
    @State private var amount: String = ""
    @State private var message: String = ""
    @State private var recipientName: String = ""
    @State private var isSearching = false
    @State private var recipientFound = false
    @State private var showConfirmation = false
    @State private var isProcessing = false
    @State private var showComprobante = false
    @State private var transactionId = ""
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        NavigationView {
            ZStack {
                Color.white.ignoresSafeArea()
                
                VStack(spacing: 0) {
                    headerView
                    
                    ScrollView {
                        VStack(spacing: 24) {
                            recipientSection
                            
                            if recipientFound {
                                amountSection
                                messageSection
                                sendButton
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 20)
                    }
                }
            }
            .navigationBarHidden(true)
        }
        .fullScreenCover(isPresented: $showComprobante) {
            ComprobanteView(
                amount: Double(amount.filter { $0.isNumber }) ?? 0,
                recipientName: recipientName,
                recipientPhone: recipientPhone,
                transactionType: .send,
                transactionId: transactionId
            )
        }
    }
    
    private var headerView: some View {
        HStack {
            Button(action: { dismiss() }) {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(nequiPurple)
            }
            
            Spacer()
            
            Text("Enviar dinero")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(nequiPurple)
            
            Spacer()
            
            Color.clear.frame(width: 20)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .background(Color.white)
    }
    
    private var recipientSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("¿A quién le envías?")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(nequiPurple)
            
            HStack {
                TextField("Número de celular", text: $recipientPhone)
                    .keyboardType(.phonePad)
                    .onChange(of: recipientPhone) { newValue in
                        recipientPhone = String(newValue.filter { $0.isNumber }.prefix(10))
                        if recipientPhone.count == 10 {
                            searchRecipient()
                        } else {
                            recipientFound = false
                            recipientName = ""
                        }
                    }
                
                if isSearching {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle())
                }
            }
            .padding()
            .background(Color.gray.opacity(0.1))
            .cornerRadius(12)
            
            if recipientFound {
                HStack(spacing: 12) {
                    Circle()
                        .fill(nequiPurple.opacity(0.2))
                        .frame(width: 40, height: 40)
                        .overlay(
                            Text(String(recipientName.prefix(1)).uppercased())
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(nequiPurple)
                        )
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(recipientName)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(nequiPurple)
                        
                        Text(formatPhone(recipientPhone))
                            .font(.system(size: 14))
                            .foregroundColor(.gray)
                    }
                    
                    Spacer()
                    
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                }
                .padding()
                .background(Color.green.opacity(0.1))
                .cornerRadius(12)
            }
        }
    }
    
    private var amountSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("¿Cuánto le envías?")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(nequiPurple)
            
            HStack {
                Text("$")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(nequiPurple)
                
                TextField("0", text: $amount)
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(nequiPurple)
                    .keyboardType(.numberPad)
                    .onChange(of: amount) { newValue in
                        amount = formatAmount(newValue)
                    }
            }
            .padding()
            .background(Color.gray.opacity(0.1))
            .cornerRadius(12)
        }
    }
    
    private var messageSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Mensaje (opcional)")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(nequiPurple)
            
            TextField("Escribe un mensaje", text: $message)
                .padding()
                .background(Color.gray.opacity(0.1))
                .cornerRadius(12)
        }
    }
    
    private var sendButton: some View {
        Button(action: processSend) {
            if isProcessing {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(nequiPink)
                    .cornerRadius(12)
            } else {
                Text("Enviar")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(nequiPink)
                    .cornerRadius(12)
            }
        }
        .disabled(amount.isEmpty || isProcessing)
        .opacity(amount.isEmpty ? 0.6 : 1.0)
        .padding(.top, 20)
    }
    
    private func searchRecipient() {
        isSearching = true
        
        let db = Firestore.firestore()
        db.collection("users")
            .whereField("telefono", isEqualTo: recipientPhone)
            .limit(to: 1)
            .getDocuments { snapshot, error in
                isSearching = false
                
                if let doc = snapshot?.documents.first,
                   let name = doc.data()["name"] as? String {
                    recipientName = name
                    recipientFound = true
                } else {
                    recipientFound = false
                    recipientName = ""
                }
            }
    }
    
    private func processSend() {
        isProcessing = true
        transactionId = generateTransactionId()
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            isProcessing = false
            showComprobante = true
        }
    }
    
    private func formatPhone(_ phone: String) -> String {
        if phone.count == 10 {
            return "\(phone.prefix(3)) \(phone.dropFirst(3).prefix(3)) \(phone.dropFirst(6))"
        }
        return phone
    }
    
    private func formatAmount(_ input: String) -> String {
        let digits = input.filter { $0.isNumber }
        guard let number = Int(digits) else { return "" }
        
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = "."
        
        return formatter.string(from: NSNumber(value: number)) ?? digits
    }
    
    private func generateTransactionId() -> String {
        let timestamp = Int(Date().timeIntervalSince1970)
        let random = Int.random(in: 1000...9999)
        return "\(timestamp)\(random)"
    }
}
