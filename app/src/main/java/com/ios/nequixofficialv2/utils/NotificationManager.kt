package com.ios.nequixofficialv2.utils
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.ios.nequixofficialv2.R
import com.ios.nequixofficialv2.HomeActivity
import kotlinx.coroutines.tasks.await

class NotificationManager(private val context: Context) {
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    
    companion object {
        private const val CHANNEL_ID = "nequi_money_transfers"
        private const val CHANNEL_NAME = "Transferencias de Dinero"
        private const val NOTIFICATION_ID_RECEIVED = 1001
        private const val NOTIFICATION_ID_SENT = 1002
    }
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Envía notificación local al receptor (llamado desde ComprobanteActivity)
     * ⚠️ SOLO SE LLAMA UNA VEZ desde saveIncomingMovementForRecipient
     */
    suspend fun sendMoneyReceivedNotification(
        receiverPhone: String,
        senderName: String,
        amount: String
    ): Boolean {
        return try {
            val title = "Nequi Colombia"
            val message = "$senderName te envió $amount, ¡lo mejor!"
            
            Log.d("NotificationManager", "📲 Enviando notificación local al receptor")
            
            // 🔔 MOSTRAR NOTIFICACIÓN LOCAL INMEDIATAMENTE
            // (El receptor verá esto en su dispositivo)
            showLocalNotification(title, message, NOTIFICATION_ID_RECEIVED)
            
            Log.d("NotificationManager", "✅ Notificación local mostrada")
            return true
        } catch (e: Exception) {
            Log.e("NotificationManager", "❌ Error mostrando notificación: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Envía una notificación cuando un usuario envía dinero (confirmación)
     */
    suspend fun sendMoneySentNotification(
        senderPhone: String,
        receiverName: String,
        amount: String
    ): Boolean {
        return try {
            val title = "Envío exitoso"
            val message = "Enviaste $amount a $receiverName exitosamente"
            
            // Mostrar notificación local directamente
            showLocalNotification(title, message, NOTIFICATION_ID_SENT)
            
            true
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error enviando notificación: ${e.message}")
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
    private suspend fun getUserFCMToken(phone: String): String? {
        return try {
            val userDocumentId = getUserDocumentIdByPhone(phone)
            if (userDocumentId == null) return null
            
            val doc = db.collection("users").document(userDocumentId).get().await()
            // Intentar ambos nombres de campo (fcmToken y fcm_token)
            doc.getString("fcmToken") ?: doc.getString("fcm_token")
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error obteniendo token FCM: ${e.message}")
            null
        }
    }
    
    /**
     * Actualiza el token FCM del usuario actual en Firebase
     */
    suspend fun updateUserFCMToken(phone: String, token: String) {
        try {
            val userDocumentId = getUserDocumentIdByPhone(phone)
            if (userDocumentId != null) {
                db.collection("users").document(userDocumentId)
                    .update("fcmToken", token)  // Usar fcmToken para consistencia
                    .await()
                Log.d("NotificationManager", "✅ Token FCM actualizado para $phone (doc: $userDocumentId): $token")
            }
        } catch (e: Exception) {
            Log.e("NotificationManager", "❌ Error actualizando token FCM: ${e.message}")
        }
    }
    
    /**
     * Envía la notificación usando Cloud Functions
     */
    
    /**
     * Registra el token FCM actual del usuario
     * CRÍTICO: Esto permite recibir notificaciones con la app cerrada
     */
    fun registerFCMToken(phone: String) {
        // Obtener token actual de FCM
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("NotificationManager", "❌ Error obteniendo token FCM", task.exception)
                    return@addOnCompleteListener
                }
                
                val token = task.result
                Log.d("NotificationManager", "🔑 Token FCM obtenido: $token")
                
                // Guardar en SharedPreferences
                val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("fcm_token", token).apply()
                
                // Actualizar en Firebase (usar fcmToken para consistencia)
                Thread {
                    try {
                        kotlinx.coroutines.runBlocking {
                            val userDocumentId = getUserDocumentIdByPhone(phone)
                            if (userDocumentId != null) {
                                db.collection("users").document(userDocumentId)
                                    .update("fcmToken", token)
                                    .addOnSuccessListener {
                                        Log.d("NotificationManager", "✅ Token FCM registrado en Firebase para $phone (doc: $userDocumentId)")
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("NotificationManager", "❌ Error actualizando token en Firebase: ${e.message}")
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NotificationManager", "❌ Error registrando token: ${e.message}")
                    }
                }.start()
            }
    }
    
    /**
     * Obtiene el token FCM actual de forma síncrona
     */
    suspend fun getCurrentFCMToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error obteniendo token FCM: ${e.message}")
            null
        }
    }
    
    /**
     * Envía una notificación de prueba local (para testing)
     */
    suspend fun sendTestNotification(phone: String, testMessage: String = "¡Prueba de notificación!"): Boolean {
        return try {
            // Mostrar notificación local de prueba
            showLocalNotification("Nequi Test", testMessage, System.currentTimeMillis().toInt())
            true
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error enviando notificación de prueba: ${e.message}")
            false
        }
    }
    /**
     * Convierte un drawable a Bitmap para usar en setLargeIcon
     */
    private fun drawableToBitmap(drawableId: Int): android.graphics.Bitmap? {
        return try {
            val drawable = context.getDrawable(drawableId)
            if (drawable != null) {
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error convirtiendo drawable a bitmap: ${e.message}")
            null
        }
    }
    
    /**
     * Muestra una notificación local en el dispositivo
     */
    private fun showLocalNotification(title: String, message: String, notificationId: Int) {
        try {
            val intent = Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("notification_type", if (notificationId == NOTIFICATION_ID_RECEIVED) "money_received" else "money_sent")
            }

            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_n) // Icono pequeño para barra de estado (N simple)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_nequixofficial)) // Logo completo a la derecha
                .setContentTitle(title) // Título del envío (ej: "Envío")
                .setContentText(message) // Mensaje completo 
                .setSubText("ahora") // Texto que aparece como origen/tiempo (sin duplicar "Nequi Kill")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(message)
                    .setBigContentTitle(title)
                    .setSummaryText("ahora")) // Para notificaciones expandidas (sin duplicar "Nequi Kill")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE) // Categoría de mensaje
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 300, 100, 300)) // Patrón de vibración mejorado
                .setLights(context.resources.getColor(R.color.nequi_pink, null), 1000, 1000) // Luces LED
                .setWhen(System.currentTimeMillis()) // Timestamp exacto
                .setShowWhen(true) // Mostrar tiempo
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
            notificationManager.notify(notificationId, notification)
            
            Log.d("NotificationManager", "Notificación local mostrada: $title - $message")
        } catch (e: Exception) {
            Log.e("NotificationManager", "Error mostrando notificación local: ${e.message}")
        }
    }
    
    /**
     * Crea el canal de notificaciones para Android 8+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                AndroidNotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de transferencias de dinero de Nequi"
                enableLights(true)
                lightColor = context.resources.getColor(R.color.nequi_pink, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d("NotificationManager", "Canal de notificaciones creado: $CHANNEL_ID")
        }
    }
    
    /**
     * Envía una notificación local simulando FCM
     */
    suspend fun sendDirectFCMNotification(
        token: String,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap()
    ) {
        try {
            Log.d("NotificationManager", "📱 Simulando FCM directo para token: ${token.take(20)}...")
            
            // Por ahora, usar notificación local como funcionalidad completa
            showLocalNotificationDirect(title, message, System.currentTimeMillis().toInt())
            Log.d("NotificationManager", "✅ Notificación local mostrada exitosamente")
            
        } catch (e: Exception) {
            Log.e("NotificationManager", "❌ Error mostrando notificación: ${e.message}")
            throw e
        }
    }
    
    /**
     * Muestra notificación local directa (fallback para FCM)
     */
    fun showLocalNotificationDirect(title: String, message: String, notificationId: Int) {
        try {
            val intent = Intent(context, context.javaClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_n)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_nequixofficial)) // Logo completo a la derecha
                .setContentTitle(title)
                .setContentText(message)
                .setSubText("ahora") // Sin duplicar "Nequi Kill"
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(message)
                    .setBigContentTitle(title)
                    .setSummaryText("ahora")) // Sin duplicar "Nequi Kill"
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 300, 100, 300))
                .setLights(context.resources.getColor(R.color.nequi_pink, null), 1000, 1000)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
            notificationManager.notify(notificationId, notification)
            
            Log.d("NotificationManager", "🔔 Notificación local directa mostrada: $title - $message")
        } catch (e: Exception) {
            Log.e("NotificationManager", "❌ Error mostrando notificación local directa: ${e.message}")
        }
    }
}
