package com.ios.nequixofficialv2.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.ios.nequixofficialv2.HomeActivity
import com.ios.nequixofficialv2.R
import kotlinx.coroutines.tasks.await

/**
 * Worker que revisa notificaciones pendientes en Firebase y las muestra
 * Funciona SIEMPRE: app abierta, cerrada o en background
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "nequi_money_notifications"
        private const val CHANNEL_NAME = "Transferencias de Dinero"
    }

    override suspend fun doWork(): Result {
        return try {
            android.util.Log.d("NotificationWorker", "🔄 [WORKER] Verificando movimientos INCOMING nuevos...")
            
            // Obtener teléfono del usuario desde SharedPreferences
            val prefs = applicationContext.getSharedPreferences("home_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", null)
            val userPhoneDigits = userPhone?.filter { it.isDigit() }
            
            if (userPhoneDigits.isNullOrEmpty() || userPhoneDigits.length != 10) {
                android.util.Log.w("NotificationWorker", "⚠️ No hay usuario logueado")
                return Result.success()
            }
            
            android.util.Log.d("NotificationWorker", "👤 [WORKER] Revisando movimientos para: $userPhoneDigits")
            
            // Obtener el email document ID usando el número de teléfono
            val db = FirebaseFirestore.getInstance()
            val userDocumentId = try {
                val query = db.collection("users")
                    .whereEqualTo("telefono", userPhoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (!query.isEmpty) {
                    query.documents.first().id
                } else {
                    android.util.Log.w("NotificationWorker", "⚠️ No se encontró usuario con telefono: $userPhoneDigits")
                    return Result.success()
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationWorker", "❌ Error buscando usuario: ${e.message}")
                return Result.success()
            }
            
            // Buscar movimientos INCOMING recientes (últimos 10 minutos)
            val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
            
            val movements = db.collection("users")
                .document(userDocumentId)
                .collection("movements")
                .whereEqualTo("type", "INCOMING")
                .get()
                .await()
            
            android.util.Log.d("NotificationWorker", "📦 Encontrados ${movements.size()} movimientos INCOMING")
            
            // Usar el mismo sistema anti-duplicados que MovementListenerService
            val notifiedPrefs = applicationContext.getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
            
            var newNotifications = 0
            
            movements.documents.forEach { doc ->
                val movementId = doc.id
                val timestamp = doc.getDate("date")?.time ?: 0L
                
                // Solo notificar si es reciente (últimos 10 min) y no ha sido notificado
                if (timestamp >= tenMinutesAgo && !notifiedPrefs.contains(movementId)) {
                    val senderName = doc.getString("name") ?: "Alguien"
                    val amount = doc.getDouble("amount") ?: 0.0
                    
                    android.util.Log.d("NotificationWorker", "🔔 [WORKER] Nueva notificación detectada!")
                    android.util.Log.d("NotificationWorker", "   - Remitente: $senderName")
                    android.util.Log.d("NotificationWorker", "   - Monto: $$amount")
                    android.util.Log.d("NotificationWorker", "   - MovementID: $movementId")
                    
                    // Mostrar notificación en barra del sistema
                    showNotification(senderName, amount.toString(), movementId)
                    
                    // Marcar como notificado (mismo sistema que el Service)
                    notifiedPrefs.edit().putBoolean(movementId, true).apply()
                    
                    newNotifications++
                }
            }
            
            android.util.Log.d("NotificationWorker", "✅ Worker completado: $newNotifications nuevas notificaciones mostradas")
            Result.success()
            
        } catch (e: Exception) {
            android.util.Log.e("NotificationWorker", "❌ Error en NotificationWorker: ${e.message}")
            e.printStackTrace()
            Result.retry()
        }
    }
    
    /**
     * Convierte un drawable a Bitmap para usar en setLargeIcon
     */
    private fun drawableToBitmap(drawableId: Int): android.graphics.Bitmap? {
        return try {
            val drawable = applicationContext.getDrawable(drawableId)
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
            android.util.Log.e("NotificationWorker", "Error convirtiendo drawable a bitmap: ${e.message}")
            null
        }
    }
    
    private fun showNotification(senderName: String, amount: String, movementId: String) {
        createNotificationChannel()
        
        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_type", "money_received")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            movementId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_n)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(applicationContext.resources, R.drawable.ic_nequixofficial)) // Logo completo a la derecha
            .setContentTitle("Nequi Colombia")
            .setContentText("$senderName te envió $$amount, ¡lo mejor!")
            .setSubText("ahora") // Sin duplicar "Nequi Kill"
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$senderName te envió $$amount, ¡lo mejor!")
                .setBigContentTitle("Nequi Colombia")
                .setSummaryText("ahora")) // Sin duplicar "Nequi Kill"
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(movementId.hashCode(), notification)
        
        android.util.Log.d("NotificationWorker", "🔔 Notificación mostrada: $senderName - $$amount")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones cuando recibes dinero"
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
