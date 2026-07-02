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

package com.fnmt.sample_dnie_app.datareader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.graphic.CanvasView;

import java.text.SimpleDateFormat;
import java.util.Date;

import de.tsenger.androsmex.mrtd.DG1_Dnie;
import de.tsenger.androsmex.mrtd.DG2;
import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.fnmt.dniedroid.help.Loader;
import es.gob.jmulticard.card.baseCard.mrtd.MrtdCard;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * Ejemplo de lectura de recuperación de datos del DNIe v3.0.
 */
public class SampleActivity_read_data extends Activity implements NfcAdapter.ReaderCallback{
    private TextView _baseInfo      = null;
    private TextView _resultInfo    = null;
    private CanvasView _photo       = null;
    private String _can             = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sample_activity);

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        _photo = findViewById(R.id.canvas);
        _photo.setType(CanvasView.TYPE.BITMAP);

        _can = getIntent().getStringExtra("CAN");

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        Common.EnableReaderMode(this);

        PasswordUI.setAppContext(this);
        PasswordUI.setPasswordDialog(null);  //Diálogo de petición de contraseña por defecto

        _baseInfo.setText("Aproxime el DNIe al dispositivo");
    }

    /**
     * Actualización de la información que se muestra en la interfaz de usuario.
     */
    private void updateInfo(final String info){
        updateInfo(info, null, null);
    }
    private void updateInfo(final String info, final String extra){
        updateInfo(info, extra, null);
    }
    private void updateInfo(final String info, final String extra, final Bitmap photo){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(info!=null){
                    _baseInfo.setText(info);
                }
                if(extra!=null){
                    _resultInfo.setVisibility(VISIBLE);
                    _resultInfo.setText(extra);
                }
                else _resultInfo.setVisibility(GONE);
                if(photo!=null){
                    _photo.setVisibility(VISIBLE);
                    _photo.setBitmap(photo);
                    _photo.invalidate();
                }
                else _photo.setVisibility(GONE);
            }
        });
    }

    /**
     * Evento de detección de dispositivo NFC (NfcAdapter.ReaderCallback)
     * @param tag
     */
    @Override
    public void onTagDiscovered(Tag tag) {
        try {
            updateInfo("Leyendo datos...");
            MrtdCard mrtdCardInfo = Loader.init(new String[]{_can}, tag).getMrtdCardInfo();
            updateInfo("Leyendo datos...", "Obteniendo fecha de nacimiento...");

            DG1_Dnie data = mrtdCardInfo.getDataGroup1();
            Date date = new SimpleDateFormat("yyMMdd").parse(data.getDateOfBirth());

            updateInfo("Leyendo datos...", "Obteniendo foto...");
            DG2 data2 = mrtdCardInfo.getDataGroup2();

            updateInfo("Fecha de nacimiento",new SimpleDateFormat("dd-MMMM-yyyy").format(date), new com.gemalto.jp2.JP2Decoder(data2.getImageBytes()).decode());
        }
        catch (Exception e){
            if(e.getMessage() != null && e.getMessage().contains("CAN incorrecto")){
                updateInfo("CAN incorrecto", "Verifique el número de 6 dígitos que aparece en el border inferior derecho del DNIe.");
            }
            else updateInfo("Aproxime el DNIe al dispositivo.","Ha ocurrido un error."+e.getMessage()!= null?" ERROR: "+e.getMessage():null);
        }
    }
}