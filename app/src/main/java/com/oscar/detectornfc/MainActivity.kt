package com.oscar.detectornfc

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var nfcDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.main)
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

        val etCan   = findViewById<EditText>(R.id.et_can)
        val btnScan = findViewById<Button>(R.id.btn_scan)

        etCan.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                etCan.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        updateNfcState(btnScan)

        btnScan.setOnClickListener {
            val can = etCan.text.toString().trim()
            Log.d(TAG, "Scan solicitado - canLength=${can.length}, canMasked=${maskSecret(can)}")
            if (can.length != 6 || !can.all { it.isDigit() }) {
                Log.w(TAG, "CAN inválido introducido - canLength=${can.length}, soloDigitos=${can.all { it.isDigit() }}")
                etCan.error = getString(R.string.invalid_can)
                etCan.requestFocus()
                return@setOnClickListener
            }

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

            // Usamos la constante definida en NFCScanActivity para evitar typos
            val intent = Intent(this, NFCScanActivity::class.java)
            intent.putExtra(NFCScanActivity.EXTRA_CAN, can)
            Log.i(TAG, "Abriendo NFCScanActivity con CAN válido")
            startActivity(intent)
        }
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

    private fun maskSecret(value: String): String {
        if (value.isBlank()) return "<vacío>"
        if (value.length <= 2) return "*".repeat(value.length)
        return "${value.take(1)}${"*".repeat(value.length - 2)}${value.takeLast(1)}"
    }
}