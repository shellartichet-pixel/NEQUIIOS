package com.ios.nequixofficialv2

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.Random
import java.util.Calendar
import java.util.TimeZone
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat as CoreContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
class VoucherActivity : AppCompatActivity() {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    
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
                android.util.Log.d("VoucherActivity", "✅ Usuario encontrado - ID documento: ${doc.id}")
                doc.id
            } else {
                android.util.Log.w("VoucherActivity", "⚠️ No se encontró usuario con telefono: $phoneDigits")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "❌ Error buscando usuario por telefono: ${e.message}", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Aplicar barra de estado morada para evitar destellos en Android 7-11
        AndroidCompatibilityHelper.applyNequiStatusBar(this)

        // Mostrar layout con loading INMEDIATAMENTE para evitar pantalla blanca
        setContentView(R.layout.activity_voucher_hq)
        
        // Solo fondo blanco - sin círculo animado para evitar duplicaciones
        val ivVoucher = findViewById<ImageView>(R.id.ivVoucherHQ)
        if (ivVoucher != null) {
            ivVoucher.visibility = View.GONE
        }
        // El fondo blanco ya está configurado en el FrameLayout (comprobante_background)

        // Verificar permisos para Android 13-15
        if (!checkAndRequestPermissions()) {
            return // Esperamos el resultado de permisos
        }

        initializeVoucher()
    }

    private fun checkAndRequestPermissions(): Boolean {
        // Para comprobantes no necesitamos permisos especiales en Android 13-15
        // Los comprobantes se generan internamente y se comparten via FileProvider
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            // Continuar con la generación independientemente del resultado
            // Los comprobantes pueden funcionar sin algunos permisos
            initializeVoucher()
        }
    }

    private fun initializeVoucher() {
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // Verificar si se pasó un archivo de comprobante directamente (para Bancolombia)
                val voucherFilePath = intent.getStringExtra("voucher_file_path")
                if (voucherFilePath != null) {
                    android.util.Log.d("VoucherActivity", "📄 Comprobante Bancolombia detectado: $voucherFilePath")
                    try {
                        val voucherFile = File(voucherFilePath)
                        if (voucherFile.exists() && voucherFile.length() > 0) {
                            android.util.Log.d("VoucherActivity", "✅ Archivo válido, mostrando comprobante de API")
                            // Llamar showOverlayImage en el hilo principal
                            AndroidCompatibilityHelper.runOnMainThread {
                                try {
                                    showOverlayImage(voucherFile)
                                } catch (e: Exception) {
                                    android.util.Log.e("VoucherActivity", "❌ Error mostrando comprobante: ${e.message}", e)
                                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherActivity, "Error mostrando comprobante")
                                    goHome()
                                }
                            }
                            return@safeExecute
                        } else {
                            android.util.Log.e("VoucherActivity", "❌ Archivo de comprobante no válido: existe=${voucherFile.exists()}, tamaño=${voucherFile.length()}")
                            AndroidCompatibilityHelper.runOnMainThread {
                                try {
                                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherActivity, "Error: Comprobante no encontrado")
                                    goHome()
                                } catch (e: Exception) {
                                    android.util.Log.e("VoucherActivity", "❌ Error en manejo de error: ${e.message}", e)
                                    finish()
                                }
                            }
                            return@safeExecute
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VoucherActivity", "❌ Error verificando archivo: ${e.message}", e)
                        AndroidCompatibilityHelper.runOnMainThread {
                            try {
                                com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherActivity, "Error al cargar comprobante")
                                goHome()
                            } catch (e2: Exception) {
                                android.util.Log.e("VoucherActivity", "❌ Error crítico: ${e2.message}", e2)
                                finish()
                            }
                        }
                        return@safeExecute
                    }
                }
                
                val phone = intent.getStringExtra("phone").orEmpty()
                val amountStr = intent.getStringExtra("amount").orEmpty()
                val maskedNameRaw = intent.getStringExtra("maskedName").orEmpty()
                // Ofuscar nombre QR: primeras 3 letras + *****
                val maskedName = if (maskedNameRaw.isNotEmpty()) {
                    AndroidCompatibilityHelper.obfuscateName(maskedNameRaw, uppercase = false)
                } else {
                    ""
                }
                val phoneDigits = phone.filter { it.isDigit() }
                // Detectar flujo QR: en QR no hay número de 10 dígitos
                val isQrVoucher = phoneDigits.length != 10

                // Obtener userPhone del intent
                val userPhone = intent.getStringExtra("user_phone").orEmpty()
                
                // Detectar si es plantilla QR (con llave guardada)
                val useQrTemplate = intent.getBooleanExtra("use_qr_template", false)
                val bankDestination = intent.getStringExtra("bank_destination").orEmpty()
                val qrKey = intent.getStringExtra("qr_key").orEmpty()
                val movementReference = intent.getStringExtra("reference") ?: "" // Obtener referencia del movimiento si está disponible
                
                // Resolver nombre y generar comprobante
                fun launchGeneration(name: String) {
                    val configName = if (isQrVoucher) "COMPROBANTE_QR_CONFIG" else "COMPROBANTE_NEQUI_CONFIG"
                    // Siempre aplicar formato Title Case al nombre
                    val formattedName = toTitleCase(name)
                    
                    // Usar AndroidCompatibilityHelper para ejecutar en hilo principal
                    AndroidCompatibilityHelper.runOnMainThread {
                        if (useQrTemplate) {
                            // Flujo QR con llave guardada
                            generateVoucherQr(formattedName, qrKey, bankDestination, amountStr, userPhone, movementReference)
                        } else {
                            // Flujo normal o Nequi
                            generateVoucherLocal(formattedName, phoneDigits, amountStr, configName, movementReference)
                        }
                    }
                }
        if (isQrVoucher && maskedName.isNotEmpty()) {
            // En QR, si viene nombre desde el código, aplicar formato
            launchGeneration(maskedName)
        } else if (phoneDigits.length == 10 && userPhone.isNotEmpty()) {
            // Buscar nombre del contacto guardado para el número destino
            CoroutineScope(Dispatchers.IO).launch {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                if (userDocumentId == null) {
                    return@launch
                }
                
                db.collection("users").document(userDocumentId)
                    .collection("contacts").document(phoneDigits)
                    .get()
                    .addOnSuccessListener { doc ->
                        val contactName = doc.getString("name")?.trim().orEmpty()
                        if (contactName.isNotEmpty()) {
                            launchGeneration(contactName)
                        } else {
                            searchRealUserNameForVoucher(phoneDigits, ::launchGeneration)
                        }
                    }
                    .addOnFailureListener { 
                        searchRealUserNameForVoucher(phoneDigits, ::launchGeneration)
                    }
            }
        } else if (phoneDigits.length == 10) {
            // Si no hay userPhone pero sí phoneDigits, buscar directamente en Firebase
            searchRealUserNameForVoucher(phoneDigits, ::launchGeneration)
        } else {
            launchGeneration("Usuario")
        }
            },
            fallback = { goHome() },
            errorMessage = "Error in initializeVoucher for Android 13-15"
        )
    }

    private fun generateVoucherLocal(name: String, phoneDigits: String, amountStr: String, configName: String, movementReference: String = "") {
        android.util.Log.d("VoucherActivity", "Iniciando generateVoucherLocal: name=$name, phone=$phoneDigits, amount=$amountStr, config=$configName")
        
        try {
            // Detectar si es comprobante Nequi
            val isNequi = configName == "COMPROBANTE_NEQUI_CONFIG"
            
            // NUEVO: Generar comprobante Nequi directamente sin Python (en background para máxima velocidad)
            if (isNequi) {
                android.util.Log.d("VoucherActivity", "🔵 Generando comprobante Nequi directamente en Android (optimizado)")
                val clean = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".")
                val amount = clean.toDoubleOrNull() ?: 0.0
                
                // Generar en background para no bloquear UI y máxima velocidad
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val outputFile = generateNequiVoucherDirect(name, phoneDigits, amount, movementReference)
                        withContext(Dispatchers.Main) {
                            if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                                android.util.Log.d("VoucherActivity", "✅ Comprobante Nequi generado directamente: ${outputFile.absolutePath}")
                                showOverlayImage(outputFile, isNequi = true)
                            } else {
                                android.util.Log.e("VoucherActivity", "❌ Error generando comprobante Nequi directamente")
                                com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherActivity, "Error generando comprobante")
                                goHome()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VoucherActivity", "❌ Error en generación directa: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherActivity, "Error: ${e.message}")
                            goHome()
                        }
                    }
                }
                // Retornar para evitar ejecutar Python (generación directa en progreso)
                return
            }
            
            // 1) Copiar assets necesarios a cacheDir (flujo que funciona en Android 12)
            val base = File(cacheDir, "nequi_assets").apply { 
                mkdirs()
                // Asegurar permisos como en Android 12
                setWritable(true, false)
                setReadable(true, false)
                setExecutable(true, false)
            }
            android.util.Log.d("VoucherActivity", "Directorio base creado con permisos: ${base.absolutePath}")
            
            // Copiar fuentes necesarias para Python (usando AssetObfuscator)
            val fuentesDir = File(base, "fuentes")
            fuentesDir.mkdirs()
            copyAssetFileToCache("fuentes/Manrope-Medium.ttf", File(fuentesDir, "Manrope-Medium.ttf"))
            
            // FLUJO NORMAL: Solo para tipos que NO son Nequi (Nequi usa generación directa)
            if (!isNequi) {
                // FLUJO NORMAL: Archivos genéricos
                android.util.Log.d("VoucherActivity", "Flujo normal: Copiando archivos genéricos")
                
                // Forzar sobrescritura de archivos Python para asegurar última versión
                File(base, "config.py").delete()
                File(base, "utils.py").delete()
                File(base, "localgen.py").delete()
                
                copyAssetFileToCache("config.py", File(base, "config.py"))
                copyAssetFileToCache("utils.py", File(base, "utils.py"))
                copyAssetFileToCache("localgen.py", File(base, "localgen.py"))
                
                // Copiar plantilla_nequi.jpg que está en la raíz de assets
                copyAssetFileToCache("plantilla_nequi.jpg", File(base, "plantilla_nequi.jpg"))
                
                // Asegurar permisos de archivos Python para Android 13-15
                listOf("config.py", "utils.py", "localgen.py").forEach { fileName ->
                    val file = File(base, fileName)
                    if (file.exists()) {
                        file.setWritable(true, false)
                        file.setReadable(true, false)
                        android.util.Log.d("VoucherActivity", "Permisos aplicados a $fileName")
                    }
                }
            }
            
            android.util.Log.d("VoucherActivity", "Assets copiados exitosamente con permisos")

            // 2) Iniciar Python (flujo original)
            if (! Python.isStarted()) {
                android.util.Log.d("VoucherActivity", "Iniciando Python...")
                Python.start(AndroidPlatform(this))
            }
            val py = Python.getInstance()
            
            // 🔧 FIX: Agregar directorio base al PYTHONPATH
            val sysModule = py.getModule("sys")
            val pathList = sysModule["path"]
            val basePath = base.absolutePath
            
            // Verificar si ya está en el path
            val pathStr = pathList?.toString() ?: ""
            if (!pathStr.contains(basePath)) {
                android.util.Log.d("VoucherActivity", "Agregando al PYTHONPATH: $basePath")
                pathList?.callAttr("insert", 0, basePath)
            }
            
            android.util.Log.d("VoucherActivity", "PYTHONPATH actualizado: ${sysModule["path"]}")
            
            // Cargar módulo Python (solo para tipos que NO son Nequi)
            val moduleName = "localgen"
            android.util.Log.d("VoucherActivity", "Cargando módulo Python: $moduleName desde $basePath")
            
            val mod = py.getModule(moduleName)
            android.util.Log.d("VoucherActivity", "✅ Módulo Python cargado exitosamente")

            // 3) Parsear monto a número
            val clean = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".")
            val amount = clean.toDoubleOrNull() ?: 0.0
            android.util.Log.d("VoucherActivity", "Monto parseado: $amount")

            // 4) Ejecutar con manejo de errores (solo para tipos que NO son Nequi)
            android.util.Log.d("VoucherActivity", "🐍 Llamando a Python generate_from_assets...")
            android.util.Log.d("VoucherActivity", "   Parámetros: basePath=${File(base.absolutePath).absolutePath}, name=$name, phone=$phoneDigits, amount=$amount, config=$configName")
            
            // Verificar que los archivos necesarios existen antes de ejecutar Python
            val requiredFiles = listOf("config.py", "utils.py", "localgen.py", "plantilla_nequi.jpg")
            
            val missingFiles = requiredFiles.filter { !File(base, it).exists() }
            if (missingFiles.isNotEmpty()) {
                android.util.Log.e("VoucherActivity", "❌ Archivos faltantes antes de ejecutar Python: $missingFiles")
                throw Exception("Archivos necesarios no encontrados: $missingFiles")
            }
            
            val outPath: PyObject? = try {
                // Para otros tipos (no Nequi), mantener comportamiento original
                mod.callAttr(
                    "generate_from_assets",
                    File(base.absolutePath).absolutePath,
                    name,
                    phoneDigits,
                    amount,
                    configName
                )
            } catch (pyError: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error ejecutando Python generate_from_assets: ${pyError.message}", pyError)
                throw Exception("Error en Python: ${pyError.message}", pyError)
            }

            val outFile = outPath?.toString()?.let { File(it) }
            android.util.Log.d("VoucherActivity", "🐍 Python retornó: ${outPath?.toString()}")
            
            if (outFile?.exists() == true && outFile.length() > 0) {
                android.util.Log.d("VoucherActivity", "✅ Archivo generado exitosamente: ${outFile.absolutePath}, tamaño: ${outFile.length()} bytes")
                showOverlayImage(outFile)
            } else {
                android.util.Log.e("VoucherActivity", "❌ Python no generó archivo válido")
                android.util.Log.e("VoucherActivity", "   - outFile existe: ${outFile?.exists()}")
                android.util.Log.e("VoucherActivity", "   - outFile tamaño: ${outFile?.length()}")
                android.util.Log.e("VoucherActivity", "   - outPath: ${outPath?.toString()}")
                
                // Listar archivos en el directorio base para debugging
                try {
                    val filesInBase = base.listFiles()?.map { it.name } ?: emptyList()
                    android.util.Log.e("VoucherActivity", "   - Archivos en base: $filesInBase")
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "   - Error listando archivos: ${e.message}")
                }
                
                // NUNCA usar comprobante ficticio - solo comprobantes originales
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: No se pudo generar el comprobante")
                goHome()
            }
        } catch (e: Exception) {
            // Si hay cualquier error en la ejecución de Python
            val errorMsg = e.message ?: "Error desconocido en Python"
            android.util.Log.e("VoucherActivity", "Error en generateVoucherLocal: $errorMsg", e)
            
            // NUNCA usar comprobante ficticio - siempre intentar solucionar el problema
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error Python: $errorMsg")
            goHome()
        }
    }

    private fun generateVoucherQr(para: String, llave: String, bancoDestino: String, amountStr: String, telefonoDesde: String, movementReference: String = "") {
        android.util.Log.d("VoucherActivity", "🔵 FLUJO QR: Iniciando generateVoucherQr: para=$para, llave=$llave, banco=$bancoDestino, desde=$telefonoDesde")
        
        try {
            // 1) Preparar directorio base (IGUAL QUE NEQUI)
            val base = File(cacheDir, "nequi_assets").apply { 
                mkdirs()
                setWritable(true, false)
                setReadable(true, false)
                setExecutable(true, false)
            }
            android.util.Log.d("VoucherActivity", "Directorio base QR: ${base.absolutePath}")
            
            // Copiar fuentes necesarias para Python QR (usando AssetObfuscator)
            val fuentesDir = File(base, "fuentes")
            fuentesDir.mkdirs()
            copyAssetFileToCache("fuentes/Manrope-Medium.ttf", File(fuentesDir, "Manrope-Medium.ttf"))
            
            // LIMPIEZA TOTAL - Borrar archivos previos
            File(base, "config_nequi.py").delete()
            File(base, "utils_nequi.py").delete()
            File(base, "localgen_qr.py").delete()
            File(base, "plantillaqr.jpg").delete()
            File(base, "comprobante_qr_generado.png").delete()
            android.util.Log.d("VoucherActivity", "✅ Cache limpiado para QR")
            
            // Copiar archivos específicos para QR
            copyAssetFileToCache("config_nequi.py", File(base, "config_nequi.py"))
            copyAssetFileToCache("utils_nequi.py", File(base, "utils_nequi.py"))
            copyAssetFileToCache("localgen_qr.py", File(base, "localgen_qr.py"))
            copyAssetFileToCache("plantillaqr.jpg", File(base, "plantillaqr.jpg"))
            
            // Aplicar permisos (IGUAL QUE NEQUI)
            listOf("config_nequi.py", "utils_nequi.py", "localgen_qr.py").forEach { fileName ->
                val file = File(base, fileName)
                if (file.exists()) {
                    file.setWritable(true, false)
                    file.setReadable(true, false)
                    android.util.Log.d("VoucherActivity", "Permisos aplicados a $fileName")
                }
            }
            
            android.util.Log.d("VoucherActivity", "Assets QR copiados exitosamente")
            
            // 2) Iniciar Python (IGUAL QUE NEQUI)
            if (!Python.isStarted()) {
                android.util.Log.d("VoucherActivity", "Iniciando Python...")
                Python.start(AndroidPlatform(this))
            }
            val py = Python.getInstance()
            
            // Agregar al PYTHONPATH
            val sysModule = py.getModule("sys")
            val pathList = sysModule["path"]
            val basePath = base.absolutePath
            
            val pathStr = pathList?.toString() ?: ""
            if (!pathStr.contains(basePath)) {
                android.util.Log.d("VoucherActivity", "Agregando al PYTHONPATH: $basePath")
                pathList?.callAttr("insert", 0, basePath)
            }
            
            android.util.Log.d("VoucherActivity", "PYTHONPATH actualizado: ${sysModule["path"]}")
            
            // 3) Cargar módulo localgen_qr (IGUAL QUE NEQUI carga localgen_nequi)
            val moduleName = "localgen_qr"
            android.util.Log.d("VoucherActivity", "Cargando módulo Python: $moduleName desde $basePath")
            
            val mod = py.getModule(moduleName)
            android.util.Log.d("VoucherActivity", "✅ Módulo QR cargado exitosamente")
            
            // 4) Parsear monto
            val clean = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".")
            val amount = clean.toDoubleOrNull() ?: 0.0
            android.util.Log.d("VoucherActivity", "Monto parseado: $amount")
            
            // 5) Llamar a generate_from_assets (IGUAL QUE NEQUI)
            android.util.Log.d("VoucherActivity", "🐍 Llamando a Python generate_from_assets para QR...")
            android.util.Log.d("VoucherActivity", "  Parámetros: para=$para, llave=$llave, banco=$bancoDestino, monto=$amount, desde=$telefonoDesde")
            
            // Verificar que los archivos necesarios existen antes de ejecutar Python
            val requiredFiles = listOf("config_nequi.py", "utils_nequi.py", "localgen_qr.py", "plantillaqr.jpg")
            val missingFiles = requiredFiles.filter { !File(base, it).exists() }
            if (missingFiles.isNotEmpty()) {
                android.util.Log.e("VoucherActivity", "❌ Archivos faltantes antes de ejecutar Python QR: $missingFiles")
                throw Exception("Archivos necesarios no encontrados: $missingFiles")
            }
            
            val outPath: PyObject? = try {
                mod.callAttr(
                    "generate_from_assets",
                    File(base.absolutePath).absolutePath,
                    para,
                    llave,
                    bancoDestino,
                    amount,
                    telefonoDesde,
                    if (movementReference.isNotBlank()) movementReference else null  // Pasar referencia si está disponible
                )
            } catch (pyError: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error ejecutando Python QR generate_from_assets: ${pyError.message}", pyError)
                throw Exception("Error en Python QR: ${pyError.message}", pyError)
            }
            
            val outFile = outPath?.toString()?.let { File(it) }
            android.util.Log.d("VoucherActivity", "🐍 Python QR retornó: ${outPath?.toString()}")
            
            if (outFile?.exists() == true && outFile.length() > 0) {
                android.util.Log.d("VoucherActivity", "✅ Comprobante QR generado: ${outFile.absolutePath}, tamaño: ${outFile.length()} bytes")
                showOverlayImage(outFile)
            } else {
                android.util.Log.e("VoucherActivity", "❌ Python no generó comprobante QR válido")
                android.util.Log.e("VoucherActivity", "   - outFile existe: ${outFile?.exists()}")
                android.util.Log.e("VoucherActivity", "   - outFile tamaño: ${outFile?.length()}")
                android.util.Log.e("VoucherActivity", "   - outPath: ${outPath?.toString()}")
                
                // Listar archivos en el directorio base para debugging
                try {
                    val filesInBase = base.listFiles()?.map { it.name } ?: emptyList()
                    android.util.Log.e("VoucherActivity", "   - Archivos en base: $filesInBase")
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "   - Error listando archivos: ${e.message}")
                }
                
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: No se pudo generar el comprobante QR")
                goHome()
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Error desconocido en Python QR"
            android.util.Log.e("VoucherActivity", "❌ Error en generateVoucherQr: $errorMsg", e)
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error Python QR: $errorMsg")
            goHome()
        }
    }

    private fun showOverlayImage(file: File, isNequi: Boolean = false) {
        try {
            android.util.Log.d("VoucherActivity", "🔍 Intentando mostrar comprobante: ${file.absolutePath}")
            
            // Verificar que el archivo existe y tiene contenido (con try-catch para Android 14+)
            val fileExists = try {
                file.exists()
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error verificando existencia: ${e.message}", e)
                false
            }
            
            if (!fileExists) {
                android.util.Log.e("VoucherActivity", "❌ Archivo no existe: ${file.absolutePath}")
                safeShowErrorAndGoHome("Archivo de comprobante no encontrado")
                return
            }
            
            val fileSize = try {
                file.length()
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error obteniendo tamaño: ${e.message}", e)
                0L
            }
            
            if (fileSize == 0L) {
                android.util.Log.e("VoucherActivity", "❌ Archivo vacío: ${file.absolutePath}")
                safeShowErrorAndGoHome("Archivo de comprobante está vacío")
                return
            }

            android.util.Log.d("VoucherActivity", "   Existe: true, Tamaño: $fileSize bytes")

            // Layout ya está establecido en onCreate, solo obtener referencias
            val ivVoucher = try {
                findViewById<ImageView>(R.id.ivVoucherHQ)
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error obteniendo ImageView: ${e.message}", e)
                safeShowErrorAndGoHome("Error al mostrar comprobante")
                return
            }
            
            val btnListo = try {
                findViewById<View>(R.id.btnListoInvisible)
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error obteniendo btnListo: ${e.message}", e)
                null
            }
            
            val btnCompartir = try {
                findViewById<View>(R.id.btnCompartirInvisible)
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error obteniendo btnCompartir: ${e.message}", e)
                null
            }

            // Loading ya está visible desde onCreate, no duplicar

            // ═══════════════════════════════════════════════════════════════
            // ULTRA MEGA 4K SUPREMA - MÁXIMA NITIDEZ POSIBLE
            // ═══════════════════════════════════════════════════════════════
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888  // 32-bit TRUE COLOR
                inScaled = false  // NO escalar automáticamente
                inDither = false  // SIN DITHERING = ULTRA MEGA NÍTIDO
                inPreferQualityOverSpeed = true  // CALIDAD SUPREMA
                inJustDecodeBounds = false  // Decodificar completo
                inMutable = false  // Inmutable = rendering optimizado
                inSampleSize = 1  // SIN SUBMUESTREO = Imagen 2x completa
                inTempStorage = ByteArray(64 * 1024)  // Buffer 64KB para imágenes grandes
                inPurgeable = false  // MANTENER en memoria
                inInputShareable = false  // No compartir
                inTargetDensity = 0  // Sin ajuste de densidad
                inDensity = 0  // Sin conversión DPI
            }
            
            // Decodificar con MÁXIMA CALIDAD - Usar FileInputStream para Android 14+
            // En Android 14+, usar siempre FileInputStream para mayor compatibilidad
            val bmp = if (android.os.Build.VERSION.SDK_INT >= 34) {
                // Android 14+: Usar FileInputStream directamente
                try {
                    android.util.Log.d("VoucherActivity", "📱 Android 14+, usando FileInputStream")
                    file.inputStream().use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "❌ Error con FileInputStream: ${e.message}", e)
                    // Fallback: leer bytes
                    try {
                        val bytes = file.readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    } catch (e2: Exception) {
                        android.util.Log.e("VoucherActivity", "❌ Error leyendo bytes: ${e2.message}", e2)
                        null
                    }
                }
            } else {
                // Android < 14: Intentar decodeFile primero
                try {
                    BitmapFactory.decodeFile(file.absolutePath, options) ?: run {
                        android.util.Log.w("VoucherActivity", "⚠️ decodeFile falló, intentando con FileInputStream")
                        file.inputStream().use { input ->
                            BitmapFactory.decodeStream(input, null, options)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "❌ Error decodificando imagen: ${e.message}", e)
                    try {
                        val bytes = file.readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    } catch (e2: Exception) {
                        android.util.Log.e("VoucherActivity", "❌ Error en último intento: ${e2.message}", e2)
                        null
                    }
                }
            }
            
            if (bmp != null) {
                android.util.Log.d("VoucherActivity", "✅ ULTRA 4K: ${bmp.width}x${bmp.height} - ${bmp.config} - ${bmp.byteCount} bytes")
                
                // Paint ULTRA MEGA 4K SUPREMA - Máxima calidad de renderizado
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true  // Antialiasing ULTRA
                    isFilterBitmap = true  // Filtrado bilinear PREMIUM
                    isDither = false  // SIN dithering = ULTRA NÍTIDO
                    alpha = 255  // 100% opaco - SIN TRANSPARENCIA para máxima nitidez
                    flags = android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG
                }
                
                // Configurar ImageView ULTRA MEGA 4K (con protección para Android 14+)
                try {
                ivVoucher.setImageBitmap(bmp)
                ivVoucher.alpha = 1.0f  // 100% opaco
                ivVoucher.setDrawingCacheEnabled(false)
                    // En Android 14+, usar software rendering para evitar crashes
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        try {
                            ivVoucher.setLayerType(View.LAYER_TYPE_SOFTWARE, paint)  // Software rendering para Android 14+
                        } catch (e: Exception) {
                            android.util.Log.w("VoucherActivity", "Error configurando layer type: ${e.message}")
                            ivVoucher.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    } else {
                ivVoucher.setLayerType(View.LAYER_TYPE_HARDWARE, paint)  // GPU con Paint premium
                    }
                
                // Mostrar la foto completa desde arriba - FIT_START para que empiece desde arriba sin espacios blancos
                ivVoucher.scaleType = android.widget.ImageView.ScaleType.FIT_START
                ivVoucher.adjustViewBounds = true
                
                // Asegurar que la imagen ocupe todo el ancho disponible
                val layoutParams = ivVoucher.layoutParams
                layoutParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ivVoucher.layoutParams = layoutParams
                
                // Mostrar plantilla completa - fondo blanco ya está visible
                ivVoucher.visibility = View.VISIBLE
                
                android.util.Log.d("VoucherActivity", "🚀 ULTRA MEGA 4K SUPREMA ACTIVADA - Nitidez máxima")
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "❌ Error configurando ImageView: ${e.message}", e)
                    safeShowErrorAndGoHome("Error mostrando comprobante")
                    return
                }
            } else {
                android.util.Log.e("VoucherActivity", "❌ Error decodificando imagen del comprobante")
                // Usar función segura para evitar crashes en Android 14+
                safeShowErrorAndGoHome("Error mostrando comprobante")
                return
            }

            // Configurar botones invisibles (con protección para Android 14+)
            // Ajustar posición del botón "Listo" solo para Nequi (más arriba donde está el botón en la imagen)
            if (isNequi && btnListo != null) {
                val layoutParams = btnListo.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (layoutParams != null) {
                    // Mover el botón más abajo - aproximadamente al 80% de la altura de la pantalla
                    // donde está el botón "Listo" en el comprobante de Nequi
                    layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams.topMargin = (resources.displayMetrics.heightPixels * 0.80f).toInt()
                    layoutParams.bottomMargin = 0
                    btnListo.layoutParams = layoutParams
                    android.util.Log.d("VoucherActivity", "📍 Botón Listo ajustado para Nequi - posición: ${layoutParams.topMargin}px desde arriba")
                }
            }
            
            btnListo?.setOnClickListener { 
                try {
                android.util.Log.d("VoucherActivity", "Botón Listo presionado")
                goHome() 
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "Error en btnListo: ${e.message}", e)
                    try {
                        finish()
                    } catch (e2: Exception) {
                        android.util.Log.e("VoucherActivity", "Error crítico: ${e2.message}", e2)
                    }
                }
            }
            btnCompartir?.setOnClickListener {
                try {
                android.util.Log.d("VoucherActivity", "Botón Compartir presionado")
                // Compartir imagen
                val shareBmp = (ivVoucher.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (shareBmp != null) {
                    shareVoucherImage(shareBmp)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "Error en btnCompartir: ${e.message}", e)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "❌ Error en showOverlayImage: ${e.message}", e)
            // Usar función segura para mostrar error y navegar (evita crashes en Android 14+)
            safeShowErrorAndGoHome("Error mostrando comprobante")
        }
    }

    private fun shareVoucherImage(bmp: android.graphics.Bitmap) {
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // Usar cacheDir para TODAS las versiones (funciona en Android 12)
                val dir = File(cacheDir, "shared_vouchers")
                
                if (!dir.exists()) {
                    dir.mkdirs()
                    // Asegurar permisos para Android 13-15
                    if (AndroidCompatibilityHelper.isAndroid13Plus()) {
                        dir.setWritable(true, false)
                        dir.setReadable(true, false)
                    }
                }
                
                val file = File(dir, "voucher_${System.currentTimeMillis()}.png")
                
                try {
                    FileOutputStream(file).use { out ->
                        val success = bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                        
                        if (!success) {
                            throw Exception("Failed to compress bitmap")
                        }
                    }
                    
                    // Verificar que el archivo se creó correctamente
                    if (!file.exists() || file.length() == 0L) {
                        throw Exception("File not created or empty")
                    }
                    
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "$packageName.provider",
                        file
                    )
                    
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        
                        // Para Android 13-15, agregar flags adicionales
                        if (AndroidCompatibilityHelper.isAndroid13Plus()) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    
                    startActivity(Intent.createChooser(intent, "Compartir comprobante"))
                    
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "Error saving/sharing image: ${e.message}")
                    AndroidCompatibilityHelper.runOnMainThread {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al compartir comprobante")
                    }
                }
            },
            fallback = {
                AndroidCompatibilityHelper.runOnMainThread {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al compartir")
                }
            },
            errorMessage = "Error in shareVoucherImage for Android 13-15"
        )
    }

    private fun copyAssetDirToCache(dirName: String, outDir: File) {
        if (!outDir.exists()) {
            outDir.mkdirs()
            outDir.setWritable(true, false)
            outDir.setReadable(true, false)
            outDir.setExecutable(true, false)
        }
        try {
            val assetFiles = assets.list(dirName) ?: return
            android.util.Log.d("VoucherActivity", "Copiando directorio $dirName con ${assetFiles.size} archivos")
            
            for (fileName in assetFiles) {
                val assetPath = "$dirName/$fileName"
                val outFile = File(outDir, fileName)
                
                try {
                    copyAssetFileToCache(assetPath, outFile)
                    
                    // Asegurar permisos de archivo para Android 13-15
                    if (outFile.exists()) {
                        outFile.setWritable(true, false)
                        outFile.setReadable(true, false)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VoucherActivity", "Error copiando archivo $assetPath: ${e.message}")
                    if (!AndroidCompatibilityHelper.isAndroid13Plus()) {
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "Error copiando directorio: $dirName", e)
            if (!AndroidCompatibilityHelper.isAndroid13Plus()) {
                throw e
            }
        }
    }

    private fun copyAssetFileToCache(name: String, outFile: File) {
        android.util.Log.d("VoucherActivity", "📦 Copiando asset ofuscado: $name -> ${outFile.absolutePath}")
        
        // Asegurar que el directorio padre existe
        outFile.parentFile?.mkdirs()
        
        if (outFile.exists()) {
            android.util.Log.d("VoucherActivity", "⚠️ Archivo ya existe, eliminando para reemplazar: $name")
            outFile.delete()
        }
        
        try {
            // Intentar primero con AssetObfuscator (para assets ofuscados)
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, name).use { input ->
                    outFile.outputStream().use { output ->
                        val bytesCopied = input.copyTo(output)
                        android.util.Log.d("VoucherActivity", "✅ Asset $name copiado exitosamente (${bytesCopied} bytes)")
                    }
                }
            } catch (e1: Exception) {
                android.util.Log.w("VoucherActivity", "⚠️ AssetObfuscator falló para $name: ${e1.message}, intentando asset directo...")
                
                // Fallback: intentar abrir el asset directamente (sin ofuscación)
                try {
                    assets.open(name).use { input ->
                        outFile.outputStream().use { output ->
                            val bytesCopied = input.copyTo(output)
                            android.util.Log.d("VoucherActivity", "✅ Asset $name copiado directamente (${bytesCopied} bytes)")
                        }
                    }
                } catch (e2: Exception) {
                    android.util.Log.e("VoucherActivity", "❌ Error copiando asset $name (ambos métodos fallaron):")
                    android.util.Log.e("VoucherActivity", "   - AssetObfuscator: ${e1.message}")
                    android.util.Log.e("VoucherActivity", "   - Asset directo: ${e2.message}")
                    throw Exception("No se pudo copiar asset $name: ${e2.message}", e2)
                }
            }
            
            // Verificar que el archivo se copió correctamente
            if (!outFile.exists() || outFile.length() == 0L) {
                throw Exception("Archivo copiado está vacío o no existe: ${outFile.absolutePath}")
            }
            
            android.util.Log.d("VoucherActivity", "✅ Verificación: ${outFile.absolutePath} existe (${outFile.length()} bytes)")
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "❌ Error crítico copiando asset $name: ${e.message}", e)
            throw e
        }
    }

    override fun onBackPressed() { 
        try {
            goHome()
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "Error en onBackPressed: ${e.message}", e)
            try {
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            } catch (e2: Exception) {
                android.util.Log.e("VoucherActivity", "Error crítico en finish: ${e2.message}", e2)
            }
        }
    }
    
    /**
     * Muestra un error de forma segura y regresa a Home sin crashear (especialmente para Android 14+)
     */
    private fun safeShowErrorAndGoHome(message: String) {
        try {
            if (!isFinishing && !isDestroyed) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, message)
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "Error mostrando alerta: ${e.message}", e)
        }
        
        // Usar postDelayed para evitar crash al llamar goHome inmediatamente
        try {
            window?.decorView?.postDelayed({ 
                try {
                    if (!isFinishing && !isDestroyed) {
                        goHome()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoucherActivity", "Error en goHome desde safeShowError: ${e.message}", e)
                    try {
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    } catch (e2: Exception) {
                        android.util.Log.e("VoucherActivity", "Error crítico en finish: ${e2.message}", e2)
                    }
                }
            }, 500)
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "Error en postDelayed: ${e.message}", e)
            try {
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            } catch (e2: Exception) {
                android.util.Log.e("VoucherActivity", "Error crítico: ${e2.message}", e2)
            }
        }
    }
    
    private fun goHome() {
        try {
            // Verificar que la actividad no esté destruida (Android 14+)
            if (isFinishing || isDestroyed) {
                android.util.Log.w("VoucherActivity", "⚠️ Actividad destruida, no se puede navegar")
                return
            }
            
        val i = Intent(this, HomeActivity::class.java)
        val userPhone = intent.getStringExtra("user_phone")
        if (!userPhone.isNullOrBlank()) {
            i.putExtra("user_phone", userPhone)
        }
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            
            // Verificar nuevamente antes de startActivity (Android 14+)
            if (!isFinishing && !isDestroyed) {
        startActivity(i)
            } else {
                android.util.Log.w("VoucherActivity", "⚠️ Actividad destruida antes de startActivity")
                return
            }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
                try {
                    if (!isFinishing && !isDestroyed) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VoucherActivity", "Error en overrideActivityTransition: ${e.message}")
                }
                window?.decorView?.postDelayed({ 
                    try {
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VoucherActivity", "Error en finish: ${e.message}", e)
                    }
                }, 120)
        } else {
            @Suppress("DEPRECATION")
                try {
                    if (!isFinishing && !isDestroyed) {
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VoucherActivity", "Error en overridePendingTransition: ${e.message}")
                }
                window?.decorView?.postDelayed({ 
                    try {
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VoucherActivity", "Error en finish: ${e.message}", e)
                    }
                }, 50)
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "❌ Error crítico en goHome: ${e.message}", e)
            // Si hay un error crítico, solo cerrar la actividad (con verificación para Android 14+)
            try {
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            } catch (e2: Exception) {
                android.util.Log.e("VoucherActivity", "❌ Error crítico en finish: ${e2.message}", e2)
            }
        }
    }

    /**
     * Busca el nombre real del usuario en Firebase para el comprobante
     * Prioridad: 1) contactos del usuario, 2) Firebase users, 3) número sin formatear
     */
    private fun searchRealUserNameForVoucher(phoneDigits: String, callback: (String) -> Unit) {
        val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        val userPhone = prefs.getString("user_phone", null).orEmpty()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userDocumentId = getUserDocumentIdByPhone(userPhone)
                
                if (userDocumentId != null) {
                    val contactDoc = db.collection("users").document(userDocumentId)
                        .collection("contacts").document(phoneDigits)
                        .get()
                        .await()
                    
                    val contactName = contactDoc.getString("name")?.trim().orEmpty()
                    if (contactName.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            callback(contactName)
                        }
                        return@launch
                    }
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
                        withContext(Dispatchers.Main) {
                            callback(realName)
                        }
                        return@launch
                    }
                }
                
                withContext(Dispatchers.Main) {
                    callback(phoneDigits)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoucherActivity", "Error buscando nombre: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(phoneDigits)
                }
            }
        }
    }

    // Layout XML eliminado: ya no se requiere ajuste de espaciadores

    private fun toTitleCase(input: String): String {
        if (input.isBlank()) return input
        return input.lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
            }
    }
    
    /**
     * Genera comprobante Nequi directamente en Android usando la plantilla ofuscada
     * sin necesidad de Python
     */
    private fun generateNequiVoucherDirect(
        name: String,
        phoneDigits: String,
        amount: Double,
        movementReference: String = ""
    ): File? {
        return try {
            android.util.Log.d("VoucherActivity", "🎨 Generando comprobante Nequi directamente en Android")
            
            // Cargar plantilla ofuscada
            val templateBitmap = loadNequiTemplateBitmap()
            if (templateBitmap == null) {
                android.util.Log.e("VoucherActivity", "❌ No se pudo cargar la plantilla Nequi ofuscada")
                return null
            }
            
            val originalWidth = templateBitmap.width
            val originalHeight = templateBitmap.height
            android.util.Log.d("VoucherActivity", "✅ Plantilla cargada: ${originalWidth}x${originalHeight}")
            
            // Tamaño de diseño esperado (basado en design_size de Python: 1242x2688)
            val designWidth = 1242f
            val designHeight = 2688f
            
            // Calcular factores de escala para adaptar coordenadas al tamaño real de la plantilla
            val scaleX = originalWidth / designWidth
            val scaleY = originalHeight / designHeight
            
            android.util.Log.d("VoucherActivity", "📐 Factores de escala: scaleX=$scaleX, scaleY=$scaleY")
            
            // OPTIMIZADO: Sin escalado para máxima velocidad - usar plantilla original
            // Declarar scaleFactor = 1 para no escalar (más rápido)
            val scaleFactor = 1
            // Usar plantilla original sin escalar para máxima velocidad
            val scaledTemplate = templateBitmap
            
            android.util.Log.d("VoucherActivity", "🚀 Plantilla sin escalar (máxima velocidad): ${originalWidth}x${originalHeight}")
            
            // Cargar fuente
            val fontTypeface = loadManropeFont()
            
            // Formatear nombre (ya viene en Title Case desde generateVoucherLocal)
            val nombreFormateado = name
            
            // Formatear valor
            val valorFormateado = formatNequiAmount(amount)
            
            // Formatear teléfono
            val telefonoFormateado = formatNequiPhone(phoneDigits)
            
            // Generar fecha
            val fecha = generateNequiDate()
            
            // Generar referencia
            val referencia = if (movementReference.isNotBlank()) {
                android.util.Log.d("VoucherActivity", "✅ Usando referencia del movimiento: $movementReference")
                movementReference
            } else {
                val generated = "M${Random().nextInt(90000000) + 10000000}"
                android.util.Log.d("VoucherActivity", "⚠️ No hay referencia, generando nueva: $generated")
                generated
            }
            
            // Crear bitmap editable con máxima calidad
            val resultBitmap = scaledTemplate.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)
            
            // Configurar Canvas para ULTRA CALIDAD 4K - Compatible con Android 14-16
            canvas.density = Bitmap.DENSITY_NONE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                canvas.drawFilter = android.graphics.PaintFlagsDrawFilter(
                    0,
                    Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.LINEAR_TEXT_FLAG
                )
            }
            
            // Configurar paint con outline blanco - ULTRA MEGA 4K SUPREMA
            val textColor = Color.parseColor("#200021")
            val outlineColor = Color.WHITE
            // Escalar fuente según factor de escala de la imagen (scaleFactor ya declarado arriba)
            val fontSize = 35f * scaleFactor
            val outlineWidth = 2f * scaleFactor
            
            android.util.Log.d("VoucherActivity", "🚀 Fuente escalada: 35px -> ${fontSize}px (factor: $scaleFactor)")
            
            // Paint con ULTRA MEGA CALIDAD 4K SUPREMA
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.DEV_KERN_TEXT_FLAG).apply {
                color = textColor
                textSize = fontSize
                typeface = fontTypeface
                alpha = 255
                isFakeBoldText = false
                isAntiAlias = true
                isSubpixelText = true
                isLinearText = true
                isFilterBitmap = true
                hinting = Paint.HINTING_ON
                isDither = false
                textAlign = Paint.Align.LEFT
                strokeWidth = 0f
                flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.DEV_KERN_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.LINEAR_TEXT_FLAG
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    isElegantTextHeight = false
                }
            }
            
            // Dibujar texto con outline blanco
            fun drawTextWithOutline(x: Float, y: Float, text: String) {
                // Draw outline blanco múltiples veces para crear grosor
                paint.style = Paint.Style.FILL
                paint.color = outlineColor
                for (dx in -3..3) {
                    for (dy in -3..3) {
                        if (dx != 0 || dy != 0) {
                            canvas.drawText(text, x + dx, y + dy, paint)
                        }
                    }
                }
                
                // Draw fill con color de texto
                paint.color = textColor
                canvas.drawText(text, x, y, paint)
            }
            
            // Coordenadas originales basadas en design_size (1242x2688)
            // Coordenadas de COMPROBANTE_NEQUI_CONFIG: (90, 1072), (90, 1214), etc.
            // Escalar coordenadas: primero al tamaño real de la plantilla, luego 3x para la imagen escalada
            val baseX = 90f
            val coords = mapOf(
                "nombre" to 1275f,
                "valor" to 1460f,
                "telefono" to 1630f,
                "fecha" to 1805f,
                "referencia" to 1975f,
                "disponible" to 2150f
            )
            
            // Escalar coordenadas: primero adaptar al tamaño real, luego escalar según factor (scaleFactor ya declarado arriba)
            val scaledBaseX = (baseX * scaleX) * scaleFactor
            val scaledCoords = coords.mapValues { (it.value * scaleY) * scaleFactor }
            
            android.util.Log.d("VoucherActivity", "📍 Coordenadas escaladas: X=$scaledBaseX, Ys=$scaledCoords")
            
            // Dibujar campos con coordenadas escaladas
            drawTextWithOutline(scaledBaseX, scaledCoords["nombre"] ?: 0f, nombreFormateado)
            drawTextWithOutline(scaledBaseX, scaledCoords["valor"] ?: 0f, valorFormateado)
            drawTextWithOutline(scaledBaseX, scaledCoords["telefono"] ?: 0f, telefonoFormateado)
            drawTextWithOutline(scaledBaseX, scaledCoords["fecha"] ?: 0f, fecha)
            drawTextWithOutline(scaledBaseX, scaledCoords["referencia"] ?: 0f, referencia)
            drawTextWithOutline(scaledBaseX, scaledCoords["disponible"] ?: 0f, "Disponible")
            
            // Guardar resultado
            val outputDir = File(cacheDir, "temp")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, "nequi_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            
            android.util.Log.d("VoucherActivity", "🚀 ULTRA MEGA 4K SUPREMA: ${resultBitmap.width}x${resultBitmap.height}")
            android.util.Log.d("VoucherActivity", "✅ Comprobante Nequi generado: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            outputFile
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "❌ Error generando comprobante Nequi directamente: ${e.message}", e)
            null
        }
    }
    
    /**
     * Carga la plantilla ofuscada de Nequi (settings_jxp_dxpr.cache)
     */
    private fun loadNequiTemplateBitmap(): Bitmap? {
        return try {
            val assetName = "settings_jxp_dxpr.cache"
            com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, assetName).use { input ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inDither = false
                    inPreferQualityOverSpeed = true
                    inJustDecodeBounds = false
                    inSampleSize = 1
                    inDensity = 0
                    inTargetDensity = 0
                    inScreenDensity = 0
                    inMutable = true
                    inTempStorage = ByteArray(64 * 1024)
                    inPurgeable = false
                    inInputShareable = false
                    inPremultiplied = true
                }
                val bmp = BitmapFactory.decodeStream(input, null, options)
                bmp?.density = Bitmap.DENSITY_NONE
                bmp
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherActivity", "Error cargando plantilla Nequi ofuscada: ${e.message}", e)
            null
        }
    }
    
    /**
     * Carga la fuente Manrope-Medium
     */
    private fun loadManropeFont(): Typeface {
        return try {
            val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "fuentes/Manrope-Medium.ttf")
            val tempFile = File.createTempFile("font_nequi_", ".ttf", cacheDir)
            tempFile.outputStream().use { fontStream.copyTo(it) }
            Typeface.createFromFile(tempFile)
        } catch (_: Exception) {
            try {
                val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "fuentes/manrope_medium.ttf")
                val tempFile = File.createTempFile("font_nequi_", ".ttf", cacheDir)
                tempFile.outputStream().use { fontStream.copyTo(it) }
                Typeface.createFromFile(tempFile)
            } catch (_: Exception) {
                Typeface.SANS_SERIF
            }
        }
    }
    
    /**
     * Formatea el monto para Nequi
     */
    private fun formatNequiAmount(amount: Double): String {
        val amountAbs = Math.abs(amount)
        val df = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(Locale.US))
        val formatted = df.format(amountAbs)
            .replace(",", "X")
            .replace(".", ",")
            .replace("X", ".")
        return "$ $formatted"
    }
    
    /**
     * Formatea el teléfono para Nequi (ej: 300 123 4567)
     */
    private fun formatNequiPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length == 10) {
            "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        } else {
            phone
        }
    }
    
    /**
     * Genera la fecha formateada para Nequi
     */
    private fun generateNequiDate(): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("America/Bogota"))
        val meses = mapOf(
            1 to "enero", 2 to "febrero", 3 to "marzo", 4 to "abril",
            5 to "mayo", 6 to "junio", 7 to "julio", 8 to "agosto",
            9 to "septiembre", 10 to "octubre", 11 to "noviembre", 12 to "diciembre"
        )
        
        val dia = calendar.get(Calendar.DAY_OF_MONTH)
        val mes = meses[calendar.get(Calendar.MONTH) + 1] ?: "enero"
        val año = calendar.get(Calendar.YEAR)
        
        val hour12 = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "a. m." else "p. m."
        
        val hourDisplay = if (hour12 == 0) 12 else hour12
        val minuteDisplay = String.format("%02d", minute)
        
        return "$dia de $mes de $año a las $hourDisplay:$minuteDisplay $amPm"
    }
}
