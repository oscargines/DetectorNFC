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

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fnmt.sample_dnie_app.R;
import com.fnmt.sample_dnie_app.SampleActivity_main;
import com.fnmt.sample_dnie_app.utils.Common;
import com.fnmt.sample_dnie_app.utils.pki.Tool;

import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

import de.tsenger.androsmex.data.CANSpecDO;
import es.gob.fnmt.dniedroid.gui.PasswordUI;
import es.gob.fnmt.dniedroid.help.Loader;
import es.gob.fnmt.dniedroid.net.http.clave.ClaveAuthentication;
import es.gob.fnmt.dniedroid.net.http.java.HTTPClient;
import es.gob.fnmt.dniedroid.net.http.okhttp.OkHTTPClient;
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
public class SampleActivity_connect_gui_clave extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private static final String URL_MICARPETA_DOMAIN        = "https://sede.administracion.gob.es/carpeta/clave.htm";
    private static final String URL_MICARPETA_TITULOUNIV    = "https://sede.administracion.gob.es/carpeta/datos/titulaciones/consultaUniversitaria.htm";

    private static final String HTML_SAMPLE =  "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>####TITULOS_UNIVERSITARIOS####</body></html>";

    private TextView _baseInfo = null;
    private TextView _resultInfo = null;
    private ImageView _ui_image = null;
    private Animation _ui_dnieanimation = null;
    private ExecutorService _executor;
    private Handler _handler;
    private String _can = null;

    private static final DnieProvider dnieProv = new DnieProvider();

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        //Establecemos el dominio para tener un control de las cookies que se van guardando.
        SampleActivity_main._cookiePolicy.setSiteHandling("CLAVE");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_activity);

        _executor = Executors.newSingleThreadExecutor();
        _handler = new Handler(Looper.getMainLooper());

        _baseInfo = this.findViewById(R.id.base_info);
        _resultInfo = this.findViewById(R.id.result_info);

        _can = getIntent().getStringExtra("CAN");

        _ui_image = findViewById(R.id.dnieImg);
        _ui_dnieanimation = AnimationUtils.loadAnimation(this, R.anim.dnie30_grey);

        Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        PasswordUI.setPasswordDialog(null);  //dialogo de PIN por defecto.
        PasswordUI.setAppContext(this);

        Common.EnableReaderMode(this);
        getRead();
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

    @Override
    public void onTagDiscovered(Tag tag) {
        reading();

        DnieLoadParameter initInfo;
        KeyStore keyStore;

        try {

            // Atención (DNIeDroid v2.03.108 y superiores):
            //      setCipherState(true)     --> para conexiones realizadas con HTTPClient
            //      setCipherState(false)    --> para conexiones con okHttpClient
            dnieProv.setCipherState(false);

            // Versión DNIeDroid v2.03.109++
            Security.insertProviderAt(dnieProv, 1);

            initInfo = DnieLoadParameter.getBuilder(new String[]{_can}, tag).build();
            keyStore = KeyStore.getInstance(DnieProvider.KEYSTORE_PROVIDER_NAME);
            keyStore.load(initInfo);

            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(keyStore.aliases().nextElement());
            if(certificate == null) {
                Common.showDialog(SampleActivity_connect_gui_clave.this,"Error obteniendo certificado","No se han encontrado certificados en la tarjeta.");
                getRead();
            } else {

                //Actualizar la BBDD de CAN de la App
                CANSpecDO canSpecDO = new CANSpecDO(_can, Tool.getCN(certificate), Tool.getNIF(certificate));
                Loader.saveCan2DB(canSpecDO, this);
                _executor.execute(() -> {
                    try {
                        doInBackground(keyStore);
                    } catch (IOException e) {
                        Common.showDialog(SampleActivity_connect_gui_clave.this,"Error en conexión",e.getMessage());
                        getRead();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            _handler.post(() -> Common.showDialog(SampleActivity_connect_gui_clave.this,"Error leyendo el DNIe",e.getMessage()));
            getRead();
        }
    }

    /**
     *
     * @param keyStore
     * @throws IOException
     */
    public void doInBackground(KeyStore keyStore) throws IOException {
        _handler.post(() -> _ui_image.setImageResource(R.drawable.grey_sec_conn_bg));
        updateInfo("Descargado datos...", null);
        HTMLParser parser = new HTMLParser(this);  //clase de ayuda para obtener información de las páginas HTML.
        
        /*// Versión < 2.03.108
        try {
            SSLSocketFactory dnieSSLSocketFactory = DNIeSSLSocketFactory.getInstance(keyStore,  //almacén del DNIe con los certificados de usuario.
                    ToolBox.getKeyStoreFromResource(R.raw.truststore, getApplicationContext()), //almacén con los certificados de CA reconocidos para la conexión con los servidores.
                    KeyManagerPolicy.getBuilder().addAlias(DnieProvider.AUTH_CERT_ALIAS).build(),  //política de selección de certificados de usuario. En este caso indicamos el certificado de autenticación.
                    this);

            String data = new HTTPClient(URL_MICARPETA_DOMAIN).getStringResponse(); //página desde donde se accede a Cl@ve. En este caso es el servicio 'Mi Carpeta'.
            parser.setContent(data);
            Element doc = parser.getDocument();
            String SAML = new HTTPClient("https://sede.administracion.gob.es/carpeta/loadSAML.htm").setSSLSocketFactory(dnieSSLSocketFactory).getStringResponse();
            doc.getElementById("divSAML").replaceWith(new Element(SAML));
            ClaveAuthentication.ClaveBean claveResponse = new ClaveAuthentication.Builder(doc.html(),"name", "SAMLRequest_form",getApplicationContext())
                    .setSSLSocketFactory(dnieSSLSocketFactory)
                    .build().doClave();

            Log.d("Clave authentication","Connection HTTP result: "+ claveResponse.getResponseCode());

            //obtener la información que interesa
            String _htmlBody;
            if(claveResponse.getResponseCode() == HttpURLConnection.HTTP_OK) {
                HTTPClient httpClient = new HTTPClient(URL_MICARPETA_TITULOUNIV);
                parser.setContent(httpClient.getStringResponse());
                String html = parser.getContent();
                Log.d("Mi Carpeta titulos UNI", "Connection HTTP result: " + httpClient.getLastResponse());
                _htmlBody = HTML_SAMPLE.replace("####TITULOS_UNIVERSITARIOS####",
                        parser.getDocument().getElementsByClass("spanResultadosEncontrados").first().html()+
                                parser.getDocument().getElementsByClass("mod_tablaV2").first().html());
            }
            else{
                parser.setContent(claveResponse.getStringResponse());
                parser.removeLinks();
                _htmlBody = parser.getContent();
            }

            Intent webViewIntent = new Intent(SampleActivity_connect_gui_clave.this, SampleActivity_webView.class);
            webViewIntent.putExtra("htmlString", _htmlBody);
            startActivity(webViewIntent);
            finishAndRemoveTask();
        }
        catch(Exception e){
            throw new IOException(e);
        }*/
        
        // Versión >=2.03.109
        try {
            
            /*// Versiones que NO USAN Cl@ve/OkHTTPClient
            SSLSocketFactory dnieSSLSocketFactory = DNIeSSLSocketFactory.getInstance(keyStore,      //almacén del DNIe con los certificados de usuario.
                    ToolBox.getKeyStoreFromResource(R.raw.truststore,getApplicationContext()),      //almacén con los certificados de CA reconocidos para la conexión con los servidores.
                    KeyManagerPolicy.getBuilder().addAlias(DnieProvider.AUTH_CERT_ALIAS).build(),   //política de selección de certificados de usuario. En este caso indicamos el certificado de autenticación
                    this);*/

            // Versión para Cl@ve/OkHTTPClient
            SSLSocketFactory dnieSSLSocketFactory = DNIeSSLSocketFactory.getInstance(keyStore,          //almacén del DNIe con los certificados de usuario.
                    ToolBox.getKeyStoreFromResource(R.raw.truststore, getApplicationContext()),    //almacén con los certificados de CA reconocidos para la conexión con los servidores.
                    KeyManagerPolicy.getBuilder().addAlias(DnieProvider.AUTH_CERT_ALIAS).build(),       //política de selección de certificados de usuario. En este caso indicamos el certificado de autenticación
                    "TLSv1.2",                                                                  //es necesario indicar la versión 1.2 del TLS
                    new BouncyCastleJsseProvider(),                                                     //motor TLS en vez del nativo Conscrypt
                    this); //activity

            OkHTTPClient okHTTPClient = OkHTTPClient.getBuilder(dnieSSLSocketFactory).setTrustCertStore(ToolBox.getKeyStoreFromResource(R.raw.truststore, this)).build();
            okHTTPClient.setRequest(new OkHTTPClient.RequestBuilder(URL_MICARPETA_DOMAIN));
            String _htmlBody = okHTTPClient.getStringResponse();

            parser.setContent(_htmlBody);
            Element mydocument = parser.getDocument();

            okHTTPClient.setRequest(new OkHTTPClient.RequestBuilder("https://sede.administracion.gob.es/carpeta/loadSAML.htm"));
            String SAML = okHTTPClient.getStringResponse();

            mydocument.getElementById("divSAML").replaceWith(new Element(SAML));

            ClaveAuthentication.ClaveBean claveResponse = new ClaveAuthentication.Builder(mydocument.html(),"name", "SAMLRequest_form", this)
                    .setHttpClient(okHTTPClient) //Nuevo cliente
                    .build().doClave();

            _htmlBody = claveResponse.getStringResponse();

            if(claveResponse.getResponseCode() == HttpURLConnection.HTTP_OK){
                okHTTPClient.setRequest(new OkHTTPClient.RequestBuilder(URL_MICARPETA_TITULOUNIV));
                parser.setContent(okHTTPClient.getStringResponse());
                String html = parser.getContent();
                Log.d("Mi Carpeta titulos UNI", "Connection HTTP result: " + okHTTPClient.getLastResponse());
                _htmlBody = HTML_SAMPLE.replace("####TITULOS_UNIVERSITARIOS####",
                        parser.getDocument().getElementsByClass("spanResultadosEncontrados").first().html()+
                                parser.getDocument().getElementsByClass("mod_tablaV2").first().html());

            } else{
                parser.setContent(claveResponse.getStringResponse());
                parser.removeLinks();
                _htmlBody = parser.getContent();
            }

            Intent webViewIntent = new Intent(SampleActivity_connect_gui_clave.this, SampleActivity_webView.class);
            webViewIntent.putExtra("htmlString", _htmlBody);
            startActivity(webViewIntent);
            finishAndRemoveTask();

        } catch(Exception e){
            e.printStackTrace();
            throw new IOException(e);
        }
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
}

