import SwiftUI
import AVFoundation

struct QRScannerView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var appState: AppState
    @State private var scannedCode: String = ""
    @State private var isScanning = true
    @State private var showPaymentSheet = false
    @State private var qrData: QRData?
    
    private let nequiPurple = Color(hex: "200020")
    
    var body: some View {
        ZStack {
            CameraPreview(scannedCode: $scannedCode, isScanning: $isScanning)
                .ignoresSafeArea()
            
            VStack {
                headerView
                Spacer()
                scanFrame
                Spacer()
                bottomControls
            }
        }
        .onChange(of: scannedCode) { newValue in
            if !newValue.isEmpty {
                processQRCode(newValue)
            }
        }
        .sheet(isPresented: $showPaymentSheet) {
            if let data = qrData {
                QRPaymentSheet(qrData: data, onDismiss: {
                    showPaymentSheet = false
                    isScanning = true
                    scannedCode = ""
                })
                .environmentObject(appState)
            }
        }
    }
    
    private var headerView: some View {
        HStack {
            Button(action: { dismiss() }) {
                Image(systemName: "xmark")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(.white)
                    .padding(12)
                    .background(Color.black.opacity(0.5))
                    .clipShape(Circle())
            }
            Spacer()
            
            Text("Escanea el código QR")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(.white)
            
            Spacer()
            
            Button(action: {}) {
                Image(systemName: "flashlight.off.fill")
                    .font(.system(size: 20))
                    .foregroundColor(.white)
                    .padding(12)
                    .background(Color.black.opacity(0.5))
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 60)
    }
    
    private var scanFrame: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.white, lineWidth: 3)
                .frame(width: 250, height: 250)
            
            VStack {
                HStack {
                    CornerShape().stroke(Color.white, lineWidth: 4)
                        .frame(width: 30, height: 30)
                    Spacer()
                    CornerShape().stroke(Color.white, lineWidth: 4)
                        .frame(width: 30, height: 30)
                        .rotationEffect(.degrees(90))
                }
                Spacer()
                HStack {
                    CornerShape().stroke(Color.white, lineWidth: 4)
                        .frame(width: 30, height: 30)
                        .rotationEffect(.degrees(-90))
                    Spacer()
                    CornerShape().stroke(Color.white, lineWidth: 4)
                        .frame(width: 30, height: 30)
                        .rotationEffect(.degrees(180))
                }
            }
            .frame(width: 250, height: 250)
        }
    }
    
    private var bottomControls: some View {
        VStack(spacing: 20) {
            Text("Apunta la cámara al código QR")
                .font(.system(size: 14))
                .foregroundColor(.white)
            
            Button(action: { dismiss() }) {
                Text("Cancelar")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 40)
                    .padding(.vertical, 12)
                    .background(Color.white.opacity(0.2))
                    .cornerRadius(25)
            }
        }
        .padding(.bottom, 50)
    }
    
    private func processQRCode(_ code: String) {
        isScanning = false
        
        if let data = parseQRCode(code) {
            qrData = data
            showPaymentSheet = true
        } else {
            isScanning = true
            scannedCode = ""
        }
    }
    
    private func parseQRCode(_ code: String) -> QRData? {
        if code.contains("nequi") || code.contains("bancolombia") {
            let components = code.components(separatedBy: "|")
            if components.count >= 2 {
                return QRData(
                    merchantName: components[0],
                    merchantId: components.count > 1 ? components[1] : "",
                    amount: components.count > 2 ? Double(components[2]) : nil
                )
            }
        }
        
        return QRData(merchantName: "Comercio", merchantId: code, amount: nil)
    }
}

struct QRData {
    let merchantName: String
    let merchantId: String
    let amount: Double?
}

struct CornerShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 0, y: rect.height))
        path.addLine(to: CGPoint(x: 0, y: 0))
        path.addLine(to: CGPoint(x: rect.width, y: 0))
        return path
    }
}

struct CameraPreview: UIViewRepresentable {
    @Binding var scannedCode: String
    @Binding var isScanning: Bool
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: UIScreen.main.bounds)
        
        let captureSession = AVCaptureSession()
        
        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else { return view }
        let videoInput: AVCaptureDeviceInput
        
        do {
            videoInput = try AVCaptureDeviceInput(device: videoCaptureDevice)
        } catch {
            return view
        }
        
        if captureSession.canAddInput(videoInput) {
            captureSession.addInput(videoInput)
        } else {
            return view
        }
        
        let metadataOutput = AVCaptureMetadataOutput()
        
        if captureSession.canAddOutput(metadataOutput) {
            captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(context.coordinator, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        } else {
            return view
        }
        
        let previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        
        DispatchQueue.global(qos: .userInitiated).async {
            captureSession.startRunning()
        }
        
        context.coordinator.captureSession = captureSession
        
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var parent: CameraPreview
        var captureSession: AVCaptureSession?
        
        init(_ parent: CameraPreview) {
            self.parent = parent
        }
        
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard parent.isScanning else { return }
            
            if let metadataObject = metadataObjects.first {
                guard let readableObject = metadataObject as? AVMetadataMachineReadableCodeObject else { return }
                guard let stringValue = readableObject.stringValue else { return }
                
                AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
                parent.scannedCode = stringValue
            }
        }
    }
}

struct QRPaymentSheet: View {
    let qrData: QRData
    let onDismiss: () -> Void
    @EnvironmentObject var appState: AppState
    @State private var amount: String = ""
    @State private var isProcessing = false
    
    private let nequiPurple = Color(hex: "200020")
    private let nequiPink = Color(hex: "da0081")
    
    var body: some View {
        NavigationView {
            VStack(spacing: 30) {
                VStack(spacing: 10) {
                    Image(systemName: "storefront.fill")
                        .font(.system(size: 50))
                        .foregroundColor(nequiPurple)
                    
                    Text(qrData.merchantName)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(nequiPurple)
                }
                .padding(.top, 30)
                
                if let fixedAmount = qrData.amount {
                    Text(formatCurrency(fixedAmount))
                        .font(.system(size: 36, weight: .bold))
                        .foregroundColor(nequiPurple)
                } else {
                    TextField("$ 0", text: $amount)
                        .font(.system(size: 36, weight: .bold))
                        .foregroundColor(nequiPurple)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .padding()
                        .background(Color.gray.opacity(0.1))
                        .cornerRadius(12)
                        .padding(.horizontal, 40)
                }
                
                Spacer()
                
                Button(action: processPayment) {
                    if isProcessing {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    } else {
                        Text("Pagar")
                            .font(.system(size: 18, weight: .bold))
                    }
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding()
                .background(nequiPink)
                .cornerRadius(12)
                .padding(.horizontal, 20)
                .disabled(isProcessing || (qrData.amount == nil && amount.isEmpty))
                
                Button(action: onDismiss) {
                    Text("Cancelar")
                        .font(.system(size: 16))
                        .foregroundColor(nequiPurple)
                }
                .padding(.bottom, 30)
            }
            .navigationBarHidden(true)
        }
    }
    
    private func formatCurrency(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale(identifier: "es_CO")
        formatter.currencySymbol = "$"
        return formatter.string(from: NSNumber(value: value)) ?? "$0"
    }
    
    private func processPayment() {
        isProcessing = true
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            isProcessing = false
            onDismiss()
        }
    }
}
