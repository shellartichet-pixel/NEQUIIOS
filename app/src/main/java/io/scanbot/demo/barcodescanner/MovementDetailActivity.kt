package io.scanbot.demo.barcodescanner

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.ios.nequixofficialv2.R
import io.scanbot.demo.barcodescanner.dialogs.setupLongPressDialog

class MovementDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECIPIENT = "extra_recipient"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_UNIQUE_ID = "extra_unique_id"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_MVALUE = "extra_mvalue"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_MSJ = "extra_msj"
        const val EXTRA_IMAGE_URL = "image_url"
    }

// Decodifica imagen con máxima calidad sin escalado
private fun decodeImageWithQuality(resolver: android.content.ContentResolver, uriOrPath: String): android.graphics.Bitmap? {
    return try {
        if (uriOrPath.startsWith("content://")) {
            val uri = android.net.Uri.parse(uriOrPath)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inScaled = false
                inDither = false
                inPreferQualityOverSpeed = true
                inMutable = false
                inSampleSize = 1
            }
            resolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, opts)
            }
        } else {
            val path = if (uriOrPath.startsWith("file://")) android.net.Uri.parse(uriOrPath).path ?: "" else uriOrPath
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inScaled = false
                inDither = false
                inPreferQualityOverSpeed = true
                inMutable = false
                inSampleSize = 1
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        }
    } catch (e: Exception) {
        android.util.Log.e("MovDetailQr", "Error decodificando imagen: ${e.message}")
        null
    }
}

// Decodifica un bitmap escalado a las dimensiones objetivo manteniendo proporción.
private fun decodeScaledBitmap(resolver: android.content.ContentResolver, uriOrPath: String, targetW: Int, targetH: Int): android.graphics.Bitmap? {
    return try {
        if (uriOrPath.startsWith("content://")) {
            val uri = android.net.Uri.parse(uriOrPath)
            // Leer bounds
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, bounds)
            }
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (sample <= 0) 1 else sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, opts)
            }
        } else {
            val path = if (uriOrPath.startsWith("file://")) android.net.Uri.parse(uriOrPath).path ?: "" else uriOrPath
            val optsBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, optsBounds)
            val sample = computeInSampleSize(optsBounds.outWidth, optsBounds.outHeight, targetW, targetH)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (sample <= 0) 1 else sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        }
    } catch (_: Exception) { null }
}

private fun computeInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    var inSampleSize = 1
    if (srcH > reqH || srcW > reqW) {
        var halfH = srcH / 2
        var halfW = srcW / 2
        while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

    private var loadingCircle: ImageView? = null
    private var imageMovementDetail: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_movement_detail)

        // Aplicar barra morada Nequi para evitar destellos al regresar
        try {
            window.statusBarColor = android.graphics.Color.parseColor("#200020")
            // Barra de navegación transparente para que no aparezca raya
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            val v = window.decorView
            @Suppress("DEPRECATION")
            run {
                // Iconos claros para barra morada
                v.systemUiVisibility = (v.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv())
            }
        } catch (_: Exception) {}

        // Configurar para pantalla completa - el contenido debe extenderse detrás de la barra de navegación
        val rootView = findViewById<android.view.View>(R.id.rootMovementDetail)
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewMovementDetail)
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            // No aplicar padding - permitir que el contenido se extienda hasta el borde
            v.setPadding(0, 0, 0, 0)
            // También aplicar al ScrollView si existe
            scrollView?.setPadding(0, 0, 0, 0)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        
        // Configurar diálogo de transacción no exitosa (presionar 1.5 segundos)
        rootView?.setupLongPressDialog(this)

        loadingCircle = findViewById(R.id.loadingCircle)
        imageMovementDetail = findViewById(R.id.imageMovementDetail)
        
        // Configurar long press en el ImageView para detectar presiones
        imageMovementDetail?.setupLongPressDialog(this)

        // Plantilla de detalles de movimientos es INDIVIDUAL - SIEMPRE generar desde cero
        // NO usar imagen guardada del comprobante, siempre generar con micstolakm.pronf + 2ditalls.cache
        android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        android.util.Log.d("MovDetailQr", "📂 GENERANDO PLANTILLA INDIVIDUAL DE DETALLES")
        android.util.Log.d("MovDetailQr", "   Plantilla: micstolakm.pronf + 2ditalls.cache")
        android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
            loadingCircle?.visibility = View.VISIBLE
            generateDetailTemplateNequi()

        // Botones invisibles superpuestos
        findViewById<View?>(R.id.btnBack)?.setOnClickListener { finishWithoutAnimation() }
        findViewById<View?>(R.id.btnQuestion)?.setOnClickListener {
            val handle = "@Sangre_binerojs"
            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Contacta al asesor")
                .setMessage("Para soporte, escribe a $handle")
                .setPositiveButton("Copiar") { d, _ ->
                    val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("asesor", handle))
                    Toast.makeText(this, "Copiado: $handle", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNegativeButton("Cerrar") { d, _ -> d.dismiss() }
                .create()
            dlg.show()
        }
        findViewById<View?>(R.id.btnShare)?.setOnClickListener {
            val url = intent.getStringExtra(EXTRA_IMAGE_URL)
            if (!url.isNullOrBlank()) {
                try {
                    val uri = when {
                        url.startsWith("content://") -> android.net.Uri.parse(url)
                        url.startsWith("file://") -> {
                            val f = java.io.File(android.net.Uri.parse(url).path ?: "")
                            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                        }
                        else -> {
                            val f = java.io.File(url)
                            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                        }
                    }
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(share, "Compartir comprobante"))
                } catch (_: Exception) {
                    Toast.makeText(this, "No se pudo compartir", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finishWithoutAnimation()
            true
        } else super.onOptionsItemSelected(item)
    }
    
    private fun finishWithoutAnimation() {
        // Desactivar animaciones para evitar destellos al regresar
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }

    private fun generateDetailTemplateNequi() {
        Thread {
            try {
                // Extraer datos del Intent
                val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "Desconocido"
                val amount = (intent.getStringExtra(EXTRA_AMOUNT) ?: "$0").replace("-", "").trim()
                val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""
                val dateMillis = intent.getStringExtra(EXTRA_DATE)?.toLongOrNull() ?: System.currentTimeMillis()
                val mvalue = intent.getStringExtra(EXTRA_MVALUE) ?: ""
                val isQrPayment = intent.getBooleanExtra("IS_QR_PAYMENT", false)
                val movementTypeStr = intent.getStringExtra(EXTRA_TYPE) ?: ""
                val msj = intent.getStringExtra(EXTRA_MSJ) ?: "" // Obtener descripción/mensaje
                
                // Detectar tipo de movimiento
                val isKeySend = movementTypeStr.contains("key_voucher", ignoreCase = true) || 
                               movementTypeStr.contains("KEY_VOUCHER", ignoreCase = true)
                val isBancolombia = movementTypeStr.contains("bancolombia", ignoreCase = true) || 
                                  movementTypeStr.contains("BANCOLOMBIA", ignoreCase = true)
                val isQr = isQrPayment || movementTypeStr.contains("qr_vouch", ignoreCase = true) || 
                          movementTypeStr.contains("QR_VOUCH", ignoreCase = true)
                val isPagoAnulado = msj.contains("Pago Anulado", ignoreCase = true) // Detectar "Pago Anulado"
                
                android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                android.util.Log.d("MovDetailQr", "🔍 DETECCIÓN DE TIPO DE PAGO:")
                android.util.Log.d("MovDetailQr", "   IS_QR_PAYMENT flag = $isQrPayment")
                android.util.Log.d("MovDetailQr", "   movementType = $movementTypeStr")
                android.util.Log.d("MovDetailQr", "   msj = $msj")
                android.util.Log.d("MovDetailQr", "   isQr = $isQr, isKeySend = $isKeySend, isBancolombia = $isBancolombia, isPagoAnulado = $isPagoAnulado")
                android.util.Log.d("MovDetailQr", "   Recipient = $recipient")
                android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                // Seleccionar plantilla según tipo de pago
                // Para Pago Anulado: usar cickel_kalvore.cache
                // Para Bancolombia: usar janskis_banco_detals.cache
                // Para QR y llaves: usar micstolakm.pronf (completa, sin recortes)
                // Para Nequi normal: usar res_detail_0xf4a1.bin
                val templateNames = when {
                    isPagoAnulado -> arrayOf("cickel_kalvore.cache")
                    isBancolombia -> arrayOf("janskis_banco_detals.cache")
                    isQr || isKeySend -> arrayOf("micstolakm.pronf")
                    else -> arrayOf("res_detail_0xf4a1.bin", "details_nequi_movements.jpg")
                }
                
                val originalBitmap = loadTemplate(templateNames) 
                    ?: throw Exception("No se encontró plantilla de detalles")
                
                // ULTRA MEGA 4K SUPREMA: Escalar plantilla 3x con máxima calidad
                val originalWidth = originalBitmap.width
                val originalHeight = originalBitmap.height
                val scaledWidth = originalWidth * 3
                val scaledHeight = originalHeight * 3
                
                android.util.Log.d("MovDetailQr", "🚀 ULTRA 4K: Escalando plantilla ${originalWidth}x${originalHeight} -> ${scaledWidth}x${scaledHeight}")
                
                // Escalar con máxima calidad
                val baseBitmap = android.graphics.Bitmap.createScaledBitmap(
                    originalBitmap,
                    scaledWidth,
                    scaledHeight,
                    true  // filter = true para mejor calidad
                )
                
                android.util.Log.d("MovDetailQr", "✅ Plantilla escalada 3x: ${baseBitmap.width}x${baseBitmap.height}")
                
                // Formatear fecha
                val date = java.util.Date(dateMillis)
                val dateFormatted = formatDateEs(date)
                
                // Formatear teléfono del destinatario
                val phoneFormatted = if (phone.length == 10) {
                    "${phone.substring(0, 3)} ${phone.substring(3, 6)} ${phone.substring(6)}"
                } else {
                    phone
                }
                
                // Obtener número del usuario (el que envía) desde SharedPreferences
                val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
                val userPhoneRaw = prefs.getString("user_phone", null).orEmpty()
                val userPhoneFormatted = if (userPhoneRaw.length == 10) {
                    "${userPhoneRaw.substring(0, 3)} ${userPhoneRaw.substring(3, 6)} ${userPhoneRaw.substring(6)}"
                } else {
                    userPhoneRaw
                }
                
                // Obtener llave guardada (para envíos por llaves Y pagos QR)
                val keyVoucher = intent.getStringExtra("key_voucher") ?: ""
                android.util.Log.d("MovDetailQr", "🔑 Llave recibida del intent: '$keyVoucher'")
                android.util.Log.d("MovDetailQr", "   isKeySend: $isKeySend, isQr: $isQr")
                
                // Obtener banco destino
                val bancoDestino = intent.getStringExtra("banco") ?: ""
                android.util.Log.d("MovDetailQr", "🏦 Banco destino recibido: '$bancoDestino'")
                
                // Formatear llave para Llaves: siempre @ + primera letra mayúscula + resto minúsculas
                // Ejemplo: @torta → @Torta, @TORTA → @Torta, @ToRta → @Torta
                val formatKeyForLlaves = { key: String ->
                    if (key.startsWith("@")) {
                        val withoutAt = key.substring(1)
                        if (withoutAt.isNotEmpty()) {
                            val firstChar = withoutAt.first().uppercaseChar()
                            val rest = withoutAt.substring(1).lowercase()
                            "@$firstChar$rest"
                        } else {
                            key
                        }
                    } else {
                        key
                    }
                }
                
                val llaveToShow = when {
                    // Si hay keyVoucher guardado (para QR o Llaves), usarlo
                    keyVoucher.isNotBlank() -> {
                        val formatted = if (isKeySend) formatKeyForLlaves(keyVoucher) else keyVoucher
                        android.util.Log.d("MovDetailQr", "✅ Usando llave guardada: '$keyVoucher' -> formateada: '$formatted'")
                        formatted
                    }
                    // Si es envío por llaves pero no hay keyVoucher, usar el phone como llave
                    isKeySend -> {
                        android.util.Log.d("MovDetailQr", "⚠️ No hay llave guardada para Llaves, usando phone: '$phoneFormatted'")
                        phoneFormatted
                    }
                    // Para QR sin llave guardada, usar el teléfono (fallback)
                    else -> {
                        android.util.Log.d("MovDetailQr", "⚠️ No hay llave guardada para QR, usando phone: '$phoneFormatted'")
                        phoneFormatted
                    }
                }
                
                android.util.Log.d("MovDetailQr", "🔑 Llave que se mostrará: '$llaveToShow'")
                
                // Generar referencia si no existe
                val reference = if (mvalue.isNotBlank()) mvalue else generateReference()
                
                // Ofuscar nombre para Llaves y Bancolombia (ejemplo: "Maria Vergara" -> "Mar** Ver****")
                val recipientToShow = if (isKeySend || isBancolombia) {
                    maskNameForLlavesBancolombia(recipient)
                } else {
                    toTitleCase(recipient)
                }
                android.util.Log.d("MovDetailQr", "👤 Nombre original: '$recipient', Nombre a mostrar: '$recipientToShow'")
                
                // Para QR y Llaves: micstolakm.pronf es COMPLETA y SIN RECORTES
                // Solo dibujar los campos encima, sin modificar la plantilla base
                val finalBitmap = if (isQr || isKeySend) {
                    // micstolakm.pronf ya está completa, solo dibujar campos encima
                    composeDetailOverlay(
                        base = baseBitmap,
                        para = recipientToShow,
                        cuanto = amount,
                        numeroNequi = llaveToShow, // Llave o teléfono
                        fecha = dateFormatted,
                        referencia = reference,
                        disponible = "Disponible",
                        isQrPayment = true, // Usar coordenadas QR para ambos
                        userPhone = userPhoneFormatted, // Número del usuario que envía
                        bancoDestino = bancoDestino // Banco destino del movimiento
                    )
                } else if (isBancolombia) {
                    // Bancolombia usa janskis_banco_detals.cache (plantilla específica para Bancolombia)
                    // Usa coordenadas específicas para esta plantilla
                    composeDetailOverlay(
                        base = baseBitmap,
                        para = recipientToShow,
                        cuanto = amount,
                        numeroNequi = phoneFormatted, // Número de cuenta de Bancolombia
                        fecha = dateFormatted,
                        referencia = reference,
                        disponible = "Disponible",
                        isQrPayment = false, // No usar coordenadas QR
                        isBancolombia = true, // Indicar que es Bancolombia para usar coordenadas específicas
                        userPhone = userPhoneFormatted, // Número del usuario que envía
                        bancoDestino = bancoDestino // Banco destino del movimiento
                    )
                } else if (isPagoAnulado) {
                    // Pago Anulado: usar cickel_kalvore.cache (plantilla específica para Pago Anulado)
                    // Solo muestra: nombre, cantidad, fecha y referencia (sin teléfono ni disponible)
                    composeDetailOverlay(
                        base = baseBitmap,
                        para = toTitleCase(recipient),
                        cuanto = amount,
                        numeroNequi = "", // Pago Anulado no muestra teléfono
                        fecha = dateFormatted,
                        referencia = reference,
                        disponible = "", // Pago Anulado no muestra disponible
                        isQrPayment = false,
                        isPagoAnulado = true // Indicar que es Pago Anulado para usar coordenadas específicas
                    )
                } else {
                    // Nequi normal: usar res_detail_0xf4a1.bin (solo primera parte)
                    val firstPart = composeDetailOverlay(
                        base = baseBitmap,
                        para = toTitleCase(recipient),
                        cuanto = amount,
                        numeroNequi = phoneFormatted,
                        fecha = dateFormatted,
                        referencia = reference,
                        disponible = "Disponible",
                        isQrPayment = false
                    )
                    
                    // 🔥 DETECTAR COLOR DEL FONDO de la plantilla y aplicarlo al área final
                    applyBackgroundColorToFinalArea(firstPart)
                }
                
                runOnUiThread {
                    loadingCircle?.visibility = View.GONE
                    imageMovementDetail?.setImageBitmap(finalBitmap)
                    // Usar FIT_START para que muestre desde arriba y permita scroll
                    imageMovementDetail?.scaleType = android.widget.ImageView.ScaleType.FIT_START
                    android.util.Log.d("MovDetailQr", "✅ Imagen mostrada en UI. isQR=$isQrPayment, dimensions=${finalBitmap.width}x${finalBitmap.height}")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("MovDetailNequi", "Error generando plantilla: ${e.message}")
                runOnUiThread {
                    loadingCircle?.visibility = View.GONE
                    Toast.makeText(this, "Error generando comprobante", Toast.LENGTH_SHORT).show()
                    imageMovementDetail?.setImageResource(R.drawable.ic_launcher_foreground)
                }
            }
        }.start()
    }
    
    private fun combineBitmaps(top: android.graphics.Bitmap, bottom: android.graphics.Bitmap): android.graphics.Bitmap {
        val topConverted = if (top.config != android.graphics.Bitmap.Config.ARGB_8888) {
            top.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else top
        
        val bottomConverted = if (bottom.config != android.graphics.Bitmap.Config.ARGB_8888) {
            bottom.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else bottom
        
        val width = topConverted.width
        val scaledBottom = if (bottomConverted.width != width) {
            val aspectRatio = bottomConverted.height.toFloat() / bottomConverted.width.toFloat()
            val newHeight = (width * aspectRatio).toInt()
            android.graphics.Bitmap.createScaledBitmap(bottomConverted, width, newHeight, true)
        } else {
            bottomConverted
        }
        
        // 🔥 SOLUCIÓN FINAL: Eliminar líneas divisorias AGRESIVAMENTE
        // Recortar muchas líneas para eliminar completamente la línea divisoria
        val linesToRemoveBottom = 15 // Eliminar 15 píxeles del inicio de la segunda parte
        val linesToRemoveTop = 5 // Eliminar 5 píxeles del final de la primera parte
        
        android.util.Log.d("MovDetailNequi", "🔧 Eliminando $linesToRemoveBottom líneas de segunda parte y $linesToRemoveTop de primera parte")
        
        // Recortar líneas de la segunda parte
        val bottomWithoutDivider = if (linesToRemoveBottom > 0 && linesToRemoveBottom < scaledBottom.height) {
            android.graphics.Bitmap.createBitmap(
                scaledBottom,
                0,
                linesToRemoveBottom,
                scaledBottom.width,
                scaledBottom.height - linesToRemoveBottom
            )
        } else {
            scaledBottom
        }
        
        // Recortar líneas del final de la primera parte
        val topWithoutDivider = if (linesToRemoveTop > 0 && linesToRemoveTop < topConverted.height) {
            android.graphics.Bitmap.createBitmap(
                topConverted,
                0,
                0,
                topConverted.width,
                topConverted.height - linesToRemoveTop
            )
        } else {
            topConverted
        }
        
        // 🔥 PEGAR DIRECTAMENTE: La segunda parte se dibuja JUSTO donde termina la primera
        // Altura = primera parte (sin las líneas eliminadas) + segunda parte (sin las líneas eliminadas)
        val height = topWithoutDivider.height + bottomWithoutDivider.height
        
        val combined = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        combined.eraseColor(0xFFFFFFFF.toInt())
        
        val canvas = android.graphics.Canvas(combined)
        canvas.drawColor(0xFFFFFFFF.toInt())
        
        val paint = android.graphics.Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
            alpha = 255
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        }
        
        // Dibujar primera parte
        canvas.drawBitmap(topWithoutDivider, 0f, 0f, paint)
        
        // 🔥 SEGUNDA PARTE PEGADA DIRECTAMENTE: Sin espacios, sin overlap, directamente pegada
        val bottomY = topWithoutDivider.height.toFloat()
        android.util.Log.d("MovDetailNequi", "🔧 Dibujando segunda parte en Y=$bottomY (pegada directamente)")
        canvas.drawBitmap(bottomWithoutDivider, 0f, bottomY, paint)
        
        // 🔥 ELIMINAR CUALQUIER LÍNEA BLANCA/GRIS en la zona de unión
        val joinY = topWithoutDivider.height
        val finalCombined = removeDividerLineFromCombined(combined, joinY, 20) // Ampliar radio para eliminar completamente
        
        android.util.Log.d("MovDetailNequi", "✅ Bitmaps combinados sin raya: ${finalCombined.width}x${finalCombined.height}")
        return finalCombined
    }
    
    /**
     * Detecta líneas divisorias en la parte superior de la segunda parte
     */
    private fun detectDividerLines(bottom: android.graphics.Bitmap): Int {
        if (bottom.height < 2) return 0
        
        var linesToRemove = 0
        val maxLinesToCheck = kotlin.math.min(10, bottom.height)
        
        for (y in 0 until maxLinesToCheck) {
            var whiteOrGrayPixels = 0
            
            for (x in 0 until bottom.width step 2) {
                val pixel = bottom.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                val isWhite = r >= 245 && g >= 245 && b >= 245
                val isLightGray = r >= 230 && g >= 230 && b >= 230 && r < 245
                val isMediumGray = r >= 200 && g >= 200 && b >= 200 && 
                                  kotlin.math.abs(r - g) < 15 && 
                                  kotlin.math.abs(r - b) < 15 &&
                                  kotlin.math.abs(g - b) < 15
                
                if (isWhite || isLightGray || isMediumGray) {
                    whiteOrGrayPixels++
                }
            }
            
            val sampleSize = (bottom.width / 2)
            if (whiteOrGrayPixels >= sampleSize * 0.6) {
                linesToRemove++
            } else {
                break
            }
        }
        
        return linesToRemove
    }
    
    /**
     * Detecta líneas divisorias en la parte inferior de la primera parte
     */
    private fun detectDividerLinesInBottom(top: android.graphics.Bitmap): Int {
        if (top.height < 2) return 0
        
        var linesToRemove = 0
        val maxLinesToCheck = kotlin.math.min(10, top.height)
        
        for (offset in 0 until maxLinesToCheck) {
            val y = top.height - 1 - offset
            if (y < 0) break
            
            var whiteOrGrayPixels = 0
            
            for (x in 0 until top.width step 2) {
                val pixel = top.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                val isWhite = r >= 245 && g >= 245 && b >= 245
                val isLightGray = r >= 230 && g >= 230 && b >= 230 && r < 245
                val isMediumGray = r >= 200 && g >= 200 && b >= 200 && 
                                  kotlin.math.abs(r - g) < 15 && 
                                  kotlin.math.abs(r - b) < 15 &&
                                  kotlin.math.abs(g - b) < 15
                
                if (isWhite || isLightGray || isMediumGray) {
                    whiteOrGrayPixels++
                }
            }
            
            val sampleSize = (top.width / 2)
            if (whiteOrGrayPixels >= sampleSize * 0.6) {
                linesToRemove++
            } else {
                break
            }
        }
        
        return linesToRemove
    }
    
    /**
     * SOLUCIÓN FINAL: Reemplaza líneas blancas/grises con el color del fondo para que NO se note
     */
    private fun removeDividerLineFromCombined(combined: android.graphics.Bitmap, joinY: Int, radius: Int): android.graphics.Bitmap {
        val mutable = combined.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        
        val startY = kotlin.math.max(0, joinY - radius)
        val endY = kotlin.math.min(mutable.height - 1, joinY + radius)
        
        // 🔥 DETECTAR COLOR DEL FONDO alrededor de la unión para usar ese mismo color
        var backgroundR = 255
        var backgroundG = 255
        var backgroundB = 255
        var sampleCount = 0
        
        // Muestrear píxeles arriba y abajo de la zona de unión para detectar el color de fondo
        for (offset in 1..5) {
            val yAbove = joinY - offset
            val yBelow = joinY + offset
            
            if (yAbove >= 0 && yAbove < mutable.height) {
                val pixel = mutable.getPixel(mutable.width / 2, yAbove)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                // Si no es completamente blanco, usar este color como referencia
                if (r < 250 || g < 250 || b < 250) {
                    backgroundR += r
                    backgroundG += g
                    backgroundB += b
                    sampleCount++
                }
            }
            
            if (yBelow >= 0 && yBelow < mutable.height) {
                val pixel = mutable.getPixel(mutable.width / 2, yBelow)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                if (r < 250 || g < 250 || b < 250) {
                    backgroundR += r
                    backgroundG += g
                    backgroundB += b
                    sampleCount++
                }
            }
        }
        
        // Calcular promedio del color de fondo, o usar blanco casi puro si no hay muestras
        val bgColor = if (sampleCount > 0) {
            android.graphics.Color.rgb(
                backgroundR / (sampleCount + 1),
                backgroundG / (sampleCount + 1),
                backgroundB / (sampleCount + 1)
            )
        } else {
            // Color muy sutil: blanco con un toque casi imperceptible
            android.graphics.Color.rgb(255, 254, 253)
        }
        
        android.util.Log.d("MovDetailNequi", "🔧 Reemplazando líneas blancas con color de fondo desde Y=$startY hasta Y=$endY")
        
        var pixelsModified = 0
        
        // 🔥 REEMPLAZAR LÍNEAS BLANCAS/GRISES con el color del fondo detectado
        for (y in startY..endY) {
            var whiteOrGrayCount = 0
            var totalPixels = 0
            
            for (x in 0 until mutable.width) {
                val pixel = mutable.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                // 🔥 DETECTAR: blancos, grises Y LÍNEAS NEGRAS
                val isWhiteOrLightGray = (r >= 220 && g >= 220 && b >= 220)
                val isDarkGrayOrBlack = (r <= 50 && g <= 50 && b <= 50) // Líneas negras/grises muy oscuras
                val isMediumGray = (r >= 200 && g >= 200 && b >= 200 && 
                                   kotlin.math.abs(r - g) < 25 && 
                                   kotlin.math.abs(r - b) < 25 &&
                                   kotlin.math.abs(g - b) < 25)
                
                if (isWhiteOrLightGray || isDarkGrayOrBlack || isMediumGray) {
                    whiteOrGrayCount++
                }
                totalPixels++
            }
            
            // Si más del 50% de la línea es blanco/gris/NEGRO, reemplazar con color de fondo
            if (whiteOrGrayCount >= totalPixels * 0.5) {
                for (x in 0 until mutable.width) {
                    mutable.setPixel(x, y, bgColor)
                    pixelsModified++
                }
            }
        }
        
        android.util.Log.d("MovDetailNequi", "✅ Reemplazadas líneas con color de fondo: $pixelsModified píxeles modificados")
        return mutable
    }
    
    /**
     * Detecta el color del fondo de la plantilla y lo aplica al área final (inferior)
     * para que toda la parte inferior tenga el mismo color que el fondo de la plantilla
     */
    private fun applyBackgroundColorToFinalArea(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val mutable = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        
        // 🔥 DETECTAR COLOR DEL FONDO de la plantilla
        // Muestrear varios puntos del área superior y media para detectar el color de fondo
        var backgroundR = 0
        var backgroundG = 0
        var backgroundB = 0
        var sampleCount = 0
        
        // Muestrear puntos en diferentes áreas de la plantilla (evitando texto)
        val sampleYPositions = listOf(
            50, // Parte superior
            150, // Parte media superior
            300, // Parte media
            450 // Parte media inferior
        )
        
        val sampleXPositions = listOf(
            mutable.width / 4,
            mutable.width / 2,
            mutable.width * 3 / 4
        )
        
        for (y in sampleYPositions) {
            if (y >= mutable.height) continue
            
            for (x in sampleXPositions) {
                if (x >= mutable.width) continue
                
                val pixel = mutable.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                // Filtrar colores muy oscuros (probablemente texto) y muy saturados
                // Solo usar colores claros o blancos como referencia de fondo
                if (r >= 200 && g >= 200 && b >= 200 && 
                    kotlin.math.abs(r - g) < 30 && 
                    kotlin.math.abs(r - b) < 30) {
                    backgroundR += r
                    backgroundG += g
                    backgroundB += b
                    sampleCount++
                }
            }
        }
        
        // Calcular color promedio del fondo
        val bgColor = if (sampleCount > 0) {
            android.graphics.Color.rgb(
                backgroundR / sampleCount,
                backgroundG / sampleCount,
                backgroundB / sampleCount
            )
        } else {
            // Fallback: color blanco casi puro si no se detecta
            android.graphics.Color.rgb(255, 254, 253)
        }
        
        android.util.Log.d("MovDetailNequi", "🔧 Color de fondo detectado: RGB(${android.graphics.Color.red(bgColor)}, ${android.graphics.Color.green(bgColor)}, ${android.graphics.Color.blue(bgColor)})")
        
        // 🔥 SOLO APLICAR a líneas divisorias puras MUY AL FINAL (últimos 50 píxeles)
        // NO tocar área donde está "Disponible" y otros campos de texto
        // Solo procesar el borde inferior donde realmente están las líneas divisorias
        val finalAreaStartY = kotlin.math.max(0, mutable.height - 50) // Solo últimas 50 líneas
        var pixelsModified = 0
        
        // Revisar línea por línea solo en el borde inferior para detectar líneas divisorias puras
        for (y in finalAreaStartY until mutable.height) {
            // Muestrear píxeles de esta línea para detectar si es una línea divisoria uniforme
            var dividerColorCount = 0
            var totalSampled = 0
            var hasTextVariation = false
            
            // Muestrear varios puntos de la línea (muy espaciado para detectar solo líneas puras)
            for (x in 0 until mutable.width step 25) {
                val pixel = mutable.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                val isWhiteOrLightGray = (r >= 220 && g >= 220 && b >= 220)
                val isDarkGrayOrBlack = (r <= 50 && g <= 50 && b <= 50)
                val isMediumGray = (r >= 100 && g >= 100 && b >= 100 && r <= 200 && g <= 200 && b <= 200 &&
                                   kotlin.math.abs(r - g) < 30 && kotlin.math.abs(r - b) < 30)
                
                if (isWhiteOrLightGray || isDarkGrayOrBlack || isMediumGray) {
                    dividerColorCount++
                } else if (r < 150 || g < 150 || b < 150) {
                    // Si hay un color oscuro (texto), marcar como variación
                    hasTextVariation = true
                    break // Salir inmediatamente si detecta texto
                }
                totalSampled++
            }
            
            // Solo reemplazar si es una línea divisoria COMPLETAMENTE uniforme (98% o más)
            // Y NO debe haber NINGUNA variación de texto
            if (dividerColorCount >= totalSampled * 0.98 && !hasTextVariation && totalSampled > 0) {
                // Esta es una línea divisoria pura sin texto, reemplazar toda la línea
                for (x in 0 until mutable.width) {
                    mutable.setPixel(x, y, bgColor)
                    pixelsModified++
                }
            }
        }
        
        android.util.Log.d("MovDetailNequi", "✅ Color de fondo aplicado solo a líneas divisorias puras (últimas 50 líneas): $pixelsModified píxeles modificados")
        return mutable
    }
    
    private fun loadTemplate(names: Array<String>): android.graphics.Bitmap? {
        for (name in names) {
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, name).use { inp ->
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888  // Máxima calidad de color
                        inScaled = false  // CRÍTICO: No escalar, mantener tamaño original
                        inDither = false  // Sin dithering para mejor nitidez
                        inPreferQualityOverSpeed = true  // Priorizar calidad sobre velocidad
                        inMutable = true  // Permitir modificaciones posteriores
                        inSampleSize = 1  // Sin reducción de muestra (máxima resolución)
                        inDensity = 0  // Sin densidad para evitar escalado automático
                        inTargetDensity = 0
                        inScreenDensity = 0
                        inPremultiplied = true  // Premultiplicar alpha para mejor rendering
                        inJustDecodeBounds = false  // Decodificar completo
                        inTempStorage = ByteArray(64 * 1024)  // Buffer 64KB para imágenes grandes
                        inPurgeable = false  // MANTENER en memoria
                        inInputShareable = false  // No compartir
                    }
                    val bmp = android.graphics.BitmapFactory.decodeStream(inp, null, opts)
                    if (bmp != null) {
                        bmp.density = android.graphics.Bitmap.DENSITY_NONE  // Sin densidad para mantener calidad original
                        android.util.Log.d("MovDetailNequi", "✅ ULTRA 4K: Plantilla cargada: ${bmp.width}x${bmp.height}, config=${bmp.config}")
                        return bmp
                    }
                }
            } catch (e: Exception) { 
                android.util.Log.e("MovDetailNequi", "Error cargando plantilla $name: ${e.message}")
                continue 
            }
        }
        return null
    }
    
    private fun loadManropeFont(): android.graphics.Typeface {
        val fontPaths = arrayOf(
            "fuentes/Manrope-Medium.ttf",
            "fuentes/manrope_medium.ttf",
            "fuentes/Manrope-Regular.ttf",
            "fonts/Manrope-Medium.ttf"
        )
        
        for (fontPath in fontPaths) {
            try {
                android.util.Log.d("MovDetailNequi", "Intentando cargar fuente: $fontPath")
                val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, fontPath)
                val tempFile = java.io.File.createTempFile("font_manrope_", ".ttf", cacheDir)
                tempFile.outputStream().use { fontStream.copyTo(it) }
                val typeface = android.graphics.Typeface.createFromFile(tempFile)
                android.util.Log.d("MovDetailNequi", "✅ Fuente Manrope cargada desde: $fontPath")
                tempFile.delete()
                return typeface
            } catch (e: Exception) {
                android.util.Log.w("MovDetailNequi", "⚠️ No se pudo cargar $fontPath: ${e.message}")
            }
        }
        
        android.util.Log.e("MovDetailNequi", "❌ No se pudo cargar Manrope, usando Typeface.DEFAULT")
        return android.graphics.Typeface.DEFAULT
    }
    
    /**
     * Carga específicamente la fuente manrope_medium.ttf para Bancolombia
     */
    private fun loadManropeMediumFont(): android.graphics.Typeface? {
        val fontPaths = arrayOf(
            "fuentes/manrope_medium.ttf",
            "fuentes/Manrope-Medium.ttf",
            "fonts/manrope_medium.ttf",
            "fonts/Manrope-Medium.ttf"
        )
        
        for (fontPath in fontPaths) {
            try {
                android.util.Log.d("MovDetailBancolombia", "Intentando cargar fuente manrope_medium: $fontPath")
                val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, fontPath)
                val tempFile = java.io.File.createTempFile("font_manrope_medium_", ".ttf", cacheDir)
                tempFile.outputStream().use { fontStream.copyTo(it) }
                val typeface = android.graphics.Typeface.createFromFile(tempFile)
                android.util.Log.d("MovDetailBancolombia", "✅ Fuente manrope_medium.ttf cargada desde: $fontPath")
                tempFile.delete()
                return typeface
            } catch (e: Exception) {
                android.util.Log.w("MovDetailBancolombia", "⚠️ No se pudo cargar $fontPath: ${e.message}")
            }
        }
        
        android.util.Log.e("MovDetailBancolombia", "❌ No se pudo cargar manrope_medium.ttf")
        return null
    }
    
    private fun composeDetailOverlay(
        base: android.graphics.Bitmap,
        para: String,
        cuanto: String,
        numeroNequi: String,
        fecha: String,
        referencia: String,
        disponible: String,
        isQrPayment: Boolean = false,
        isBancolombia: Boolean = false, // Indicador para plantilla específica de Bancolombia
        isPagoAnulado: Boolean = false, // Indicador para plantilla específica de Pago Anulado
        userPhone: String = "", // Número del usuario que envía (para "DESDE DONDE SALIO")
        bancoDestino: String = "" // Banco destino del movimiento
    ): android.graphics.Bitmap {
        val out = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        
        // Configurar Canvas para ULTRA MEGA 4K SUPREMA - Compatible con Android 7-16
        canvas.density = android.graphics.Bitmap.DENSITY_NONE // Sin densidad para mantener calidad original
        // Aplicar configuración ULTRA MEGA 4K SUPREMA para TODAS las versiones de Android (7-16) - SIN DITHER para máxima nitidez
        canvas.drawFilter = android.graphics.PaintFlagsDrawFilter(
            0,
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG
        )
        
        // Leer configuración desde config_nequi.py según tipo de pago
        val configSection = if (isQrPayment) "DETALLE_MOVIMIENTO_QR_CONFIG" else "DETALLE_MOVIMIENTO_NEQUI_CONFIG"
        val config = loadConfigFromPython(configSection)
        
        // Cargar fuente Manrope Medium para TODOS los movimientos
        val manrope = loadManropeMediumFont() ?: loadManropeFont()
        
        // Para Bancolombia, QR y Llaves: usar color específico #200021
        val color = if (isBancolombia || isQrPayment) {
            android.graphics.Color.parseColor("#200021")
        } else {
            android.graphics.Color.parseColor(config["color"] ?: "#200021")
        }
        // Para Bancolombia: usar tamaño de fuente un poco más pequeño
        val baseFontSize = if (isBancolombia) {
            (config["font_size"]?.toFloatOrNull() ?: 18f) * 0.85f // Reducir 15% para Bancolombia
        } else {
            config["font_size"]?.toFloatOrNull() ?: if (isQrPayment) 22f else 18f
        }
        val fontSize = baseFontSize * 3  // ULTRA MEGA 4K SUPREMA: Escalar fuente 3x
        
        android.util.Log.d("MovDetailQr", "🚀 Fuente escalada: ${baseFontSize}px -> ${fontSize}px")
        
        // Paint con ULTRA MEGA CALIDAD 4K SUPREMA - Máxima calidad de renderizado para Android 7-16
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG or android.graphics.Paint.DEV_KERN_TEXT_FLAG).apply {
            textSize = fontSize
            typeface = manrope
            this.color = color
            alpha = 255 // 100% opaco - SIN TRANSPARENCIA para máxima nitidez
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            isFilterBitmap = true
            hinting = android.graphics.Paint.HINTING_ON // Máxima calidad de hinting
            isDither = false // SIN dithering para máxima nitidez
            textAlign = android.graphics.Paint.Align.LEFT
            strokeWidth = 0f
            flags = android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.DEV_KERN_TEXT_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG
            // Configuraciones adicionales para Android 14-16
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+): Máxima calidad de renderizado
                isElegantTextHeight = false // Deshabilitar para mejor calidad
            }
        }
        
        // Coordenadas ULTRA MEGA 4K SUPREMA (escaladas 3x)
        val fields = when {
            isPagoAnulado -> {
                // ✅ Coordenadas específicas para plantilla cickel_kalvore.cache (Pago Anulado)
                // Basado en la imagen: solo muestra nombre, cantidad, fecha y referencia
                val fieldsMap = mutableMapOf<String, Float>()
                
                // 1. Para (Nombre del destinatario) - Y: 300 (escalado 3x = 1686)
                if (para.isNotBlank()) {
                    fieldsMap[para] = 300f * 3
                }
                
                // 2. Valor/Cantidad - Y: 385 (escalado 3x = 1905)
                if (cuanto.isNotBlank()) {
                    fieldsMap[cuanto] = 385f * 3
                }
                
                // 3. Fecha - Y: 465 (escalado 3x = 2145)
                if (fecha.isNotBlank()) {
                    fieldsMap[fecha] = 465f * 3
                }
                
                // 4. Referencia - Y: 550 (escalado 3x = 2355)
                if (referencia.isNotBlank()) {
                    fieldsMap[referencia] = 550f * 3
                }
                
                // NOTA: Pago Anulado NO muestra teléfono ni disponible
                
                android.util.Log.d("MovDetailPagoAnulado", "📋 Campos Pago Anulado (coordenadas):")
                android.util.Log.d("MovDetailPagoAnulado", "   1. Para: '$para' -> Y: ${562f * 3} (base: 562)")
                android.util.Log.d("MovDetailPagoAnulado", "   2. Cuanto: '$cuanto' -> Y: ${635f * 3} (base: 635)")
                android.util.Log.d("MovDetailPagoAnulado", "   3. Fecha: '$fecha' -> Y: ${715f * 3} (base: 715)")
                android.util.Log.d("MovDetailPagoAnulado", "   4. Referencia: '$referencia' -> Y: ${785f * 3} (base: 785)")
                
                fieldsMap
            }
            isBancolombia -> {
                // ✅ Coordenadas específicas para plantilla janskis_banco_detals.cache (636x1280 píxeles, escaladas 3x)
                // Coordenadas exactas proporcionadas para la plantilla de Bancolombia
                // Orden correcto según la plantilla visual (de arriba hacia abajo):
                val fieldsMap = mutableMapOf<String, Float>()
                
                // 1. Para (Nombre del destinatario) - Y: 490 (escalado 3x = 1470)
                if (para.isNotBlank()) {
                    fieldsMap[para] = 490f * 3
                }
                
                // 2. Valor/Cantidad - Y: 555 (escalado 3x = 1665)
                if (cuanto.isNotBlank()) {
                    fieldsMap[cuanto] = 555f * 3
                }
                
                // 3. Fecha - Y: 620 (escalado 3x = 1860)
                if (fecha.isNotBlank()) {
                    fieldsMap[fecha] = 620f * 3
                }
                
                // 4. Banco - Y: 685 (escalado 3x = 2055)
                // Formatear banco destino: primera letra mayúscula, resto minúsculas
                val bancoToShow = if (bancoDestino.isNotBlank()) {
                    formatBankDestination(bancoDestino)
                } else {
                    "Bancolombia"
                }
                if (bancoToShow.isNotBlank()) {
                    fieldsMap[bancoToShow] = 685f * 3
                }
                
                // 5. Número de cuenta - Y: 755 (escalado 3x = 2265)
                if (numeroNequi.isNotBlank()) {
                    fieldsMap[numeroNequi] = 753f * 3
                }
                
                // 6. Referencia - Y: 820 (escalado 3x = 2460)
                if (referencia.isNotBlank()) {
                    fieldsMap[referencia] = 817f * 3
                }
                
                // 7. Disponible - Y: 885 (escalado 3x = 2655)
                if (disponible.isNotBlank()) {
                    fieldsMap[disponible] = 885f * 3
                }
                
                // Log para debug - mostrar qué texto va en qué coordenada Y
                android.util.Log.d("MovDetailBancolombia", "📋 Campos Bancolombia (coordenadas exactas):")
                android.util.Log.d("MovDetailBancolombia", "   1. Para: '$para' -> Y: ${490f * 3} (base: 490)")
                android.util.Log.d("MovDetailBancolombia", "   2. Cuanto: '$cuanto' -> Y: ${555f * 3} (base: 555)")
                android.util.Log.d("MovDetailBancolombia", "   3. Fecha: '$fecha' -> Y: ${620f * 3} (base: 620)")
                android.util.Log.d("MovDetailBancolombia", "   4. Banco: '$bancoToShow' -> Y: ${685f * 3} (base: 685)")
                android.util.Log.d("MovDetailBancolombia", "   5. Numero cuenta: '$numeroNequi' -> Y: ${755f * 3} (base: 755)")
                android.util.Log.d("MovDetailBancolombia", "   6. Referencia: '$referencia' -> Y: ${820f * 3} (base: 820)")
                android.util.Log.d("MovDetailBancolombia", "   7. Disponible: '$disponible' -> Y: ${885f * 3} (base: 885)")
                
                fieldsMap
            }
            isQrPayment -> {
            // Para pagos QR y Llaves: usar coordenadas específicas para micstolakm.pronf (escaladas 3x)
            // Coordenadas correctas proporcionadas por el usuario
            val fieldsMap = mutableMapOf<String, Float>()
            
            // PARA: 45, 562 (escalado 3x)
            if (para.isNotBlank()) {
                fieldsMap[para] = 585f * 3
            }
            
            // LLAVE: 45, 635 (escalado 3x)
            if (numeroNequi.isNotBlank()) {
                    fieldsMap[numeroNequi] = 660f * 3
            }
            
                // BANCO: 45, 715 (escalado 3x) - usar banco destino del movimiento guardado por el usuario
                // Para Llaves: NO usar "Bancolombia" por defecto, solo mostrar si está guardado
                // Para QR: puede usar banco guardado si existe
                // Formatear banco destino: primera letra mayúscula, resto minúsculas
                if (bancoDestino.isNotBlank()) {
                    val bancoFormateado = formatBankDestination(bancoDestino)
                    fieldsMap[bancoFormateado] = 730f * 3
            }
            
            // FECHA: 45, 785 (escalado 3x)
            if (fecha.isNotBlank()) {
                fieldsMap[fecha] = 805f * 3
            }
            
            // CUANTO: 45, 860 (escalado 3x)
            if (cuanto.isNotBlank()) {
                fieldsMap[cuanto] = 880f * 3
            }
            
            // REFERENCIA: 45, 930 (escalado 3x)
            if (referencia.isNotBlank()) {
                fieldsMap[referencia] = 950f * 3
            }
            
            // DESDE DONDE SALIO: 45, 1010 (escalado 3x)
            if (userPhone.isNotBlank()) {
                fieldsMap[userPhone] = 1025f * 3
            }
            
            // DISPONIBLE: 45, 1080 (escalado 3x)
            if (disponible.isNotBlank()) {
                fieldsMap[disponible] = 1095f * 3
            }
            
            fieldsMap
            }
            else -> {
            // Para pagos normales: usar campos de DETALLE_MOVIMIENTO_NEQUI_CONFIG (escalados 3x)
            mapOf(
                para to ((config["para_y"]?.toFloatOrNull() ?: 600f) * 3),
                cuanto to ((config["cuanto_y"]?.toFloatOrNull() ?: 685f) * 3),
                numeroNequi to ((config["numero_nequi_y"]?.toFloatOrNull() ?: 770f) * 3),
                fecha to ((config["fecha_y"]?.toFloatOrNull() ?: 850f) * 3),
                referencia to ((config["referencia_y"]?.toFloatOrNull() ?: 935f) * 3),
                disponible to ((config["disponible_y"]?.toFloatOrNull() ?: 1020f) * 3)
            )
        }
        }
        
        // X position: 40 para Bancolombia, 45 para QR/Llaves y Pago Anulado, 48 para Nequi normal (escalado 3x)
        val xPos = when {
            isBancolombia -> 40f * 3
            isQrPayment || isPagoAnulado -> 45f * 3
            else -> (config["x_position"]?.toFloatOrNull() ?: 48f) * 3
        }
        
        val coordinateType = when {
            isPagoAnulado -> "Pago Anulado (cickel_kalvore.cache)"
            isBancolombia -> "Bancolombia (janskis_banco_detals.cache)"
            isQrPayment -> "QR/Llaves (micstolakm.pronf)"
            else -> "Nequi normal"
        }
        android.util.Log.d("MovDetailQr", "🚀 ULTRA 4K: Coordenadas escaladas 3x - Tipo: $coordinateType, xPos=$xPos")
        
        fields.forEach { (text, y) ->
            if (text.isNotBlank()) {
                canvas.drawText(text, xPos, y, paint)
                // Log detallado para Bancolombia
                if (isBancolombia) {
                    android.util.Log.d("MovDetailBancolombia", "✏️ Dibujando: '$text' en X=$xPos, Y=$y")
                }
            }
        }
        
        return out
    }
    
    private fun loadConfigFromPython(configSection: String): Map<String, String> {
        return try {
            val configText = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "config_nequi.py").use { inp ->
                inp.bufferedReader().use { it.readText() }
            }
            
            val config = mutableMapOf<String, String>()
            val lines = configText.lines()
            var inDetailSection = false
            
            for (line in lines) {
                if (line.contains(configSection)) {
                    inDetailSection = true
                    continue
                }
                
                if (inDetailSection) {
                    if (line.trim().startsWith("#") && !line.contains("Para") && !line.contains("Cuánto") && !line.contains("Llave")) {
                        break
                    }
                    if (line.contains("}") && !line.contains("fields")) {
                        break
                    }
                    
                    if (line.contains("\"font_size\":")) {
                        val value = line.substringAfter(":").replace(",", "").trim()
                        config["font_size"] = value
                    }
                    
                    if (line.contains("\"color\":")) {
                        val value = line.substringAfter(":").replace("\"", "").replace(",", "").trim()
                        config["color"] = value
                    }
                    
                    if (line.contains("\"para\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) {
                            config["x_position"] = coords[0].trim()
                            config["para_y"] = coords[1].trim()
                        }
                    }
                    if (line.contains("\"llave\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["llave_y"] = coords[1].trim()
                    }
                    if (line.contains("\"banco_destino\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["banco_destino_y"] = coords[1].trim()
                    }
                    if (line.contains("\"cuanto\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["cuanto_y"] = coords[1].trim()
                    }
                    if (line.contains("\"numero_nequi\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["numero_nequi_y"] = coords[1].trim()
                    }
                    if (line.contains("\"fecha\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["fecha_y"] = coords[1].trim()
                    }
                    if (line.contains("\"referencia\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["referencia_y"] = coords[1].trim()
                    }
                    if (line.contains("\"desde\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["desde_y"] = coords[1].trim()
                    }
                    if (line.contains("\"disponible\":")) {
                        val coords = line.substringAfter("(").substringBefore(")").split(",")
                        if (coords.size == 2) config["disponible_y"] = coords[1].trim()
                    }
                }
            }
            
            android.util.Log.d("MovDetail", "Config '$configSection' cargado: $config")
            config
        } catch (e: Exception) {
            android.util.Log.e("MovDetail", "Error leyendo config_nequi.py para $configSection: ${e.message}")
            // Valores por defecto según la sección
            if (configSection.contains("QR")) {
                mapOf(
                    "x_position" to "45",
                    "para_y" to "530",
                    "llave_y" to "605",
                    "banco_destino_y" to "675",
                    "fecha_y" to "750",
                    "cuanto_y" to "830",
                    "referencia_y" to "900",
                    "desde_y" to "975",
                    "disponible_y" to "1050",
                    "font_size" to "22",
                    "color" to "#200021"
                )
            } else {
                mapOf(
                    "x_position" to "48",
                    "para_y" to "600",
                    "cuanto_y" to "685",
                    "numero_nequi_y" to "770",
                    "fecha_y" to "850",
                    "referencia_y" to "935",
                    "disponible_y" to "1020",
                    "font_size" to "22",
                    "color" to "#200021"
                )
            }
        }
    }
    
    private fun formatDateEs(date: java.util.Date): String {
        val locale = java.util.Locale("es", "CO")
        val sdf = java.text.SimpleDateFormat("d 'de' MMMM 'de' yyyy 'a las' hh:mm a", locale)
        val raw = sdf.format(date)
        return raw.replace("AM", "a.m.").replace("PM", "p.m.")
    }
    
    private fun generateReference(): String {
        val n = (10000000..99999999).random()
        return "M$n"
    }
    
    private fun toTitleCase(input: String): String {
        return input.lowercase().split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        }
    }
    
    /**
     * Ofusca el nombre para Llaves y Bancolombia
     * Ejemplo: "Maria Vergara" -> "Mar** Ver****"
     * Siempre muestra las primeras 3 letras + asteriscos por el resto
     * Para Llaves: Siempre capitaliza la primera letra de cada palabra
     */
    private fun maskNameForLlavesBancolombia(name: String): String {
        if (name.isBlank()) return ""
        
        val words = name.trim().split("\\s+".toRegex())
        return words.joinToString(" ") { word ->
            // Normalizar a minúsculas primero para procesamiento consistente
            val normalizedWord = word.trim().lowercase()
            if (normalizedWord.isEmpty()) return@joinToString ""
            
            if (normalizedWord.length <= 3) {
                // Si tiene 3 o menos letras, mostrarla completa con primera SIEMPRE en mayúscula
                if (normalizedWord.isNotEmpty()) {
                    normalizedWord.first().uppercaseChar() + normalizedWord.substring(1)
            } else {
                    normalizedWord
                }
            } else {
                // Mostrar primeras 3 letras: PRIMERA SIEMPRE EN MAYÚSCULA + siguientes 2 en minúscula + asteriscos
                val firstChar = normalizedWord.first().uppercaseChar()
                val nextTwo = normalizedWord.substring(1, 3).lowercase()
                val visiblePart = firstChar + nextTwo
                val asterisks = "*".repeat(normalizedWord.length - 3)
                visiblePart + asterisks
            }
        }
    }
    
    /**
     * Formatea el banco destino para mostrarlo en los detalles del movimiento
     * - Si es "nequi" (en cualquier variación), siempre muestra "Nequi"
     * - Para cualquier otro banco, pone la primera letra mayúscula y el resto minúsculas
     */
    private fun formatBankDestination(bank: String): String {
        if (bank.isBlank()) return ""
        
        val normalized = bank.trim().lowercase()
        
        // Si es "nequi" en cualquier variación, siempre mostrar "Nequi"
        if (normalized == "nequi") {
            return "Nequi"
        }
        
        // Para cualquier otro banco, poner primera letra mayúscula y resto minúsculas
        return normalized.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() 
        }
    }
}
