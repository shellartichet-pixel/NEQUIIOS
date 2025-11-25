package com.ios.nequixofficialv2.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.ios.nequixofficialv2.HomeActivity
import com.ios.nequixofficialv2.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Servicio en segundo plano que escucha movimientos INCOMING en Firestore
 * y muestra notificaciones automáticamente, INDEPENDIENTE de HomeActivity.
 * 
 * ✅ Funciona con la app cerrada
 * ✅ Funciona en segundo plano
 * ✅ Funciona en cualquier pantalla
 */
class MovementListenerService : Service() {
    
    private val db = FirebaseFirestore.getInstance()
    private var movementListener: ListenerRegistration? = null
    private var isForegroundStarted = false // Rastrear si ya se inició foreground
    private var isFirstLoad = true
    private var lastNotificationUpdateTime = 0L // Rastrear última vez que se actualizó la notificación
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
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
            Log.e(TAG, "❌ Error buscando usuario por telefono: ${e.message}")
            null
        }
    }
    
    companion object {
        private const val TAG = "MovementListenerService"
        private const val CHANNEL_ID = "nequi_background_service"
        private const val CHANNEL_NAME = "Servicio de Nequi"
        private const val NOTIFICATION_CHANNEL_ID = "nequi_money_transfers"
        private const val FOREGROUND_NOTIFICATION_ID = 9999
        
        // Sincronización para evitar duplicados
        @Volatile
        private var isCreatingNotification = false
        private val notificationLock = Any()
        
        /**
         * Verifica si el servicio ya está corriendo
         */
        private fun isServiceRunning(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            return services.any { it.service.className == MovementListenerService::class.java.name }
        }
        
        fun start(context: Context, userPhone: String) {
            // Verificar si el servicio ya está corriendo
            if (isServiceRunning(context)) {
                Log.d(TAG, "⚠️ Servicio ya está corriendo, no se iniciará de nuevo")
                return
            }
            
            val intent = Intent(context, MovementListenerService::class.java).apply {
                putExtra("user_phone", userPhone)
            }
            
            // NO usar startForegroundService - usar startService normal para evitar notificación
            // Android puede matar el servicio, pero START_STICKY lo reiniciará
            context.startService(intent)
            
            Log.d(TAG, "🚀 Servicio de notificaciones iniciado (sin foreground) para: $userPhone")
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, MovementListenerService::class.java)
            context.stopService(intent)
            Log.d(TAG, "🛑 Servicio de notificaciones detenido")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.d(TAG, "📱 Servicio creado")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Manejar acción de detener servicio (desde notificación)
        if (intent?.action == "ACTION_STOP_SERVICE") {
            Log.d(TAG, "🛑 Usuario cerró el servicio")
            stopForeground(true)  // Remover notificación
            stopSelf()  // Detener servicio
            return START_NOT_STICKY
        }
        
        val userPhone = intent?.getStringExtra("user_phone") ?: run {
            // Si no hay teléfono en el intent, intentar recuperarlo de SharedPreferences
            val prefs = getSharedPreferences("home_prefs", Context.MODE_PRIVATE)
            prefs.getString("user_phone", null)
        }
        
        if (userPhone.isNullOrEmpty()) {
            Log.e(TAG, "❌ No se proporcionó teléfono de usuario")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Guardar teléfono en SharedPreferences para recuperación
        val prefs = getSharedPreferences("home_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("user_phone", userPhone).apply()
        
        // ELIMINADO COMPLETAMENTE: No se muestra ninguna notificación "Servicio activo"
        // Cancelar cualquier notificación existente para evitar que aparezca
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
            Log.d(TAG, "🗑️ Cancelando cualquier notificación existente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cancelando notificación: ${e.message}")
        }
        
        // NO usar startForeground() - el servicio corre en background SIN notificación visible
        // El servicio funciona correctamente sin necesidad de notificación foreground
        isForegroundStarted = false
        Log.d(TAG, "🔇 Servicio corriendo en background SIN notificación (NUNCA aparecerá)")
        
        // Remover listener anterior si existe (evitar duplicados)
        movementListener?.remove()
        
        // Iniciar escucha de movimientos
        startListeningForMovements(userPhone)
        
        Log.d(TAG, "✅ Servicio iniciado en BACKGROUND y escuchando movimientos para: $userPhone")
        
        // START_STICKY = Android reinicia el servicio si lo mata
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        movementListener?.remove()
        isForegroundStarted = false // Resetear flag cuando se destruye el servicio
        Log.d(TAG, "🛑 Servicio destruido y listener removido")
    }
    
    /**
     * Escucha cambios en tiempo real en la colección de movimientos del usuario
     */
    private fun startListeningForMovements(userPhone: String) {
        val userPhoneDigits = userPhone.filter { it.isDigit() }
        
        Log.d(TAG, "👂 Iniciando listener para movimientos de: $userPhoneDigits")
        
        // Obtener el email document ID usando el número de teléfono
        coroutineScope.launch {
            val userDocumentId = getUserDocumentIdByPhone(userPhoneDigits)
            if (userDocumentId == null) {
                Log.e(TAG, "❌ No se encontró usuario con telefono: $userPhoneDigits")
                return@launch
            }
            
            movementListener = db.collection("users")
                .document(userDocumentId)
                .collection("movements")
            .whereEqualTo("type", "INCOMING")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error en listener de movimientos: ${error.message}")
                    return@addSnapshotListener
                }
                
                if (snapshots == null) return@addSnapshotListener
                
                // En la PRIMERA carga, solo marcar movimientos existentes (no notificar)
                if (isFirstLoad) {
                    val prefs = getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    snapshots.documents.forEach { doc ->
                        editor.putBoolean(doc.id, true)
                    }
                    editor.apply()
                    isFirstLoad = false
                    Log.d(TAG, "📋 Primera carga: ${snapshots.documents.size} movimientos existentes marcados")
                    return@addSnapshotListener
                }
                
                // DESPUÉS de la primera carga, notificar SOLO movimientos AÑADIDOS
                snapshots.documentChanges.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val documentSnapshot = change.document
                        val movement = documentSnapshot.data
                        val movementId = documentSnapshot.id
                        val senderNameFromMovement = movement["name"] as? String ?: "Alguien"
                        val senderPhone = movement["phone"] as? String ?: ""
                        val amount = movement["amount"] as? Double ?: 0.0
                        val date = movement["date"] as? com.google.firebase.Timestamp
                        val movementDate = date?.toDate()?.time ?: System.currentTimeMillis()
                        
                        // Solo notificar movimientos recientes (últimos 5 minutos) para evitar notificar movimientos antiguos
                        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                        val isRecent = movementDate >= fiveMinutesAgo
                        
                        Log.d(TAG, "💰 NUEVO MOVIMIENTO INCOMING detectado: $senderNameFromMovement - $$amount (ID: $movementId, Reciente: $isRecent)")
                        
                        // Control anti-duplicados Y verificar que sea reciente
                        if (!hasNotified(movementId) && isRecent) {
                            Log.d(TAG, "🔔 Mostrando notificación para movimiento reciente")
                            
                            // 🔥 BUSCAR NOMBRE REAL del remitente si el nombre del movimiento es un teléfono
                            if (senderNameFromMovement.startsWith("+57") || 
                                senderNameFromMovement.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
                                // Es un teléfono, buscar nombre real en Firebase
                                Log.d(TAG, "📞 Nombre es teléfono ($senderNameFromMovement), buscando nombre real en Firebase para: $senderPhone")
                                resolveRealSenderName(senderPhone) { realName ->
                                    val nameToUse = if (realName.isNotBlank() && 
                                        !realName.equals("NEQUI SAN", ignoreCase = true) &&
                                        !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                                        !realName.equals("USUARIO NEQUI", ignoreCase = true) &&
                                        !realName.equals("Alguien", ignoreCase = true)) {
                                        realName
                                    } else {
                                        senderNameFromMovement // Usar el teléfono formateado si no se encuentra nombre
                                    }
                                    Log.d(TAG, "✅ Nombre final para notificación: $nameToUse")
                                    showMoneyReceivedNotification(nameToUse, amount)
                                    markAsNotified(movementId)
                                    Log.d(TAG, "✅ Notificación del sistema mostrada exitosamente")
                                }
                            } else {
                                // Ya es un nombre válido, usar directamente
                                Log.d(TAG, "✅ Usando nombre del movimiento directamente: $senderNameFromMovement")
                                showMoneyReceivedNotification(senderNameFromMovement, amount)
                                markAsNotified(movementId)
                                Log.d(TAG, "✅ Notificación del sistema mostrada exitosamente")
                            }
                        } else if (hasNotified(movementId)) {
                            Log.d(TAG, "⏭️ Movimiento ya notificado, omitiendo")
                        } else if (!isRecent) {
                            Log.d(TAG, "⏭️ Movimiento no es reciente (${(System.currentTimeMillis() - movementDate) / 1000}s atrás), omitiendo")
                            // Marcar como notificado para no volver a intentar
                            markAsNotified(movementId)
                        }
                    }
                }
            }
        
            Log.d(TAG, "✅ Listener de movimientos activo")
        }
    }
    
    /**
     * Verifica si un movimiento ya fue notificado
     */
    private fun hasNotified(movementId: String): Boolean {
        val prefs = getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
        return prefs.contains(movementId)
    }
    
    /**
     * Marca un movimiento como notificado
     */
    private fun markAsNotified(movementId: String) {
        val prefs = getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(movementId, true).apply()
        
        // Limpiar notificaciones antiguas (mantener solo últimas 100)
        val allNotified = prefs.all
        if (allNotified.size > 100) {
            val editor = prefs.edit()
            allNotified.keys.take(20).forEach { editor.remove(it) }
            editor.apply()
        }
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
            Log.e(TAG, "Error convirtiendo drawable a bitmap: ${e.message}")
            null
        }
    }
    
    /**
     * Busca el nombre real del remitente en Firebase cuando el nombre del movimiento es un teléfono
     */
    private fun resolveRealSenderName(senderPhone: String, callback: (String) -> Unit) {
        if (senderPhone.isBlank()) {
            callback("Alguien")
            return
        }
        
        val phoneDigits = senderPhone.filter { it.isDigit() }
        if (phoneDigits.length != 10) {
            callback("Alguien")
            return
        }
        
        coroutineScope.launch {
            try {
                // Buscar en Firebase users
                val userQuery = db.collection("users")
                    .whereEqualTo("telefono", phoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (!userQuery.isEmpty) {
                    val realName = userQuery.documents.first().getString("name")?.trim().orEmpty()
                    if (realName.isNotBlank() && 
                        !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                        !realName.equals("USUARIO NEQUI", ignoreCase = true) &&
                        !realName.equals("NEQUI SAN", ignoreCase = true)) {
                        Log.d(TAG, "✅ Nombre real encontrado en Firebase: $realName")
                        callback(realName)
                        return@launch
                    }
                }
                
                // Si no se encuentra, usar "Alguien"
                Log.d(TAG, "⚠️ No se encontró nombre real para $phoneDigits")
                callback("Alguien")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error buscando nombre real: ${e.message}")
                callback("Alguien")
            }
        }
    }
    
    /**
     * Muestra notificación de dinero recibido
     */
    private fun showMoneyReceivedNotification(senderName: String, amount: Double) {
        // ✅ VERIFICAR PERMISO DE NOTIFICACIONES EN ANDROID 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Permiso POST_NOTIFICATIONS no concedido en Android 13+. No se puede mostrar notificación.")
                Log.e(TAG, "⚠️ IMPORTANTE: El usuario debe conceder permisos de notificación en Configuración > Apps > Nequi Kill > Notificaciones")
                // Intentar mostrar notificación de todas formas (algunos dispositivos permiten)
            }
        }
        
        val title = "Nequi Colombia"
        val message = "$senderName te envió $${String.format("%.0f", amount)}, ¡lo mejor!"
        
        Log.d(TAG, "🔔 Mostrando notificación: $title - $message")
        
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_type", "money_received")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_n)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_nequixofficial)) // Logo completo a la derecha
            .setContentTitle(title)
            .setContentText(message)
            // Eliminado setSubText para evitar duplicación de "ahora" (el sistema ya lo muestra)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(message)
                .setBigContentTitle(title))
            // Eliminado setSummaryText para evitar duplicación de "ahora"
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setLights(resources.getColor(R.color.nequi_pink, null), 1000, 1000)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .build()
        
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notification)
            
            Log.d(TAG, "✅ Notificación mostrada exitosamente (ID: $notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERROR CRÍTICO mostrando notificación: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    // Función shouldShowForegroundNotification eliminada - ya no se usa
    // Siempre se usa notificación invisible para no molestar al usuario
    
    // ELIMINADO COMPLETAMENTE: createServiceNotification() - No se muestra ninguna notificación "Servicio activo"
    
    /**
     * Crea notificación COMPLETAMENTE INVISIBLE
     * Android 8+ requiere una notificación para foreground service, pero la hacemos invisible
     */
    private fun createCompletelyInvisibleNotification(): Notification {
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("") // Vacío completamente
            .setContentText("") // Vacío completamente
            .setSubText("") // Vacío completamente
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim) // Icono del sistema (más pequeño)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Prioridad mínima
            .setOngoing(true) // REQUERIDO para foreground service
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Oculto en lockscreen
            .setShowWhen(false) // Sin tiempo
            .setSilent(true) // Silenciosa
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setLocalOnly(true)
            .setGroup("nequi_service")
            .setGroupSummary(false)
            .setOnlyAlertOnce(true) // No alertar múltiples veces
            .setDefaults(0) // Sin defaults (sin sonido, vibración, luces)
            .setSound(null) // Sin sonido
            .setVibrate(null) // Sin vibración
            .setLights(0, 0, 0) // Sin luces LED
        
        // En Android 14+ (API 34+), usar setForegroundServiceBehavior para hacerla invisible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo configurar FOREGROUND_SERVICE_IMMEDIATE: ${e.message}")
            }
        }
        
        return notificationBuilder.build()
    }
    
    // Función createForegroundNotification eliminada - ya no se usa
    // Solo se usa createCompletelyInvisibleNotification() que es completamente invisible
    
    /**
     * Crea los canales de notificación necesarios (Android 8+)
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Canal para el servicio en foreground (baja prioridad, completamente invisible)
            // IMPORTANTE: Para foreground services necesitamos al menos IMPORTANCE_LOW
            // Pero lo hacemos lo más invisible posible
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Mínimo permitido para foreground service
            ).apply {
                description = "Mantiene el servicio de Nequi activo en segundo plano"
                setShowBadge(false)
                setSound(null, null) // Sin sonido
                enableVibration(false) // Sin vibración
                enableLights(false) // Sin luces LED
                setBypassDnd(false) // No pasar modo "No interrumpir"
                lockscreenVisibility = Notification.VISIBILITY_SECRET // Oculto en lockscreen
                // Hacer que el canal sea lo más silencioso posible
                enableVibration(false)
                vibrationPattern = null
            }
            
            // Canal para notificaciones de dinero (alta prioridad)
            val transferChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Transferencias de Dinero",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de transferencias de dinero recibidas"
                enableLights(true)
                lightColor = resources.getColor(R.color.nequi_pink, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 100, 300)
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(transferChannel)
            
            Log.d(TAG, "✅ Canales de notificación creados")
        }
    }
}
