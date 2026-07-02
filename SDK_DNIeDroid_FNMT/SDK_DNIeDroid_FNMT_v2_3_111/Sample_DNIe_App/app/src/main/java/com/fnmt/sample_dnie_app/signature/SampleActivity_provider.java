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
import com.fnmt.sample_dnie_app.utils.pki.OCSP;
import com.fnmt.sample_dnie_app.utils.pki.Tool;

import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;

import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.tsenger.androsmex.iso7816.command.exception.AuthenticationModeLockedException;
import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.jmulticard.jse.provider.DnieLoadParameter;
import es.gob.jmulticard.jse.provider.DnieProvider;

/**
* Ejemplo básico de firma de datos con el DNIe v3.0 utilizando la implementación del java.security.Provider de la librería.
*/
public class SampleActivity_provider extends Activity implements NfcAdapter.ReaderCallback{
    private TextView _baseInfo = null;
    private TextView _resultInfo = null;
    private String _can = null;
    private ExecutorService _executor;
    private Handler _handler;
    private byte[] _signature;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _executor = Executors.newSingleThreadExecutor();
        _handler = new Handler(Looper.getMainLooper());

        setContentView(R.layout.sample_activity);

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        _can = getIntent().getStringExtra("CAN");

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        PasswordUI.setAppContext(this);
        PasswordUI.setPasswordDialog(null);  //Diálogo de petición de contraseña por defecto

        _baseInfo.setText("Aproxime el Dnie al dispositivo");
        Common.EnableReaderMode(this);
    }



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

    @Override
    public void onTagDiscovered(Tag tag) {
        try {
            final DnieProvider p = new DnieProvider();
            runOnUiThread(() -> {
                _resultInfo.setVisibility(GONE);
                _baseInfo.setText("Leyendo datos...");
            });
            Security.insertProviderAt(p, 1);

            KeyStore keyStore = KeyStore.getInstance("DNIeKS");
            DnieLoadParameter loadParameter = DnieLoadParameter.getBuilder(_can, tag).build();
            keyStore.load(loadParameter);
            Tool.SignatureCertificateBean certificateBean = Tool.selectCertificate(this, keyStore);
            final PrivateKey privateKey = (PrivateKey) keyStore.getKey(certificateBean.getAlias(), null);
            final X509Certificate[] certificateChain = (X509Certificate[]) keyStore.getCertificateChain(certificateBean.getAlias());

            _executor.execute(()->{
                final String result = doInBackground(privateKey, certificateChain);
                _handler.post(() -> {
                    updateInfo(result==null?"Firma realizada.":"Error en proceso de firma.",
                            result==null?Base64.encodeToString(_signature, Base64.DEFAULT):result);
                });
            });
        }
        catch(AuthenticationModeLockedException amle){
            updateInfo("Aproxime el Dnie al dispositivo","ERROR: Máximo número de intentos alcanzado. DNIe bloqueado." );
        }
        catch (Exception e){
            updateInfo("Aproxime el Dnie al dispositivo","ERROR: "+e.getMessage());
            e.printStackTrace();
        }
    }

    private String doInBackground(PrivateKey privateKey, X509Certificate[] _certificateChain){
        try{
            CertificateStatus status = new UnknownStatus();
            if( _certificateChain.length > 1) {
                updateInfo("Comprobando estado del certificado...", null);
                try {
                    status = OCSP.checkCertificateStatus(_certificateChain[0], _certificateChain[1], "http://ocsp.dnie.es");
                } catch (IOException ioe) {
                    updateInfo("Error en verificación OCSP.", "Verificación OCSP: " + ioe.getMessage());
                }
            }

            if(status == CertificateStatus.GOOD || status instanceof UnknownStatus){
                updateInfo("Realizando firma...", null);
                _signature = Common.getSignature(privateKey);
            }
            else if(status instanceof RevokedStatus){
                return "Certificate is revoked";
            }
            else return "Certificate status not recoverable";
        }
        catch (Exception e){
            return e.getMessage();
        }
        return null;
    }
}