import SwiftUI
import FirebaseFirestore

struct MovementsView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var viewModel = MovementsViewModel()
    @State private var searchText = ""
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        VStack(spacing: 0) {
            headerView
            searchBar
            
            if viewModel.isLoading {
                loadingView
            } else if viewModel.movements.isEmpty {
                emptyView
            } else {
                movementsList
            }
        }
        .onAppear {
            viewModel.loadMovements(documentId: appState.userDocumentId)
        }
    }
    
    private var headerView: some View {
        HStack {
            Text("Movimientos")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(nequiPurple)
            
            Spacer()
            
            Button(action: {}) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                    .font(.system(size: 22))
                    .foregroundColor(nequiPurple)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }
    
    private var searchBar: some View {
        HStack {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.gray)
            
            TextField("Buscar movimiento", text: $searchText)
        }
        .padding(12)
        .background(Color.gray.opacity(0.1))
        .cornerRadius(10)
        .padding(.horizontal, 20)
        .padding(.bottom, 10)
    }
    
    private var loadingView: some View {
        VStack {
            Spacer()
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle())
            Text("Cargando movimientos...")
                .font(.system(size: 14))
                .foregroundColor(.gray)
                .padding(.top, 10)
            Spacer()
        }
    }
    
    private var emptyView: some View {
        VStack(spacing: 20) {
            Spacer()
            
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 60))
                .foregroundColor(.gray.opacity(0.5))
            
            Text("No tienes movimientos")
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(.gray)
            
            Text("Aquí verás tus transacciones")
                .font(.system(size: 14))
                .foregroundColor(.gray.opacity(0.8))
            
            Spacer()
        }
    }
    
    private var movementsList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filteredMovements) { movement in
                    MovementRow(movement: movement)
                    
                    Divider()
                        .padding(.horizontal, 20)
                }
            }
        }
    }
    
    private var filteredMovements: [Movement] {
        if searchText.isEmpty {
            return viewModel.movements
        }
        return viewModel.movements.filter {
            $0.name.lowercased().contains(searchText.lowercased()) ||
            $0.description.lowercased().contains(searchText.lowercased())
        }
    }
}

struct MovementRow: View {
    let movement: Movement
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(movement.isIncoming ? Color.green.opacity(0.2) : Color.red.opacity(0.2))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: movement.isIncoming ? "arrow.down.left" : "arrow.up.right")
                        .font(.system(size: 18))
                        .foregroundColor(movement.isIncoming ? .green : .red)
                )
            
            VStack(alignment: .leading, spacing: 4) {
                Text(movement.name)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(nequiPurple)
                    .lineLimit(1)
                
                Text(movement.description)
                    .font(.system(size: 13))
                    .foregroundColor(.gray)
                    .lineLimit(1)
            }
            
            Spacer()
            
            VStack(alignment: .trailing, spacing: 4) {
                Text(movement.formattedAmount)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(movement.isIncoming ? .green : nequiPurple)
                
                Text(movement.formattedDate)
                    .font(.system(size: 12))
                    .foregroundColor(.gray)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }
}

struct Movement: Identifiable {
    let id: String
    let name: String
    let description: String
    let amount: Double
    let isIncoming: Bool
    let date: Date
    let type: MovementType
    
    enum MovementType: String {
        case send = "SEND"
        case receive = "RECEIVE"
        case qrPayment = "QR_PAYMENT"
        case bancolombia = "BANCOLOMBIA"
    }
    
    var formattedAmount: String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale(identifier: "es_CO")
        formatter.currencySymbol = isIncoming ? "+$" : "-$"
        return formatter.string(from: NSNumber(value: abs(amount))) ?? "$0"
    }
    
    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd MMM"
        formatter.locale = Locale(identifier: "es_CO")
        return formatter.string(from: date)
    }
}

class MovementsViewModel: ObservableObject {
    @Published var movements: [Movement] = []
    @Published var isLoading = false
    
    private var listener: ListenerRegistration?
    
    func loadMovements(documentId: String) {
        guard !documentId.isEmpty else { return }
        
        isLoading = true
        
        let db = Firestore.firestore()
        listener = db.collection("users").document(documentId)
            .collection("movements")
            .order(by: "timestamp", descending: true)
            .limit(to: 50)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self = self else { return }
                
                self.isLoading = false
                
                guard let documents = snapshot?.documents else { return }
                
                self.movements = documents.compactMap { doc -> Movement? in
                    let data = doc.data()
                    
                    guard let name = data["name"] as? String,
                          let amount = data["amount"] as? Double else {
                        return nil
                    }
                    
                    let isIncoming = data["isIncoming"] as? Bool ?? false
                    let description = data["description"] as? String ?? ""
                    let typeString = data["type"] as? String ?? "SEND"
                    let type = Movement.MovementType(rawValue: typeString) ?? .send
                    
                    var date = Date()
                    if let timestamp = data["timestamp"] as? Timestamp {
                        date = timestamp.dateValue()
                    }
                    
                    return Movement(
                        id: doc.documentID,
                        name: name,
                        description: description,
                        amount: amount,
                        isIncoming: isIncoming,
                        date: date,
                        type: type
                    )
                }
            }
    }
    
    deinit {
        listener?.remove()
    }
}
