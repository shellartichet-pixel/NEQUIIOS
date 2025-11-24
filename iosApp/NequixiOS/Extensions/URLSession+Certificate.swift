import Foundation

extension URLSession {
    /// Crea una configuración de URLSession que confía en el certificado mitmproxy
    static func createWithMitmProxyCertificate() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.default
        
        // Cargar el certificado desde el bundle
        guard let certPath = Bundle.main.path(forResource: "mitmproxy-ca-cert", ofType: "pem") ??
              Bundle.main.path(forResource: "mitmproxy-ca-cert (1)", ofType: "pem") else {
            print("⚠️ Certificado mitmproxy no encontrado en el bundle - usando configuración por defecto")
            return config
        }
        
        guard let certData = NSData(contentsOfFile: certPath) else {
            print("⚠️ No se pudo leer el certificado desde: \(certPath)")
            return config
        }
        
        // Verificar que el certificado no sea un placeholder vacío
        let certString = String(data: certData as Data, encoding: .utf8) ?? ""
        if certString.contains("Placeholder") || certString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            print("⚠️ Certificado mitmproxy es un placeholder - usando configuración por defecto")
            return config
        }
        
        guard let certificate = SecCertificateCreateWithData(nil, certData) else {
            print("⚠️ No se pudo crear el certificado desde los datos")
            return config
        }
        
        // Configurar el certificado en la configuración de la sesión
        // Nota: En iOS, los certificados CA personalizados se manejan a través de
        // URLSessionDelegate o configurando el trust store del sistema
        print("✅ Certificado mitmproxy cargado correctamente")
        
        return config
    }
    
    /// Crea un URLSession con el certificado mitmproxy configurado
    static func createSecureSession() -> URLSession {
        let config = createWithMitmProxyCertificate()
        let delegate = CertificateTrustDelegate()
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }
}

/// Delegate para manejar la confianza en certificados
class CertificateTrustDelegate: NSObject, URLSessionDelegate {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        
        // Cargar el certificado mitmproxy
        guard let certPath = Bundle.main.path(forResource: "mitmproxy-ca-cert", ofType: "pem") ??
              Bundle.main.path(forResource: "mitmproxy-ca-cert (1)", ofType: "pem") else {
            // Si no hay certificado, usar el comportamiento por defecto
            completionHandler(.performDefaultHandling, nil)
            return
        }
        
        guard let certData = NSData(contentsOfFile: certPath) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        
        // Verificar que el certificado no sea un placeholder
        let certString = String(data: certData as Data, encoding: .utf8) ?? ""
        if certString.contains("Placeholder") || certString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Es un placeholder, usar comportamiento por defecto
            completionHandler(.performDefaultHandling, nil)
            return
        }
        
        guard let certificate = SecCertificateCreateWithData(nil, certData) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        
        // Verificar si el certificado del servidor es confiable
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            if let serverTrust = challenge.protectionSpace.serverTrust {
                // Agregar el certificado mitmproxy al trust store
                SecTrustSetAnchorCertificates(serverTrust, [certificate] as CFArray)
                SecTrustSetAnchorCertificatesOnly(serverTrust, false) // También confiar en certificados del sistema
                
                var error: CFError?
                let trustResult = SecTrustEvaluateWithError(serverTrust, &error)
                
                if trustResult {
                    let credential = URLCredential(trust: serverTrust)
                    completionHandler(.useCredential, credential)
                    return
                }
            }
        }
        
        // Si falla, usar el comportamiento por defecto
        completionHandler(.performDefaultHandling, nil)
    }
}

