/*
 * "Ejemplo DNIe de uso de API", desarrollada por CNP-FNMT.
 *
 * La aplicación implementa un escenario de ejemplo de uso de la API para la interacción
 * con el DNIe 3.0. y realizar así autenticación con certificado de cliente al acceder a
 * páginas Web que requieran de ésta.
 *
 * Copyright (C) 2021. Cuerpo Nacional de Policía - Fábrica Nacional de Moneda y Timbre.
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
package com.fnmt.sample_dnie_app.network;

import static android.view.View.VISIBLE;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.SampleActivity_main;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.pki.Tool;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocketFactory;

import de.tsenger.androsmex.data.CANSpecDO;
import de.tsenger.androsmex.mrtd.DG1_Dnie;
import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.fnmt.dniedroid.gui.SignatureNotification;
import es.gob.fnmt.dniedroid.help.Loader;
import es.gob.fnmt.dniedroid.net.http.java.HTTPClient;
import es.gob.fnmt.dniedroid.net.ssl.DNIeSSLSocketFactory;
import es.gob.fnmt.dniedroid.net.tool.HTMLParser;
import es.gob.fnmt.dniedroid.net.tool.ToolBox;
import es.gob.fnmt.dniedroid.policy.KeyManagerPolicy;
import es.gob.jmulticard.card.baseCard.mrtd.MrtdCard;
import es.gob.jmulticard.jse.provider.DnieLoadParameter;
import es.gob.jmulticard.jse.provider.DnieProvider;

/**
 * Ejemplo de uso de DNIe v3.0 para la autenticación con certificado de cliente en sitio web.
 */
public class SampleActivity_connect_gui extends AppCompatActivity implements NfcAdapter.ReaderCallback, SignatureNotification {

    private static final int STEP = 10;
    private TextView _baseInfo = null;
    private TextView _resultInfo = null;
    private ImageView _ui_image = null;
    private Animation _ui_dnieanimation = null;
    private ExecutorService _executor;
    private String _can = null;
    private Handler _handler;

    private ProgressBar _progressBar = null;

    private static final DnieProvider dnieProv = new DnieProvider();

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        //Establecemos el dominio para tener un control de las cookies que se van guardando.
        SampleActivity_main._cookiePolicy.setSiteHandling("AEAT");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_activity);

        _executor = Executors.newSingleThreadExecutor();
        _handler = new Handler(Looper.getMainLooper());

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        _ui_image = findViewById(R.id.dnieImg);
        _ui_dnieanimation = AnimationUtils.loadAnimation(this, R.anim.dnie30_grey);
        _progressBar =  findViewById(R.id.progressBar);

        _can = getIntent().getStringExtra("CAN");

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        PasswordUI.setPasswordDialog(new MyPasswordDialog(this,false)); //Establecemos nuestro propio diálogo de petición de PIN.
        PasswordUI.setAppContext(this);

        Common.EnableReaderMode(this);
        getRead();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        reading();

        try {
            //Clase 'Initializer' que nos devuelve directamente el keystore
            //Loader.InitInfo initInfo= Loader.init(new String[]{_can}, tag, this);

            // Atención (mayo 2022):
            //      setCipherState(true)     --> para conexiones realizadas con HTTPClient
            //      setCipherState(false)    --> para conexiones con okHttpClient
            dnieProv.setCipherState(true);

            // Versión DNIeDroid v2.03.109++
            Security.insertProviderAt(dnieProv, 1);

            updateInfo("Leyendo datos...", "Obteniendo certificado...");
            DnieLoadParameter initInfo = DnieLoadParameter.getBuilder(new String[]{_can}, tag).build();
            KeyStore keyStore = KeyStore.getInstance(DnieProvider.KEYSTORE_PROVIDER_NAME);
            keyStore.load(initInfo);

            updateInfo("Leyendo datos...", "Obteniendo datos del DNIe...");
            MrtdCard mrtdCardInfo = initInfo.getMrtdCardInfo();
            DG1_Dnie data = mrtdCardInfo.getDataGroup1();
            String numDNIe = data.getOptData();

            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(keyStore.aliases().nextElement());
            if(certificate == null) {
                Common.showDialog(SampleActivity_connect_gui.this,"Error obteniendo certificado","No se han encontrado certificados en la tarjeta.");
                getRead();
            }
            else {
                //Clase 'Initializer' que nos permite actualizar la BBDD de CAN de la App
                CANSpecDO canSpecDO;
                if(initInfo.getKeyStoreType().equalsIgnoreCase(DnieProvider.KEYSTORE_TYPE_AVAILABLE.get(1))) {
                    canSpecDO = new CANSpecDO(_can, Tool.getCN(certificate), Tool.getNIF(certificate));
                }else{
                    canSpecDO = new CANSpecDO(_can, Tool.getCN(certificate), "");
                }
                Loader.saveCan2DB(canSpecDO, this);
                _executor.execute(() -> {
                    try {
                        doInBackground(keyStore, numDNIe);
                    } catch (IOException e) {
                        _handler.post(() -> Common.showDialog(SampleActivity_connect_gui.this,"Error en conexión",e.getMessage()));
                        getRead();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            _handler.post(() -> Common.showDialog(SampleActivity_connect_gui.this,"Error leyendo el DNIe",e.getMessage()));
            getRead();
        }
    }

    private void doInBackground(KeyStore keyStore, String numDnie) throws IOException {
        _handler.post(() -> {
            _progressBar.setVisibility(VISIBLE);
            _ui_image.setImageResource(R.drawable.grey_sec_conn_bg);
        });
        int llamada;
        HTMLParser parser = new HTMLParser(this);   //clase de ayuda para obtener información de las páginas HTML.
        Map <String, String> mapParams = new HashMap<>();

        try {

            // Factoría de sockets para HTTPClient
            // Versiones que NO USAN Cl@ve/OkHTTPClient
            SSLSocketFactory dnieSSLSocketFactory = DNIeSSLSocketFactory.getInstance(keyStore,      //almacén del DNIe con los certificados de usuario.
                    ToolBox.getKeyStoreFromResource(R.raw.truststore, this),                //almacén con los certificados de CA reconocidos para la conexión con los servidores.
                    KeyManagerPolicy.getBuilder().addAlias(DnieProvider.AUTH_CERT_ALIAS).build(),   //política de selección de certificados de usuario. En este caso indicamos el certificado de autenticación.
                    this);

            /*// Versión para Cl@ve/OkHTTPClient
            SSLSocketFactory dnieSSLSocketFactory = DNIeSSLSocketFactory.getInstance(keyStore,          //almacén del DNIe con los certificados de usuario.
                    ToolBox.getKeyStoreFromResource(R.raw.truststore, getApplicationContext()),         //almacén con los certificados de CA reconocidos para la conexión con los servidores.
                    KeyManagerPolicy.getBuilder().addAlias(DnieProvider.AUTH_CERT_ALIAS).build(),       //política de selección de certificados de usuario. En este caso indicamos el certificado de autenticación
                    "TLSv1.2",                                                                          //es necesario indicar la versión 1.2 del TLS
                    new BouncyCastleJsseProvider(),                                                     //motor TLS en vez del nativo Conscrypt
                    this);                                                                              //activity
            */

            llamada = 0;
            updateProgressDlg("Descargando datos...","Llamada "+ ++llamada);

            // Versión válida desde 1 marzo 2022
            String _htmlBody;
            Elements elementsSecciones, elementNif;
            Document document;

            // Portal AEAT
            _htmlBody = new HTTPClient("https://www1.agenciatributaria.gob.es/wlpl/DFPA-D182/SvVisDF21Net").setSSLSocketFactory(dnieSSLSocketFactory).getStringResponse();

            try {
                updateProgressDlg("Descargando datos...","Llamada "+ ++llamada);

                mapParams.put("accion", "iravalidar");
                mapParams.put("idi", "ES");
                mapParams.put("ejf", "2021");
                mapParams.put("nif", numDnie);
                mapParams.put("idbotonaceptar", "Acceder");
                _htmlBody = new HTTPClient("https://www1.agenciatributaria.gob.es/wlpl/DFPA-D182/SvVisDF21Net", mapParams).setSSLSocketFactory(dnieSSLSocketFactory).getStringResponse();

            } catch (Exception e) {
                e.printStackTrace();
            }

            // Parseamos el documento para eliminar información no relevante
            parser.setContent(_htmlBody);
            document = (Document) parser.getDocument();

            elementNif = document.getElementsByClass("seccionSalida");
            if(elementNif!=null && !elementNif.isEmpty()) {

                elementsSecciones = document.getElementsByClass("header-sup_aeat w-100 fondo-pro");
                for (Element element : elementsSecciones) {
                    element.remove();
                }

                elementsSecciones = document.getElementsByClass("filaTotal");
                for (Element element : elementsSecciones) {
                    if (element.toString().toLowerCase().contains("nota:"))
                        element.remove();
                }

                elementsSecciones = document.getElementsByClass("seccionSalida");
                for (Element element : elementsSecciones) {
                    if ((element.text().equalsIgnoreCase("DATOS IDENTIFICATIVOS")) ||
                            (element.text().equalsIgnoreCase("DOMICILIO FISCAL")))
                        element.remove();
                }

                elementsSecciones = document.getElementsByClass("conborde");
                for (Element element : elementsSecciones)
                    element.remove();

                _htmlBody = document.html();
            }

            Intent webViewIntent = new Intent(SampleActivity_connect_gui.this, SampleActivity_webView.class);
            webViewIntent.putExtra("htmlString", _htmlBody);
            startActivity(webViewIntent);
            finishAndRemoveTask();
        }
        catch(Exception e){
            throw new IOException(e);
        }
        _handler.post(() -> _progressBar.setVisibility(View.GONE));
    }


    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //Eliminamos las cookies que pertenecen a este dominio.
        SampleActivity_main._cookiePolicy.deleteCookies(SampleActivity_main._cookieManager.getCookieStore());
    }

    /**
     *
     */
    private void getRead(){
        _handler.post(() -> {
            updateInfo("Aproxime el DNIe al dispositivo", null);
            _ui_image.setImageResource(R.drawable.dni30_grey_peq);
            _ui_image.setVisibility(View.VISIBLE);
            _ui_image.startAnimation(_ui_dnieanimation);
            _progressBar.setProgress(0);
            _progressBar.setVisibility(View.GONE);
        });
    }

    /**
     *
     */
    private void reading(){
        _handler.post(() -> {
            updateInfo(getString(R.string.lib_process_title), getString(R.string.lib_process_msg_read));
            _ui_image.clearAnimation();
            _ui_image.setImageResource(R.drawable.dni30_peq);
            _ui_image.setVisibility(View.VISIBLE);
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

    private void updateProgressDlg(final String title, final String msg){
        updateProgressDlg(title, msg, STEP);
    }
    private void updateProgressDlg(final String title, final String msg, final int step){
        runOnUiThread(() -> {
            if(title != null)_baseInfo.setText(title);
            _resultInfo.setText(msg);
            _progressBar.incrementProgressBy(step);
        });
    }


    //Callback para la interfaz es.gob.fnmt.gui.SignatureNotification
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
                message = null;
        }
        runOnUiThread(() -> {
            _baseInfo.setText("Proceso de firma");
            if (message != null) {
                _resultInfo.setText(message);
                _resultInfo.setVisibility(VISIBLE);
                _progressBar.incrementProgressBy(STEP);
            }
        });
    }
}

class MyPasswordDialog implements es.gob.jmulticard.ui.passwordcallback.DialogUIHandler {

    private final Activity activity;

    /**
     * Flag que indica si se cachea el PIN.
     */
    private final boolean cachePIN;

    /**
     * El password introducido. Si está activado el cacheo se reutilizará.
     */
    private char[] password = null;

    public MyPasswordDialog(final Context context, final boolean cachePIN) {

        // Guardamos el contexto para poder mostrar el diálogo
        activity = ((Activity) context);
        this.cachePIN = cachePIN;

        // Cuadro de diálogo para confirmación de firmas
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity);
        alertDialogBuilder.setIcon(R.drawable.alert_dialog_icon);
    }

    @Override
    public int showConfirmDialog(String message) {
        return doShowConfirmDialog(message);
    }

    public int doShowConfirmDialog(String message) {
        final AlertDialog.Builder dialog 	= new AlertDialog.Builder(activity);
        final MyPasswordDialog instance 	= this;
        final StringBuilder resultBuilder 	= new StringBuilder();
        resultBuilder.append(message);

        synchronized (instance)
        {
            activity.runOnUiThread(() -> {
                try {
                    dialog.setTitle("Proceso de firma con el DNI electrónico");
                    dialog.setMessage(resultBuilder);
                    dialog.setPositiveButton(R.string.lib_dialog_ok, (dialog1, which) -> {
                        synchronized (instance) {
                            resultBuilder.delete(0, resultBuilder.length());
                            resultBuilder.append("0");
                            instance.notifyAll();
                        }
                    });
                    dialog.setNegativeButton(R.string.lib_dialog_cancel, (dialog1, which) -> {
                        synchronized (instance) {
                            resultBuilder.delete(0, resultBuilder.length());
                            resultBuilder.append("1");
                            instance.notifyAll();
                        }
                    });
                    dialog.setCancelable(false);
                    dialog.create().show();
                } catch (es.gob.jmulticard.ui.passwordcallback.CancelledOperationException ex) {
                    android.util.Log.e(SampleActivity_main.APP_TAG, "Excepción en diálogo de confirmación" + ex.getMessage());
                } catch (Error err) {
                    android.util.Log.e(SampleActivity_main.APP_TAG, "Error en diálogo de confirmación" + err.getMessage()!=null?err.getMessage():"");
                }
            });
            try
            {
                instance.wait();
                return Integer.parseInt(resultBuilder.toString());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (Exception ex) {
                throw new es.gob.jmulticard.ui.passwordcallback.CancelledOperationException();
            }
        }
    }

    private char[] doShowPasswordDialog(final int retries) {
        final AlertDialog.Builder dialogBuilder 	= new AlertDialog.Builder(activity);
        final LayoutInflater inflater 		= activity.getLayoutInflater();
        final StringBuilder passwordBuilder = new StringBuilder();
        final MyPasswordDialog instance 	= this;
        dialogBuilder.setMessage(getTriesMessage(retries));

        synchronized (instance)
        {
            activity.runOnUiThread(() -> {
                try {
                    final View passwordView = inflater.inflate(R.layout.password_entry, null);

                    final EditText passwordEdit = passwordView.findViewById(R.id.password_edit);
                    final CheckBox passwordShow = passwordView.findViewById(R.id.checkBoxShow);

                    dialogBuilder.setPositiveButton(R.string.lib_dialog_ok, new DialogInterface.OnClickListener() {

                        /**
                         * @param dialog El diálogo que genera el evento.
                         * @see DialogInterface.OnClickListener#onClick(DialogInterface,
                         *      int)
                         */
                        @Override
                        public void onClick(final DialogInterface dialog, final int which) {
                            synchronized (instance) {
                                passwordBuilder.delete(0, passwordBuilder.length());
                                passwordBuilder.append(passwordEdit.getText().toString());
                                instance.notifyAll();
                            }
                        }
                    });
                    dialogBuilder.setNegativeButton(R.string.lib_dialog_cancel, new DialogInterface.OnClickListener() {

                        /**
                         * @param dialog El diálogo que genera el evento.
                         * @see DialogInterface.OnClickListener#onClick(DialogInterface,
                         *      int)
                         */
                        @Override
                        public void onClick(final DialogInterface dialog, final int which) {
                            synchronized (instance) {
                                passwordBuilder.delete(0, passwordBuilder.length());
                                instance.notifyAll();
                            }
                        }
                    });
                    passwordShow.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        if (isChecked) {
                            passwordEdit.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
                            passwordShow.setText(activity.getString(R.string.lib_psswd_dialog_show));
                        } else {
                            passwordEdit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                            passwordShow.setText(activity.getString(R.string.lib_psswd_dialog_hide));
                        }
                    });
                    dialogBuilder.setCancelable(false);
                    dialogBuilder.setView(passwordView);
                    Dialog dialog = dialogBuilder.create();
                    dialog.getWindow().setBackgroundDrawableResource(R.drawable.layout_frame);
                    dialog.show();

                } catch (Exception ex) {
                    android.util.Log.e("MyPasswordFragment", "Excepción en diálogo de contraseña" + ex.getMessage());
                } catch (Error err) {
                    android.util.Log.e("MyPasswordFragment", "Error en diálogo de contraseña" + err.getMessage());
                }
            });
            try
            {
                instance.wait();
                return passwordBuilder.toString().toCharArray();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public char[] showPasswordDialog(final int retries) {
        char[] returning;

        if (retries < 0 && cachePIN && password != null && password.length > 0)
            returning = password.clone();
        else
            returning = doShowPasswordDialog(retries);

        if (cachePIN && returning != null && returning.length > 0)
            password = returning.clone();
        else if( returning != null && returning.length == 0)
            returning = null;

        return returning;
    }

    /**
     * Genera el mensaje de reintentos del diálogo de contraseña.
     *
     * @param retries El número de reintentos pendientes. Si es negativo, se considera que no se conocen los intentos.
     * @return El mensaje a mostrar.
     */
    private String getTriesMessage(final int retries) {
        String text;
        if (retries < 0) {
            text = activity.getString(R.string.lib_dni_password_msg);
        } else if (retries == 1) {
            text = activity.getString(R.string.lib_dni_password_msg_one_left);
        } else {
            text = "Introduzca PIN. Quedan " +retries+" reintentos.";
        }
        return text;
    }
}
