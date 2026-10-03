package com.oscar.detectornfc

import android.app.DatePickerDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var nfcDialog: AlertDialog? = null

    /** Fecha de nacimiento en formato MRZ yymmdd (null si no seleccionada). */
    private var mrzDateOfBirth: String? = null

    /** Fecha de caducidad en formato MRZ yymmdd (null si no seleccionada). */
    private var mrzDateOfExpiry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.main)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.i(TAG, "onCreate() - nfcSupported=${nfcAdapter != null}, nfcEnabled=${nfcAdapter?.isEnabled == true}")

        val etCan = findViewById<EditText>(R.id.et_can)
        val etDocNumber = findViewById<EditText>(R.id.et_doc_number)
        val etDob = findViewById<EditText>(R.id.et_dob)
        val etExpiry = findViewById<EditText>(R.id.et_expiry)
        val rgAccessMode = findViewById<RadioGroup>(R.id.rg_access_mode)
        val btnScan = findViewById<Button>(R.id.btn_scan)

        etCan.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                etCan.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        etDocNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                etDocNumber.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        rgAccessMode.setOnCheckedChangeListener { _, checkedId ->
            val canMode = checkedId == R.id.rb_mode_can
            findViewById<View>(R.id.can_section).visibility = if (canMode) View.VISIBLE else View.GONE
            findViewById<View>(R.id.mrz_section).visibility = if (canMode) View.GONE else View.VISIBLE
            Log.d(TAG, "Modo de acceso cambiado a ${if (canMode) "CAN" else "MRZ"}")
        }
        rgAccessMode.check(R.id.rb_mode_can)

        etDob.setOnClickListener { showDatePicker { yymmdd, display -> mrzDateOfBirth = yymmdd; etDob.setText(display) } }
        etExpiry.setOnClickListener { showDatePicker { yymmdd, display -> mrzDateOfExpiry = yymmdd; etExpiry.setText(display) } }

        updateNfcState(btnScan)

        btnScan.setOnClickListener {
            val credentials = collectCredentials(etCan, etDocNumber)
            if (credentials == null) {
                Log.w(TAG, "Scan solicitado sin credenciales válidas")
                return@setOnClickListener
            }
            Log.d(TAG, "Scan solicitado - métodos=${credentials.describe()}")

            if (nfcAdapter == null) {
                Log.w(TAG, "No se inicia el escaneo: dispositivo sin NFC")
                showNfcNotSupported()
                return@setOnClickListener
            }
            if (nfcAdapter?.isEnabled != true) {
                Log.w(TAG, "No se inicia el escaneo: NFC desactivado")
                promptEnableNfc()
                return@setOnClickListener
            }

            val intent = Intent(this, NFCScanActivity::class.java)
            credentials.can?.let { intent.putExtra(NFCScanActivity.EXTRA_CAN, it) }
            credentials.mrz?.let { mrz ->
                intent.putExtra(NFCScanActivity.EXTRA_MRZ_DOC_NUMBER, mrz.documentNumber)
                intent.putExtra(NFCScanActivity.EXTRA_MRZ_DATE_OF_BIRTH, mrz.dateOfBirth)
                intent.putExtra(NFCScanActivity.EXTRA_MRZ_DATE_OF_EXPIRY, mrz.dateOfExpiry)
            }
            Log.i(TAG, "Abriendo NFCScanActivity con credenciales ${credentials.describe()}")
            startActivity(intent)
        }
    }

    /**
     * Valida las credenciales según el modo seleccionado y devuelve null
     * (mostrando el error correspondiente) si no hay al menos un método válido.
     */
    private fun collectCredentials(etCan: EditText, etDocNumber: EditText): AccessCredentials? {
        val canMode = findViewById<RadioGroup>(R.id.rg_access_mode).checkedRadioButtonId == R.id.rb_mode_can

        if (canMode) {
            val can = etCan.text.toString().trim()
            if (!AccessCredentials.isValidCan(can)) {
                Log.w(TAG, "CAN inválido introducido - canLength=${can.length}")
                etCan.error = getString(R.string.invalid_can)
                etCan.requestFocus()
                return null
            }
            return AccessCredentials.withCan(can)
        }

        val docNumber = etDocNumber.text.toString().trim().uppercase()
        val dob = mrzDateOfBirth
        val expiry = mrzDateOfExpiry
        if (docNumber.isBlank() || dob == null || expiry == null) {
            Log.w(TAG, "Datos MRZ incompletos - doc=${docNumber.isNotBlank()}, dob=${dob != null}, expiry=${expiry != null}")
            etDocNumber.error = getString(R.string.invalid_mrz)
            (findViewById<EditText>(R.id.et_dob)).error = getString(R.string.invalid_mrz)
            (findViewById<EditText>(R.id.et_expiry)).error = getString(R.string.invalid_mrz)
            return null
        }
        return AccessCredentials.withMrz(docNumber, dob, expiry)
    }

    private fun showDatePicker(onDatePicked: (yymmdd: String, display: String) -> Unit) {
        val calendar = Calendar.getInstance()
        val picker = DatePickerDialog(
            this,
            { _, year, month, day ->
                val mm = (month + 1).toString().padStart(2, '0')
                val dd = day.toString().padStart(2, '0')
                val yymmdd = "$year$mm$dd"
                val display = "$dd/$mm/$year"
                Log.d(TAG, "Fecha seleccionada - yymmdd=${yymmdd.takeLast(6)}")
                onDatePicked(yymmdd, display)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        picker.show()
    }

    override fun onResume() {
        super.onResume()
        nfcDialog?.dismiss()
        nfcDialog = null
        Log.d(TAG, "onResume() - refrescando estado NFC")
        updateNfcState(findViewById(R.id.btn_scan))
    }

    private fun updateNfcState(btnScan: Button) {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.d(TAG, "updateNfcState() - nfcSupported=${nfcAdapter != null}, nfcEnabled=${nfcAdapter?.isEnabled == true}")
        when {
            nfcAdapter == null -> {
                btnScan.isEnabled = false
                Log.w(TAG, "Botón escanear deshabilitado: NFC no soportado")
            }
            nfcAdapter?.isEnabled != true -> {
                btnScan.isEnabled = false
                Log.w(TAG, "Botón escanear deshabilitado: NFC desactivado")
            }
            else -> {
                btnScan.isEnabled = true
                Log.d(TAG, "Botón escanear habilitado")
            }
        }
    }

    private fun showNfcNotSupported() {
        if (isFinishing || isDestroyed || nfcDialog?.isShowing == true) return
        Log.w(TAG, "Mostrando diálogo: NFC no soportado")
        nfcDialog = AlertDialog.Builder(this)
            .setTitle("NFC no soportado")
            .setMessage("Este dispositivo no dispone de NFC.")
            .setPositiveButton("Aceptar", null)
            .setCancelable(true)
            .show()
    }

    private fun promptEnableNfc() {
        if (isFinishing || isDestroyed || nfcDialog?.isShowing == true) return
        Log.i(TAG, "Mostrando diálogo para activar NFC")
        nfcDialog = AlertDialog.Builder(this)
            .setTitle("NFC desactivado")
            .setMessage("NFC está desactivado. ¿Deseas abrir los ajustes para activarlo?")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(true)
            .show()
    }
}
