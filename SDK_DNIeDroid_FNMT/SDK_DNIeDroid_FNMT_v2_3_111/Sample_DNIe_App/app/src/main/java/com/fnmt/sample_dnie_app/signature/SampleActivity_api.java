/*
 * "Ejemplo DNIe de uso de API", desarrollada por CNP-FNMT.
 *
 * La aplicación implementa un escenario de ejemplo de uso de la API para la interacción
 * con el DNIe 3.0. y realizar así firmas digitales.
 *
 * Copyright (C) 2019. Cuerpo Nacional de Policía - Fábrica Nacional de Moneda y Timbre.
 *
 * Esta aplicación puede ser redistribuida y/o modificada bajo los términos de la
 * Lesser GNU General Public License publicada por la Free Software Foundation,
 * tanto en la versión 3 de la Licencia, o en una versión posterior.
 *
 * Este programa es distribuido con la esperanza de que sea útil, pero
 * SIN NINGUNA GARANTÍA; incluso sin la garantía implícita de comercialización
 * o idoneidad para un propósito particular. Para más detalles vea GNU General Public
 * License.
 *
 * Debería recibir una copia de la GNU Lesser General Public License, si aplica, junto
 * con este programa. Si no, consúltelo en <http://www.gnu.org/licenses/>.
 */

package com.fnmt.sample_dnie_app.signature;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Button;
import android.widget.TextView;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.pki.Tool;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.jmulticard.jse.provider.DnieLoadParameter;
import es.gob.jmulticard.jse.provider.DnieProvider;

/**
 * Ejemplo de firma de datos con el DNIe v3.0 reutilizando los Fragments de comunicación de la librería.
 */
public class SampleActivity_api extends Activity implements NfcAdapter.ReaderCallback {
    private TextView _baseInfo = null;
    private TextView _resultInfo = null;
    private String _can = null;
    private ExecutorService _executor;
    private Handler _handler;
    private byte[] _signature;

    private static final DnieProvider dnieProv = new DnieProvider();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _executor = Executors.newSingleThreadExecutor();
        _handler = new Handler(Looper.getMainLooper());

        setContentView(R.layout.sample_activity);

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        PasswordUI.setAppContext(this);
        PasswordUI.setPasswordDialog(null);  //Diálogo de petición de contraseña por defecto

        _baseInfo.setText("Aproxime el Dnie al dispositivo");

        _can = getIntent().getStringExtra("CAN");

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        Common.EnableReaderMode(this);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        runOnUiThread(() -> {
            _resultInfo.setVisibility(GONE);
            _baseInfo.setText("Leyendo datos...");
        });
        try {
            //Clase 'Initializer' que nos devuelve directamente el keystore
            //KeyStore keyStore = Loader.init(new String[]{_can}, tag).getKeyStore();

            // Versión DNIeDroid v2.03.109++
            Security.insertProviderAt(dnieProv, 1);

            DnieLoadParameter initInfo = DnieLoadParameter.getBuilder(new String[]{_can}, tag).build();
            KeyStore keyStore = KeyStore.getInstance(DnieProvider.KEYSTORE_PROVIDER_NAME);
            keyStore.load(initInfo);

            Tool.SignatureCertificateBean certificateBean = Tool.selectCertificate(this, keyStore);
            final PrivateKey privateKey = (PrivateKey) keyStore.getKey(certificateBean.getAlias(), null);

            _executor.execute(()->{
                final String result = doInBackground(privateKey);
                _handler.post(() -> {
                    //UI Thread work here
                    updateInfo(result==null?"Firma realizada.":"Error en proceso de firma.",
                            result==null?Base64.encodeToString(_signature, Base64.DEFAULT):result);
                });
            });
        } catch (GeneralSecurityException | IOException gsioe) {
            gsioe.printStackTrace();
            updateInfo("Error leyendo el DNIEe.", gsioe.getMessage());
        }
    }

    /**
     *
     * @param privateKey
     * @return
     */
    private String doInBackground(PrivateKey privateKey){
        try{
            _signature = Common.getSignature(privateKey);
        }
        catch (Exception e){
            return e.getMessage();
        }
        return null;
    }

    /**
     *
     * @param info
     * @param extra
     */
    public void updateInfo(final String info, final String extra){
        runOnUiThread(() -> {
            if(info!=null){
                _baseInfo.setText(info);
            }
            if(extra!=null){
                _resultInfo.setVisibility(VISIBLE);
                _resultInfo.setText(extra);
            }
        });
    }
}