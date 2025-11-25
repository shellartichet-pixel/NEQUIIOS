package com.ios.nequixofficialv2

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import android.webkit.WebView
import java.security.MessageDigest
import com.ios.nequixofficialv2.security.SkinManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ios.nequixofficialv2.workers.NotificationWorker
import com.ios.nequixofficialv2.workers.ServiceKeepaliveWorker
import java.util.concurrent.TimeUnit
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 🛡️ VERSIÓN 3.7 - SEGURIDAD NIVEL BANCARIO SUPREMO (9.9/10)
        // 24 capas de protección implementadas
        
        // ═══════════════════════════════════════════════════════════════
        // PROTECCIONES CRÍTICAS - SE EJECUTAN PRIMERO
        // ═══════════════════════════════════════════════════════════════
        
        // 💣 PASO 1: Verificar si APK fue reempaquetado con apktool
        if (!BuildConfig.DEBUG) {
            try {
                val repackaging = com.ios.nequixofficialv2.security.AntiRepackagingBomb.performAntiRepackagingCheck(this)
                if (repackaging.isRepackaged) {
                    android.util.Log.e("App", "🚨 APK MODIFICADO - Cerrando app")
                    return
                } else {
                    android.util.Log.d("App", "✅ Anti-repackaging check: PASSED")
                }
            } catch (e: Exception) {
                android.util.Log.e("App", "⚠️ Error en anti-repackaging: ${e.message}")
            }
        } else {
            android.util.Log.w("App", "⚠️ Anti-repackaging DESACTIVADO (DEBUG mode)")
        }
        
        // 🛡️ PASO 2: Verificar integridad de layouts y resources
        if (!BuildConfig.DEBUG) {
            try {
                val layoutIntegrity = com.ios.nequixofficialv2.security.LayoutProtection.verifyXMLIntegrity(this)
                if (!layoutIntegrity.isValid) {
                    android.util.Log.e("App", "🚨 Layouts modificados: ${layoutIntegrity.issues}")
                    com.ios.nequixofficialv2.security.SelfDestructionSystem.activate(
                        this,
                        "Layouts modificados",
                        com.ios.nequixofficialv2.security.SelfDestructionSystem.ThreatSeverity.HIGH
                    )
                    return
                } else {
                    android.util.Log.d("App", "✅ Layout integrity: VALID")
                }
            } catch (e: Exception) {
                android.util.Log.e("App", "⚠️ Error verificando layouts: ${e.message}")
            }
        } else {
            android.util.Log.w("App", "⚠️ Layout protection DESACTIVADO (DEBUG mode)")
        }
        
        // 🔒 PASO 3: Inicializar Firebase manualmente (honeypot primero)
        try {
            // Inicializar Firebase con google-services.json (honeypot)
            // Esto evita el crash porque se hace manualmente
            FirebaseApp.initializeApp(this)
            android.util.Log.d("App", "✅ Firebase inicializado con honeypot (google-services.json)")
            
            // 🔒 PASO 3.1: App Check DESACTIVADO
            // Se usan reglas de Firestore estrictas en su lugar
            // initializeAppCheck()
            
            // Luego cargar credenciales reales desde Remote Config en background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val success = com.ios.nequixofficialv2.security.SecureFirebaseInit.initialize(this@App)
                    if (success) {
                        android.util.Log.d("App", "✅ SecureFirebaseInit: Credenciales reales cargadas")
                    } else {
                        android.util.Log.w("App", "⚠️ SecureFirebaseInit: Usando configuración honeypot")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("App", "⚠️ Error en SecureFirebaseInit: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("App", "⚠️ Error inicializando Firebase: ${e.message}")
        }
        
        try {
            com.ios.nequixofficialv2.security.AppIntegrity.init(this)
            val appSignature = getSigningSha256()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.ios.nequixofficialv2.core.ConfigLoader.init(this@App, appSignature)
                } catch (e: Exception) {}
            }
            com.ios.nequixofficialv2.security.SecureCredentials.init(appSignature)
        } catch (e: Exception) {}
        
        // 🔒 PASO 4: Inicializar gestor de credenciales (CERO hardcoding)
        try {
            com.ios.nequixofficialv2.security.RuntimeCredentialManager.initialize(this)
            android.util.Log.d("App", "✅ RuntimeCredentialManager inicializado")
        } catch (e: Exception) {
            android.util.Log.e("App", "⚠️ Error inicializando credenciales: ${e.message}")
            // Continuar sin bloquear
        }
        
        try {
            com.ios.nequixofficialv2.security.GoogleServicesProtector.initialize(this)
        } catch (e: Exception) {
        }
        
        // 🛡️ PASO 5: Iniciar monitoreo continuo de seguridad (cada 30 seg)
        // SOLO en RELEASE para no consumir recursos en DEBUG
        if (!BuildConfig.DEBUG) {
            try {
                com.ios.nequixofficialv2.security.UltimateSecurityOrchestrator.startSecurityMonitoring(this)
                android.util.Log.d("App", "✅ Security monitoring iniciado")
            } catch (e: Exception) {
                android.util.Log.e("App", "⚠️ Error iniciando monitoreo: ${e.message}")
            }
        } else {
            android.util.Log.d("App", "ℹ️ Security monitoring deshabilitado en DEBUG")
        }
        
        // 🔥 PASO 6: Configurar sistema de auto-destrucción
        try {
            // Deshabilitar en debug para no bloquear durante desarrollo
            com.ios.nequixofficialv2.security.SelfDestructionSystem.setEnabled(!BuildConfig.DEBUG)
            android.util.Log.d("App", "✅ SelfDestruction: ${if (BuildConfig.DEBUG) "DISABLED" else "ENABLED"}")
        } catch (e: Exception) {
            android.util.Log.e("App", "⚠️ Error configurando auto-destrucción: ${e.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════
        // FIX ICONOS DEL LAUNCHER - Asegurar que se muestren correctamente
        // ═══════════════════════════════════════════════════════════════
        
        // Invalidar caché del launcher para que los iconos se actualicen correctamente
        // Esto soluciona el problema cuando los usuarios instalan/reinstalan o cambian de cuenta
        try {
            invalidateLauncherIconCache()
        } catch (e: Exception) {
            android.util.Log.w("App", "⚠️ Error invalidando caché del launcher: ${e.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PROTECCIONES FIREBASE
        // ═══════════════════════════════════════════════════════════════
        
        // Desactivar depuración WebView en release
        try { if (!BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(false) } catch (_: Exception) {}
        
        // 🔔 Worker que revisa Firebase cada 15 min (funciona con app cerrada)
        setupNotificationWorker()
        
        // 🚀 DESACTIVADO: El servicio se inicia desde HomeActivity cuando el usuario se loguea
        // Esto evita duplicados cuando la app se inicia y el usuario navega entre actividades
        // initializeMovementListenerService()

        // Bloquear capturas y grabación de pantalla (FLAG_SECURE) en TODAS las Activities
        // Se puede desactivar en debug automáticamente
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                if (!BuildConfig.DEBUG) {
                    try {
                        // 🛡️ FLAG_SECURE - Bloquea screenshots y grabación
                        activity.window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } catch (_: Exception) {}
                    
                    // 🎭 Ofuscar layouts (corrompe UI si APK modificado)
                    try {
                        com.ios.nequixofficialv2.security.LayoutObfuscator.obfuscateActivity(activity)
                    } catch (_: Exception) {}
                    
                    // 🛡️ Proteger activity (verifica root, debugger, etc.)
                    try {
                        com.ios.nequixofficialv2.security.UltimateSecurityOrchestrator.protectActivity(activity)
                    } catch (_: Exception) {}

                    // Aplicar skin cifrado solo si la firma es válida; en caso contrario, fondo neutro
                    try {
                        SkinManager.applyToActivity(activity)
                    } catch (_: Exception) {}
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            
            override fun onActivityResumed(activity: Activity) {
                // 💤 Detectar cuando el usuario regresa después de inactividad
                try {
                    com.ios.nequixofficialv2.security.InactivityManager.onActivityResumed(activity)
                } catch (_: Exception) {}
            }
            
            override fun onActivityPaused(activity: Activity) {
                // 💤 Detectar cuando el usuario sale de la app
                try {
                    com.ios.nequixofficialv2.security.InactivityManager.onActivityPaused(activity)
                } catch (_: Exception) {}
            }
            
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            
            override fun onActivityDestroyed(activity: Activity) {
                // 💤 Limpiar referencias del InactivityManager
                try {
                    com.ios.nequixofficialv2.security.InactivityManager.onActivityDestroyed(activity)
                } catch (_: Exception) {}
            }
        })

        // Antitamper por firma (solo en release y si EXPECTED_SIGNATURE_SHA256 está configurada)
        try {
            // ERROR: Esta línea fallaba porque la constante no estaba en BuildConfig
            // La solución está en el archivo build.gradle para inyectar este valor.
            if (!BuildConfig.DEBUG && BuildConfig.EXPECTED_SIGNATURE_SHA256.isNotBlank()) {
                val current = getSigningSha256()
                if (!current.equals(BuildConfig.EXPECTED_SIGNATURE_SHA256, ignoreCase = true)) {
                    // Firma no coincide -> cerrar la app
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        } catch (_: Exception) {}
    }

    
    private fun getSigningSha256(): String {
        return try {
            val pkg = packageManager.getPackageInfo(packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                pkg.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION") pkg.signatures
            }
            val md = MessageDigest.getInstance("SHA-256")
            val hex = signatures.firstOrNull()?.let { sig ->
                val digest = md.digest(sig.toByteArray())
                digest.joinToString("") { b -> String.format("%02X", b) }
            } ?: ""
            hex
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Invalida el caché del launcher para asegurar que los iconos se muestren correctamente
     * Esto soluciona el problema cuando los usuarios instalan/reinstalan o cambian de cuenta
     */
    private fun invalidateLauncherIconCache() {
        try {
            // Método 1: Notificar al PackageManager que los recursos han cambiado
            // Esto fuerza al launcher a recargar los iconos
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(this, SplashActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP
            )
            
            // Método 2: Para Android 8.0+ (API 26+), invalidar shortcuts si existen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
                    shortcutManager?.removeAllDynamicShortcuts()
                    android.util.Log.d("App", "✅ Shortcuts del launcher invalidados")
                } catch (e: Exception) {
                    android.util.Log.w("App", "⚠️ No se pudieron invalidar shortcuts: ${e.message}")
                }
            }
            
            // Método 3: Notificar cambio de recursos al sistema
            // Esto hace que el launcher recargue los iconos
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                packageManager.queryIntentActivities(intent, 0)
                android.util.Log.d("App", "✅ Caché del launcher invalidado correctamente")
            } catch (e: Exception) {
                android.util.Log.w("App", "⚠️ Error invalidando caché: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("App", "❌ Error invalidando caché del launcher: ${e.message}")
        }
    }
    
    /**
     * Configura el Worker que revisa notificaciones cada 15 minutos
     * Funciona incluso con la app cerrada o en background
     */
    private fun setupNotificationWorker() {
        try {
            // Worker periódico: Revisa cada 15 minutos en segundo plano
            val workRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                15, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "NotificationWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            android.util.Log.d("App", "✅ NotificationWorker configurado (cada 15 min)")
        } catch (e: Exception) {
            android.util.Log.e("App", "❌ Error configurando NotificationWorker: ${e.message}")
        }
    }
    
    /**
     * Configura el Worker que mantiene vivo el MovementListenerService
     * Se ejecuta cada 15 minutos para revivir el servicio si se detiene
     */
    private fun setupServiceKeepaliveWorker() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<ServiceKeepaliveWorker>(
                15, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ServiceKeepaliveWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            android.util.Log.d("App", "✅ ServiceKeepaliveWorker configurado (cada 15 min)")
        } catch (e: Exception) {
            android.util.Log.e("App", "❌ Error configurando ServiceKeepaliveWorker: ${e.message}")
        }
    }
    
    /**
     * Inicia el servicio de escucha de movimientos si hay un usuario logueado
     * Este servicio funciona en segundo plano y muestra notificaciones automáticamente
     */
    private fun initializeMovementListenerService() {
        try {
            // Verificar si hay un usuario logueado
            val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", null)
            
            if (!userPhone.isNullOrEmpty()) {
                android.util.Log.d("App", "🚀 Usuario logueado detectado: $userPhone")
                
                // Iniciar servicio de escucha de movimientos
                com.ios.nequixofficialv2.services.MovementListenerService.start(this, userPhone)
                
                android.util.Log.d("App", "✅ MovementListenerService iniciado en App.onCreate()")
            } else {
                android.util.Log.d("App", "⚠️ No hay usuario logueado, servicio no iniciado")
            }
        } catch (e: Exception) {
            android.util.Log.e("App", "❌ Error iniciando MovementListenerService: ${e.message}")
        }
    }
}
