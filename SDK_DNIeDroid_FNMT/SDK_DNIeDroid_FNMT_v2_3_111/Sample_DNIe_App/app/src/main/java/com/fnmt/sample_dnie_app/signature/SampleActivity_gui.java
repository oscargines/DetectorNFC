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

import static android.view.View.VISIBLE;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.pki.Tool;

import java.security.PrivateKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.tsenger.androsmex.data.CANSpecDO;
import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.fnmt.dniedroid.gui.SignatureNotification;
import es.gob.fnmt.dniedroid.help.Loader;
import es.gob.jmulticard.jse.provider.DnieProvider;

/**
 * Ejemplo de firma de datos con el DNIe v3.0 reutilizando la interfaz gráfica de la librería.
 */
public class SampleActivity_gui extends AppCompatActivity implements NfcAdapter.ReaderCallback, SignatureNotification {
    private TextView _baseInfo = null;
    private TextView _resultInfo = null;
    private ImageView _ui_dnie = null;
    private Animation _ui_dnieanimation = null;
    private ExecutorService _executor;
    private String _can = null;
    private Handler _handler;
    private byte[] _signature;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _executor = Executors.newSingleThreadExecutor();
        _handler = new Handler(Looper.getMainLooper());

        setContentView(R.layout.sample_activity);

        _can = getIntent().getStringExtra("CAN");

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        PasswordUI.setAppContext(this);
        PasswordUI.setPasswordDialog(null);  //Diálogo de petición de contraseña por defecto

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        _ui_dnie = findViewById(R.id.dnieImg);
        _ui_dnieanimation = AnimationUtils.loadAnimation(this, R.anim.dnie30_grey);

        Common.EnableReaderMode(this);
        getRead();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        reading();

        try {
            //Clase 'Initializer' que nos devuelve directamente el keystore
            Loader.InitInfo initInfo= Loader.init(new String[]{_can}, tag, this);
            Tool.SignatureCertificateBean certificateBean = Tool.selectCertificate(this, initInfo.getKeyStore());
            if(certificateBean == null) {
                Common.showDialog(SampleActivity_gui.this,"Error obteniendo certificado","No se han encontrado certificados en la tarjeta.");
                getRead();
            }
            else {
                //Clase 'Initializer' que nos permite actualizar la BBDD de CAN de la App
                CANSpecDO canSpecDO;
                if(initInfo.getKeyStoreType().equalsIgnoreCase(DnieProvider.KEYSTORE_TYPE_AVAILABLE.get(1))) {
                    canSpecDO = new CANSpecDO(_can, Tool.getCN(certificateBean.getCertificate()), Tool.getNIF(certificateBean.getCertificate()));
                }else{
                    canSpecDO = new CANSpecDO(_can, Tool.getCN(certificateBean.getCertificate()), "");
                }
                Loader.saveCan2DB(canSpecDO, this);
                final PrivateKey privateKey = (PrivateKey) initInfo.getKeyStore().getKey(certificateBean.getAlias(), null);
                _executor.execute(() -> {
                    final String result = doInBackground(privateKey);
                    _handler.post(() -> {
                        //UI Thread work here
                        updateInfo(result == null ? "Firma realizada." : "Error en proceso de firma.",
                                result == null ? Base64.encodeToString(_signature, Base64.DEFAULT) : result);
                    });
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            _handler.post(() -> Common.showDialog(SampleActivity_gui.this,"Error leyendo el DNIe",e.getMessage()));
            getRead();
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
     */
    private void getRead(){
        _handler.post(() -> {
            updateInfo("Aproxime el DNIe al dispositivo", null);
            _ui_dnie.setImageResource(R.drawable.dni30_grey_peq);
            _ui_dnie.setVisibility(View.VISIBLE);
            _ui_dnie.startAnimation(_ui_dnieanimation);
        });
    }

    /**
     *
     */
    private void reading(){
        _handler.post(() -> {
            updateInfo(getString(R.string.lib_process_title), getString(R.string.lib_process_msg_read));
            _ui_dnie.clearAnimation();
            _ui_dnie.setImageResource(R.drawable.dni30_peq);
            _ui_dnie.setVisibility(View.VISIBLE);
        });
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
            }else
                _resultInfo.setVisibility(View.GONE);
        });
    }

    //Callback para la interfaz es.gob.fnmt.dniedroid.gui.SignatureNotification
    @Override
    public void doNotify(sign_callback_notify notify) {
        final String message;
        switch(notify){
            case SIGNATURE_INIT:
                message = "Iniciando firma, no retire el DNIe del dispositivo NFC.";
                break;
            case SIGNATURE_UPDATE:
                message = "Actualizando datos a firmar.";
                break;
            case SIGNATURE_START:
                message = "Firmando los datos.";
                break;
            case SIGNATURE_DONE:
                message = "Firma realizada, puede retirar el DNIe. Continuando con descarga de datos...";
                break;
            default:
                message = "";
        }
        runOnUiThread(() -> Common.showDialog(SampleActivity_gui.this,"Proceso de firma", message));
    }
}