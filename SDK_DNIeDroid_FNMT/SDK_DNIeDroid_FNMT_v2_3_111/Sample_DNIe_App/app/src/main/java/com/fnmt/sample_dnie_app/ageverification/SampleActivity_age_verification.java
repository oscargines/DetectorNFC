/*
 * "Ejemplo DNIe de uso de API", desarrollada por CNP-FNMT.
 *
 * La aplicación implementa un escenario de ejemplo de uso de la API para la interacción
 * con el DNIe 3.0. y obtener información del documento de identidad.
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

package com.fnmt.sample_dnie_app.ageverification;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.Spinner;
import android.widget.TextView;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.graphic.CanvasView;

import java.security.KeyStore;
import java.security.ProviderException;
import java.security.Security;
import java.util.Calendar;

import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.jmulticard.jse.provider.DnieLoadParameter;
import es.gob.jmulticard.jse.provider.DnieProvider;

/**
 * Ejemplo de verificación de edad del DNIe v3.0.
 */
public class SampleActivity_age_verification extends Activity implements NfcAdapter.ReaderCallback{
    private TextView _baseInfo      = null;
    private TextView _resultInfo    = null;
    private DatePicker _date        = null;
    private CanvasView _image       = null;
    private String _can = null;

    private static final Calendar c = Calendar.getInstance();
    private boolean spinnerChanged = false;

    private static final DnieProvider dnieProv = new DnieProvider();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sample_activity);

        _baseInfo   = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        _date = findViewById(R.id.date);
        final Spinner _age = findViewById(R.id.age);
        _image = findViewById(R.id.canvas);
        _image.setType(CanvasView.TYPE.DRAW);

        findViewById(R.id.date_age).setVisibility(VISIBLE);

        _image.setVisibility(VISIBLE);
        _date.init(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH),
                (datePicker, year, month, dayOfMonth) -> {
                    if (!spinnerChanged){
                        _age.setSelection(4);
                    }
                    _image.setAge(Integer.toString(c.get(Calendar.YEAR) - _date.getYear()));
                    _image.setCensored(CanvasView.CENSORED.UNKWON);
                    _image.invalidate();
                });

        _age.setSelection(1);
        _age.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(!((String)_age.getSelectedItem()).equalsIgnoreCase("custom")) {
                    int age = Integer.parseInt((String) _age.getSelectedItem());
                    spinnerChanged = true;
                    _date.updateDate(c.get(Calendar.YEAR) - age, c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
                    spinnerChanged = false;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        _can = getIntent().getStringExtra("CAN");

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        Common.EnableReaderMode(this);

        PasswordUI.setAppContext(this);
        PasswordUI.setPasswordDialog(null);  //Diálogo de petición de contraseña por defecto

        _baseInfo.setText("Aproxime el DNIe al dispositivo");
    }

    /**
     * Actualización de la información que se muestra en la interfaz de usuario.
     */
    private void updateInfo(final String info){
        updateInfo(info, null);
    }
    private void updateInfo(final String info, final String extra){
        runOnUiThread(() -> {
            if(info!=null){
                _baseInfo.setText(info);
            }
            if(extra!=null){
                _resultInfo.setVisibility(VISIBLE);
                _resultInfo.setText(extra);
            }
            else _resultInfo.setVisibility(GONE);
            _image.invalidate();
        });
    }

    /**
     * Evento de detección de dispositivo NFC (NfcAdapter.ReaderCallback)
     * @param tag
     */
    @Override
    public void onTagDiscovered(Tag tag) {
        try {

            // Versión DNIeDroid v2.03.109++
            Security.insertProviderAt(dnieProv, 1);

            updateInfo("Leyendo datos...");
            DnieLoadParameter initInfo = DnieLoadParameter.getBuilder(new String[]{_can}, tag).build();
            KeyStore keyStore = KeyStore.getInstance(DnieProvider.KEYSTORE_PROVIDER_NAME);
            keyStore.load(initInfo);

            updateInfo("Leyendo datos...", "Verificando edad...");
            final Calendar calendar = Calendar.getInstance();
            calendar.set(_date.getYear(), _date.getMonth(), _date.getDayOfMonth());
            _image.setCensored(DnieProvider.verifyAge(calendar.getTime())? CanvasView.CENSORED.FALSE: CanvasView.CENSORED.TRUE);

            // Actualizamos la imagen a mostrar
            updateInfo("Comprobar edad", null);
        } catch (Exception e){
            e.printStackTrace();
            if(e.getMessage() != null && e.getMessage().contains("CAN incorrecto")) {
                updateInfo("CAN incorrecto", "Verifique el número de 6 dígitos que aparece en el border inferior derecho del DNIe.");
            } if(e.getMessage() != null && e.getMessage().contains("6A80")){
                updateInfo("Aproxime el DNIe al dispositivo.","Ha ocurrido un error."+e.getMessage()!= null?" ERROR: "+e.getMessage():null);
            } else
                updateInfo("Aproxime el DNIe al dispositivo.","Ha ocurrido un error."+e.getMessage()!= null?" ERROR: "+e.getMessage():null);
        }
    }
}