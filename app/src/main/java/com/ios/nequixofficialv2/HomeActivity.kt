package com.ios.nequixofficialv2
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.core.view.isVisible
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import com.ios.nequixofficialv2.security.AssetObfuscator
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.ios.nequixofficialv2.databinding.Kihom1Binding
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.scanbot.demo.barcodescanner.e
import io.scanbot.demo.barcodescanner.model.Movement
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import com.ios.nequixofficialv2.update.UpdateManager
import com.ios.nequixofficialv2.update.UpdateDialog
import android.os.Build
import android.app.PendingIntent
import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper
import com.ios.nequixofficialv2.utils.NotificationManager as AppNotificationManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ios.nequixofficialv2.workers.NotificationWorker
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: Kihom1Binding
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var userPhone: String = ""
    private var isHomeShimmerActive: Boolean = false
    private var isSaldoShimmerActive: Boolean = false
    private var isBalanceHidden: Boolean = false
    private var lastDisponibleValue: Long = 0L
    private var lastTotalValue: Long = 0L
    // Sin flags extra: estado simple y estable
    private var refreshingHeader: Boolean = false
    
    // Constantes para permisos
    private val NOTIFICATION_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_CODE = 1002
    
    private enum class MovTab { HOY, MAS }
    private var currentMovTab: MovTab = MovTab.HOY
    private var saldoListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var movementsAdapter: io.scanbot.demo.barcodescanner.adapter.SectionedMovementAdapter? = null
    private var loadedMovements: List<Movement> = emptyList()
    private val updateManager: UpdateManager by lazy { UpdateManager(this) }
    private var updateDialog: UpdateDialog? = null
    private val appNotificationManager: AppNotificationManager by lazy { AppNotificationManager(this) }
    
    // Variables para prevenir clicks rápidos y bugs de navegación
    private var isNavigating = false
    private var lastClickTime = 0L
    private val CLICK_DEBOUNCE_TIME = 600L // 600ms entre clicks (aumentado para evitar superposición)
    private var currentSection: BottomSection = BottomSection.HOME
    private val pendingRunnables = mutableListOf<Runnable>()
    
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
                android.util.Log.d("HomeActivity", "✅ Usuario encontrado - ID documento: ${doc.id}")
                doc.id
            } else {
                android.util.Log.w("HomeActivity", "⚠️ No se encontró usuario con telefono: $phoneDigits")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeActivity", "❌ Error buscando usuario por telefono: ${e.message}", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Usa ViewBinding correctamente: infla y asigna una sola vez
        binding = Kihom1Binding.inflate(layoutInflater)
        // Parche defensivo: si por alguna razón ya tiene padre, remover antes de setear
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
        setContentView(binding.root)

        // Aplicar color morado original a la barra de estado (statusBar)
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
            // NO tocar navigationBarColor - respetar configuración del usuario
        } catch (_: Exception) {}

        // ✅ Aplicar SOLO padding bottom para respetar barra de navegación inferior
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        userPhone = intent.getStringExtra("user_phone") ?: ""
        if (userPhone.isEmpty()) {
            // Si no hay usuario, cerramos para evitar estados inconsistentes
            finish()
            return
        }
        // Persistir teléfono para gestores que leen de SharedPreferences
        getSharedPreferences("home_prefs", MODE_PRIVATE)
            .edit()
            .putString("user_phone", userPhone)
            .apply()

        // Solicitar permisos de notificación para Android 13+
        requestNotificationPermissions()

        // Interacciones de UI (bottom bar, cash menu, ojo de saldo, etc.)
        setupInteractions()
        
        // Aplicar diseño de Cuenta de Ahorros si el modo TikTok Live está activo
        applyNequiAhorrosDesign()
        
        // Actualizar badge de notificaciones
        updateNotificationBadge()
        
        // Registrar token FCM para notificaciones push
        appNotificationManager.registerFCMToken(userPhone)
        
        // 🚀 INICIAR SERVICIO DE ESCUCHA DE TRANSFERENCIAS EN SEGUNDO PLANO
        // Este servicio funciona independiente de HomeActivity (app cerrada o abierta)
        com.ios.nequixofficialv2.services.MovementListenerService.start(this, userPhone)
        
        
        // ELIMINAR COMPLETAMENTE el SwipeRefreshLayout - no lo usamos
        binding.homeSwipe.isEnabled = false
        binding.homeSwipe.setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
        binding.homeSwipe.setColorSchemeColors(Color.TRANSPARENT)
        
        // Configurar el spinner personalizado como BLANCO desde el inicio
        val white = 0xFFFFFFFF.toInt()
        binding.pullProgressBar.apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(white)
            indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            indeterminateDrawable?.apply {
                setTint(white)
                setTintMode(android.graphics.PorterDuff.Mode.SRC_IN)
                setTintList(android.content.res.ColorStateList.valueOf(white))
            }
        }

        // Pull-to-refresh MANUAL - SOLO desde la parte superior
        var startY = 0f
        var startScrollY = 0
        var pulling = false
        var triggered = false
        
        binding.mainScrollView.setOnTouchListener { _, event ->
            // ✅ SI ESTÁ REFRESCANDO: BLOQUEAR TODO (consumir eventos)
            if (refreshingHeader) {
                return@setOnTouchListener true // Consumir evento = no se mueve NADA
            }
            
            // Comportamiento normal de pull-to-refresh
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startScrollY = binding.mainScrollView.scrollY
                    // SOLO permitir pull si estamos EXACTAMENTE en la parte superior
                    pulling = startScrollY == 0 && !refreshingHeader && currentSection == BottomSection.HOME
                    triggered = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Verificar que seguimos en la parte superior y no hay refresh activo
                    val currentScrollY = binding.mainScrollView.scrollY
                    val deltaY = event.y - startY
                    
                    // Solo activar si:
                    // 1. Estamos en modo pulling
                    // 2. No se ha disparado aún
                    // 3. El scroll sigue en 0 (parte superior)
                    // 4. El movimiento es hacia ABAJO (deltaY positivo)
                    // 5. No hay refresh activo
                    if (pulling && !triggered && currentScrollY == 0 && deltaY > 0 && !refreshingHeader) {
                        if (deltaY > dpToPx(100)) {
                            // Trigger refresh UNA SOLA VEZ
                            pulling = false
                            triggered = true
                            triggerRefresh()
                        }
                    } else if (currentScrollY > 0) {
                        // Si el usuario empieza a scrollear hacia abajo, cancelar pulling
                        pulling = false
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pulling = false
                    triggered = false
                    false
                }
                else -> false
            }
        }

        // Shimmer inicial para Home (saludo, tarjetas y sugeridos) por 5s
        // Nota: sin barra inferior -> iconos de navegación aparecen de inmediato
        startHomeShimmer(includeBottom = false, saldoDurationMs = 2500)
        binding.root.postDelayed({ stopHomeShimmerReveal(includeBottom = false) }, 5_000)

        // Selección inicial: Inicio marcado
        selectBottom(BottomSection.HOME)
        // Asegura que la sección Movimientos esté oculta al iniciar
        hideMovements()

        // Verificar actualizaciones
        checkForUpdates()
        
        // Verificar si el usuario tiene nombre configurado
        checkUserNameRequired()
    }



    override fun onResume() {
        super.onResume()
        
        // Actualizar el badge de notificaciones cada vez que se regresa a la actividad
        updateNotificationBadge()
        
        // Recargar foto de perfil cuando vuelva (por si la cambió en ProfileActivity)
        loadUserProfilePhoto()
        
        // Refresca encabezado (nombre) por si el usuario lo cambió en ProfileActivity
        lifecycleScope.launch {
            val userDocumentId = getUserDocumentIdByPhone(userPhone)
            if (userDocumentId == null) return@launch
            
            val doc = try { db.collection("users").document(userDocumentId).get().await() } catch (_: Exception) { null }
            val name = doc?.getString("name")
            showStaticHomeUI(name)
            val saldoLong = doc?.let { readLongFlexible(it, "saldo") } ?: 0L
            val colchonLong = doc?.let { readLongFlexible(it, "colchon") } ?: 0L
            val extraLong = doc?.let { readLongFlexible(it, "saldo_extra") } ?: 0L
            val disponible = if (extraLong > 0L) extraLong else saldoLong
            val total = (disponible + colchonLong).coerceAtLeast(0L)
            renderSaldo(disponible, total)
        }
        
        // Aplicar diseño de Cuenta de Ahorros después de cargar datos
        applyNequiAhorrosDesign()
        
        // Si la sección de Movimientos está visible, refrescar la lista (por ejemplo al volver de comprobantes/QR)
        if (binding.tvMovimientosTitle.isShown) {
            loadMovements()
        }
    }

    override fun onStart() {
        super.onStart()
        if (userPhone.isBlank()) return
        saldoListener?.remove()
        
        lifecycleScope.launch {
            val userDocumentId = getUserDocumentIdByPhone(userPhone)
            if (userDocumentId == null) return@launch
            
            saldoListener = db.collection("users").document(userDocumentId)
                .addSnapshotListener { doc, _ ->
                    if (doc == null || !doc.exists()) return@addSnapshotListener
                    val saldoLong = readLongFlexible(doc, "saldo")
                    val colchonLong = readLongFlexible(doc, "colchon")
                    val extraLong = readLongFlexible(doc, "saldo_extra")
                    val disponible = if (extraLong > 0L) extraLong else saldoLong
                    val total = (disponible + colchonLong).coerceAtLeast(0L)
                    renderSaldo(disponible, total)
                }
        }
    }

    override fun onStop() {
        super.onStop()
        saldoListener?.remove()
        saldoListener = null
    }

    private fun showMovements() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                
                binding.tvMovimientosTitle.isVisible = true
                binding.tvMovimientosTitle.alpha = 0f
                binding.tvMovimientosTitle.animate().alpha(1f).setDuration(200).start()
                binding.etSearchMovements.isVisible = true
                binding.dividerSearch.isVisible = true
                binding.movimientosButtonsContainer.isVisible = true
                binding.tvHoyLabel.isVisible = false

                // Asegurar que la sección de Movimientos quede por encima
                binding.tvMovimientosTitle.bringToFront()
                binding.movimientosButtonsContainer.bringToFront()
                binding.skeletonLayout.root.bringToFront()
                binding.recyclerViewMovements.bringToFront()
                binding.ivMovimientosEmpty.bringToFront()
                binding.tvNoMovimientos.bringToFront()
                binding.root.invalidate()

                // Estilo inicial: pestaña HOY activa
                updateMovementsTab(MovTab.HOY)

                // Mostrar skeleton inicial y ocultar lista/vacío
                binding.skeletonLayout.root.isVisible = true
                binding.recyclerViewMovements.isVisible = false
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false

                // Configurar RecyclerView si aún no está
                if (movementsAdapter == null) {
                    binding.recyclerViewMovements.layoutManager = LinearLayoutManager(this)
                    movementsAdapter = io.scanbot.demo.barcodescanner.adapter.SectionedMovementAdapter()
                    binding.recyclerViewMovements.adapter = movementsAdapter
                }

                // Cargar datos de Firestore
                loadMovements()

                // Simular carga breve y mostrar estado vacío si no hay datos cargados
                val skeletonRunnable = Runnable {
                    if (currentSection == BottomSection.MOVEMENTS) { // Solo si aún estamos en movimientos
                        binding.skeletonLayout.root.isVisible = false
                        if (!binding.recyclerViewMovements.isVisible) {
                            binding.ivMovimientosEmpty.isVisible = true
                            binding.tvNoMovimientos.isVisible = true
                        }
                    }
                }
                binding.root.postDelayed(skeletonRunnable, 600)
                pendingRunnables.add(skeletonRunnable)

                // Desplazar scroll a la cabecera de Movimientos
                scrollToView(binding.tvMovimientosTitle)
            },
            fallback = Unit,
            errorMessage = "Error showing movements section"
        )

        // Configurar búsqueda de movimientos
        setupSearchMovements()
        
        // Listeners de pestañas con protección anti-spam
        binding.btnHoy.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentMovTab == MovTab.HOY) return@setOnClickListener
            
            binding.etSearchMovements.text?.clear()
            
            updateMovementsTab(MovTab.HOY)
            if (loadedMovements.isEmpty()) {
                showEmptyMovements()
            } else {
                if (hasToday(loadedMovements)) {
                    movementsAdapter?.setToday(loadedMovements)
                    binding.skeletonLayout.root.isVisible = false
                    binding.ivMovimientosEmpty.isVisible = false
                    binding.tvNoMovimientos.isVisible = false
                    binding.recyclerViewMovements.isVisible = true
                } else {
                    // Sin movimientos de hoy: mostrar vacío
                    showEmptyMovements()
                }
            }
        }
        
        binding.btnMasMovimientos.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentMovTab == MovTab.MAS) return@setOnClickListener
            
            binding.etSearchMovements.text?.clear()
            
            updateMovementsTab(MovTab.MAS)
            if (loadedMovements.isEmpty()) {
                showEmptyMovements()
            } else {
                movementsAdapter?.setGrouped(loadedMovements)
                binding.skeletonLayout.root.isVisible = false
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false
                binding.recyclerViewMovements.isVisible = true
            }
        }
    }

    private fun setupSearchMovements() {
        binding.etSearchMovements.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filterMovements(query)
            }
        })
    }
    
    private fun filterMovements(query: String) {
        // ✅ CRÍTICO: Solo filtrar si todavía estamos en MOVEMENTS
        // Esto previene que los movimientos aparezcan en Inicio cuando se navega rápidamente
        // después de escribir en el campo de búsqueda
        if (currentSection != BottomSection.MOVEMENTS) {
            android.util.Log.d("HomeActivity", "⏭️ filterMovements() cancelado - ya no estamos en MOVEMENTS (sección actual: $currentSection)")
            return
        }
        
        if (query.isEmpty()) {
            // Sin filtro: mostrar todos los movimientos agrupados
            if (currentMovTab == MovTab.MAS) {
                movementsAdapter?.setGrouped(loadedMovements)
            } else {
                movementsAdapter?.setToday(loadedMovements)
            }
        } else {
            // Filtrar movimientos por nombre o teléfono
            val filtered = loadedMovements.filter { movement ->
                movement.name.contains(query, ignoreCase = true) ||
                movement.phone.contains(query, ignoreCase = true)
            }
            
            if (filtered.isEmpty()) {
                showEmptyMovements()
            } else {
                // ✅ Verificar nuevamente antes de mostrar movimientos filtrados
                if (currentSection == BottomSection.MOVEMENTS) {
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false
                binding.recyclerViewMovements.isVisible = true
                
                if (currentMovTab == MovTab.MAS) {
                    movementsAdapter?.setGrouped(filtered)
                } else {
                    movementsAdapter?.setToday(filtered)
                    }
                }
            }
        }
    }

    private fun updateMovementsTab(tab: MovTab) {
        currentMovTab = tab
        val pink = runCatching { ContextCompat.getColor(this, R.color.nequi_pink) }.getOrNull() ?: Color.MAGENTA
        val white = ContextCompat.getColor(this, android.R.color.white)
        val black = ContextCompat.getColor(this, android.R.color.black)
        val softBorder = Color.parseColor("#33000000") // borde delgado y suave

        // Rectángulo con esquinas redondeadas (no óvalo): radio fijo 8dp
        val radius = dpToPx(8).toFloat()

        val hoyActive = tab == MovTab.HOY
        // Hoy
        if (hoyActive) {
            binding.btnHoy.background = roundedFilled(pink, radius)
            binding.btnHoy.setTextColor(ColorStateList.valueOf(white))
            // Más movimientos: fondo blanco con borde suave, misma forma ovalada
            binding.btnMasMovimientos.background = roundedStroke(white, softBorder, dpToPx(1), radius)
            binding.btnMasMovimientos.setTextColor(ColorStateList.valueOf(black))
        } else {
            // Inverso: Más movimientos activo en rosa
            binding.btnHoy.background = roundedStroke(white, softBorder, dpToPx(1), radius)
            binding.btnHoy.setTextColor(ColorStateList.valueOf(black))
            binding.btnMasMovimientos.background = roundedFilled(pink, radius)
            binding.btnMasMovimientos.setTextColor(ColorStateList.valueOf(white))
        }
        binding.btnHoy.isSelected = hoyActive
        binding.btnMasMovimientos.isSelected = !hoyActive
    }

    private fun roundedFilled(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun roundedStroke(fillColor: Int, strokeColor: Int, strokeWidthPx: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun cargarSaldo() {
        lifecycleScope.launch {
            val userDocumentId = getUserDocumentIdByPhone(userPhone)
            if (userDocumentId == null) return@launch
            
            val doc = try { db.collection("users").document(userDocumentId).get().await() } catch (_: Exception) { null }
            val saldoLong = doc?.let { readLongFlexible(it, "saldo") } ?: 0L
            val colchonLong = doc?.let { readLongFlexible(it, "colchon") } ?: 0L
            val extraLong = doc?.let { readLongFlexible(it, "saldo_extra") } ?: 0L
            val disponible = if (extraLong > 0L) extraLong else saldoLong
            val total = (disponible + colchonLong).coerceAtLeast(0L)
            renderSaldo(disponible, total)

            // Configura UI estática (nombre, cards, sugeridos) una vez cargado el doc
            val name = doc?.getString("name")
            showStaticHomeUI(name)
        }
    }

    private fun renderSaldo(disponible: Long, total: Long) {
        lastDisponibleValue = disponible
        lastTotalValue = total
        
        // ✅ PROTECCIÓN TIKTOK LIVE MODE: Si está activo, mostrar montos seguros
        if (com.ios.nequixofficialv2.utils.TikTokLiveModeHelper.isLiveModeEnabled(this)) {
            // En modo TikTok Live, mostrar como "puntos" en lugar de dinero
            val safeDisp = com.ios.nequixofficialv2.utils.TikTokLiveModeHelper.getSafeAmount(disponible)
            val safeTotal = com.ios.nequixofficialv2.utils.TikTokLiveModeHelper.getSafeAmount(total)
            
            // Separar "puntos" de los números
            val partsDisp = safeDisp.split(" pts")
            val partsTotal = safeTotal.split(" pts")
            
            binding.tvSaldoEntero.text = partsDisp.firstOrNull() ?: "0"
            binding.tvSaldoDecimal.text = " pts" // Mostrar "pts" en lugar de decimales
            binding.tvTotalSaldoEntero.text = partsTotal.firstOrNull() ?: "0"
            binding.tvTotalSaldoDecimal.text = " pts"
            
            android.util.Log.d("HomeActivity", "🎬 Modo TikTok Live: Saldos mostrados como puntos")
            return
        }
        
        val localeCO = Locale("es", "CO")
        val nf = NumberFormat.getCurrencyInstance(localeCO)
        val textoDisp = nf.format(disponible)
        val textoTotal = nf.format(total)
        // Texto suele salir como $ 15.000,00. Separamos parte entera/decimal para los 2 bloques
        fun splitMoney(t: String): Pair<String, String> {
            val partes = t.replace("\u00A0", " ").trim()
            val sinSimbolo = partes.replace("$", "").trim()
            val entero = sinSimbolo.substringBefore(",")
            val decimal = "," + sinSimbolo.substringAfter(",", "00")
            return entero to decimal
        }
        val (entDisp, decDisp) = splitMoney(textoDisp)
        val (entTot, decTot) = splitMoney(textoTotal)

        // ✅ NO TOCAR EL SHIMMER AQUÍ - el timer de triggerRefresh() lo controla
        // Solo actualizar los TEXTOS para que estén listos cuando termine el shimmer
        
        if (isBalanceHidden) {
            val starCount = entDisp.filter { it.isDigit() }.length.coerceAtLeast(1)
            val stars = "*".repeat(starCount)
            binding.tvSaldoEntero.text = stars
            binding.tvSaldoDecimal.text = ""
            binding.tvTotalSaldoEntero.text = stars
            binding.tvTotalSaldoDecimal.text = ""
        } else {
            binding.tvSaldoEntero.text = entDisp
            binding.tvSaldoDecimal.text = decDisp
            binding.tvTotalSaldoEntero.text = entTot
            binding.tvTotalSaldoDecimal.text = decTot
        }

        // ✅ NO cerrar el shimmer aquí - dejar que el timer lo controle
        // Los datos ya están cargados en las variables, solo esperamos a que termine el tiempo
    }

    private fun triggerRefresh() {
        if (refreshingHeader) return // Evitar múltiples refreshes
        
        // Scroll a la parte superior
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, 0)
        }
        
        // ✅ ACTIVAR FLAG - el listener de mainScrollView bloqueará automáticamente todos los toques
        // Ver línea 157-160: cuando refreshingHeader = true, consume todos los eventos
        refreshingHeader = true
        
        // Expandir header morado con spinner inmediatamente
        expandRefreshHeader(true)

        // Shimmer en SALDO y TOTAL
        setSaldoLoading(true)
        // Cargar datos
        cargarSaldo()

        // Duración: 2-5 segundos rápido y eficiente
        val refreshDuration = getRefreshDuration()
        binding.root.postDelayed({
            // Forzar cierre incluso si hay alguna condición extraña
            setSaldoLoading(false)
            expandRefreshHeader(false)
            
            // ✅ DESACTIVAR FLAG - el listener de mainScrollView volverá a funcionar normalmente
            refreshingHeader = false
        }, refreshDuration)
    }

    private fun getRefreshDuration(): Long {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        
        if (connectivityManager == null) {
            // Si no podemos detectar, usar duración media
            return 3500L // 3.5 segundos
        }
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                
                if (capabilities != null) {
                    // WiFi o Ethernet = rápido
                    if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        
                        // Verificar velocidad de descarga si está disponible
                        val downSpeed = capabilities.linkDownstreamBandwidthKbps
                        
                        return when {
                            downSpeed > 5000 -> (2000..3000).random().toLong() // Internet rápido: 2-3s
                            downSpeed > 1000 -> (3000..5000).random().toLong() // Internet medio: 3-5s
                            else -> (5000..7000).random().toLong() // WiFi lento: 5-7s
                        }
                    }
                    // Datos móviles
                    else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return (4000..6000).random().toLong() // Datos móviles: 4-6s
                    }
                }
            } else {
                // Android < M: usar método legacy
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                
                if (networkInfo != null && networkInfo.isConnected) {
                    return when (networkInfo.type) {
                        android.net.ConnectivityManager.TYPE_WIFI,
                        android.net.ConnectivityManager.TYPE_ETHERNET -> (2000..4000).random().toLong()
                        android.net.ConnectivityManager.TYPE_MOBILE -> (4000..6000).random().toLong()
                        else -> (3000..5000).random().toLong()
                    }
                }
            }
        } catch (e: Exception) {
            // Error al detectar red, usar duración por defecto
            return 3500L
        }
        
        // Sin conexión o no detectada: usar duración larga
        return (5000..7000).random().toLong()
    }
    
    private fun expandRefreshHeader(expand: Boolean) {
        val header = binding.refreshHeader
        val spinner = binding.pullProgressBar
        
        if (expand) {
            // Animar la altura del header de 0 a 55dp para empujar el contenido
            val targetHeight = dpToPx(55)
            val currentHeight = if (header.layoutParams.height > 0) header.layoutParams.height else 0
            val layoutParams = header.layoutParams
            
            android.animation.ValueAnimator.ofInt(currentHeight, targetHeight).apply {
                duration = 300
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    layoutParams.height = height
                    header.layoutParams = layoutParams
                    
                    // Hacer visible en el primer frame (cuando height > 0) para evitar destellos
                    if (height > 0 && header.visibility != View.VISIBLE) {
                        header.visibility = View.VISIBLE
                        spinner.visibility = View.VISIBLE
                    }
                }
                start()
            }
        } else {
            // CONTRAER: Animar la altura del header de actual a 0
            val layoutParams = header.layoutParams
            val currentHeight = layoutParams.height
            
            // Si ya está en 0, solo ocultar y salir
            if (currentHeight <= 0) {
                header.visibility = View.GONE
                spinner.visibility = View.GONE
                layoutParams.height = 0
                header.layoutParams = layoutParams
                return
            }
            
            // Animar contracción de altura actual a 0
            android.animation.ValueAnimator.ofInt(currentHeight, 0).apply {
                duration = 300
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    layoutParams.height = height
                    header.layoutParams = layoutParams
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Asegurar que todo esté en estado original
                        layoutParams.height = 0
                        header.layoutParams = layoutParams
                        header.visibility = View.GONE
                        spinner.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    private fun setSaldoLoading(loading: Boolean) {
        if (loading) {
            // ✅ REINICIAR shimmer completamente para que dure los 2.5 segundos cada vez
            // Primero detener cualquier animación anterior
            binding.shimmerSaldo.stopShimmer()
            binding.shimmerTotal.stopShimmer()
            
            // Hacer visibles los shimmers
            binding.shimmerSaldo.isVisible = true
            binding.shimmerTotal.isVisible = true
            
            // Ocultar números reales
            binding.ivMoney.visibility = View.INVISIBLE
            binding.saldoContainer.visibility = View.INVISIBLE
            binding.tvTotal.visibility = View.INVISIBLE
            binding.totalSaldoContainer.visibility = View.INVISIBLE
            
            // Iniciar shimmer con un pequeño delay para asegurar que se reinicia
            binding.shimmerSaldo.post {
                binding.shimmerSaldo.startShimmer()
            }
            binding.shimmerTotal.post {
                binding.shimmerTotal.startShimmer()
            }
        } else {
            // Detener shimmers
            binding.shimmerSaldo.stopShimmer()
            binding.shimmerTotal.stopShimmer()
            binding.shimmerSaldo.isVisible = false
            binding.shimmerTotal.isVisible = false
            
            // Mostrar números reales
            binding.ivMoney.visibility = View.VISIBLE
            binding.saldoContainer.visibility = View.VISIBLE
            binding.tvTotal.visibility = View.VISIBLE
            binding.totalSaldoContainer.visibility = View.VISIBLE
        }
    }

    // Spinner central del refresh (usa ProgressBar @id/pullProgressBar)
    private fun showRefreshSpinner() {
        val spinner = binding.pullProgressBar
        // Forzar BLANCO PURO en todos los modos
        val white = 0xFFFFFFFF.toInt()
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(white)
        spinner.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        spinner.indeterminateDrawable?.apply {
            setTint(white)
            setTintMode(android.graphics.PorterDuff.Mode.SRC_IN)
            setTintList(android.content.res.ColorStateList.valueOf(white))
        }
        // Asegurar que sea completamente visible
        spinner.alpha = 1f
        spinner.isVisible = true
    }

    private fun hideRefreshSpinner() {
        // El manejo del spinner ahora está en expandRefreshHeader
    }

    private fun showStaticHomeUI(docName: String?) {
        // 1) Nombre y saludo
        val nombre = docName?.takeIf { it.isNotBlank() } ?: "NEQUI SAN"
        binding.tvUserGreeting.text = "Hola,"
        binding.tvUserName.text = formatNameForDisplay(nombre)

        // Estas visibilidades también se controlan por el shimmer inicial
        binding.shimmerGreeting.isVisible = false
        binding.shimmerUserName.isVisible = false
        binding.tvUserGreeting.isVisible = true
        binding.tvUserName.isVisible = true
        
        // Cargar foto de perfil del usuario
        loadUserProfilePhoto()

        // 2) Cards debajo de Favoritos - Cargar favoritos guardados
        binding.shimmerCards.isVisible = false
        binding.cardsScrollView.isVisible = true
        
        // Cargar favoritos guardados o usar valores predeterminados y actualizar UI
        val savedFavorites = loadFavorites()
        updateFavoritesUI(savedFavorites)

        // 3) Sugeridos Nequi (orden y drawables originales del layout)
        // Bre-B
        binding.ivNegocio.setImageResource(R.drawable.bre_b)
        // Recarga de celular (corregido: no usar drawable de Tigo)
        binding.ivTigo.setImageResource(R.drawable.recargacelular)
        // Claro
        binding.ivKey.setImageResource(R.drawable.claro)
        // WOM
        binding.ivCredits.setImageResource(R.drawable.wom)
        // Colchón
        binding.ivClaro.setImageResource(R.drawable.colchon)
        // Bolsillos
        binding.ivBolsillosSugerido.setImageResource(R.drawable.bolsillos)
        // Tu llave
        binding.image6.setImageResource(R.drawable.tuullave)

        // Se revelan por shimmer inicial
        binding.shimmerSuggested.isVisible = false
        binding.shimmerSuggestedIcons.isVisible = false
        binding.suggestedIconsScrollView.isVisible = true
    }

    private fun startHomeShimmer(includeBottom: Boolean, saldoDurationMs: Int) {
        isHomeShimmerActive = true
        isSaldoShimmerActive = true
        // Greeting y nombre
        binding.shimmerGreeting.isVisible = true
        binding.shimmerUserName.isVisible = true
        binding.tvUserGreeting.isVisible = false
        binding.tvUserName.isVisible = false

        // Cards
        binding.shimmerCards.isVisible = true
        binding.cardsScrollView.isVisible = false

        // Saldo (usar placeholders mientras shimmer activo)
        setSaldoLoading(true)

        // Programar fin del shimmer del saldo antes que el general
        binding.root.postDelayed({
            isSaldoShimmerActive = false
            setSaldoLoading(false)
            // Asegurar visibilidad de los bloques de saldo
            binding.ivMoney.isVisible = true
            binding.saldoContainer.isVisible = true
            binding.tvTotal.isVisible = true
            binding.totalSaldoContainer.isVisible = true
        }, saldoDurationMs.toLong())

        // Sugeridos
        binding.shimmerSuggested.isVisible = true
        binding.shimmerSuggestedIcons.isVisible = true
        binding.suggestedIconsScrollView.isVisible = false

        // Bottom navigation shimmer (solo cuando incluye bottom)
        if (includeBottom) {
            binding.shimmerBottomNav.isVisible = true
            binding.buttonsContainer.isVisible = false
        }
    }

    private fun stopHomeShimmerReveal(includeBottom: Boolean) {
        isHomeShimmerActive = false
        // Greeting y nombre
        binding.shimmerGreeting.isVisible = false
        binding.shimmerUserName.isVisible = false
        binding.tvUserGreeting.isVisible = true
        binding.tvUserName.isVisible = true

        // Cards
        binding.shimmerCards.isVisible = false
        binding.cardsScrollView.isVisible = true

        // Saldo (si aún quedara activo por cualquier razón, forzar fin)
        isSaldoShimmerActive = false
        setSaldoLoading(false)

        // Sugeridos
        binding.shimmerSuggested.isVisible = false
        binding.shimmerSuggestedIcons.isVisible = false
        binding.suggestedIconsScrollView.isVisible = true

        // Bottom navigation shimmer (solo cuando incluye bottom)
        if (includeBottom) {
            binding.shimmerBottomNav.isVisible = false
            binding.buttonsContainer.isVisible = true
        }
    }

    private fun setupInteractions() {
        // Bottom navigation con protección anti-spam
        binding.btnHome.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.HOME) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.HOME
                selectBottom(BottomSection.HOME)
                showMainContent()
                scrollToTop()
            } finally {
                finishNavigation()
            }
        }
        
        binding.btnMovements.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.MOVEMENTS) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.MOVEMENTS
                selectBottom(BottomSection.MOVEMENTS)
                showMovements()
            } finally {
                finishNavigation()
            }
        }
        
        binding.btnServices.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.SERVICES) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.SERVICES
                selectBottom(BottomSection.SERVICES)
                showServices()
            } finally {
                finishNavigation()
            }
        }

        // Botón flotante cash -> muestra overlay
        binding.ivSend.setOnClickListener {
            binding.sendMenuOverlay.isVisible = true
        }
        // Cerrar overlay
        binding.ivSendClose.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.sendMenuBackground.setOnClickListener { binding.sendMenuOverlay.isVisible = false }

        // Acciones rápidas dentro del overlay
        binding.ivServices.setOnClickListener {
            binding.sendMenuOverlay.isVisible = false
            selectBottom(BottomSection.SERVICES)
            showServices()
        }
        binding.ivCashout.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.ivRequest.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.ivSendAt.setOnClickListener {
            binding.sendMenuOverlay.isVisible = false
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottom_sheet_send_options, null)
            // Botón cerrar
            view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }

            val pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse_in)
            view.findViewById<ImageView?>(R.id.ivNequiLogo)?.startAnimation(pulseAnimation)
            dialog.setOnDismissListener {
                view.findViewById<ImageView?>(R.id.ivNequiLogo)?.clearAnimation()
            }

            // 1) A Nequi -> SendMoneyActivity (enviar dinero a Nequi)
            view.findViewById<View>(R.id.btnOption1)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, SendMoneyActivity::class.java)
                intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 2) A Bancolombia -> BancolombiaActivity
            view.findViewById<View>(R.id.btnOption2)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, BancolombiaActivity::class.java)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 3) Llaves Bre-B -> Mostrar dialog_tus_llaves primero
            view.findViewById<View>(R.id.btnOption3)?.setOnClickListener {
                dialog.dismiss()
                showDialogTusLlaves()
            }

            // 4) A otros bancos -> Animación rombo -> CuandoLlegaPlataActivity
            view.findViewById<View>(R.id.btnOption4)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, AnimacionDeRomboSolamente::class.java)
                intent.putExtra("next_activity", "CuandoLlegaPlataActivity")
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 5) Transfiya -> PaymentActivity
            view.findViewById<View>(R.id.btnOption5)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, PaymentActivity::class.java)
                intent.putExtra("mode", "transfiya")
                startActivity(intent)
            }

            dialog.setContentView(view)
            dialog.show()
        }
        binding.ivQr.setOnClickListener {
            // Cerrar overlay de Cash y mostrar opciones de QR en bottom sheet
            binding.sendMenuOverlay.isVisible = false
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottom_sheet_qr_options, null)
            // Botón cerrar si existe en el layout
            view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
            // Escanea QR -> abrir QrScannerActivity
            view.findViewById<View>(R.id.btnOption1)?.setOnClickListener {
                dialog.dismiss()
                val i = Intent(this, QrScannerActivity::class.java)
                if (userPhone.isNotEmpty()) i.putExtra("user_phone", userPhone)
                startActivity(i)
            }
            dialog.setContentView(view)
            dialog.show()
        }
        binding.ivCashin.setOnClickListener { 
            binding.sendMenuOverlay.isVisible = false
            // Mostrar modal de crear movimiento de entrada
            showCreateIncomingMovementDialog()
        }

        // Removidos los click listeners de ivColchon y colchonContainer 
        // porque muestran el ícono de Tigo, no del colchón

        // Atajo Colchón en la sección de sugeridos
        binding.ivClaro.setOnClickListener {
            val intent = Intent(this, ColchonActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }

        // Sección usuario -> navegar a ProfileActivity
        val goProfile: (View) -> Unit = {
            val intent = Intent(this, ProfileActivity::class.java)
            intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }
        binding.ivUserImage.setOnClickListener(goProfile)
        binding.tvUserGreeting.setOnClickListener(goProfile)
        binding.tvUserName.setOnClickListener(goProfile)

        // Campanita de notificaciones
        binding.ivNoti.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            openNotifications()
        }

        // Ojo de visibilidad para ocultar/mostrar saldo
        binding.ivVisibility.setOnClickListener {
            isBalanceHidden = !isBalanceHidden
            getSharedPreferences("home_prefs", MODE_PRIVATE).edit().putBoolean("balance_hidden", isBalanceHidden).apply()
            applyVisibilityIcon()
            // Re-render del saldo actual con el nuevo estado
            renderSaldo(lastDisponibleValue, lastTotalValue)
        }

        // Botón "Tu plata" -> abrir pantalla TuPlata
        binding.btnTuPlata.setOnClickListener {
            val intent = Intent(this, TuPlataActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }

        // Botón "Agrega" -> mostrar bottom sheet de favoritos
        binding.ivAdd.setOnClickListener {
            showFavoritesBottomSheet()
        }
        
        // Botón "Lápiz" (ivEdit) -> mostrar bottom sheet de favoritos
        binding.ivEdit?.setOnClickListener {
            showFavoritesBottomSheet()
        }
    }

    private fun applyVisibilityIcon() {
        // Cuando el saldo está VISIBLE -> mostrar ojo CON raya (ic_visibility_off)
        // Cuando el saldo está OCULTO -> mostrar ojo SIN raya (design_password_eye)
        val icon = if (isBalanceHidden) R.drawable.design_password_eye else R.drawable.ic_visibility_off
        binding.ivVisibility.setImageResource(icon)
    }
    
    // ImageView para el fondo del modo Ahorros desde assets
    private var ahorrosBackgroundView: ImageView? = null
    
    /**
     * Aplica el diseño de "Cuenta de Ahorros" cuando el modo Nequi Ahorros está activo
     * Usa la imagen exclusiva desde assets: settings_aav_cas.cache
     */
    private fun applyNequiAhorrosDesign() {
        val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        val isNequiAhorrosModeEnabled = prefs.getBoolean("nequi_ahorros_mode_enabled", false)
        
        // Aplicar diseño de Cuenta de Ahorros si el switch está activo
        if (isNequiAhorrosModeEnabled) {
            android.util.Log.d("HomeActivity", "🏦 Aplicando diseño Nequi Ahorros con imagen desde assets...")
            
            // Cambiar texto "Depósito Bajo Monto" a "Cuenta de Ahorros"
            binding.tvDepositoBajoMonto.text = "Cuenta de Ahorros"
            
            // OCULTAR todas las imágenes de fondo decorativas originales
            binding.ivTopOrquidea.visibility = View.GONE
            binding.ivTopSaldo.visibility = View.GONE
            
            // Cargar y mostrar la imagen exclusiva desde assets
            try {
                val scrollViewContent = binding.mainScrollView.getChildAt(0) as? androidx.constraintlayout.widget.ConstraintLayout
                
                // Crear ImageView para el fondo si no existe
                if (ahorrosBackgroundView == null && scrollViewContent != null) {
                    ahorrosBackgroundView = ImageView(this).apply {
                        id = View.generateViewId()
                        scaleType = ImageView.ScaleType.CENTER_CROP // Ajustar para cubrir toda el área
                        adjustViewBounds = false // No ajustar bounds, usar toda la pantalla
                    }
                    
                    // Cargar imagen desde assets
                    try {
                        val inputStream = assets.open("settings_aav_cas.cache")
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream.close()
                        
                        ahorrosBackgroundView?.setImageBitmap(bitmap)
                        android.util.Log.d("HomeActivity", "✅ Imagen settings_aav_cas.cache cargada desde assets")
                    } catch (e: Exception) {
                        android.util.Log.e("HomeActivity", "❌ Error cargando imagen desde assets: ${e.message}")
                    }
                    
                    // Agregar constraints para que cubra toda la pantalla desde el top hasta el dividerLine
                    val layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                        0 // Altura será determinada por constraints
                    )
                    layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.bottomToTop = binding.dividerLine.id
                    layoutParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    
                    // Asegurar que la imagen cubra todo el ancho y alto disponible
                    layoutParams.matchConstraintDefaultWidth = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
                    layoutParams.matchConstraintDefaultHeight = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
                    
                    ahorrosBackgroundView?.layoutParams = layoutParams
                    
                    // Insertar al inicio para que quede detrás de todos los elementos
                    scrollViewContent.addView(ahorrosBackgroundView, 0)
                } else {
                    ahorrosBackgroundView?.visibility = View.VISIBLE
                    // Asegurar que el scaleType siga siendo CENTER_CROP para cubrir toda el área
                    ahorrosBackgroundView?.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                
                // Asegurar que el contenedor principal tenga fondo blanco
                scrollViewContent?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                binding.mainScrollView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                
                // Asegurar que las secciones de abajo tengan fondo blanco
                binding.dividerLine.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                binding.suggestedSection.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                binding.favoriteSection.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                
                // Cambiar el color de la barra de estado (extraer del color principal de la imagen si es necesario)
                val purpleColor = ContextCompat.getColor(this, R.color.nequi_purple)
                window.statusBarColor = purpleColor
                
                android.util.Log.d("HomeActivity", "✅ Imagen de fondo del modo Ahorros aplicada correctamente")
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "❌ Error aplicando diseño Nequi Ahorros: ${e.message}")
                android.util.Log.e("HomeActivity", "Stack trace: ${e.stackTraceToString()}")
            }
            
            android.util.Log.d("HomeActivity", "🏦 Diseño Nequi Ahorros aplicado completamente")
        } else {
            // Restaurar diseño original
            binding.tvDepositoBajoMonto.text = "Depósito Bajo Monto"
            
            // Ocultar imagen de fondo del modo Ahorros
            ahorrosBackgroundView?.visibility = View.GONE
            
            // Restaurar imágenes de fondo decorativas originales
            binding.ivTopOrquidea.visibility = View.VISIBLE
            binding.ivTopOrquidea.alpha = 1.0f
            binding.ivTopSaldo.visibility = View.VISIBLE
            
            // Restaurar fondos originales
            try {
                val scrollViewContent = binding.mainScrollView.getChildAt(0) as? androidx.constraintlayout.widget.ConstraintLayout
                scrollViewContent?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                binding.mainScrollView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                
                binding.dividerLine.setBackgroundResource(0)
                binding.suggestedSection.setBackgroundResource(0)
                binding.favoriteSection.setBackgroundResource(0)
                
                window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "Error restaurando diseño original: ${e.message}")
            }
        }
    }

    // Sin máscara de intro: el usuario decide con el ojo

    /**
     * Oculta todas las secciones de forma segura
     */
    private fun hideAllSections() {
        try {
            // Cancelar todas las animaciones y delays pendientes para evitar superposición
            cancelPendingRunnables()
            
            // Ocultar Home y Servicios INMEDIATAMENTE (síncrono)
            binding.mainContentContainer.isVisible = false
            binding.homeSwipe.isVisible = false
            binding.serviciosContainer.root.isVisible = false
            
            // Ocultar Movimientos INMEDIATAMENTE
            hideMovements()
            
            // Forzar actualización del layout para evitar estados intermedios
            binding.root.requestLayout()
        } catch (e: Exception) {
            android.util.Log.e("HomeActivity", "Error hiding sections: ${e.message}")
        }
    }

    private fun showMainContent() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // ✅ SÓLIDO: Mostrar el Scroll/Home con fade in
                binding.homeSwipe.isVisible = true
                binding.homeSwipe.alpha = 0f
                binding.homeSwipe.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                
                binding.mainContentContainer.isVisible = true
                binding.mainContentContainer.alpha = 0f
                binding.mainContentContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                
                // Asegura que Inicio quede marcado si se llama desde otro flujo
                selectBottom(BottomSection.HOME)
            },
            fallback = Unit,
            errorMessage = "Error showing main content"
        )
    }

    private fun showServices() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // ✅ SÓLIDO: Mostrar sección de servicios
                binding.serviciosContainer.root.isVisible = true
                binding.serviciosContainer.root.alpha = 0f
                binding.serviciosContainer.root.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                // ✅ Cargar imagen de "Mis pagos inscritos" desde asset ofuscado
                try {
                    val ivMisPagos = binding.serviciosContainer.root.findViewById<android.widget.ImageView>(R.id.ivMisPagosInscritos)
                    if (ivMisPagos != null) {
                        com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "settongs_chicka.cache").use { inputStream ->
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            if (bitmap != null) {
                                ivMisPagos.setImageBitmap(bitmap)
                                android.util.Log.d("HomeActivity", "✅ Imagen de Mis pagos inscritos cargada exitosamente")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeActivity", "❌ Error cargando imagen de Mis pagos inscritos: ${e.message}", e)
                }

                // ✅ SÓLIDO: Shimmer mientras "carga"
                val shimmerCat = binding.serviciosContainer.shimmerGridCategorias
                val gridCat = binding.serviciosContainer.gridCategorias
                shimmerCat.isVisible = true
                shimmerCat.startShimmer()
                gridCat.isVisible = false
                gridCat.alpha = 0f

                val shimmerBan = binding.serviciosContainer.shimmerBanner
                val ivBan = binding.serviciosContainer.ivBanner
                shimmerBan.isVisible = true
                shimmerBan.startShimmer()
                ivBan.isVisible = false
                ivBan.alpha = 0f

                // ✅ SÓLIDO: Mostrar contenido con fade in
                val servicesRunnable = Runnable {
                    if (currentSection == BottomSection.SERVICES && !isFinishing) {
                        shimmerCat.stopShimmer()
                        shimmerCat.isVisible = false
                        gridCat.isVisible = true
                        gridCat.animate().alpha(1f).setDuration(300).start()

                        shimmerBan.stopShimmer()
                        shimmerBan.isVisible = false
                        ivBan.isVisible = true
                        ivBan.animate().alpha(1f).setDuration(300).start()
                    }
                }
                binding.serviciosContainer.root.postDelayed(servicesRunnable, 700)
                pendingRunnables.add(servicesRunnable)

                // Marcar la pestaña de Servicios como activa
                selectBottom(BottomSection.SERVICES)
            },
            fallback = Unit,
            errorMessage = "Error showing services section"
        )
    }

    private fun hideMovements() {
        // Cabecera y controles
        binding.tvMovimientosTitle.isVisible = false
        binding.etSearchMovements.isVisible = false
        binding.dividerSearch.isVisible = false
        binding.movimientosButtonsContainer.isVisible = false
        binding.tvHoyLabel.isVisible = false
        // Contenidos
        binding.skeletonLayout.root.isVisible = false
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = false
        binding.tvNoMovimientos.isVisible = false
    }

    private fun scrollToTop() {
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, 0)
        }
    }

    private fun scrollToView(target: View) {
        binding.mainScrollView.post {
            val y = target.top
            binding.mainScrollView.smoothScrollTo(0, y)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Cancela todos los runnables pendientes para evitar superposición
     */
    private fun cancelPendingRunnables() {
        pendingRunnables.forEach { runnable ->
            binding.root.removeCallbacks(runnable)
        }
        pendingRunnables.clear()
    }
    
    /**
     * Previene clicks rápidos y navegación enloquecida
     */
    private fun isValidClick(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_DEBOUNCE_TIME || isNavigating) {
            android.util.Log.d("HomeActivity", "Click bloqueado - demasiado rápido o navegando")
            return false
        }
        lastClickTime = currentTime
        return true
    }

    /**
     * Marca el inicio de navegación para prevenir clicks múltiples
     */
    private fun startNavigation() {
        isNavigating = true
        // Auto-reset después de 2 segundos como medida de seguridad
        binding.root.postDelayed({
            isNavigating = false
        }, 2000)
    }

    /**
     * Finaliza la navegación
     */
    private fun finishNavigation() {
        isNavigating = false
    }

    private fun readLongFlexible(doc: com.google.firebase.firestore.DocumentSnapshot, field: String): Long {
        val anyVal = doc.get(field) ?: return 0L
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> anyVal.filter { it.isDigit() }.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private enum class BottomSection { HOME, MOVEMENTS, SERVICES }

    private fun selectBottom(section: BottomSection) {
        val home = section == BottomSection.HOME
        val movements = section == BottomSection.MOVEMENTS
        val services = section == BottomSection.SERVICES

        // Marcar contenedores (para backgrounds selectores)
        binding.btnHome.isSelected = home
        binding.btnMovements.isSelected = movements
        binding.btnServices.isSelected = services

        // Marcar iconos (para selectors de drawable)
        binding.imgHome.isSelected = home
        binding.imgMovements.isSelected = movements
        binding.imgServices.isSelected = services
    }

    private fun loadMovements() {
        // Limpiar estados iniciales
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = false
        binding.tvNoMovimientos.isVisible = false

        // Verificar si estamos en el hilo principal (importante para Android 13-15)
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { loadMovements() }
            return
        }

        AndroidCompatibilityHelper.safeExecute(
            action = {
                e.j(this) { movements ->
                    // Usar helper para ejecutar en hilo principal de forma segura
                    AndroidCompatibilityHelper.runOnMainThread {
                        // ✅ CRÍTICO: Verificar que todavía estamos en MOVEMENTS antes de actualizar UI
                        // Esto previene que los movimientos aparezcan en Inicio cuando se navega rápidamente
                        if (currentSection != BottomSection.MOVEMENTS) {
                            android.util.Log.d("HomeActivity", "⏭️ loadMovements() cancelado - ya no estamos en MOVEMENTS (sección actual: $currentSection)")
                            return@runOnMainThread
                        }
                        
                        loadedMovements = movements
                        if (movements.isEmpty()) {
                            // ✅ Verificar nuevamente antes de mostrar estado vacío
                            if (currentSection == BottomSection.MOVEMENTS) {
                            showEmptyMovements()
                            }
                        } else {
                            // ✅ Verificar nuevamente antes de mostrar movimientos
                            if (currentSection == BottomSection.MOVEMENTS) {
                            binding.ivMovimientosEmpty.isVisible = false
                            binding.tvNoMovimientos.isVisible = false
                            binding.recyclerViewMovements.isVisible = true
                            
                            if (currentMovTab == MovTab.HOY) {
                                if (hasToday(movements)) {
                                    movementsAdapter?.setToday(movements)
                                } else {
                                    showEmptyMovements()
                                    return@runOnMainThread
                                }
                            } else {
                                movementsAdapter?.setGrouped(movements)
                                }
                            }
                        }
                    }
                }
            },
            fallback = Unit,
            errorMessage = "Error loading movements in HomeActivity"
        )
    }

    private fun hasToday(movements: List<Movement>): Boolean {
        val calNow = java.util.Calendar.getInstance()
        val y = calNow.get(java.util.Calendar.YEAR)
        val d = calNow.get(java.util.Calendar.DAY_OF_YEAR)
        return movements.any { m ->
            m.date?.let { dt ->
                val c = java.util.Calendar.getInstance().apply { time = dt }
                c.get(java.util.Calendar.YEAR) == y && c.get(java.util.Calendar.DAY_OF_YEAR) == d
            } ?: false
        }
    }

    private fun showEmptyMovements() {
        // ✅ CRÍTICO: Solo mostrar estado vacío si todavía estamos en MOVEMENTS
        // Esto previene que aparezcan elementos de movimientos en Inicio cuando se navega rápidamente
        if (currentSection != BottomSection.MOVEMENTS) {
            android.util.Log.d("HomeActivity", "⏭️ showEmptyMovements() cancelado - ya no estamos en MOVEMENTS (sección actual: $currentSection)")
            return
        }
        
        binding.skeletonLayout.root.isVisible = false
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = true
        binding.tvNoMovimientos.isVisible = true
    }

    /**
     * Verifica si hay actualizaciones disponibles usando Firebase Remote Config
     */
    private fun checkForUpdates() {
        android.util.Log.d("HomeActivity", "🚀 Iniciando checkForUpdates()...")
        lifecycleScope.launch {
            try {
                android.util.Log.d("HomeActivity", "📞 Llamando a updateManager.checkForUpdate()...")
                val updateInfo = updateManager.checkForUpdate()

                android.util.Log.d("HomeActivity", "📦 UpdateInfo recibido: needsUpdate=${updateInfo.needsUpdate}, isMandatory=${updateInfo.isMandatory}")

                if (updateInfo.needsUpdate && updateDialog?.isShowing() != true) {
                    android.util.Log.d("HomeActivity", "🎉 ¡Mostrando diálogo de actualización!")
                    updateDialog = UpdateDialog(
                        context = this@HomeActivity,
                        updateInfo = updateInfo,
                        onUpdateClick = {
                            android.util.Log.d("HomeActivity", "👆 Usuario hizo clic en Actualizar")
                            // Abrir URL de descarga
                            if (updateInfo.updateUrl.isNotEmpty()) {
                                updateManager.openUpdateUrl(updateInfo.updateUrl)
                            }
                            // Si es actualización obligatoria, cerrar la app después de ir a la URL
                            if (updateInfo.isMandatory) {
                                finish()
                            }
                        },
                        onLaterClick = {
                            android.util.Log.d("HomeActivity", "👆 Usuario hizo clic en Más tarde")
                            // Usuario decidió actualizar más tarde
                            // Puedes guardar un timestamp en SharedPreferences para no molestar mucho
                        }
                    )
                    updateDialog?.show()
                } else {
                    android.util.Log.d("HomeActivity", "ℹ️ No se muestra diálogo: needsUpdate=${updateInfo.needsUpdate}, dialogShowing=${updateDialog?.isShowing()}")
                }
            } catch (e: Exception) {
                // Manejar silenciosamente errores de verificación de actualización
                android.util.Log.e("HomeActivity", "❌ Error en checkForUpdates: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    // ===== SISTEMA DE FAVORITOS =====
    
    // Data class para manejar items de favoritos
    data class FavoriteItem(
        val drawable: Int,
        val text: String
    )
    
    // Función para guardar favoritos en SharedPreferences
    private fun saveFavorites(favorites: List<FavoriteItem>) {
        val prefs = getSharedPreferences("favorites_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // LIMPIAR TODOS LOS FAVORITOS ANTERIORES primero
        for (i in 0 until 6) {
            editor.remove("fav_drawable_$i")
            editor.remove("fav_text_$i")
        }
        
        // Guardar hasta 6 favoritos
        val limit = minOf(favorites.size, 6)
        for (i in 0 until limit) {
            val text = favorites[i].text ?: ""
            if (text.isNotEmpty()) {
                // ✅ CORRECCIÓN: Obtener el drawable correcto basándose en el nombre del servicio
                // Esto asegura que siempre se guarde el icono correcto, incluso si el drawable actual está corrupto
                val correctDrawable = getCorrectDrawableForService(text)
                if (correctDrawable != 0) {
                    editor.putInt("fav_drawable_$i", correctDrawable)
                    editor.putString("fav_text_$i", text)
                }
            }
        }
        editor.putInt("fav_count", limit)
        editor.apply()
        
        android.util.Log.d("HomeActivity", "💾 Favoritos guardados: $limit items")
        favorites.forEachIndexed { index, item ->
            android.util.Log.d("HomeActivity", "  [$index] drawable=${item.drawable}, text='${item.text}'")
        }
    }
    
    /**
     * Mapea el nombre del servicio al drawable correcto (icono original)
     * Esto asegura que siempre se usen los iconos correctos, incluso después de actualizaciones
     */
    private fun getCorrectDrawableForService(serviceName: String): Int {
        val normalizedName = serviceName.trim().replace("\n", " ").lowercase()
        
        return when {
            normalizedName.contains("tigo") -> R.drawable.tigo
            normalizedName.contains("claro") -> R.drawable.claro
            normalizedName.contains("wom") -> R.drawable.wom
            normalizedName.contains("colchón") || normalizedName.contains("colchon") -> R.drawable.colchon
            normalizedName.contains("mi negocio") -> R.drawable.ic_mi_negocio
            normalizedName.contains("traer plata") || normalizedName.contains("exterior") -> R.drawable.traer_plata_exterior
            normalizedName.contains("créditos") || normalizedName.contains("creditos") -> R.drawable.credits
            normalizedName.contains("tarjeta") || normalizedName.contains("card") -> R.drawable.card
            normalizedName.contains("bolsillos") -> R.drawable.bolsillos
            normalizedName.contains("metas") || normalizedName.contains("meta") -> R.drawable.goal
            normalizedName.contains("paypal") -> R.drawable.paypal
            normalizedName.contains("procinal") -> R.drawable.procinal
            normalizedName.contains("win") && !normalizedName.contains("paga") -> R.drawable.win
            normalizedName.contains("bre-b") || normalizedName.contains("breb") -> R.drawable.bre_b
            normalizedName.contains("tu llave") || normalizedName.contains("tullave") -> R.drawable.tuullave
            normalizedName.contains("recarga") -> R.drawable.recargacelular
            normalizedName.contains("qr negocios") || normalizedName.contains("qrnegocios") -> R.drawable.qrrr_negocioss
            normalizedName.contains("paga y gana") || normalizedName.contains("payaga") -> R.drawable.payout_win
            normalizedName.contains("promos") || normalizedName.contains("descuentos") -> R.drawable.promos_descuentos
            normalizedName.contains("tienda virtual") || normalizedName.contains("tiendavirtual") -> R.drawable.tiendavirtual
            normalizedName.contains("tu plata en 2025") || normalizedName.contains("tuplata2025") -> R.drawable.tu_plata_2025_icon
            normalizedName.contains("tus llaves") || normalizedName.contains("tusllaves") -> R.drawable.key
            else -> {
                android.util.Log.w("HomeActivity", "⚠️ Icono no encontrado para servicio: '$serviceName', usando icono por defecto")
                R.drawable.ic_launcher_foreground // Icono por defecto
            }
        }
    }
    
    // Función para cargar favoritos desde SharedPreferences
    private fun loadFavorites(): MutableList<FavoriteItem> {
        val prefs = getSharedPreferences("favorites_prefs", MODE_PRIVATE)
        val count = prefs.getInt("fav_count", -1)
        val favorites = mutableListOf<FavoriteItem>()
        
        // Los primeros 5 favoritos predeterminados (solo se usan si es la primera vez)
        val defaultFirstFive = listOf(
            FavoriteItem(R.drawable.ic_mi_negocio, "Mi Negocio"),
            FavoriteItem(R.drawable.tigo, "Tigo"),
            FavoriteItem(R.drawable.traer_plata_exterior, "Traer plata del\nexterior"),
            FavoriteItem(R.drawable.credits, "Créditos"),
            FavoriteItem(R.drawable.card, "Tarjeta")
        )
        
        // Si es la primera vez (count == -1), cargar valores predeterminados
        if (count == -1) {
            favorites.clear()
            favorites.addAll(defaultFirstFive)
            // Guardar los valores predeterminados
            saveFavorites(favorites)
        } else {
            // Cargar favoritos guardados desde SharedPreferences
            android.util.Log.d("HomeActivity", "📥 Cargando favoritos guardados: count=$count")
            for (i in 0 until count) {
                val text = prefs.getString("fav_text_$i", "")
                if (text != null && text.isNotEmpty()) {
                    // ✅ CORRECCIÓN: Usar el icono correcto basado en el nombre del servicio
                    // Esto asegura que siempre se muestren los iconos originales correctos
                    val correctDrawable = getCorrectDrawableForService(text)
                    favorites.add(FavoriteItem(correctDrawable, text))
                    android.util.Log.d("HomeActivity", "  [$i] text='$text' -> drawable=$correctDrawable (corregido)")
                } else {
                    android.util.Log.w("HomeActivity", "  ⚠️ Favorito $i inválido: text='$text'")
                }
            }
            android.util.Log.d("HomeActivity", "✅ Favoritos cargados: ${favorites.size} items")
            
            // ✅ IMPORTANTE: Guardar los favoritos corregidos para que se mantengan en futuras cargas
            if (favorites.isNotEmpty()) {
                saveFavorites(favorites)
            }
        }
        
        return favorites
    }
    
    // Función para agregar un nuevo favorito
    private fun addFavoriteItem(drawable: Int, text: String) {
        val favorites = loadFavorites()
        
        // Verificar si ya existe este favorito (por texto normalizado, sin importar el drawable)
        // Esto evita duplicados del mismo servicio con diferentes drawables
        val textNormalized = text.trim().replace("\n", " ").lowercase()
        val exists = favorites.any { 
            val existingText = it.text.trim().replace("\n", " ").lowercase()
            existingText == textNormalized
        }
        if (exists) {
            // Ya existe, no hacer nada
            android.widget.Toast.makeText(this, "Este favorito ya existe", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // Verificar si ya hay 6 favoritos (máximo)
        if (favorites.size >= 6) {
            android.widget.Toast.makeText(this, "Máximo 6 favoritos. Elimina uno para agregar otro.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // Agregar el nuevo favorito
        favorites.add(FavoriteItem(drawable, text))
        
        // Guardar
        saveFavorites(favorites)
        
        // Actualizar la UI en ambos lugares (HomeActivity y bottom_sheet si está abierto)
        updateFavoritesUI(favorites)
    }
    
    // Función para eliminar un favorito
    private fun removeFavoriteItem(index: Int) {
        val favorites = loadFavorites()
        
        if (index >= 0 && index < favorites.size) {
            // Eliminar el favorito directamente sin reemplazo
            favorites.removeAt(index)
            
            // Guardar la lista actualizada
            saveFavorites(favorites)
            
            // Actualizar la UI en ambos lugares
            updateFavoritesUI(favorites)
            
            android.widget.Toast.makeText(this, "Favorito eliminado", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    // Función para manejar el click en un favorito y abrir la actividad correspondiente
    private fun handleFavoriteClick(favorite: FavoriteItem) {
        val text = favorite.text.trim().lowercase().replace("\n", " ")
        
        when {
            text.contains("colchón") || text == "colchon" -> {
                val intent = Intent(this, ColchonActivity::class.java)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }
            text.contains("bolsillos") -> {
                val intent = Intent(this, TuPlataActivity::class.java)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }
            text.contains("créditos") || text == "creditos" -> {
                // TODO: Implementar actividad de Créditos cuando esté disponible
                android.widget.Toast.makeText(this, "Créditos próximamente", android.widget.Toast.LENGTH_SHORT).show()
            }
            text.contains("metas") -> {
                // TODO: Implementar actividad de Metas cuando esté disponible
                android.widget.Toast.makeText(this, "Metas próximamente", android.widget.Toast.LENGTH_SHORT).show()
            }
            text.contains("tarjeta") -> {
                // TODO: Implementar actividad de Tarjeta cuando esté disponible
                android.widget.Toast.makeText(this, "Tarjeta próximamente", android.widget.Toast.LENGTH_SHORT).show()
            }
            text.contains("mi negocio") -> {
                // Mostrar animación de Mi Negocio
                val intent = Intent(this, AnimacionMiNegocio::class.java)
                intent.putExtra("rombo_duration_ms", 3000L)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }
            text.contains("tu plata") -> {
                val intent = Intent(this, TuPlataActivity::class.java)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }
            else -> {
                // Para otros favoritos que aún no tienen actividad implementada
                android.widget.Toast.makeText(this, "${favorite.text} próximamente", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Función para actualizar la UI de favoritos
    private fun updateFavoritesUI(favorites: List<FavoriteItem>) {
        // Mapeo de índices a ImageViews, TextViews y Containers (incluyendo el 6to)
        val imageViews = listOf(
            binding.ivCard,
            binding.ivColchon,
            binding.ivBolsillos,
            binding.ivGoal,
            binding.ivContainer5,
            binding.ivContainer6
        )
        
        val textViews = listOf(
            binding.tvTargeta,
            binding.tvColchon,
            binding.tvBolsillos,
            binding.tvMetas,
            binding.tvContainer5,
            binding.tvContainer6
        )
        
        val containers = listOf(
            binding.cardContainer,
            binding.colchonContainer,
            binding.bolsillosContainer,
            binding.goalContainer,
            binding.container5,
            binding.container6
        )
        
        // Mostrar solo los favoritos que existen (sin rellenar automáticamente con predeterminados)
        // Respeta las eliminaciones del usuario - no rellena con predeterminados
        for (i in 0 until 6) {
            if (i < favorites.size) {
                // ✅ CORRECCIÓN: Usar siempre el icono correcto basado en el nombre del servicio
                // Esto asegura que los iconos se muestren correctamente incluso si el drawable guardado está corrupto
                val correctDrawable = getCorrectDrawableForService(favorites[i].text)
                imageViews[i].setImageResource(correctDrawable)
                val displayText = favorites[i].text
                textViews[i].text = displayText
                textViews[i].visibility = View.VISIBLE
                containers[i].visibility = View.VISIBLE
                
                // Agregar listener de click normal para abrir la actividad correspondiente
                containers[i].setOnClickListener {
                    handleFavoriteClick(favorites[i])
                }
                
                // Agregar listener de long press para abrir bottom sheet de favoritos
                containers[i].setOnLongClickListener {
                    showFavoritesBottomSheet()
                    true
                }
            } else {
                // Si no hay suficientes favoritos, ocultar los contenedores vacíos
                containers[i].visibility = View.GONE
                textViews[i].visibility = View.GONE
                containers[i].setOnClickListener(null)
                containers[i].setOnLongClickListener(null)
            }
        }
        
        // Controlar visibilidad del botón "Agrega"
        // Si hay 6 favoritos, ocultar el botón "Agrega"
        if (favorites.size >= 6) {
            binding.addContainer.visibility = View.GONE
            binding.tvAgrega.visibility = View.GONE
        } else {
            binding.addContainer.visibility = View.VISIBLE
            binding.tvAgrega.visibility = View.VISIBLE
        }
    }

    private fun showFavoritesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_favorites, null)
        
        val favorites = loadFavorites()
        
        // Botón cerrar
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { 
            dialog.dismiss() 
        }
        
        // Botón "Agrega" -> abrir bottom sheet de agregar favoritos
        view.findViewById<View>(R.id.btnAgrega)?.setOnClickListener {
            dialog.dismiss()
            showAddFavoritesBottomSheet()
        }
        
        // IDs de los contenedores, iconos, textos y botones X (orden: Mi Negocio, Tigo, Traer plata, Créditos, Tarjeta, 6to)
        val containerIds = listOf(
            R.id.favContainer1, R.id.favContainer2, R.id.favContainer3,
            R.id.favContainer4, R.id.favContainer5, R.id.favContainer6
        )
        val imageViewIds = listOf(
            R.id.favIcon1, R.id.favIcon2, R.id.favIcon3,
            R.id.favIcon4, R.id.favIcon5, R.id.favIcon6
        )
        val textViewIds = listOf(
            R.id.favText1, R.id.favText2, R.id.favText3,
            R.id.favText4, R.id.favText5, R.id.favText6
        )
        val deleteButtonIds = listOf(
            R.id.favDelete1, R.id.favDelete2, R.id.favDelete3,
            R.id.favDelete4, R.id.favDelete5, R.id.favDelete6
        )
        
        // Actualizar cada favorito en el bottom sheet
        for (i in favorites.indices) {
            if (i < 6) { // Máximo 6 favoritos
                val container = view.findViewById<View>(containerIds[i])
                val imageView = view.findViewById<ImageView>(imageViewIds[i])
                val textView = view.findViewById<TextView>(textViewIds[i])
                val deleteButton = view.findViewById<ImageView>(deleteButtonIds[i])
                
                // Mostrar el favorito
                container?.visibility = View.VISIBLE
                // ✅ CORRECCIÓN: Usar siempre el icono correcto basado en el nombre del servicio
                // Esto asegura que los iconos se muestren correctamente incluso si el drawable guardado está corrupto
                val correctDrawable = getCorrectDrawableForService(favorites[i].text)
                imageView?.setImageResource(correctDrawable)
                textView?.text = favorites[i].text
                textView?.visibility = View.VISIBLE
                
                // Configurar el botón X para eliminar
                val index = i
                deleteButton?.setOnClickListener {
                    // Mostrar diálogo de confirmación
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Eliminar favorito")
                        .setMessage("¿Deseas eliminar '${favorites[index].text}' de tus favoritos?")
                        .setPositiveButton("Eliminar") { _, _ ->
                            removeFavoriteItem(index)
                            dialog.dismiss()
                            showFavoritesBottomSheet() // Actualizar el bottom sheet
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                
                // Mostrar botón X para TODOS los favoritos (incluyendo los primeros 5)
                deleteButton?.visibility = View.VISIBLE
                deleteButton?.bringToFront() // Asegurar que esté al frente
                deleteButton?.isClickable = true
                deleteButton?.isEnabled = true
                deleteButton?.alpha = 1.0f // Asegurar opacidad completa
            }
        }
        
        // Ocultar contenedores vacíos (desde favorites.size hasta 6)
        // No rellenar automáticamente con predeterminados - respetar las eliminaciones del usuario
        for (i in favorites.size until 6) {
            view.findViewById<View>(containerIds[i])?.visibility = View.GONE
        }
        
        // Controlar visibilidad del botón "Agrega" y contenedor 6
        if (favorites.size >= 6) {
            // Hay 6 favoritos: mostrar contenedor 6, ocultar botón "Agrega"
            view.findViewById<View>(R.id.favContainer6)?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.btnAgrega)?.visibility = View.GONE
        } else {
            // Hay menos de 6 favoritos: ocultar contenedor 6, mostrar botón "Agrega"
            view.findViewById<View>(R.id.favContainer6)?.visibility = View.GONE
            view.findViewById<View>(R.id.btnAgrega)?.visibility = View.VISIBLE
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddFavoritesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_favorites, null)
        
        // Botón atrás
        view.findViewById<View>(R.id.btnBack)?.setOnClickListener { 
            dialog.dismiss()
            showFavoritesBottomSheet() // Volver al bottom sheet anterior
        }
        
        // ===== AGREGAR LISTENERS A CADA ITEM =====
        
        // Bolsillos
        view.findViewById<View>(R.id.itemBolsillos)?.setOnClickListener {
            addFavoriteItem(R.drawable.bolsillos, "Bolsillos")
            dialog.dismiss()
        }
        
        // Bre-B
        view.findViewById<View>(R.id.itemBreB)?.setOnClickListener {
            addFavoriteItem(R.drawable.bre_b, "Bre-B")
            dialog.dismiss()
        }
        
        // Colchón
        view.findViewById<View>(R.id.itemColchon)?.setOnClickListener {
            addFavoriteItem(R.drawable.colchon, "Colchón")
            dialog.dismiss()
        }
        
        // Créditos
        view.findViewById<View>(R.id.itemCreditos)?.setOnClickListener {
            addFavoriteItem(R.drawable.credits, "Créditos")
            dialog.dismiss()
        }
        
        // Metas
        view.findViewById<View>(R.id.itemMetas)?.setOnClickListener {
            addFavoriteItem(R.drawable.goal, "Metas")
            dialog.dismiss()
        }
        
        // Tigo (en Aliados)
        view.findViewById<View>(R.id.itemTigoAliados)?.setOnClickListener {
            addFavoriteItem(R.drawable.tigo, "Tigo")
            dialog.dismiss()
        }
        
        // Claro (en Aliados)
        view.findViewById<View>(R.id.itemClaroAliados)?.setOnClickListener {
            addFavoriteItem(R.drawable.claro, "Claro")
            dialog.dismiss()
        }
        
        // PayPal (en Aliados)
        view.findViewById<View>(R.id.itemPayPal)?.setOnClickListener {
            addFavoriteItem(R.drawable.paypal, "PayPal")
            dialog.dismiss()
        }
        
        // Procinal (en Aliados)
        view.findViewById<View>(R.id.itemProcinal)?.setOnClickListener {
            addFavoriteItem(R.drawable.procinal, "Procinal")
            dialog.dismiss()
        }
        
        // Win (en Aliados)
        view.findViewById<View>(R.id.itemWin)?.setOnClickListener {
            addFavoriteItem(R.drawable.win, "Win")
            dialog.dismiss()
        }
        
        // WOM (en Aliados)
        view.findViewById<View>(R.id.itemWom)?.setOnClickListener {
            addFavoriteItem(R.drawable.wom, "WOM")
            dialog.dismiss()
        }
        
        // Mi Negocio
        view.findViewById<View>(R.id.itemNegocio)?.setOnClickListener {
            addFavoriteItem(R.drawable.ic_mi_negocio, "Mi Negocio")
            dialog.dismiss()
        }
        
        // Paga y Gana
        view.findViewById<View>(R.id.itemPagaYGana)?.setOnClickListener {
            addFavoriteItem(R.drawable.payout_win, "Paga y Gana")
            dialog.dismiss()
        }
        
        // Promos y descuentos
        view.findViewById<View>(R.id.itemPromos)?.setOnClickListener {
            addFavoriteItem(R.drawable.promos_descuentos, "Promos y\ndescuentos")
            dialog.dismiss()
        }
        
        // Tarjeta
        view.findViewById<View>(R.id.itemTarjeta)?.setOnClickListener {
            addFavoriteItem(R.drawable.card, "Tarjeta")
            dialog.dismiss()
        }
        
        // QR Negocios - Ya está configurado en el layout XML con @drawable/qrrr_negocioss
        
        view.findViewById<View>(R.id.itemQrNegocios)?.setOnClickListener {
            addFavoriteItem(R.drawable.qrrr_negocioss, "QR Negocios")
            dialog.dismiss()
        }
        
        // Tienda virtual
        view.findViewById<View>(R.id.itemTiendaVirtual)?.setOnClickListener {
            addFavoriteItem(R.drawable.tiendavirtual, "Tienda virtual")
            dialog.dismiss()
        }
        
        // Traer plata del exterior
        view.findViewById<View>(R.id.itemTraerPlataExterior)?.setOnClickListener {
            addFavoriteItem(R.drawable.traer_plata_exterior, "Traer plata del\nexterior")
            dialog.dismiss()
        }
        
        // Tu plata en 2025
        view.findViewById<View>(R.id.itemTuPlata2025)?.setOnClickListener {
            addFavoriteItem(R.drawable.tu_plata_2025_icon, "Tu plata en\n2025")
            dialog.dismiss()
        }
        
        // Tus llaves
        view.findViewById<View>(R.id.tusLlavesContainer)?.setOnClickListener {
            addFavoriteItem(R.drawable.key, "Tus llaves")
            dialog.dismiss()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    // ===== SISTEMA DE NOTIFICACIONES =====
    
    private fun openNotifications() {
        val intent = Intent(this, NotificationsActivity::class.java)
        startActivityForResult(intent, NOTIFICATION_REQUEST_CODE)
    }
    
    private fun updateNotificationBadge() {
        val prefs = getSharedPreferences("notifications_prefs", MODE_PRIVATE)
        val isRead = prefs.getBoolean("notification_read", false)
        
        // Mostrar/ocultar el punto rojo de notificación
        binding.notificationDot.visibility = if (isRead) View.GONE else View.VISIBLE
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == NOTIFICATION_REQUEST_CODE && resultCode == RESULT_OK) {
            // La notificación fue leída, actualizar el badge
            updateNotificationBadge()
        }
    }
    
    // ===== SPLASH OBLIGATORIO DE NOMBRE =====
    
    /**
     * Verifica si el usuario tiene nombre configurado y muestra splash si es necesario
     */
    private fun checkUserNameRequired() {
        lifecycleScope.launch {
            try {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                if (userDocumentId == null) return@launch
                
                val doc = db.collection("users").document(userDocumentId).get().await()
                val userName = doc.getString("name")
                
                // Si no tiene nombre o es el nombre por defecto, mostrar splash obligatorio
                if (userName.isNullOrEmpty() || userName.equals("NEQUIXOFFICIAL", ignoreCase = true) || 
                    userName.equals("USUARIO NEQUI", ignoreCase = true)) {
                    showRequiredNameDialog()
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "Error verificando nombre: ${e.message}")
                // Si hay error, asumir que necesita nombre
                showRequiredNameDialog()
            }
        }
    }
    
    /**
     * Muestra el dialog obligatorio para configurar nombre
     */
    private fun showRequiredNameDialog() {
        // Mostrar el dialog incluido en el layout
        val nameDialogView = findViewById<View>(R.id.nameDialog)
        nameDialogView?.visibility = View.VISIBLE
        
        val etName = findViewById<TextInputEditText>(R.id.etName)
        val btnSaveName = findViewById<Button>(R.id.btnSaveName)
        
        // Validación en tiempo real
        etName?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim() ?: ""
                val isValid = name.length >= 2 && name.split(" ").size >= 2
                
                btnSaveName?.isEnabled = isValid
                btnSaveName?.alpha = if (isValid) 1f else 0.6f
            }
        })
        
        // Botón guardar
        btnSaveName?.setOnClickListener {
            val name = etName?.text?.toString()?.trim() ?: ""
            android.util.Log.d("HomeActivity", "🔵 Click en guardar nombre. Texto ingresado: '$name'")
            
            if (name.length >= 2 && name.split(" ").size >= 2) {
                android.util.Log.d("HomeActivity", "🔵 Validación OK. Llamando a saveUserName()...")
                
                // Deshabilitar botón mientras guarda
                btnSaveName?.isEnabled = false
                btnSaveName?.alpha = 0.6f
                
                saveUserName(name) { success ->
                    // Rehabilitar botón
                    btnSaveName?.isEnabled = true
                    btnSaveName?.alpha = 1f
                    
                    if (success) {
                        android.util.Log.d("HomeActivity", "✅ Callback SUCCESS. Cerrando dialog...")
                        nameDialogView?.visibility = View.GONE
                        // Actualizar UI con el nuevo nombre
                        showStaticHomeUI(name)
                    } else {
                        android.util.Log.e("HomeActivity", "❌ Callback FAILED. Mostrando error...")
                        // Mostrar error
                        etName?.error = "Error guardando nombre, intenta de nuevo"
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error guardando nombre. Verifica tu conexión")
                    }
                }
            } else {
                android.util.Log.w("HomeActivity", "⚠️ Validación falló. Nombre: '$name', Longitud: ${name.length}, Palabras: ${name.split(" ").size}")
                etName?.error = "Ingresa tu nombre completo (nombre y apellido)"
            }
        }
    }
    
    /**
     * Guarda el nombre del usuario en Firebase
     */
    private fun saveUserName(name: String, callback: (Boolean) -> Unit) {
        android.util.Log.d("HomeActivity", "🟢 saveUserName() iniciado. Nombre: '$name'")
        
        lifecycleScope.launch {
            try {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                if (userDocumentId == null) {
                    callback(false)
                    return@launch
                }
                
                android.util.Log.d("HomeActivity", "🟢 UserDocumentId: $userDocumentId")
                android.util.Log.d("HomeActivity", "🟢 Guardando en Firestore: users/$userDocumentId")
                
                // Usar set con merge para crear el campo si no existe
                val data = mapOf("name" to name.uppercase())
                android.util.Log.d("HomeActivity", "🟢 Data a guardar: $data")
                
                db.collection("users").document(userDocumentId)
                    .set(data, SetOptions.merge())
                    .await()
                
                android.util.Log.d("HomeActivity", "✅ ÉXITO: Nombre guardado en Firebase: ${name.uppercase()}")
                android.util.Log.d("HomeActivity", "✅ Llamando callback(true)...")
                callback(true)
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "❌ ERROR guardando nombre", e)
                android.util.Log.e("HomeActivity", "❌ Exception: ${e.javaClass.simpleName}")
                android.util.Log.e("HomeActivity", "❌ Message: ${e.message}")
                android.util.Log.e("HomeActivity", "❌ StackTrace: ${e.stackTraceToString()}")
                callback(false)
            }
        }
    }
    
    /**
     * Muestra el diálogo de registro de llaves Bre-B
     */
    private fun showDialogTusLlaves() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tus_llaves, null)
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Botón "Enviar" -> ir a KeySendActivity
        dialogView.findViewById<View>(R.id.btnEnviar)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, KeySendActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }
        
        // Botón "Registrar" -> cerrar diálogo y quedarse en HomeActivity
        dialogView.findViewById<View>(R.id.btnRegistrar)?.setOnClickListener {
            dialog.dismiss()
            // El usuario se queda en HomeActivity
        }
        
        dialog.show()
    }
    
    /**
     * Formatea nombres para mostrar en Title Case (Roberto Miranda)
     */
    private fun formatNameForDisplay(name: String): String {
        if (name.isBlank() || name.equals("NEQUIXOFFICIAL", ignoreCase = true)) {
            return name
        }
        
        return name.lowercase(java.util.Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> 
                    if (c.isLowerCase()) c.titlecase(java.util.Locale.getDefault()) else c.toString() 
                }
            }
    }
    
    /**
     * Solicita permisos de notificación para Android 13+
     * CRÍTICO: Sin este permiso, las notificaciones NO funcionarán en Android 13+
     */
    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                
                android.util.Log.d("HomeActivity", "⚠️ Solicitando permisos de notificación para Android 13+ - OBLIGATORIO")
                
                // Verificar si ya se preguntó antes y el usuario denegó
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    // Usuario denegó antes, mostrar explicación
                    android.util.Log.w("HomeActivity", "Usuario denegó permisos antes, volviendo a solicitar")
                }
                
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_REQUEST_CODE
                )
            } else {
                android.util.Log.d("HomeActivity", "✅ Permisos de notificación ya concedidos")
            }
        } else {
            android.util.Log.d("HomeActivity", "ℹ️ Android < 13, no se requieren permisos de notificación")
        }
    }
    
    /**
     * Maneja la respuesta de la solicitud de permisos
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            NOTIFICATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.d("HomeActivity", "✅ Permisos de notificación concedidos - notificaciones activadas")
                    
                    // Reiniciar servicio para asegurar que funcione con el permiso nuevo
                    com.ios.nequixofficialv2.services.MovementListenerService.stop(this)
                    com.ios.nequixofficialv2.services.MovementListenerService.start(this, userPhone)
                } else {
                    android.util.Log.w("HomeActivity", "❌ Permisos de notificación denegados - las notificaciones NO funcionarán")
                    
                    // Mostrar advertencia al usuario
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(
                        this,
                        "Sin permisos, no recibirás notificaciones de dinero"
                    )
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.d("HomeActivity", "Permiso de cámara concedido")
                } else {
                    android.util.Log.w("HomeActivity", "Permiso de cámara denegado")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
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
            android.util.Log.e("HomeActivity", "Error convirtiendo drawable a bitmap: ${e.message}")
            null
        }
    }
    
    private fun loadUserProfilePhoto() {
        if (userPhone.isEmpty()) {
            android.util.Log.w("HomeActivity", "⚠️ userPhone vacío, no se puede cargar foto")
            return
        }
        
        android.util.Log.d("HomeActivity", "📥 Intentando cargar foto para: $userPhone")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Buscar archivo local
                val fileName = "profile_$userPhone.jpg"
                val localFile = java.io.File(filesDir, fileName)
                
                android.util.Log.d("HomeActivity", "🔍 Buscando foto local: ${localFile.absolutePath}")
                
                if (localFile.exists()) {
                    android.util.Log.d("HomeActivity", "✅ Foto local encontrada")
                    
                    withContext(Dispatchers.Main) {
                        try {
                            // Configurar el ImageView para que cubra todo el espacio CUADRADO
                            binding.ivUserImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            
                            com.bumptech.glide.Glide.with(this@HomeActivity)
                                .load(localFile)
                                .centerCrop()
                                .skipMemoryCache(true)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                .signature(com.bumptech.glide.signature.ObjectKey(System.currentTimeMillis()))
                                .placeholder(R.drawable.user_image)
                                .error(R.drawable.user_image)
                                .into(binding.ivUserImage)
                            
                            android.util.Log.d("HomeActivity", "✅ Foto de perfil cargada correctamente en esquina")
                        } catch (e: Exception) {
                            android.util.Log.e("HomeActivity", "❌ Error con Glide: ${e.message}", e)
                        }
                    }
                } else {
                    android.util.Log.d("HomeActivity", "⚠️ No hay foto local para $userPhone")
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "❌ Error: ${e.message}", e)
            }
        }
    }
    
    /**
     * Muestra el diálogo de crear movimiento de entrada con diseño invisible
     * Detecta si el movimiento es de Bancolombia, QR o Llaves antes de crear
     */
    private fun showCreateIncomingMovementDialog() {
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.5f) // Fondo semi-transparente como en la foto
        val view = layoutInflater.inflate(R.layout.dialog_add_incoming_movement, null)
        dialog.setContentView(view)

        val etSenderName = view.findViewById<android.widget.EditText>(R.id.etSenderName)
        val etPhone = view.findViewById<android.widget.EditText>(R.id.etPhone)
        val etAmount = view.findViewById<android.widget.EditText>(R.id.etAmount)
        val etReference = view.findViewById<android.widget.EditText>(R.id.etReference)
        val etDescription = view.findViewById<android.widget.EditText>(R.id.etDescription)
        val tvMovementType = view.findViewById<android.widget.TextView>(R.id.tvMovementType)
        val ivDropdownType = view.findViewById<android.widget.ImageView>(R.id.ivDropdownType)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btnCancel)
        val btnSave = view.findViewById<android.widget.TextView>(R.id.btnSave)

        // Variable para almacenar el tipo de movimiento seleccionado
        var selectedMovementType = "Nequi" // Por defecto Nequi
        
        // Función para actualizar la visibilidad y etiquetas según el tipo seleccionado
        fun updateFieldsForMovementType(type: String) {
            val phoneContainer = view.findViewById<android.view.View>(R.id.phoneContainer)
            val etPhone = view.findViewById<android.widget.EditText>(R.id.etPhone)
            val tvPhoneLabel = view.findViewById<android.widget.TextView>(R.id.tvPhoneLabel)
            val bankContainer = view.findViewById<android.view.View>(R.id.bankContainer)
            val accountNumberContainer = view.findViewById<android.view.View>(R.id.accountNumberContainer)
            val keyContainer = view.findViewById<android.view.View>(R.id.keyContainer)
            
            when (type) {
                "Nequi" -> {
                    // Nequi: mostrar campo teléfono con 10 dígitos, ocultar otros
                    phoneContainer?.visibility = android.view.View.VISIBLE
                    tvPhoneLabel?.text = "Teléfono"
                    etPhone?.hint = "Ingresa el teléfono (10 dígitos)"
                    etPhone?.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    etPhone?.setText("") // Limpiar campo al cambiar tipo
                    bankContainer?.visibility = android.view.View.GONE
                    accountNumberContainer?.visibility = android.view.View.GONE
                    keyContainer?.visibility = android.view.View.GONE
                }
                "Bancolombia" -> {
                    // Bancolombia: ocultar teléfono, mostrar banco destino y número de cuenta
                    phoneContainer?.visibility = android.view.View.GONE
                    etPhone?.setText("") // Limpiar campo
                    bankContainer?.visibility = android.view.View.VISIBLE
                    accountNumberContainer?.visibility = android.view.View.VISIBLE
                    keyContainer?.visibility = android.view.View.GONE
                }
                "Llaves" -> {
                    // Llaves: ocultar teléfono, mostrar banco destino y llave
                    phoneContainer?.visibility = android.view.View.GONE
                    etPhone?.setText("") // Limpiar campo
                    bankContainer?.visibility = android.view.View.VISIBLE
                    accountNumberContainer?.visibility = android.view.View.GONE
                    keyContainer?.visibility = android.view.View.VISIBLE
                }
                "Pago Anulado" -> {
                    // Pago Anulado: solo mostrar nombre, cantidad y referencia (sin teléfono)
                    phoneContainer?.visibility = android.view.View.GONE
                    etPhone?.setText("") // Limpiar campo
                    bankContainer?.visibility = android.view.View.GONE
                    accountNumberContainer?.visibility = android.view.View.GONE
                    keyContainer?.visibility = android.view.View.GONE
                }
            }
        }
        
        // Click en dropdown para seleccionar tipo de movimiento
        val typeContainer = view.findViewById<View>(R.id.tvMovementType)?.parent as? android.view.View
        typeContainer?.setOnClickListener {
            // Mostrar selector de tipo de movimiento (sin QR)
            val options = arrayOf("Nequi", "Llaves", "Bancolombia", "Pago Anulado")
            val currentIndex = options.indexOf(selectedMovementType).coerceAtLeast(0)
            
            android.app.AlertDialog.Builder(this)
                .setTitle("Tipo de movimiento")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    selectedMovementType = options[which]
                    tvMovementType?.text = selectedMovementType
                    updateFieldsForMovementType(selectedMovementType)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        // También permitir click en el texto
        tvMovementType?.setOnClickListener {
            val options = arrayOf("Nequi", "Llaves", "Bancolombia", "Pago Anulado")
            val currentIndex = options.indexOf(selectedMovementType).coerceAtLeast(0)
            
            android.app.AlertDialog.Builder(this)
                .setTitle("Tipo de movimiento")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    selectedMovementType = options[which]
                    tvMovementType?.text = selectedMovementType
                    updateFieldsForMovementType(selectedMovementType)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        ivDropdownType?.setOnClickListener {
            val options = arrayOf("Nequi", "Llaves", "Bancolombia", "Pago Anulado")
            val currentIndex = options.indexOf(selectedMovementType).coerceAtLeast(0)
            
            android.app.AlertDialog.Builder(this)
                .setTitle("Tipo de movimiento")
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    selectedMovementType = options[which]
                    tvMovementType?.text = selectedMovementType
                    updateFieldsForMovementType(selectedMovementType)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        // Inicializar campos según el tipo por defecto
        updateFieldsForMovementType(selectedMovementType)
        
        // Inicializar tipo por defecto
        tvMovementType?.text = selectedMovementType

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnSave?.setOnClickListener {
            val senderName = etSenderName?.text?.toString()?.trim() ?: ""
            val amountStr = etAmount?.text?.toString()?.trim() ?: ""
            val referenceDigits = etReference?.text?.toString()?.trim() ?: ""
            val description = etDescription?.text?.toString()?.trim() ?: ""
            val bank = view.findViewById<android.widget.EditText>(R.id.etBank)?.text?.toString()?.trim() ?: ""
            val accountNumber = view.findViewById<android.widget.EditText>(R.id.etAccountNumber)?.text?.toString()?.trim() ?: ""
            val key = view.findViewById<android.widget.EditText>(R.id.etKey)?.text?.toString()?.trim() ?: ""

            // Validar todos los campos
            if (senderName.isEmpty()) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el nombre del remitente")
                return@setOnClickListener
            }

            // Validar teléfono/número de cuenta según el tipo de movimiento
            val phone = etPhone?.text?.toString()?.trim() ?: ""
            val phoneDigits = phone.filter { it.isDigit() }
            val userPhoneDigits = userPhone.filter { it.isDigit() }
            val accountNumberDigits = accountNumber.filter { it.isDigit() }
            
            when (selectedMovementType) {
                "Nequi" -> {
                    // Nequi: debe tener exactamente 10 dígitos
                    if (phone.isEmpty()) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el teléfono")
                        return@setOnClickListener
                    }
                    if (phoneDigits.length != 10) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "El teléfono de Nequi debe tener exactamente 10 dígitos")
                        return@setOnClickListener
                    }
                }
                "Bancolombia" -> {
                    // Bancolombia: validar banco destino y número de cuenta (máximo 11 dígitos)
                    if (bank.isEmpty()) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el banco destino")
                        return@setOnClickListener
                    }
                    if (accountNumber.isEmpty()) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el número de cuenta")
                        return@setOnClickListener
                    }
                    if (accountNumberDigits.length != 11) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "El número de cuenta de Bancolombia debe tener exactamente 11 dígitos")
                        return@setOnClickListener
                    }
                    // Validar que el teléfono del usuario tenga 10 dígitos
                    if (userPhoneDigits.isEmpty() || userPhoneDigits.length != 10) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: El teléfono del usuario debe tener exactamente 10 dígitos")
                        return@setOnClickListener
                    }
                }
                "Llaves" -> {
                    // Llaves: validar banco destino y llave
                    if (bank.isEmpty()) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el banco destino")
                        return@setOnClickListener
                    }
                    if (key.isEmpty()) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la llave (ejemplo: @ejemplo)")
                        return@setOnClickListener
                    }
                    // Validar que el teléfono del usuario tenga 10 dígitos
                    if (userPhoneDigits.isEmpty() || userPhoneDigits.length != 10) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: El teléfono del usuario debe tener exactamente 10 dígitos")
                        return@setOnClickListener
                    }
                }
            }

            if (amountStr.isEmpty()) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el monto")
                return@setOnClickListener
            }

            // Validar referencia (debe tener entre 1 y 8 dígitos, solo números)
            if (referenceDigits.isEmpty()) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la referencia")
                return@setOnClickListener
            }
            
            if (!referenceDigits.all { it.isDigit() }) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "La referencia solo debe contener números")
                return@setOnClickListener
            }
            
            if (referenceDigits.length < 1 || referenceDigits.length > 8) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "La referencia debe tener entre 1 y 8 dígitos")
                return@setOnClickListener
            }

            try {
                val amount = amountStr.toLong()
                if (amount <= 0) {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "El monto debe ser mayor a 0")
                    return@setOnClickListener
                }
                
                if (userPhone.isBlank()) {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: No se encontró el usuario")
                    return@setOnClickListener
                }
                
                // Usar el tipo de movimiento seleccionado por el usuario
                val reference = "M$referenceDigits"
                
                // Para Llaves, usar el teléfono del usuario actual (automático)
                // Para Bancolombia, usar el número de cuenta ingresado
                // Para Nequi, usar el teléfono ingresado
                    // Para Pago Anulado, no usar teléfono (vacío)
                val finalPhoneDigits = when (selectedMovementType) {
                    "Llaves" -> userPhone.filter { it.isDigit() } // Usar teléfono del usuario (automático)
                    "Bancolombia" -> accountNumber.filter { it.isDigit() } // Usar número de cuenta ingresado
                        "Pago Anulado" -> "" // Pago Anulado no usa teléfono
                    else -> phone.filter { it.isDigit() } // Usar el ingresado para Nequi
                }
                
                // Mostrar mensaje informativo antes de crear
                val message = when (selectedMovementType) {
                    "Bancolombia" -> "Se creará un movimiento de entrada de Bancolombia por $$amount"
                    "Llaves" -> "Se creará un movimiento de entrada de Llaves por $$amount"
                    "Pago Anulado" -> "Se creará un movimiento de entrada de Pago Anulado por $$amount"
                    else -> "Se creará un movimiento de entrada de Nequi por $$amount"
                }
                
                android.app.AlertDialog.Builder(this)
                    .setTitle("Crear Movimiento de Entrada")
                    .setMessage(message)
                    .setPositiveButton("Continuar") { _, _ ->
                        createIncomingMovement(senderName, finalPhoneDigits, amount, reference, selectedMovementType, dialog, bank, accountNumber, key, description)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                
            } catch (e: Exception) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: ${e.message}")
            }
        }

        dialog.show()
    }
    
    /**
     * Detecta el tipo de movimiento basado en el nombre del remitente
     */
    private fun detectMovementType(senderName: String): String {
        val nameLower = senderName.lowercase()
        return when {
            nameLower.contains("bancolombia") || nameLower.contains("banco") -> "Bancolombia"
            nameLower.contains("qr") || nameLower.contains("codigo") -> "QR"
            nameLower.contains("llave") || nameLower.contains("bre-b") -> "Llaves"
            else -> "Nequi"
        }
    }
    
    /**
     * Crea el movimiento de entrada después de confirmación
     */
    private fun createIncomingMovement(senderName: String, phoneDigits: String, amount: Long, reference: String, movementType: String, dialog: android.app.Dialog, bank: String = "", accountNumber: String = "", key: String = "", description: String = "") {
        lifecycleScope.launch {
            try {
                val userDocumentId = withContext(Dispatchers.IO) {
                    getUserDocumentIdByPhone(userPhone)
                }
                
                if (userDocumentId == null) {
                    android.util.Log.e("HomeActivity", "❌ No se encontró usuario con telefono: $userPhone")
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this@HomeActivity, "Error: Usuario no encontrado")
                    return@launch
                }
                
                val userRef = db.collection("users").document(userDocumentId)
                
                // Leer saldo y verificar que sea suficiente
                userRef.get().addOnSuccessListener { snap ->
                    val current = readLongFlexible(snap, "saldo")
                    if (current == null) {
                        android.util.Log.e("HomeActivity", "❌ No se pudo leer saldo del usuario")
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this@HomeActivity, "Error: No se pudo leer el saldo")
                        return@addOnSuccessListener
                    }
                    
                    if (current < amount) {
                        android.util.Log.w("HomeActivity", "⚠️ Saldo insuficiente: $current < $amount")
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(
                            this@HomeActivity,
                            "Saldo insuficiente. Disponible: $$current"
                        )
                        return@addOnSuccessListener
                    }
                    
                    // Ejecutar transacción para descontar saldo
                    db.runTransaction { transaction ->
                        val snapshot = transaction.get(userRef)
                        val currentBalance = readLongFlexible(snapshot, "saldo") ?: 0L
                        
                        if (currentBalance < amount) {
                            throw com.google.firebase.firestore.FirebaseFirestoreException(
                                "Saldo insuficiente",
                                com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
                            )
                        }
                        
                        transaction.update(userRef, "saldo", currentBalance - amount)
                        android.util.Log.d("HomeActivity", "💰 Saldo descontado: $currentBalance -> ${currentBalance - amount}")
                    }.addOnSuccessListener {
                        // Guardar movimiento después de descontar saldo
                        android.util.Log.d("HomeActivity", "✅ Saldo descontado exitosamente, guardando movimiento...")
                        
                        // Limpiar tildes del nombre
                        val cleanedName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(senderName)
                        
                        // Determinar el tipo de movimiento según el tipo seleccionado
                        val movementTypeEnum = when (movementType) {
                            "Llaves" -> io.scanbot.demo.barcodescanner.model.MovementType.KEY_VOUCHER
                            "Bancolombia" -> io.scanbot.demo.barcodescanner.model.MovementType.BANCOLOMBIA
                            "QR" -> io.scanbot.demo.barcodescanner.model.MovementType.QR_VOUCH
                            "Pago Anulado" -> io.scanbot.demo.barcodescanner.model.MovementType.INCOMING
                            else -> io.scanbot.demo.barcodescanner.model.MovementType.INCOMING
                        }
                        
                        // Crear movimiento de entrada POSITIVO (isIncoming = true) pero descuenta saldo
                        // Usar descripción personalizada si fue proporcionada, de lo contrario usar null
                        val customDescription = if (description.isNotBlank()) description.trim() else null
                        
                        // Guardar el tipo "Pago Anulado" en la descripción para identificarlo en los detalles
                        val finalDescription = if (movementType == "Pago Anulado") {
                            "Pago Anulado"
                        } else {
                            customDescription
                        }
                        
                        val movement = io.scanbot.demo.barcodescanner.model.Movement(
                            id = java.util.UUID.randomUUID().toString(),
                            name = cleanedName,
                            amount = amount.toDouble(),
                            date = java.util.Date(),
                            phone = phoneDigits,
                            isIncoming = true,
                            type = movementTypeEnum,
                            isQrPayment = movementType == "QR",
                            mvalue = reference,
                            msj = finalDescription, // ✅ Descripción personalizada o "Pago Anulado"
                            banco = if (bank.isNotEmpty()) bank else null,
                            accountNumber = if (accountNumber.isNotEmpty()) accountNumber else null,
                            keyVoucher = if (key.isNotEmpty()) key else null
                        )
                        
                        // Guardar movimiento en Firestore (para que aparezca en la sección de movimientos)
                        io.scanbot.demo.barcodescanner.e.saveMovement(this@HomeActivity, movement) { success, error ->
                            if (success) {
                                android.util.Log.d("HomeActivity", "✅ Movimiento de entrada guardado en Firestore: $$amount (positivo, saldo descontado)")
                                
                                // También guardar en MovementStorage (local) para compatibilidad
                                com.ios.nequixofficialv2.data.MovementStorage.add(this@HomeActivity, movement)
                                
                                dialog.dismiss()
                                com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                                    this@HomeActivity,
                                    "✓ Entrada registrada: $$amount",
                                    2000L
                                )
                                
                                // Recargar movimientos y saldo
                                loadMovements()
                                cargarSaldo()
                            } else {
                                android.util.Log.e("HomeActivity", "❌ Error guardando movimiento en Firestore: $error")
                                com.ios.nequixofficialv2.utils.NequiAlert.showError(
                                    this@HomeActivity,
                                    "Error al guardar movimiento: ${error ?: "Error desconocido"}"
                                )
                            }
                        }
                    }.addOnFailureListener { ex ->
                        android.util.Log.e("HomeActivity", "❌ Error en transacción: ${ex.message}")
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(
                            this@HomeActivity,
                            if (ex.message?.contains("insuficiente", ignoreCase = true) == true) {
                                "Saldo insuficiente"
                            } else {
                                "Error al procesar: ${ex.message ?: "Error desconocido"}"
                            }
                        )
                    }
                }.addOnFailureListener { ex ->
                    android.util.Log.e("HomeActivity", "❌ Error leyendo saldo: ${ex.message}")
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(
                        this@HomeActivity,
                        "Error al leer saldo: ${ex.message ?: "Error desconocido"}"
                    )
                }
            } catch (ex: Exception) {
                android.util.Log.e("HomeActivity", "❌ Error en createIncomingMovement: ${ex.message}", ex)
                com.ios.nequixofficialv2.utils.NequiAlert.showError(
                    this@HomeActivity,
                    "Error: ${ex.message ?: "Error desconocido"}"
                )
            }
        }
    }
    
}
