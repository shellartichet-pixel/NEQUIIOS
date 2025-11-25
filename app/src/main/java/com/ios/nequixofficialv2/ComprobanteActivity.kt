package com.ios.nequixofficialv2

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import android.widget.Toast
import io.scanbot.demo.barcodescanner.e
import io.scanbot.demo.barcodescanner.model.Movement
import io.scanbot.demo.barcodescanner.model.MovementType
import java.util.Date
import com.ios.nequixofficialv2.utils.NotificationManager as AppNotificationManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class ComprobanteActivity : AppCompatActivity() {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var animDrawable: AnimationDrawable? = null
    private var avd: AnimatedVectorDrawable? = null
    private var avdCompat: AnimatedVectorDrawableCompat? = null
    private val appNotificationManager: AppNotificationManager by lazy { AppNotificationManager(this) }
    
    /**
     * Obtiene el document ID del usuario (correo) buscando por el campo telefono
     * El documento ID es un correo (ej: usertest@gmail.com), pero la app busca por telefono
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
                val doc = query.documents.first()
                android.util.Log.d("ComprobanteActivity", "✅ Usuario encontrado - ID documento: ${doc.id}")
                doc.id
            } else {
                android.util.Log.w("ComprobanteActivity", "⚠️ No se encontró usuario con telefono: $phoneDigits")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ComprobanteActivity", "❌ Error buscando usuario por telefono: ${e.message}", e)
            null
        }
    }
    
    companion object {
        // 🚨 CONTROL ESTÁTICO ANTI-DUPLICADOS: Sobrevive a recreaciones de Activity
        private val processedTransfers = mutableSetOf<String>()
        
        // Limpiar transferencias antiguas (mayores a 30 segundos)
        private val transferTimestamps = mutableMapOf<String, Long>()
        
        private fun isTransferProcessed(transferId: String): Boolean {
            val now = System.currentTimeMillis()
            
            // Limpiar transferencias antiguas (>30 segundos)
            transferTimestamps.entries.removeAll { (_, timestamp) ->
                now - timestamp > 30000
            }
            processedTransfers.removeAll { id ->
                transferTimestamps[id]?.let { now - it > 30000 } ?: true
            }
            
            return processedTransfers.contains(transferId)
        }
        
        private fun markTransferAsProcessed(transferId: String) {
            processedTransfers.add(transferId)
            transferTimestamps[transferId] = System.currentTimeMillis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comprobante)

        // Aplicar barra de estado morada para evitar destellos en Android 7-11
        AndroidCompatibilityHelper.applyNequiStatusBar(this)

        // TRANSICIÓN SUAVE: Fade-in del layout para evitar saltos blancos
        window.setBackgroundDrawableResource(R.color.comprobante_background)
        
        // Iniciar animación del círculo de carga si aplica
        startLoadingAnimation()

        // PROCESO FLUIDO: Iniciar validación inmediatamente sin delay para evitar saltos
        processPayment()
    }

    private fun processPayment() {
        // ANIMACIÓN FLUIDA: Delay mínimo para mostrar la animación sin saltos
        // Usar Handler con Looper explícito para Android 13-15
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val phone = intent.getStringExtra("phone").orEmpty()
                val amount = intent.getStringExtra("amount").orEmpty()
                // Fallback: si no viene en Intent, usar FirebaseAuth y normalizar a 10 dígitos
                val userPhone = intent.getStringExtra("user_phone").orEmpty().ifBlank {
                FirebaseAuth.getInstance().currentUser?.phoneNumber
                    ?.filter { it.isDigit() }
                    ?.let { if (it.length > 10) it.takeLast(10) else it }
                    .orEmpty()
            }

            val amountValue = parseAmountToLong(amount)

            fun go(clazz: Class<*>, reference: String? = null) {
                val i = Intent(this, clazz)
                i.putExtra("phone", phone)
                i.putExtra("amount", amount)
                i.putExtra("user_phone", userPhone)
                
                // Pasar la referencia del movimiento si está disponible
                if (reference != null && reference.isNotBlank()) {
                    i.putExtra("reference", reference)
                    android.util.Log.d("ComprobanteActivity", "📝 Pasando referencia a VoucherActivity: $reference")
                }

                // Diferenciar si es un pago QR según longitud de número (en QR no hay 10 dígitos)
                if (clazz == VoucherActivity::class.java) {
                    val digits = phone.filter { it.isDigit() }
                    val isQrPayment = digits.length != 10
                    i.putExtra("is_qr_voucher", isQrPayment)
                    if (isQrPayment) {
                        intent.getStringExtra("maskedName")?.let { i.putExtra("maskedName", it) }
                    }
                }
                // Detener animaciones antes de navegar
                animDrawable?.stop()
                avd?.stop()
                avdCompat?.stop()
                startActivity(i)
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 120)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 50)
                }
            }

            if (userPhone.isBlank() || amountValue == null) {
                // En flujo QR, continuar al comprobante
                val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                go(target)
                return@postDelayed
            }

            // Captura no nula para usar dentro de la transacción
            val required: Long = amountValue

            // Obtener el email document ID usando el número de teléfono
            lifecycleScope.launch {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                if (userDocumentId == null) {
                    android.util.Log.e("ComprobanteActivity", "❌ No se encontró usuario con telefono: $userPhone")
                    go(HomeActivity::class.java)
                    return@launch
                }
                
                val userRef = db.collection("users").document(userDocumentId)

                // 1) Leer saldo primero y decidir insuficiencia real
                userRef.get().addOnSuccessListener { snap ->
                    val current = readBalanceFlexible(snap, "saldo")
                    if (current == null) {
                        android.util.Log.e("ComprobanteActivity", "❌ No se pudo leer saldo del usuario")
                        go(HomeActivity::class.java)
                        return@addOnSuccessListener
                    }
                    if (current < required) {
                        val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                        go(target)
                        return@addOnSuccessListener
                    }

                    // 2) Ejecutar transacción para descontar
                    db.runTransaction { transaction ->
                        val snapshot = transaction.get(userRef)
                        val currentBalance = readBalanceFlexible(snapshot, "saldo") ?: 0L
                        if (currentBalance < required) {
                            throw com.google.firebase.firestore.FirebaseFirestoreException("Saldo insuficiente", com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED)
                        }
                        transaction.update(userRef, "saldo", currentBalance - required)
                    }.addOnSuccessListener {
                    android.util.Log.d("ComprobanteActivity", "✅✅✅ SALDO DEL REMITENTE DESCONTADO EXITOSAMENTE")
                    
                    // Registrar movimiento de salida antes de ir al comprobante
                    val phoneDigits = phone.filter { it.isDigit() }
                    val isQrPayment = intent.hasExtra("maskedName") && phoneDigits.length != 10
                    
                    // ✅✅✅ CRÍTICO: ACTUALIZAR SALDO DEL RECEPTOR INMEDIATAMENTE (solo si es transferencia normal, no QR)
                    if (!isQrPayment && phoneDigits.length == 10) {
                        android.util.Log.d("ComprobanteActivity", "💰💰💰 ACTUALIZANDO SALDO DEL RECEPTOR INMEDIATAMENTE (transferencia normal)")
                        updateRecipientBalance(phoneDigits, required.toDouble())
                    }
                    
                    // Para pagos QR, usar el nombre ofuscado directamente
                    if (isQrPayment) {
                        val qrNameRaw = intent.getStringExtra("maskedName").orEmpty()
                        val qrNameObfuscated = com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper.obfuscateName(qrNameRaw, uppercase = true)
                        
                        android.util.Log.d("ComprobanteActivity", "💳 Pago QR - Nombre original: $qrNameRaw")
                        android.util.Log.d("ComprobanteActivity", "💳 Pago QR - Nombre ofuscado: $qrNameObfuscated")
                        
                        // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre QR
                        val cleanedQrName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(qrNameObfuscated)
                        
                        // 🔧 Generar referencia ANTES de crear el Movement para que sea la misma en comprobante y detalles
                        val referencia = generateReference()
                        
                        val movement = Movement(
                            id = "",
                            name = cleanedQrName.ifBlank { phoneDigits },
                            amount = required.toDouble(),
                            date = Date(),
                            phone = phoneDigits,
                            type = MovementType.OUTGOING,
                            isIncoming = false,
                            isQrPayment = true,
                            mvalue = referencia // ✅ Guardar referencia para usar en detalles
                        )
                        
                        // 🚨 CONTROL ANTI-DUPLICADOS para QR
                        val transferId = "${userPhone}_to_${phoneDigits}_${required}_${System.nanoTime()}"
                        
                        if (isTransferProcessed(transferId)) {
                            android.util.Log.w("ComprobanteActivity", "⚠️ PAGO QR YA PROCESADO: $transferId")
                            go(VoucherActivity::class.java, referencia)
                            return@addOnSuccessListener
                        }
                        
                        markTransferAsProcessed(transferId)
                        android.util.Log.d("ComprobanteActivity", "🎯 PROCESANDO PAGO QR: $transferId")
                        
                        // Guardar movimiento OUTGOING para QR
                        e.saveMovement(this@ComprobanteActivity, movement) { success, error ->
                            android.util.Log.d("ComprobanteActivity", "✅ PAGO QR OUTGOING guardado - Success: $success")
                            go(VoucherActivity::class.java, referencia)
                        }
                        
                        // Guardar INCOMING para el receptor (si aplica)
                        saveIncomingMovementForRecipient(phoneDigits, qrNameObfuscated, required.toDouble(), userPhone, transferId)
                    } else {
                        // Flujo normal: resolver nombre del contacto/usuario
                        resolveRecipientName(userPhone, phoneDigits) { recipientName ->
                            // Aplicar formato Title Case consistente
                            val normalizedName = toTitleCase(recipientName.trim())
                            
                            // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre del destinatario
                            val cleanedRecipientName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(normalizedName)
                            
                            // 🔧 Generar referencia ANTES de crear el Movement para que sea la misma en comprobante y detalles
                            val referencia = generateReference()
                            
                            val movement = Movement(
                                id = "",
                                name = cleanedRecipientName.ifBlank { phoneDigits },
                                amount = required.toDouble(),
                                date = Date(),
                                phone = phoneDigits,
                                type = MovementType.OUTGOING,
                                isIncoming = false,
                                isQrPayment = false,
                                mvalue = referencia // ✅ Guardar referencia para usar en detalles
                            )
                        // 🚨 CONTROL ANTI-DUPLICADOS: Crear ID único para esta transferencia
                        // Usar nanoTime para máxima unicidad (evita colisiones en milisegundo)
                        val transferId = "${userPhone}_to_${phoneDigits}_${required}_${System.nanoTime()}"
                        
                        if (isTransferProcessed(transferId)) {
                            android.util.Log.w("ComprobanteActivity", "⚠️⚠️⚠️ TRANSFERENCIA YA PROCESADA, OMITIENDO: $transferId")
                            go(VoucherActivity::class.java, referencia)
                            return@resolveRecipientName
                        }
                        
                        // Marcar como procesado INMEDIATAMENTE
                        markTransferAsProcessed(transferId)
                        android.util.Log.d("ComprobanteActivity", "🎯 PROCESANDO TRANSFERENCIA ÚNICA: $transferId")
                        android.util.Log.d("ComprobanteActivity", "Datos - PhoneDigits: $phoneDigits, NormalizedName: $normalizedName, Required: $required, UserPhone: $userPhone")
                        
                        // SOLUCIÓN SIMPLE: Solo guardar OUTGOING aquí
                        e.saveMovement(this@ComprobanteActivity, movement) { success, error ->
                            android.util.Log.d("ComprobanteActivity", "✅ OUTGOING guardado - Success: $success")
                            // Ir directamente al comprobante SIN crear INCOMING duplicado
                            go(VoucherActivity::class.java, referencia)
                        }
                        
                        // SEPARADO: Crear INCOMING para el receptor UNA SOLA VEZ (fuera del callback para evitar duplicados)
                        android.util.Log.d("ComprobanteActivity", "🎯 Iniciando guardado INCOMING para: $phoneDigits")
                        
                        // Nota: El saldo ya se actualizó arriba (línea ~205), solo guardamos el movimiento aquí
                        android.util.Log.d("ComprobanteActivity", "📝 Guardando movimiento INCOMING (el saldo ya fue actualizado)")
                        
                        // Guardar INCOMING inmediatamente después de marcar como procesado
                        saveIncomingMovementForRecipient(phoneDigits, normalizedName, required.toDouble(), userPhone, transferId)
                        }
                    }
                    }.addOnFailureListener {
                        val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                        go(target)
                    }
                }.addOnFailureListener {
                    android.util.Log.e("ComprobanteActivity", "❌ Error leyendo saldo: ${it.message}")
                    go(HomeActivity::class.java)
                }
            }
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "Error in processPayment: ${e.message}")
                goHome()
            }
        }, 800) // FLUIDO: Reducido de 2000ms a 800ms para evitar saltos
    }

    override fun onBackPressed() { goHome() }

    private fun goHome() {
        val i = Intent(this, HomeActivity::class.java)
        val userPhone = intent.getStringExtra("user_phone")
        if (!userPhone.isNullOrBlank()) {
            i.putExtra("user_phone", userPhone)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            window.decorView.postDelayed({ finish() }, 120)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            window.decorView.postDelayed({ finish() }, 50)
        }
    }

    private fun resolveRecipientName(userPhone: String, phoneDigits: String, cb: (String) -> Unit) {
        if (userPhone.isBlank() || phoneDigits.isBlank()) {
            cb("")
            return
        }
        
        lifecycleScope.launch {
            try {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                if (userDocumentId == null) {
                    val query = db.collection("users")
                        .whereEqualTo("telefono", phoneDigits)
                        .limit(1)
                        .get()
                        .await()
                    
                    if (!query.isEmpty) {
                        val userName = query.documents.first().getString("name")?.trim().orEmpty()
                        if (userName.isNotBlank() && 
                            !userName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                            !userName.equals("USUARIO NEQUI", ignoreCase = true)) {
                            cb(userName)
                            return@launch
                        }
                    }
                    cb(phoneDigits)
                    return@launch
                }
                
                // 🔥 CORRECCIÓN: Buscar en contactos (víctimas guardadas) y verificar tipo "Nequi"
                val contactDoc = db.collection("users").document(userDocumentId)
                    .collection("contacts").document(phoneDigits)
                    .get()
                    .await()
                
                if (contactDoc.exists()) {
                    val contactType = contactDoc.getString("type") ?: ""
                    // Solo usar si es tipo "Nequi" o si no tiene tipo (compatibilidad con contactos antiguos)
                    if (contactType == "Nequi" || contactType.isEmpty()) {
                        val contactName = contactDoc.getString("name")?.trim().orEmpty()
                        if (contactName.isNotEmpty()) {
                            android.util.Log.d("ComprobanteActivity", "✅ Víctima encontrada en contactos: $contactName (tipo: $contactType)")
                            cb(contactName)
                            return@launch
                        }
                    } else {
                        android.util.Log.d("ComprobanteActivity", "⚠️ Contacto encontrado pero tipo incorrecto: $contactType (esperado: Nequi)")
                    }
                } else {
                    android.util.Log.d("ComprobanteActivity", "⚠️ No se encontró contacto con ID: $phoneDigits")
                }
                
                val userQuery = db.collection("users")
                    .whereEqualTo("telefono", phoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (!userQuery.isEmpty) {
                    val realName = userQuery.documents.first().getString("name")?.trim().orEmpty()
                    if (realName.isNotBlank() && 
                        !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                        !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                        cb(realName)
                        return@launch
                    }
                }
                
                cb(phoneDigits)
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "Error resolviendo nombre: ${e.message}")
                cb(phoneDigits)
            }
        }
    }

    private fun startLoadingAnimation() {
        val iv = findViewById<ImageView?>(R.id.loadingCircleComprobante) ?: return
        val d = iv.drawable ?: return
        when (d) {
            is AnimationDrawable -> {
                animDrawable = d.apply { isOneShot = false; start() }
            }
            is AnimatedVectorDrawable -> {
                avd = d
                avd?.start()
            }
            else -> {
                val maybeCompat = AnimatedVectorDrawableCompat.create(this, R.drawable.loading_circle_comprobante)
                if (maybeCompat != null) {
                    iv.setImageDrawable(maybeCompat)
                    avdCompat = maybeCompat
                    avdCompat?.start()
                }
            }
        }
    }
    override fun onPause() {
        super.onPause()
        // Detener para ahorrar recursos
        animDrawable?.stop()
        // AVD se detiene solo, pero por seguridad
    }

    private fun parseAmountToLong(amount: String?): Long? {
        if (amount.isNullOrBlank()) return null
        // Solución directa: ignorar todo excepto los dígitos
        val digits = amount.filter { it.isDigit() }
        return digits.toLongOrNull()
    }

    private fun readBalanceFlexible(snap: com.google.firebase.firestore.DocumentSnapshot, field: String): Long? {
        val anyVal = snap.get(field) ?: return null
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> {
                // Solución directa: ignorar todo excepto los dígitos
                val digits = anyVal.filter { it.isDigit() }
                digits.toLongOrNull()
            }
            else -> null
        }
    }

    private fun toTitleCase(input: String): String {
        if (input.isBlank()) return input
        return input.lowercase(java.util.Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> 
                    if (c.isLowerCase()) c.titlecase(java.util.Locale.getDefault()) 
                    else c.toString() 
                }
            }
    }
    
    /**
     * Guarda movimiento INCOMING para el receptor de la transferencia
     */
    private fun saveIncomingMovementForRecipient(
        recipientPhoneDigits: String, 
        recipientName: String, 
        amount: Double, 
        senderPhone: String,
        transferId: String
    ) {
        android.util.Log.d("ComprobanteActivity", "🎯 GUARDANDO MOVIMIENTO INCOMING PARA: $recipientPhoneDigits")
        android.util.Log.d("ComprobanteActivity", "Remitente: $senderPhone, Monto: +$amount, TransferID: $transferId")
        
        // Nota: El saldo ya se actualizó en processPayment() antes de llegar aquí
        // Solo guardamos el movimiento INCOMING para el historial
        
        val senderPhoneDigits = senderPhone.filter { it.isDigit() }
        
        // 🔥 CORRECCIÓN: Buscar nombre real del REMITENTE antes de guardar
        android.util.Log.d("ComprobanteActivity", "🔍 Buscando nombre real del remitente: $senderPhoneDigits")
        resolveSenderNameForIncoming(senderPhoneDigits) { senderName ->
            android.util.Log.d("ComprobanteActivity", "📇 Nombre del remitente resuelto: '$senderName' (longitud: ${senderName.length})")
            
            // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre del remitente
            val cleanedSenderName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(senderName)
            android.util.Log.d("ComprobanteActivity", "🧹 Nombre limpiado: '$cleanedSenderName'")
            
            // ✅ Asegurar que si el nombre está vacío o es solo números, formatear el teléfono
            val finalName = if (cleanedSenderName.isBlank() || 
                cleanedSenderName.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } ||
                cleanedSenderName.equals("NEQUI SAN", ignoreCase = true) ||
                cleanedSenderName.equals("NEQUIXOFFICIAL", ignoreCase = true) ||
                cleanedSenderName.equals("USUARIO NEQUI", ignoreCase = true)) {
                // Si es solo números, está vacío, o es un nombre inválido, formatear el teléfono
                val formattedPhone = "+57 ${senderPhoneDigits.substring(0,3)} ${senderPhoneDigits.substring(3,6)} ${senderPhoneDigits.substring(6)}"
                android.util.Log.d("ComprobanteActivity", "📞 Usando teléfono formateado como nombre: $formattedPhone")
                formattedPhone
            } else {
                android.util.Log.d("ComprobanteActivity", "✅ Usando nombre real: $cleanedSenderName")
                cleanedSenderName
            }
                            
            // 🔧 Generar referencia para movimiento INCOMING (misma que OUTGOING)
            val referencia = generateReference()
            
            // Crear movimiento INCOMING con nombre real del remitente SIN TILDES
            val incomingMovement = Movement(
                id = "", // ← VACÍO para que Firebase genere ID automático único
                name = finalName, // ✅ Nombre real o teléfono formateado (nunca "NEQUI SAN")
                amount = amount,
                date = Date(),
                phone = senderPhoneDigits,
                type = MovementType.INCOMING,
                isIncoming = true,
                                isQrPayment = false,
                                mvalue = referencia // ✅ Guardar referencia para usar en detalles
            )
            
            android.util.Log.d("ComprobanteActivity", "📥 Creando INCOMING: name=$senderName, amount=$amount, phone=$senderPhoneDigits")
            
            // Nota: El saldo ya se actualizó ANTES de entrar a este callback (línea ~490)
            // Solo guardamos el movimiento aquí, el saldo ya está actualizado
            
            // Guardar con nombre real
            e.saveMovementForUser(recipientPhoneDigits, incomingMovement) { success, error ->
                if (success) {
                    android.util.Log.d("ComprobanteActivity", "✅ INCOMING guardado exitosamente con nombre: $senderName")
                    
                    // 🔔 CREAR NOTIFICACIÓN PERSISTENTE EN FIREBASE para que el receptor la detecte
                    // Esto funciona incluso si el servicio no está corriendo
                    lifecycleScope.launch {
                        try {
                            val recipientDocumentId = getUserDocumentIdByPhone(recipientPhoneDigits)
                            if (recipientDocumentId != null) {
                                val notificationData = mapOf(
                                    "type" to "money_received",
                                    "sender_name" to finalName,
                                    "amount" to amount,
                                    "message" to "$finalName te envió $$amount, ¡lo mejor!",
                                    "timestamp" to com.google.firebase.Timestamp.now(),
                                    "read" to false,
                                    "movement_id" to incomingMovement.id.ifEmpty { "pending_${System.currentTimeMillis()}" }
                                )
                                
                                db.collection("users")
                                    .document(recipientDocumentId)
                                    .collection("notifications")
                                    .add(notificationData)
                                    .addOnSuccessListener {
                                        android.util.Log.d("ComprobanteActivity", "✅ Notificación persistente creada en Firebase")
                                    }
                                    .addOnFailureListener { error ->
                                        android.util.Log.e("ComprobanteActivity", "❌ Error creando notificación persistente: ${error.message}")
                                    }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ComprobanteActivity", "❌ Error en notificación persistente: ${e.message}")
                        }
                    }
                    
                    // 🔔 TAMBIÉN intentar enviar notificación push real
                    sendRealPushNotification(recipientPhoneDigits, finalName, amount)
                    
                    android.util.Log.d("ComprobanteActivity", "✅ Notificación push enviada al receptor: $recipientPhoneDigits")
                    android.util.Log.d("ComprobanteActivity", "📲 Movimiento guardado y notificaciones enviadas")
                    
                } else {
                    android.util.Log.e("ComprobanteActivity", "❌ Error guardando INCOMING: $error")
                }
            }
        }
    }
    
    /**
     * Busca el nombre real del REMITENTE para el movimiento INCOMING
     * Prioridad: 1) contactos del receptor, 2) Firebase users, 3) número sin formatear
     */
    private fun resolveSenderNameForIncoming(senderPhoneDigits: String, cb: (String) -> Unit) {
        if (senderPhoneDigits.isBlank()) {
            cb("")
            return
        }
        
        val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        val recipientPhone = prefs.getString("user_phone", null).orEmpty()
        
        lifecycleScope.launch {
            try {
                val recipientDocumentId = getUserDocumentIdByPhone(recipientPhone)
                
                if (recipientDocumentId != null) {
                    val contactDoc = db.collection("users").document(recipientDocumentId)
                        .collection("contacts").document(senderPhoneDigits)
                        .get()
                        .await()
                    
                    val contactName = contactDoc.getString("name")?.trim().orEmpty()
                    if (contactName.isNotEmpty()) {
                        cb(contactName)
                        return@launch
                    }
                }
                
                val userQuery = db.collection("users")
                    .whereEqualTo("telefono", senderPhoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (!userQuery.isEmpty) {
                    val realName = userQuery.documents.first().getString("name")?.trim().orEmpty()
                    android.util.Log.d("ComprobanteActivity", "🔍 Nombre encontrado en Firebase: '$realName' para $senderPhoneDigits")
                    if (realName.isNotBlank() && 
                        !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                        !realName.equals("USUARIO NEQUI", ignoreCase = true) &&
                        !realName.equals("NEQUI SAN", ignoreCase = true)) {
                        android.util.Log.d("ComprobanteActivity", "✅ Usando nombre real: $realName")
                        cb(realName)
                        return@launch
                    } else {
                        android.util.Log.w("ComprobanteActivity", "⚠️ Nombre inválido o vacío: '$realName', usando teléfono formateado")
                    }
                } else {
                    android.util.Log.w("ComprobanteActivity", "⚠️ Usuario no encontrado en Firebase para: $senderPhoneDigits")
                }
                
                // Si no se encontró nombre válido, formatear teléfono en lugar de devolver solo números
                val formattedPhone = "+57 ${senderPhoneDigits.substring(0,3)} ${senderPhoneDigits.substring(3,6)} ${senderPhoneDigits.substring(6)}"
                android.util.Log.d("ComprobanteActivity", "📞 Usando teléfono formateado: $formattedPhone")
                cb(formattedPhone)
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "Error resolviendo nombre del remitente: ${e.message}")
                cb(senderPhoneDigits)
            }
        }
    }
    
    
    /**
     * Obtiene el nombre del receptor para la notificación del remitente
     */
    private fun getRecipientNameForNotification(recipientPhoneDigits: String, callback: (String) -> Unit) {
        val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        val senderPhone = prefs.getString("user_phone", null).orEmpty()
        
        lifecycleScope.launch {
            try {
                val senderDocumentId = getUserDocumentIdByPhone(senderPhone)
                
                if (senderDocumentId != null) {
                    val contactDoc = db.collection("users").document(senderDocumentId)
                        .collection("contacts").document(recipientPhoneDigits)
                        .get()
                        .await()
                    
                    val contactName = contactDoc.getString("name")?.trim().orEmpty()
                    if (contactName.isNotEmpty()) {
                        callback(toTitleCase(contactName))
                        return@launch
                    }
                }
                
                val userQuery = db.collection("users")
                    .whereEqualTo("telefono", recipientPhoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (!userQuery.isEmpty) {
                    val realName = userQuery.documents.first().getString("name")?.trim().orEmpty()
                    if (realName.isNotBlank() && 
                        !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) && 
                        !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                        callback(toTitleCase(realName))
                        return@launch
                    }
                }
                
                callback(recipientPhoneDigits)
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "Error obteniendo nombre para notificación: ${e.message}")
                callback(recipientPhoneDigits)
            }
        }
    }
    
    /**
     * Actualiza el saldo del receptor cuando recibe dinero
     * ✅ CRÍTICO: Esta función DEBE ejecutarse para que el dinero llegue al receptor
     */
    private fun updateRecipientBalance(recipientPhoneDigits: String, amount: Double) {
        // Normalizar teléfono: solo dígitos, máximo 10
        val normalizedPhone = recipientPhoneDigits.filter { it.isDigit() }.let { 
            if (it.length > 10) it.takeLast(10) else it 
        }
        
        android.util.Log.d("ComprobanteActivity", "💰💰💰 INICIANDO ACTUALIZACIÓN DE SALDO")
        android.util.Log.d("ComprobanteActivity", "   Receptor (original): $recipientPhoneDigits")
        android.util.Log.d("ComprobanteActivity", "   Receptor (normalizado): $normalizedPhone")
        android.util.Log.d("ComprobanteActivity", "   Monto: $amount")
        
        if (normalizedPhone.length != 10) {
            android.util.Log.e("ComprobanteActivity", "❌❌❌ Teléfono receptor inválido: '$normalizedPhone' (debe tener 10 dígitos)")
            return
        }
        
        // Convertir amount a Long para consistencia con el resto del código
        val amountLong = amount.toLong()
        
        if (amountLong <= 0) {
            android.util.Log.e("ComprobanteActivity", "❌❌❌ Monto inválido: $amountLong (debe ser mayor a 0)")
            return
        }
        
        // Buscar el email document ID usando el número de teléfono
        lifecycleScope.launch {
            try {
                android.util.Log.d("ComprobanteActivity", "🔍 Buscando usuario receptor en Firebase...")
                val query = db.collection("users")
                    .whereEqualTo("telefono", normalizedPhone)
                    .limit(1)
                    .get()
                    .await()
                
                if (query.isEmpty) {
                    android.util.Log.e("ComprobanteActivity", "❌❌❌ USUARIO RECEPTOR NO ENCONTRADO con telefono: $normalizedPhone")
                    android.util.Log.e("ComprobanteActivity", "❌ Verifica que el usuario existe en Firebase con el campo 'telefono' = '$normalizedPhone'")
                    return@launch
                }
                
                val userDoc = query.documents.first()
                val userDocumentId = userDoc.id // Este es el correo (ej: usertest@gmail.com)
                val userRef = db.collection("users").document(userDocumentId)
                
                android.util.Log.d("ComprobanteActivity", "✅ Usuario receptor encontrado: $normalizedPhone (doc: $userDocumentId)")
                
                // Leer saldo actual ANTES de la transacción para logging
                val currentBalanceDoc = userDoc.get("saldo")
                val currentBalanceBefore = when (currentBalanceDoc) {
                    is Number -> currentBalanceDoc.toLong()
                    is String -> currentBalanceDoc.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    else -> 0L
                }
                android.util.Log.d("ComprobanteActivity", "💰 Saldo actual del receptor (antes de actualizar): $currentBalanceBefore")
                
                // Usar transacción para actualizar saldo de forma segura
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(userRef)
                    // ✅ CORRECCIÓN: Usar readBalanceFlexible para leer como Long (consistente con el resto del código)
                    val currentBalance = readBalanceFlexible(snapshot, "saldo") ?: 0L
                    val newBalance = currentBalance + amountLong
                    
                    android.util.Log.d("ComprobanteActivity", "💰💰💰 DENTRO DE TRANSACCIÓN:")
                    android.util.Log.d("ComprobanteActivity", "   Saldo anterior: $currentBalance")
                    android.util.Log.d("ComprobanteActivity", "   Monto a sumar: $amountLong")
                    android.util.Log.d("ComprobanteActivity", "   Nuevo saldo calculado: $newBalance")
                    
                    transaction.update(userRef, "saldo", newBalance)
                    newBalance
                }.addOnSuccessListener { newBalance ->
                    android.util.Log.d("ComprobanteActivity", "✅✅✅✅✅ SALDO ACTUALIZADO EXITOSAMENTE ✅✅✅✅✅")
                    android.util.Log.d("ComprobanteActivity", "   Receptor: $normalizedPhone (doc: $userDocumentId)")
                    android.util.Log.d("ComprobanteActivity", "   Saldo anterior: $currentBalanceBefore")
                    android.util.Log.d("ComprobanteActivity", "   Monto recibido: $amountLong")
                    android.util.Log.d("ComprobanteActivity", "   Nuevo saldo: $newBalance")
                    
                    // Verificar que realmente se actualizó leyendo de nuevo
                    userRef.get().addOnSuccessListener { verifyDoc ->
                        val verifiedBalance = readBalanceFlexible(verifyDoc, "saldo") ?: 0L
                        if (verifiedBalance == newBalance) {
                            android.util.Log.d("ComprobanteActivity", "✅✅✅ VERIFICACIÓN: Saldo confirmado en Firebase: $verifiedBalance")
                        } else {
                            android.util.Log.e("ComprobanteActivity", "⚠️⚠️⚠️ ADVERTENCIA: Saldo no coincide. Esperado: $newBalance, Leído: $verifiedBalance")
                        }
                    }
                }.addOnFailureListener { error ->
                    android.util.Log.e("ComprobanteActivity", "❌❌❌❌❌ ERROR CRÍTICO ACTUALIZANDO SALDO ❌❌❌❌❌")
                    android.util.Log.e("ComprobanteActivity", "   Receptor: $normalizedPhone (doc: $userDocumentId)")
                    android.util.Log.e("ComprobanteActivity", "   Error: ${error.message}")
                    if (error is com.google.firebase.firestore.FirebaseFirestoreException) {
                        android.util.Log.e("ComprobanteActivity", "   Código Firestore: ${error.code}")
                    }
                    android.util.Log.e("ComprobanteActivity", "   Stack trace: ${error.stackTraceToString()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "❌❌❌ EXCEPCIÓN buscando usuario receptor")
                android.util.Log.e("ComprobanteActivity", "   Teléfono: $normalizedPhone")
                android.util.Log.e("ComprobanteActivity", "   Error: ${e.message}")
                android.util.Log.e("ComprobanteActivity", "   Stack trace: ${e.stackTraceToString()}")
            }
        }
    }
    
    /**
     * Crea una notificación persistente en Firebase que se activará cuando el receptor abra la app
     */
    private fun createPersistentNotification(recipientPhoneDigits: String, senderName: String, amount: Double) {
        android.util.Log.d("ComprobanteActivity", "🔔 Creando notificación persistente para: $recipientPhoneDigits")
        
        lifecycleScope.launch {
            val recipientDocumentId = getUserDocumentIdByPhone(recipientPhoneDigits)
            if (recipientDocumentId == null) {
                android.util.Log.e("ComprobanteActivity", "❌ No se encontró receptor con telefono: $recipientPhoneDigits")
                return@launch
            }
            
            val notificationData = mapOf(
                "type" to "money_received",
                "sender_name" to senderName,
                "amount" to amount,
                "message" to "$senderName te envió $$amount, ¡lo mejor!",
                "timestamp" to com.google.firebase.Timestamp.now(),
                "read" to false
            )
            
            // Guardar en Firebase para que el receptor la vea cuando abra la app
            db.collection("users")
                .document(recipientDocumentId)
                .collection("notifications")
                .add(notificationData)
                .addOnSuccessListener {
                    android.util.Log.d("ComprobanteActivity", "✅ Notificación persistente creada exitosamente")
                    
                    // ENVIAR PUSH NOTIFICATION REAL usando FCM
                    sendRealPushNotification(recipientPhoneDigits, senderName, amount)
                }
                .addOnFailureListener { error ->
                    android.util.Log.e("ComprobanteActivity", "❌ Error creando notificación persistente: ${error.message}")
                }
        }
    }
    
    /**
     * Envía push notification real usando FCM
     */
    private fun sendRealPushNotification(recipientPhoneDigits: String, senderName: String, amount: Double) {
        android.util.Log.d("ComprobanteActivity", "📱 Enviando PUSH NOTIFICATION REAL para: $recipientPhoneDigits")
        
        lifecycleScope.launch {
            val recipientDocumentId = getUserDocumentIdByPhone(recipientPhoneDigits)
            if (recipientDocumentId == null) {
                android.util.Log.e("ComprobanteActivity", "❌ No se encontró receptor con telefono: $recipientPhoneDigits")
                return@launch
            }
            
            // Buscar el FCM token del receptor
            db.collection("users")
                .document(recipientDocumentId)
                .get()
                .addOnSuccessListener { document ->
                    val fcmToken = document.getString("fcm_token")
                    if (!fcmToken.isNullOrEmpty()) {
                        android.util.Log.d("ComprobanteActivity", "🎯 Token FCM encontrado: ${fcmToken.take(20)}...")
                    
                        // Usar el NotificationManager existente para enviar push notification
                        lifecycleScope.launch {
                            try {
                                appNotificationManager.sendDirectFCMNotification(
                                    token = fcmToken,
                                    title = "Nequi Colombia",
                                    message = "$senderName te envió $$amount, ¡lo mejor!",
                                    data = mapOf(
                                        "type" to "money_received",
                                        "sender_name" to senderName,
                                        "amount" to amount.toString()
                                    )
                                )
                                android.util.Log.d("ComprobanteActivity", "📲 PUSH NOTIFICATION REAL enviada exitosamente")
                            } catch (e: Exception) {
                                android.util.Log.e("ComprobanteActivity", "❌ Error enviando push notification real: ${e.message}")
                            }
                        }
                    } else {
                        android.util.Log.w("ComprobanteActivity", "⚠️ Token FCM no encontrado para: $recipientPhoneDigits")
                    }
                }
                .addOnFailureListener { error ->
                    android.util.Log.e("ComprobanteActivity", "❌ Error buscando token FCM: ${error.message}")
                }
        }
    }
    
    /**
     * Genera una referencia en el formato M{8 dígitos aleatorios}
     * Ejemplo: M12345678
     */
    private fun generateReference(): String {
        val n = (10000000..99999999).random()
        return "M$n"
    }
}
