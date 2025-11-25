package com.ios.nequixofficialv2

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.FirebaseFirestore
import io.scanbot.demo.barcodescanner.e
import io.scanbot.demo.barcodescanner.model.Movement
import io.scanbot.demo.barcodescanner.model.MovementType
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AddOutputMovementActivity : AppCompatActivity() {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var userPhone: String = ""
    private var selectedType: String = "Nequi" // Nequi, Bancolombia, QR, Llaves

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_movement)

        // Aplicar color morado original a la barra de estado
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
        } catch (_: Exception) {}

        userPhone = intent.getStringExtra("user_phone") ?: ""

        // Cambiar título
        findViewById<android.widget.TextView>(R.id.tvTitle)?.text = "Agregar movimientos de salida"

        // Botón atrás
        findViewById<android.widget.ImageView>(R.id.btnBack)?.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }

        // Setup cards de selección
        setupSelectionCards()

        // Botón continuar
        findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fabContinue)?.setOnClickListener {
            if (selectedType.isNotEmpty()) {
                showAddMovementBottomSheet()
            } else {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Selecciona un tipo de movimiento")
            }
        }
    }

    private fun setupSelectionCards() {
        val cardNequi = findViewById<CardView>(R.id.cardNequi)
        val cardBancolombia = findViewById<CardView>(R.id.cardBancolombia)
        val cardQR = findViewById<CardView>(R.id.cardQR)
        val cardLlaves = findViewById<CardView>(R.id.cardLlaves)
        val llSelectedIndicator = findViewById<android.widget.LinearLayout>(R.id.llSelectedIndicator)
        val ivSelectedIcon = findViewById<android.widget.ImageView>(R.id.ivSelectedIcon)
        val tvSelectedTitle = findViewById<android.widget.TextView>(R.id.tvSelectedTitle)
        val tvSelectedSubtitle = findViewById<android.widget.TextView>(R.id.tvSelectedSubtitle)

        val updateCardSelection: () -> Unit = {
            // Reset all cards
            cardNequi?.cardElevation = 2f
            cardBancolombia?.cardElevation = 2f
            cardQR?.cardElevation = 2f
            cardLlaves?.cardElevation = 2f

            // Highlight selected
            when (selectedType) {
                "Nequi" -> {
                    cardNequi?.cardElevation = 8f
                    ivSelectedIcon?.setImageResource(R.drawable.ic_nequi_logo)
                    tvSelectedTitle?.text = "Nequi"
                    tvSelectedSubtitle?.text = "Nequi a Nequi"
                    llSelectedIndicator?.visibility = android.view.View.VISIBLE
                }
                "Bancolombia" -> {
                    cardBancolombia?.cardElevation = 8f
                    ivSelectedIcon?.setImageResource(R.drawable.ic_bancolombia_logo)
                    tvSelectedTitle?.text = "Bancolombia"
                    tvSelectedSubtitle?.text = "Transferencia bancaria"
                    llSelectedIndicator?.visibility = android.view.View.VISIBLE
                }
                "QR" -> {
                    cardQR?.cardElevation = 8f
                    ivSelectedIcon?.setImageResource(R.drawable.ic_qr_code)
                    tvSelectedTitle?.text = "QR"
                    tvSelectedSubtitle?.text = "Pago con QR"
                    llSelectedIndicator?.visibility = android.view.View.VISIBLE
                }
                "Llaves" -> {
                    cardLlaves?.cardElevation = 8f
                    ivSelectedIcon?.setImageResource(R.drawable.key)
                    tvSelectedTitle?.text = "Llaves"
                    tvSelectedSubtitle?.text = "Envío por llaves"
                    llSelectedIndicator?.visibility = android.view.View.VISIBLE
                }
                else -> {
                    llSelectedIndicator?.visibility = android.view.View.GONE
                }
            }
        }

        cardNequi?.setOnClickListener {
            selectedType = "Nequi"
            updateCardSelection()
        }

        cardBancolombia?.setOnClickListener {
            selectedType = "Bancolombia"
            updateCardSelection()
        }

        cardQR?.setOnClickListener {
            selectedType = "QR"
            updateCardSelection()
        }

        cardLlaves?.setOnClickListener {
            selectedType = "Llaves"
            updateCardSelection()
        }

        // Select Nequi by default
        selectedType = "Nequi"
        updateCardSelection()
    }

    private fun showAddMovementBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val layoutRes = when (selectedType) {
            "Nequi" -> R.layout.bottom_sheet_add_movement_nequi
            "Bancolombia" -> R.layout.bottom_sheet_add_movement_bancolombia
            "QR" -> R.layout.bottom_sheet_add_movement_qr
            "Llaves" -> R.layout.bottom_sheet_add_movement_llaves
            else -> R.layout.bottom_sheet_add_movement_nequi
        }
        
        val view = layoutInflater.inflate(layoutRes, null)
        bottomSheetDialog.setContentView(view)

        val btnSave = view.findViewById<android.widget.Button>(R.id.btnSave)

        btnSave?.setOnClickListener {
            when (selectedType) {
                "Nequi" -> saveNequiMovement(view, bottomSheetDialog)
                "Bancolombia" -> saveBancolombiaMovement(view, bottomSheetDialog)
                "QR" -> saveQRMovement(view, bottomSheetDialog)
                "Llaves" -> saveLlavesMovement(view, bottomSheetDialog)
            }
        }

        bottomSheetDialog.show()
    }

    private fun saveNequiMovement(view: android.view.View, dialog: BottomSheetDialog) {
        val etName = view.findViewById<android.widget.EditText>(R.id.etName)
        val etAmount = view.findViewById<android.widget.EditText>(R.id.etAmount)
        val etPhone = view.findViewById<android.widget.EditText>(R.id.etPhone)
        val etReference = view.findViewById<android.widget.EditText>(R.id.etReference)

        val name = etName?.text?.toString()?.trim() ?: ""
        val amountStr = etAmount?.text?.toString()?.trim() ?: ""
        val phone = etPhone?.text?.toString()?.trim() ?: ""
        val referenceDigits = etReference?.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el nombre")
            return
        }

        if (amountStr.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la cantidad")
            return
        }

        val amount = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa una cantidad válida")
            return
        }

        if (phone.isEmpty() || phone.filter { it.isDigit() }.length != 10) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "El número de Nequi debe tener exactamente 10 dígitos")
            return
        }

        // Validar referencia (solo dígitos, sin la M)
        val referenceError = validateReference(referenceDigits)
        if (referenceError != null) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, referenceError)
            return
        }

        // Agregar "M" automáticamente a la referencia
        val reference = "M$referenceDigits"

        val phoneDigits = phone.filter { it.isDigit() }
        
        // Limpiar tildes del nombre
        val cleanedName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(name)

        // Validar saldo ANTES de crear el movimiento
        validateBalanceAndSave(amount) { isValid ->
            if (!isValid) {
                return@validateBalanceAndSave
            }
            
            val movement = Movement(
                id = "",
                name = cleanedName,
                amount = amount,
                date = Date(),
                phone = phoneDigits,
                type = MovementType.OUTGOING,
                isIncoming = false,
                isQrPayment = false,
                mvalue = reference
            )

            saveMovement(movement, dialog)
        }
    }

    private fun saveBancolombiaMovement(view: android.view.View, dialog: BottomSheetDialog) {
        val etFirstName = view.findViewById<android.widget.EditText>(R.id.etFirstName)
        val etLastName = view.findViewById<android.widget.EditText>(R.id.etLastName)
        val etKey = view.findViewById<android.widget.EditText>(R.id.etKey)
        val etAmount = view.findViewById<android.widget.EditText>(R.id.etAmount)
        val etReference = view.findViewById<android.widget.EditText>(R.id.etReference)

        val firstName = etFirstName?.text?.toString()?.trim() ?: ""
        val lastName = etLastName?.text?.toString()?.trim() ?: ""
        val key = etKey?.text?.toString()?.trim() ?: ""
        val amountStr = etAmount?.text?.toString()?.trim() ?: ""
        val referenceDigits = etReference?.text?.toString()?.trim() ?: ""

        if (firstName.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el nombre")
            return
        }

        if (lastName.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el apellido")
            return
        }

        if (key.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la llave")
            return
        }

        if (amountStr.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la cantidad")
            return
        }

        val amount = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa una cantidad válida")
            return
        }

        // Validar referencia (solo dígitos, sin la M)
        val referenceError = validateReference(referenceDigits)
        if (referenceError != null) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, referenceError)
            return
        }

        // Agregar "M" automáticamente a la referencia
        val reference = "M$referenceDigits"

        // Formatear nombre: primeras 3 letras + asteriscos para el resto
        // Ejemplo: "Pedro" -> "Ped**", "Jose" -> "Jos*"
        val firstNameTrimmed = firstName.trim().replace(" ", "")
        val lastNameTrimmed = lastName.trim().replace(" ", "")
        
        val firstNameFormatted = if (firstNameTrimmed.length > 3) {
            val firstThree = firstNameTrimmed.take(3)
            val remaining = firstNameTrimmed.length - 3
            "$firstThree${"*".repeat(remaining)}"
        } else {
            firstNameTrimmed // Si tiene 3 o menos caracteres, mostrar todo
        }
        
        val lastNameFormatted = if (lastNameTrimmed.length > 3) {
            val firstThree = lastNameTrimmed.take(3)
            val remaining = lastNameTrimmed.length - 3
            "$firstThree${"*".repeat(remaining)}"
        } else {
            lastNameTrimmed // Si tiene 3 o menos caracteres, mostrar todo
        }
        
        val fullName = "$firstNameFormatted $lastNameFormatted"

        // Limpiar tildes del nombre
        val cleanedName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(fullName)

        // Validar saldo ANTES de crear el movimiento
        validateBalanceAndSave(amount) { isValid ->
            if (!isValid) {
                return@validateBalanceAndSave
            }
            
            val movement = Movement(
                id = "",
                name = cleanedName,
                amount = amount,
                date = Date(),
                phone = key,
                type = MovementType.BANCOLOMBIA,
                isIncoming = false,
                isQrPayment = false,
                mvalue = reference,
                accountNumber = key
            )

            saveMovement(movement, dialog)
        }
    }

    private fun saveQRMovement(view: android.view.View, dialog: BottomSheetDialog) {
        val etName = view.findViewById<android.widget.EditText>(R.id.etName)
        val etKey = view.findViewById<android.widget.EditText>(R.id.etKey)
        val etAmount = view.findViewById<android.widget.EditText>(R.id.etAmount)
        val etReference = view.findViewById<android.widget.EditText>(R.id.etReference)

        val name = etName?.text?.toString()?.trim() ?: ""
        val key = etKey?.text?.toString()?.trim() ?: ""
        val amountStr = etAmount?.text?.toString()?.trim() ?: ""
        val referenceDigits = etReference?.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el nombre del negocio")
            return
        }

        if (key.isEmpty() || key.filter { it.isDigit() }.length != 10) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "La llave debe tener exactamente 10 dígitos")
            return
        }

        if (amountStr.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la cantidad")
            return
        }

        val amount = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa una cantidad válida")
            return
        }

        // Validar referencia (solo dígitos, sin la M)
        val referenceError = validateReference(referenceDigits)
        if (referenceError != null) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, referenceError)
            return
        }

        // Agregar "M" automáticamente a la referencia
        val reference = "M$referenceDigits"

        val keyDigits = key.filter { it.isDigit() }
        
        // Formatear nombre: primeras 3 letras + asteriscos para el resto
        // Ejemplo: "Pedro" -> "Ped**", "Jose" -> "Jos*"
        val nameTrimmed = name.trim().replace(" ", "")
        val formattedName = if (nameTrimmed.length > 3) {
            val firstThree = nameTrimmed.take(3)
            val remaining = nameTrimmed.length - 3
            "$firstThree${"*".repeat(remaining)}"
        } else {
            nameTrimmed // Si tiene 3 o menos caracteres, mostrar todo
        }
        
        // Limpiar tildes del nombre formateado
        val cleanedName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(formattedName)

        // Validar saldo ANTES de crear el movimiento
        validateBalanceAndSave(amount) { isValid ->
            if (!isValid) {
                return@validateBalanceAndSave
            }
            
            val movement = Movement(
                id = "",
                name = cleanedName,
                amount = amount,
                date = Date(),
                phone = keyDigits,
                type = MovementType.OUTGOING,
                isIncoming = false,
                isQrPayment = true,
                mvalue = reference,
                keyVoucher = keyDigits
            )

            saveMovement(movement, dialog)
        }
    }

    private fun saveLlavesMovement(view: android.view.View, dialog: BottomSheetDialog) {
        val etName = view.findViewById<android.widget.EditText>(R.id.etName)
        val etKey = view.findViewById<android.widget.EditText>(R.id.etKey)
        val etAmount = view.findViewById<android.widget.EditText>(R.id.etAmount)
        val etReference = view.findViewById<android.widget.EditText>(R.id.etReference)

        val name = etName?.text?.toString()?.trim() ?: ""
        val key = etKey?.text?.toString()?.trim() ?: ""
        val amountStr = etAmount?.text?.toString()?.trim() ?: ""
        val referenceDigits = etReference?.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa el nombre")
            return
        }

        if (key.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la llave Nequi")
            return
        }

        if (amountStr.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa la cantidad")
            return
        }

        val amount = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Ingresa una cantidad válida")
            return
        }

        // Validar referencia (solo dígitos, sin la M)
        val referenceError = validateReference(referenceDigits)
        if (referenceError != null) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, referenceError)
            return
        }

        // Agregar "M" automáticamente a la referencia
        val reference = "M$referenceDigits"

        // Formatear nombre: primeras 3 letras + asteriscos para el resto
        // Ejemplo: "Pedro" -> "Ped**", "Jose" -> "Jos*"
        val nameTrimmed = name.trim().replace(" ", "")
        val formattedName = if (nameTrimmed.length > 3) {
            val firstThree = nameTrimmed.take(3)
            val remaining = nameTrimmed.length - 3
            "$firstThree${"*".repeat(remaining)}"
        } else {
            nameTrimmed // Si tiene 3 o menos caracteres, mostrar todo
        }
        
        // Limpiar tildes del nombre formateado
        val cleanedName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(formattedName)

        // Validar saldo ANTES de crear el movimiento
        validateBalanceAndSave(amount) { isValid ->
            if (!isValid) {
                return@validateBalanceAndSave
            }
            
            val movement = Movement(
                id = "",
                name = cleanedName,
                amount = amount,
                date = Date(),
                phone = key,
                type = MovementType.KEY_VOUCHER,
                isIncoming = false,
                isQrPayment = false,
                mvalue = reference,
                keyVoucher = key
            )

            saveMovement(movement, dialog)
        }
    }

    /**
     * Valida el saldo antes de guardar el movimiento
     * @param amount Cantidad a validar
     * @param onValidated Callback que se ejecuta si el saldo es suficiente (recibe true si es válido, false si no)
     */
    private fun validateBalanceAndSave(amount: Double, onValidated: (Boolean) -> Unit) {
        val userPhoneDigits = userPhone.filter { it.isDigit() }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val query = db.collection("users")
                    .whereEqualTo("telefono", userPhoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (query.isEmpty) {
                    runOnUiThread {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this@AddOutputMovementActivity, "Usuario no encontrado")
                        onValidated(false)
                    }
                    return@launch
                }
                
                val userDoc = query.documents.first()
                // ✅ CORRECCIÓN: Usar readBalanceFlexible para leer como Long (consistente con el resto del código)
                val currentBalance = readBalanceFlexible(userDoc, "saldo") ?: 0L
                val amountLong = amount.toLong()
                
                // Validar saldo ANTES de guardar
                if (currentBalance < amountLong) {
                    runOnUiThread {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(
                            this@AddOutputMovementActivity, 
                            "Tu saldo es insuficiente para generar este movimiento"
                        )
                        onValidated(false)
                    }
                    return@launch
                }
                
                // Saldo suficiente, continuar
                onValidated(true)
            } catch (ex: Exception) {
                runOnUiThread {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this@AddOutputMovementActivity, "Error: ${ex.message ?: "Error desconocido"}")
                    onValidated(false)
                }
            }
        }
    }

    private fun saveMovement(movement: Movement, dialog: BottomSheetDialog) {
        val userPhoneDigits = userPhone.filter { it.isDigit() }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val query = db.collection("users")
                    .whereEqualTo("telefono", userPhoneDigits)
                    .limit(1)
                    .get()
                    .await()
                
                if (query.isEmpty) {
                    runOnUiThread {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this@AddOutputMovementActivity, "Usuario no encontrado")
                    }
                    return@launch
                }
                
                val userDoc = query.documents.first()
                val userDocumentId = userDoc.id
                val userRef = db.collection("users").document(userDocumentId)
                
                // Guardar movimiento
                e.saveMovement(this@AddOutputMovementActivity, movement) { success, error ->
                    runOnUiThread {
                        if (success) {
                            // Actualizar saldo: RESTAR para movimientos de salida
                            updateUserBalance(movement.amount, isIncoming = false, userRef)
                            dialog.dismiss()
                            Toast.makeText(this@AddOutputMovementActivity, "Movimiento agregado", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            com.ios.nequixofficialv2.utils.NequiAlert.showError(this@AddOutputMovementActivity, "Error guardando movimiento: ${error ?: "Error desconocido"}")
                        }
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this@AddOutputMovementActivity, "Error: ${ex.message ?: "Error desconocido"}")
                }
            }
        }
    }

    /**
     * Actualiza el saldo del usuario usando transacción
     * @param amount Cantidad a restar (salida)
     * @param isIncoming false para restar (salida)
     * @param userRef Referencia al documento del usuario en Firestore
     */
    /**
     * Lee el saldo de forma flexible (Long o String)
     */
    private fun readBalanceFlexible(snap: com.google.firebase.firestore.DocumentSnapshot, field: String): Long? {
        val anyVal = snap.get(field) ?: return null
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> {
                val digits = anyVal.filter { it.isDigit() }
                digits.toLongOrNull()
            }
            else -> null
        }
    }
    
    private fun updateUserBalance(amount: Double, isIncoming: Boolean, userRef: com.google.firebase.firestore.DocumentReference) {
        // Convertir amount a Long para consistencia
        val amountLong = amount.toLong()
        
        // Usar transacción para actualizar saldo de forma segura
        db.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            // ✅ CORRECCIÓN: Usar readBalanceFlexible para leer como Long (consistente con el resto del código)
            val currentBalance = readBalanceFlexible(snapshot, "saldo") ?: 0L
            
            if (!isIncoming && currentBalance < amountLong) {
                throw com.google.firebase.firestore.FirebaseFirestoreException("Saldo insuficiente", com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED)
            }
            
            val newBalance = if (isIncoming) {
                currentBalance + amountLong
            } else {
                currentBalance - amountLong
            }
            
            android.util.Log.d("AddOutputMovementActivity", "💰 Saldo anterior: $currentBalance, nuevo saldo: $newBalance (${if (isIncoming) "suma" else "resta"}: $amountLong)")
            
            transaction.update(userRef, "saldo", newBalance)
            newBalance
        }.addOnSuccessListener { newBalance ->
            android.util.Log.d("AddOutputMovementActivity", "✅ Saldo actualizado exitosamente: ahora tiene $$newBalance")
        }.addOnFailureListener { error ->
            android.util.Log.e("AddOutputMovementActivity", "❌ Error actualizando saldo: ${error.message}")
        }
    }

    /**
     * Valida que la referencia (solo dígitos) cumpla con los requisitos:
     * - Los primeros 2 dígitos deben formar un número par (ej: 12, 24, 36, 48, 20, etc.)
     * - Debe tener mínimo 2 dígitos y máximo 8 dígitos
     * - Solo debe contener números
     * 
     * @param digits Solo los dígitos ingresados por el usuario (sin la M)
     * @return null si es válida, mensaje de error si no lo es
     */
    private fun validateReference(digits: String): String? {
        if (digits.isEmpty()) {
            return "Ingresa la referencia"
        }
        
        // Solo debe contener números
        if (!digits.all { it.isDigit() }) {
            return "La referencia solo debe contener números"
        }
        
        // Debe tener al menos 2 dígitos y máximo 8
        if (digits.length < 2 || digits.length > 8) {
            return "La referencia debe tener entre 2 y 8 dígitos"
        }
        
        // Los primeros 2 dígitos deben formar un número par
        val firstTwoDigits = digits.take(2).toIntOrNull()
        if (firstTwoDigits == null) {
            return "Error en la referencia"
        }
        
        if (firstTwoDigits % 2 != 0) {
            return "Los primeros 2 dígitos deben formar un número par (ej: 12, 24, 36, 48)"
        }
        
        return null // Válida
    }

    /**
     * Genera una referencia válida en el formato M{dígitos pares}
     * Genera 2, 4, 6 u 8 dígitos aleatorios
     */
    private fun generateReference(): String {
        // Generar cantidad par aleatoria: 2, 4, 6 u 8
        val digitCount = listOf(2, 4, 6, 8).random()
        
        // Generar número con esa cantidad de dígitos
        val min = when (digitCount) {
            2 -> 10
            4 -> 1000
            6 -> 100000
            8 -> 10000000
            else -> 10
        }
        val max = when (digitCount) {
            2 -> 99
            4 -> 9999
            6 -> 999999
            8 -> 99999999
            else -> 99
        }
        
        val n = (min..max).random()
        return "M$n"
    }
}

