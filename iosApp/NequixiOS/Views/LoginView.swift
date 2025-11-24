import SwiftUI
import FirebaseAuth
import FirebaseFirestore

struct LoginView: View {
    @EnvironmentObject var appState: AppState
    @State private var phoneNumber: String = ""
    @State private var isLoading: Bool = false
    @State private var errorMessage: String = ""
    @State private var showError: Bool = false
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        ZStack {
            LinearGradient(
                gradient: Gradient(colors: [nequiPurple, nequiPurple.opacity(0.8)]),
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            
            VStack(spacing: 30) {
                Spacer()
                
                VStack(spacing: 10) {
                    Image(systemName: "circle.hexagongrid.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.white)
                    
                    Text("Nequi")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                }
                
                Spacer()
                
                VStack(spacing: 20) {
                    TextField("Ingresa tu número", text: $phoneNumber)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .padding()
                        .background(Color.white.opacity(0.9))
                        .cornerRadius(12)
                        .onChange(of: phoneNumber) { newValue in
                            phoneNumber = formatPhoneNumber(newValue)
                        }
                    
                    Button(action: handleLogin) {
                        if isLoading {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(nequiPink)
                                .cornerRadius(12)
                        } else {
                            Text("Continuar")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(nequiPink)
                                .cornerRadius(12)
                        }
                    }
                    .disabled(phoneNumber.count < 10 || isLoading)
                    .opacity(phoneNumber.count < 10 ? 0.6 : 1.0)
                }
                .padding(.horizontal, 30)
                
                Spacer()
                    .frame(height: 100)
            }
        }
        .alert("Error", isPresented: $showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }
    
    private func formatPhoneNumber(_ input: String) -> String {
        let digits = input.filter { $0.isNumber }
        let maxLength = 12
        
        if digits.count > maxLength {
            return String(digits.prefix(maxLength))
        }
        
        if digits.count == 10 {
            return String(format: "%@ %@ %@", 
                         String(digits.prefix(3)),
                         String(digits.dropFirst(3).prefix(3)),
                         String(digits.dropFirst(6)))
        }
        
        return digits
    }
    
    private func handleLogin() {
        guard phoneNumber.count >= 10 else { return }
        
        isLoading = true
        errorMessage = ""
        
        let phoneDigits = phoneNumber.filter { $0.isNumber }
        
        Task {
            do {
                let documentId = try await getUserDocumentIdByPhone(phoneDigits)
                
                await MainActor.run {
                    if let docId = documentId {
                        signInAnonymously(phone: phoneDigits, documentId: docId)
                    } else {
                        isLoading = false
                        errorMessage = "No se encontró un usuario con este número"
                        showError = true
                    }
                }
            } catch {
                await MainActor.run {
                    isLoading = false
                    errorMessage = "Error de conexión. Intenta de nuevo."
                    showError = true
                }
            }
        }
    }
    
    private func signInAnonymously(phone: String, documentId: String) {
        Auth.auth().signInAnonymously { result, error in
            if error != nil {
                isLoading = false
                errorMessage = "Error de autenticación"
                showError = true
                return
            }
            
            if let user = result?.user {
                let changeRequest = user.createProfileChangeRequest()
                changeRequest.displayName = phone
                changeRequest.commitChanges { _ in
                    DispatchQueue.main.async {
                        appState.setUserPhone(phone)
                        appState.userDocumentId = documentId
                        appState.currentView = .pin
                        isLoading = false
                    }
                }
            }
        }
    }
    
    private func getUserDocumentIdByPhone(_ phone: String) async throws -> String? {
        let phoneDigits = phone.filter { $0.isNumber }
        let db = Firestore.firestore()
        
        let querySnapshot = try await db.collection("users")
            .whereField("telefono", isEqualTo: phoneDigits)
            .limit(to: 1)
            .getDocuments()
        
        return querySnapshot.documents.first?.documentID
    }
}

