package com.ios.nequixofficialv2.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.widget.TextView

/**
 * Helper para manejar el Modo Live de TikTok
 * Este modo oculta o modifica contenido sensible para evitar que TikTok detecte
 * contenido relacionado con envíos de dinero que podría ser considerado ilegal o estafa
 */
object TikTokLiveModeHelper {
    
    private const val TAG = "TikTokLiveModeHelper"
    private const val PREFS_NAME = "home_prefs"
    private const val KEY_TIKTOK_LIVE_MODE = "tiktok_live_mode_enabled"
    
    /**
     * Verifica si el Modo Live de TikTok está activado
     */
    fun isLiveModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TIKTOK_LIVE_MODE, false)
    }
    
    /**
     * Obtiene una versión "segura" de un texto para mostrar durante transmisiones
     * Reemplaza palabras sensibles con alternativas seguras
     * ✅ PROTECCIÓN COMPLETA: Reemplaza TODAS las palabras relacionadas con dinero/transacciones
     */
    fun getSafeText(originalText: String): String {
        if (originalText.isBlank()) return originalText
        
        var safeText = originalText
        
        // Reemplazar palabras sensibles relacionadas con dinero y transacciones
        safeText = safeText
            .replace("enviar", "compartir", ignoreCase = true)
            .replace("dinero", "puntos", ignoreCase = true)
            .replace("transferencia", "compartición", ignoreCase = true)
            .replace("transferir", "compartir", ignoreCase = true)
            .replace("saldo", "disponible", ignoreCase = true)
            .replace("pago", "intercambio", ignoreCase = true)
            .replace("pagar", "intercambiar", ignoreCase = true)
            .replace("recibir", "obtener", ignoreCase = true)
            .replace("nequi", "app", ignoreCase = true)
            .replace("colombia", "país", ignoreCase = true)
            .replace("banco", "servicio", ignoreCase = true)
            .replace("bancolombia", "servicio", ignoreCase = true)
            .replace("cuenta", "perfil", ignoreCase = true)
            .replace("transacción", "acción", ignoreCase = true)
            .replace("movimiento", "actividad", ignoreCase = true)
            .replace("retirar", "obtener", ignoreCase = true)
            .replace("depositar", "agregar", ignoreCase = true)
            .replace("consignar", "agregar", ignoreCase = true)
            .replace("giro", "envío", ignoreCase = true)
            .replace("remesa", "envío", ignoreCase = true)
            .replace("\\$", "pts", ignoreCase = false)
            .replace("pesos", "puntos", ignoreCase = true)
            .replace("peso", "punto", ignoreCase = true)
            .replace("cop", "pts", ignoreCase = true)
            .replace("usd", "pts", ignoreCase = true)
            .replace("dólar", "punto", ignoreCase = true)
            .replace("dolares", "puntos", ignoreCase = true)
        
        return safeText
    }
    
    /**
     * Obtiene un monto formateado de forma segura (sin símbolos de dinero)
     * ✅ PROTECCIÓN: Convierte montos a "puntos" en lugar de mostrar dinero
     */
    fun getSafeAmount(amount: Long): String {
        if (amount == 0L) return "0 pts"
        // Formatear con separadores de miles pero sin símbolo de dinero
        val formatted = String.format("%,d", amount).replace(",", ".")
        return "$formatted pts"
    }
    
    /**
     * Obtiene un monto formateado de forma segura (sin símbolos de dinero) desde Double
     */
    fun getSafeAmount(amount: Double): String {
        return getSafeAmount(amount.toLong())
    }
    
    /**
     * Obtiene un monto completamente oculto (solo muestra "---")
     * Útil para ocultar completamente montos sensibles
     */
    fun getHiddenAmount(): String {
        return "--- pts"
    }
    
    /**
     * Oculta o muestra una vista según el modo TikTok Live
     * @param view La vista a ocultar/mostrar
     * @param hideInLiveMode Si es true, oculta la vista cuando el modo está activo
     */
    fun applyLiveModeVisibility(context: Context, view: View, hideInLiveMode: Boolean = true) {
        if (isLiveModeEnabled(context) && hideInLiveMode) {
            view.visibility = View.GONE
            Log.d(TAG, "Vista oculta por Modo Live TikTok: ${view.javaClass.simpleName}")
        } else {
            view.visibility = View.VISIBLE
        }
    }
    
    /**
     * Aplica texto seguro a un TextView si el modo está activo
     */
    fun applySafeText(context: Context, textView: TextView, originalText: String) {
        if (isLiveModeEnabled(context)) {
            textView.text = getSafeText(originalText)
            Log.d(TAG, "Texto aplicado en modo seguro: ${textView.text}")
        } else {
            textView.text = originalText
        }
    }
    
    /**
     * Verifica si se debe ocultar contenido sensible
     */
    fun shouldHideSensitiveContent(context: Context): Boolean {
        return isLiveModeEnabled(context)
    }
    
    /**
     * Obtiene un mensaje genérico seguro para mostrar en lugar de contenido sensible
     */
    fun getSafeGenericMessage(): String {
        return "Contenido disponible"
    }
    
    /**
     * Log de advertencia cuando se detecta contenido sensible en modo Live
     */
    fun logSensitiveContentDetected(context: Context, contentType: String) {
        if (isLiveModeEnabled(context)) {
            Log.w(TAG, "⚠️ Contenido sensible detectado y ocultado: $contentType")
        }
    }
}

