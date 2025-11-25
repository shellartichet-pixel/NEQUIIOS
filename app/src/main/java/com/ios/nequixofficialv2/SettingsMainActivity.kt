package com.ios.nequixofficialv2

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class SettingsMainActivity : AppCompatActivity() {
    
    // ✅ SÓLIDO: Variables de control
    private var isNavigating = false
    private val userPhone: String by lazy { intent.getStringExtra("user_phone") ?: "" }
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_main)
        
        // Inicializar SharedPreferences
        prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        
        // Aplicar color blanco a la barra de estado
        try {
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.white)
        } catch (_: Exception) {}

        // Botón atrás
        findViewById<ImageView>(R.id.btnBack)?.setOnClickListener {
            if (!isNavigating) {
                finish()
            }
        }

        // Card Agregar Víctimas
        findViewById<CardView>(R.id.cardAddUser)?.setOnClickListener {
            navigateToActivity(SettingsUserActivity::class.java)
        }

        // Card Agregar Huella Digital
        findViewById<CardView>(R.id.cardFingerprint)?.setOnClickListener {
            setupFingerprint()
        }

        // Card Agregar Movimientos de Salida
        findViewById<CardView>(R.id.cardAddOutputMovement)?.setOnClickListener {
            val intent = Intent(this, AddOutputMovementActivity::class.java)
            intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }
        
        // Configurar Modo Live TikTok (con verificación de null)
        setupTikTokLiveMode()
        
        // Configurar Modo Nequi Ahorros (con verificación de null)
        setupNequiAhorrosMode()
    }
    
    private fun setupNequiAhorrosMode() {
        val switchNequiAhorros = findViewById<SwitchCompat>(R.id.switchNequiAhorros)
        val tvNequiAhorrosStatus = findViewById<TextView>(R.id.tvNequiAhorrosStatus)
        
        // Verificar que las vistas existan
        if (switchNequiAhorros == null || tvNequiAhorrosStatus == null) {
            android.util.Log.e("SettingsMainActivity", "❌ No se encontraron las vistas de Nequi Ahorros")
            return
        }
        
        // Cargar estado actual
        val isNequiAhorrosEnabled = prefs.getBoolean("nequi_ahorros_mode_enabled", false)
        
        // ✅ IMPORTANTE: Configurar el estado SIN listener primero para evitar que se dispare automáticamente
        switchNequiAhorros.setOnCheckedChangeListener(null) // Remover cualquier listener previo
        switchNequiAhorros.isChecked = isNequiAhorrosEnabled
        updateNequiAhorrosStatus(tvNequiAhorrosStatus, isNequiAhorrosEnabled)
        
        // Ahora agregar el listener (solo se ejecutará cuando el usuario toque el switch)
        switchNequiAhorros.setOnCheckedChangeListener { _, isChecked ->
            // Guardar el nuevo estado
            prefs.edit().putBoolean("nequi_ahorros_mode_enabled", isChecked).apply()
            updateNequiAhorrosStatus(tvNequiAhorrosStatus, isChecked)
            
            android.util.Log.d("SettingsMainActivity", "🏦 Modo Nequi Ahorros ${if (isChecked) "ACTIVADO" else "DESACTIVADO"}")
            
            // Mostrar mensaje de confirmación
            val message = if (isChecked) {
                "Modo Nequi Ahorros activado. El diseño cambiará a cuenta de ahorros."
            } else {
                "Modo Nequi Ahorros desactivado."
            }
            
            com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                this,
                message,
                3000L
            )
        }
    }
    
    private fun updateNequiAhorrosStatus(tvNequiAhorrosStatus: TextView, isEnabled: Boolean) {
        if (isEnabled) {
            tvNequiAhorrosStatus.text = "Activado - Diseño de cuenta de ahorros"
            tvNequiAhorrosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvNequiAhorrosStatus.text = "Desactivado"
            tvNequiAhorrosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
    
    private fun setupTikTokLiveMode() {
        val switchTikTokLiveMode = findViewById<SwitchCompat>(R.id.switchTikTokLiveMode)
        val tvTikTokStatus = findViewById<TextView>(R.id.tvTikTokStatus)
        
        // Verificar que las vistas existan
        if (switchTikTokLiveMode == null || tvTikTokStatus == null) {
            android.util.Log.e("SettingsMainActivity", "❌ No se encontraron las vistas de TikTok Live Mode")
            return
        }
        
        // Cargar estado actual
        val isTikTokLiveModeEnabled = prefs.getBoolean("tiktok_live_mode_enabled", false)
        
        // ✅ IMPORTANTE: Configurar el estado SIN listener primero para evitar que se dispare automáticamente
        switchTikTokLiveMode.setOnCheckedChangeListener(null) // Remover cualquier listener previo
        switchTikTokLiveMode.isChecked = isTikTokLiveModeEnabled
        updateTikTokStatus(tvTikTokStatus, isTikTokLiveModeEnabled)
        
        // Ahora agregar el listener (solo se ejecutará cuando el usuario toque el switch)
        switchTikTokLiveMode.setOnCheckedChangeListener { _, isChecked ->
            // Guardar el nuevo estado
            prefs.edit().putBoolean("tiktok_live_mode_enabled", isChecked).apply()
            updateTikTokStatus(tvTikTokStatus, isChecked)
            
            android.util.Log.d("SettingsMainActivity", "🎬 Modo Live TikTok ${if (isChecked) "ACTIVADO" else "DESACTIVADO"}")
            
            // Mostrar mensaje de confirmación
            val message = if (isChecked) {
                "Modo Live TikTok activado. La app ocultará contenido sensible durante transmisiones."
            } else {
                "Modo Live TikTok desactivado."
            }
            
            com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                this,
                message,
                3000L
            )
        }
    }
    
    private fun updateTikTokStatus(tvTikTokStatus: TextView, isEnabled: Boolean) {
        if (isEnabled) {
            tvTikTokStatus.text = "Activado - Protección activa"
            tvTikTokStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvTikTokStatus.text = "Desactivado"
            tvTikTokStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
    
    private fun setupFingerprint() {
        val biometricHelper = BiometricHelper(this)
        
        // Verificar si el dispositivo soporta biometría
        if (!biometricHelper.isBiometricAvailable()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(
                this,
                "Tu dispositivo no soporta autenticación por huella digital"
            )
            return
        }
        
        // Verificar si ya tiene huella registrada
        if (biometricHelper.isFingerprintEnabled()) {
            showFingerprintOptionsDialog()
            return
        }
        
        // Mostrar diálogo de registro
        showFingerprintRegistrationDialog()
    }
    
    private fun showFingerprintRegistrationDialog() {
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val view = layoutInflater.inflate(R.layout.dialog_fingerprint_registration, null)
        dialog.setContentView(view)
        
        val tvProgress = view.findViewById<android.widget.TextView>(R.id.tvProgress)
        val progressBar = view.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivFingerprintIcon)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btnCancel)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // Iniciar registro biométrico
        val biometricHelper = BiometricHelper(this)
        biometricHelper.showFingerprintRegistration(
            userPhone = userPhone,
            onProgress = { progress ->
                tvProgress.text = "Registrando... $progress%"
                progressBar.progress = progress
                // Animar icono
                ivIcon.animate().scaleX(1.2f).scaleY(1.2f).setDuration(200)
                    .withEndAction {
                        ivIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    }.start()
            },
            onSuccess = {
                tvProgress.text = "¡Registro completado! 100%"
                progressBar.progress = 100
                // Pequeño delay para mostrar el 100%
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dialog.dismiss()
                    com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                        this,
                        "Huella registrada exitosamente",
                        2000L
                    )
                }, 500)
            },
            onError = { error ->
                tvProgress.text = error
                tvProgress.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
        )
    }
    
    private fun showFingerprintOptionsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Huella Digital Registrada")
            .setMessage("Ya tienes una huella digital registrada. ¿Qué deseas hacer?")
            .setPositiveButton("Eliminar Huella") { _, _ ->
                BiometricHelper(this).clearFingerprint()
                com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                    this,
                    "Huella eliminada",
                    2000L
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    // Función de navegación segura
    private fun navigateToActivity(activityClass: Class<*>) {
        if (isNavigating || userPhone.isEmpty()) return
        
        isNavigating = true
        try {
            val intent = Intent(this, activityClass)
            intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("SettingsMainActivity", "Error navegando: ${e.message}")
        } finally {
            // Restablecer flag después de un breve delay
            findViewById<CardView>(R.id.cardAddUser)?.postDelayed({
                isNavigating = false
            }, 500)
        }
    }

}
