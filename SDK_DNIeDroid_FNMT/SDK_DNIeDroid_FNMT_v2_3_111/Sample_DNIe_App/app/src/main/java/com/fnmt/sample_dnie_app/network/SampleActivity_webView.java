/*
 * "Ejemplo DNIe de uso de API", desarrollada por CNP-FNMT.
 *
 * La aplicación implementa un escenario de ejemplo de uso de la API para la interacción
 * con el DNIe 3.0. y realizar así autenticación con certificado de cliente al acceder a
 * páginas Web que requieran de ésta.
 *
 * Copyright (C) 2020. Cuerpo Nacional de Policía - Fábrica Nacional de Moneda y Timbre.
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

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;

import com.fnmt.sample_dnie_app.R;

/**
 * Ejemplo de uso de DNIe v3.0 para la autenticación con certificado de cliente en sitio web.
 */
public class SampleActivity_webView extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.sample_webview);

        final Button back = findViewById(R.id.back2main);
        back.setOnClickListener(v -> finish());

        android.webkit.WebView _webView = (android.webkit.WebView) findViewById(R.id.mainwebview);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String htmlContent = extras.getString("htmlString");

            _webView.setVisibility(android.webkit.WebView.VISIBLE);
            _webView.setInitialScale(1);
            _webView.getSettings().setJavaScriptEnabled(true);
            _webView.getSettings().setSupportZoom(true);
            _webView.getSettings().setBuiltInZoomControls(true);
            _webView.getSettings().setUseWideViewPort(true);
            //_webView.loadData(htmlContent, "text/html; charset=utf-8","utf-8");
            //_webView.loadDataWithBaseURL("file:///android_asset/rec_aeat", htmlContent, "text/html", "utf-8", "file:///");
            //_webView.loadData(htmlContent, "text/html","utf-8");
            _webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", "file:///");
        }
    }
}