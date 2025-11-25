package com.ios.nequixofficialv2.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.firestore.FirebaseFirestore
import com.ios.nequixofficialv2.HomeActivity
import com.ios.nequixofficialv2.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FCMService : FirebaseMessagingService() {
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Obtiene el document ID del usuario (correo) buscando por el campo telefono
     */
    private suspend fun getUserDocumentIdByPhone(phone: String): String? {
        return try {
            val phoneDigits = phone.filter { it.isDigit() }
            val query = FirebaseFirestore.getInstance().collection("users")
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
            android.util.Log.e("FCMService", "❌ Error buscando usuario por telefono: ${e.message}")
            null
        }
    }

    companion object {
        private const val CHANNEL_ID = "nequi_notifications"
        private const val CHANNEL_NAME = "Notificaciones de Nequi"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        android.util.Log.d("FCMService", "🔔 Mensaje FCM recibido: ${remoteMessage.data}")
        
        // Procesar la notificación recibida
        remoteMessage.data.let { data ->
            val type = data["type"]
            android.util.Log.d("FCMService", "📨 Tipo de notificación: $type")
            
            when (type) {
                "money_received" -> {
                    val senderName = data["sender_name"] ?: "Alguien"
                    val amount = data["amount"] ?: "0"
                    android.util.Log.d("FCMService", "💰 Dinero recibido: $senderName - $amount")
                    showMoneyReceivedNotification(senderName, amount)
                }
                "money_sent" -> {
                    val receiverName = data["receiver_name"] ?: "Alguien"
                    val amount = data["amount"] ?: "0"
                    android.util.Log.d("FCMService", "💸 Dinero enviado: $receiverName - $amount")
                    showMoneySentNotification(receiverName, amount)
                }
                else -> {
                    android.util.Log.w("FCMService", "⚠️ Tipo de notificación desconocido: $type")
                }
            }
        }
        
        // También procesar notification payload si existe
        remoteMessage.notification?.let { notification ->
            android.util.Log.d("FCMService", "📬 Notification payload: ${notification.title}")
            showNotification(
                notification.title ?: "Nequi",
                notification.body ?: "",
                "notification"
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        android.util.Log.d("FCMService", "🔑 Nuevo token FCM generado: $token")
        
        // Guardar el token localmente
        saveTokenToPreferences(token)
        
        // Intentar guardar en Firebase si hay usuario logueado
        saveTokenToFirebase(token)
    }

    private fun saveTokenToPreferences(token: String) {
        val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        android.util.Log.d("FCMService", "✅ Token guardado en SharedPreferences")
    }

    private fun saveTokenToFirebase(token: String) {
        try {
            // Obtener teléfono del usuario logueado
            val prefs = getSharedPreferences("home_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", null)
            val userPhoneDigits = userPhone?.filter { it.isDigit() }
            
            if (!userPhoneDigits.isNullOrEmpty() && userPhoneDigits.length == 10) {
                coroutineScope.launch {
                    val userDocumentId = getUserDocumentIdByPhone(userPhoneDigits)
                    if (userDocumentId != null) {
                        val db = FirebaseFirestore.getInstance()
                        db.collection("users").document(userDocumentId)
                            .update("fcmToken", token)
                            .addOnSuccessListener {
                                android.util.Log.d("FCMService", "✅ Token FCM guardado en Firebase para: $userPhoneDigits (doc: $userDocumentId)")
                            }
                            .addOnFailureListener { error ->
                                android.util.Log.e("FCMService", "❌ Error guardando token en Firebase: ${error.message}")
                            }
                    } else {
                        android.util.Log.w("FCMService", "⚠️ No se encontró usuario con telefono: $userPhoneDigits")
                    }
                }
            } else {
                android.util.Log.w("FCMService", "⚠️ No hay usuario logueado, token se guardará en próximo login")
            }
        } catch (e: Exception) {
            android.util.Log.e("FCMService", "❌ Error en saveTokenToFirebase: ${e.message}")
        }
    }

    private fun showMoneyReceivedNotification(senderName: String, amount: String) {
        val title = "Nequi Colombia"
        val message = "$senderName te envió $amount, ¡lo mejor!"
        
        showNotification(title, message, "money_received")
    }

    private fun showMoneySentNotification(receiverName: String, amount: String) {
        val title = "Envío exitoso"
        val message = "Enviaste $amount a $receiverName"
        
        showNotification(title, message, "money_sent")
    }

    /**
     * Convierte un drawable a Bitmap para usar en setLargeIcon
     */
    private fun drawableToBitmap(drawableId: Int): android.graphics.Bitmap? {
        return try {
            val drawable = getDrawable(drawableId)
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
            android.util.Log.e("FCMService", "Error convirtiendo drawable a bitmap: ${e.message}")
            null
        }
    }
    
    private fun showNotification(title: String, message: String, type: String) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_type", type)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_n) // Icono pequeño para barra de estado (N simple)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_nequixofficial)) // Logo completo a la derecha
            .setContentTitle("$title") // Solo el título del envío
            .setContentText(message) // El mensaje completo
            .setSubText("ahora") // Texto que aparece como origen/tiempo (sin duplicar "Nequi Kill")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(message)
                .setBigContentTitle(title)
                .setSummaryText("ahora")) // Para notificaciones expandidas (sin duplicar "Nequi Kill")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // Categoría de mensaje
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 100, 300)) // Patrón de vibración
            .setLights(resources.getColor(R.color.nequi_pink, null), 1000, 1000) // Luces LED
            .setWhen(System.currentTimeMillis()) // Timestamp exacto
            .setShowWhen(true) // Mostrar tiempo
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        android.util.Log.d("FCMService", "Notificación mostrada: $title - $message")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de transferencias de dinero"
                enableLights(true)
                lightColor = resources.getColor(R.color.nequi_pink, null)
                enableVibration(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
