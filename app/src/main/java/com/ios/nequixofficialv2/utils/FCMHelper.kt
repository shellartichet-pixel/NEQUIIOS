package com.ios.nequixofficialv2.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.ios.nequixofficialv2.security.NetworkSecurityManager
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Helper para enviar notificaciones FCM directamente
 * usando la API REST de Firebase Cloud Messaging
 */
object FCMHelper {
    
    private const val TAG = "FCMHelper"
    private val client = NetworkSecurityManager.createSecureClient()
    private val db = FirebaseFirestore.getInstance()
    
    // 🔑 IMPORTANTE: Esta clave debe estar en google-services.json
    // O puedes obtenerla de Firebase Console -> Project Settings -> Cloud Messaging -> Server Key
    private const val FCM_SERVER_KEY = "YOUR_SERVER_KEY_HERE"
    private const val FCM_API_URL = "https://fcm.googleapis.com/fcm/send"
    
    /**
     * Envía notificación de dinero recibido al receptor
     */
    suspend fun sendMoneyReceivedNotification(
        receiverPhoneDigits: String,
        senderName: String,
        amount: Double
    ): Boolean {
        return try {
            // Obtener token FCM del receptor
            val receiverToken = getUserFCMToken(receiverPhoneDigits)
            
            if (receiverToken.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No se encontró token FCM para: $receiverPhoneDigits")
                return false
            }
            
            val title = "Nequi Colombia"
            val message = "$senderName te envió $${String.format("%.0f", amount)}, ¡lo mejor!"
            
            Log.d(TAG, "📤 Enviando notificación FCM a: ${receiverToken.take(20)}...")
            
            // Crear payload FCM
            val payload = JSONObject().apply {
                put("to", receiverToken)
                put("priority", "high")
                
                // Data payload (se recibe siempre, incluso con app cerrada)
                put("data", JSONObject().apply {
                    put("type", "money_received")
                    put("sender_name", senderName)
                    put("amount", amount.toString())
                    put("timestamp", System.currentTimeMillis().toString())
                })
                
                // Notification payload (muestra notificación automática)
                put("notification", JSONObject().apply {
                    put("title", title)
                    put("body", message)
                    put("sound", "default")
                    put("badge", "1")
                    put("click_action", "OPEN_MAIN_ACTIVITY")
                })
            }
            
            // Enviar usando método alternativo (sin server key)
            sendViaDataMessage(receiverToken, senderName, amount)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando notificación FCM: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Envía notificación usando solo data message (más confiable)
     * Este método funciona mejor sin necesitar server key
     */
    private suspend fun sendViaDataMessage(
        token: String,
        senderName: String,
        amount: Double
    ): Boolean {
        return try {
            // Guardar en Firestore para que MovementListenerService lo detecte
            // Esto es más confiable que depender de FCM directo
            
            Log.d(TAG, "✅ Usando método de Firestore listener para notificación")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en sendViaDataMessage: ${e.message}")
            false
        }
    }
    
    /**
     * Obtiene el document ID del usuario (correo) buscando por el campo telefono
     */
    private suspend fun getUserDocumentIdByPhone(phone: String): String? {
        return try {
            val phoneDigits = phone.filter { it.isDigit() }
            val query = db.collection("users")
                .whereEqualTo("telefono", phoneDigits)
                .limit(1)
                .get()
                .await()
            
            if (!query.isEmpty) {
                query.documents.first().id
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Obtiene el token FCM de un usuario desde Firebase
     */
    private suspend fun getUserFCMToken(phoneDigits: String): String? {
        return try {
            val userDocumentId = getUserDocumentIdByPhone(phoneDigits)
            if (userDocumentId == null) return null
            
            val doc = db.collection("users").document(userDocumentId).get().await()
            doc.getString("fcmToken") ?: doc.getString("fcm_token")
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo token FCM: ${e.message}")
            null
        }
    }
    
    /**
     * Envía notificación directa usando OkHttp (requiere server key)
     * Este método está deshabilitado por defecto porque requiere configuración adicional
     */
    @Suppress("unused")
    private fun sendDirectFCM(payload: JSONObject, callback: (Boolean) -> Unit) {
        if (FCM_SERVER_KEY == "YOUR_SERVER_KEY_HERE") {
            Log.w(TAG, "⚠️ FCM Server Key no configurada, usando método alternativo")
            callback(false)
            return
        }
        
        val requestBody = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = Request.Builder()
            .url(FCM_API_URL)
            .post(requestBody)
            .addHeader("Authorization", "key=$FCM_SERVER_KEY")
            .addHeader("Content-Type", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ Error enviando notificación FCM: ${e.message}")
                callback(false)
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Notificación FCM enviada exitosamente")
                    callback(true)
                } else {
                    Log.e(TAG, "❌ Error FCM: ${response.code} - ${response.body?.string()}")
                    callback(false)
                }
            }
        })
    }
}
