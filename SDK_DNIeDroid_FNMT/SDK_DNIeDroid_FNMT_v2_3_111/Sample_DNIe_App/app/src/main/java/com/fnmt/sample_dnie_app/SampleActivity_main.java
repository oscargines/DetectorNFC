package com.fnmt.sample_dnie_app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fnmt.sample_dnie_app.ageverification.SampleActivity_age_verification;
import com.fnmt.sample_dnie_app.datareader.SampleActivity_read_data;
import com.fnmt.sample_dnie_app.network.SampleActivity_connect_gui;
import com.fnmt.sample_dnie_app.network.SampleActivity_connect_gui_clave;
import com.fnmt.sample_dnie_app.signature.SampleActivity_api;
import com.fnmt.sample_dnie_app.signature.SampleActivity_gui;
import com.fnmt.sample_dnie_app.signature.SampleActivity_provider;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.Vector;

import de.tsenger.androsmex.data.CANSpecDO;
import de.tsenger.androsmex.data.CANSpecDOStore;
import es.dniedroidfnmt.BuildConfig;
import es.gob.fnmt.dniedroid.net.http.CookiePolicyHandler;

public class SampleActivity_main extends Activity{
    public static final String APP_TAG = "DNIe_ejemplos";

    private CANSpecDOStore _canStore = null;

    public static CookieManager _cookieManager = new CookieManager();
    public static CookiePolicyHandler _cookiePolicy = new CookiePolicyHandler();

    static{
        _cookieManager.setCookiePolicy(_cookiePolicy);
        CookieHandler.setDefault(_cookieManager);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sample_activity_main);
        Button read = (Button)findViewById(R.id.reader_button);
        Button age = (Button)findViewById(R.id.age_button);
        Button sign = (Button)findViewById(R.id.signature_button);
        Button auth = (Button)findViewById(R.id.authentication_button);
        Button setCan = (Button)findViewById(R.id.can_button);

        _canStore = new CANSpecDOStore(this);

        String dnieFroidLibVersionName = BuildConfig.LIBRARY_PACKAGE_NAME + " v" +BuildConfig.VERSION+" vc"+BuildConfig.VERSION_REVISION;
        ((TextView)findViewById(R.id.dniedroidinfo)).setText(dnieFroidLibVersionName);

        read.setOnClickListener(v -> {
            if(_canStore.getAll().isEmpty()){
                warnNoCAN();
            }
            else {
                 showCanList(SampleActivity_read_data.class);
            }
        });

        age.setOnClickListener(v -> {
            if(_canStore.getAll().isEmpty()){
                warnNoCAN();
            }
            else {
                showCanList(SampleActivity_age_verification.class);
            }
        });
        sign.setOnClickListener(v -> {
            if(_canStore.getAll().isEmpty()) warnNoCAN();
            else {
                LayoutInflater factory = LayoutInflater.from(SampleActivity_main.this);
                final View entryView = factory.inflate(R.layout.sample_ui_select, null);
                ((TextView)entryView.findViewById(R.id.uiselect)).setText("Seleccione interfaz:");
                final AlertDialog ad = new AlertDialog.Builder(SampleActivity_main.this).create();
                ad.setCancelable(true);
                ad.setIcon(R.drawable.alert_dialog_icon);
                ad.setView(entryView);
                ad.setButton(AlertDialog.BUTTON_NEGATIVE, "Provider", (dialog, which) -> showCanList(SampleActivity_provider.class));
                ad.setButton(AlertDialog.BUTTON_NEUTRAL, "Interfaz", (dialog, which) -> showCanList(SampleActivity_api.class));
                ad.setButton(AlertDialog.BUTTON_POSITIVE, "GUI", (dialog, which) -> showCanList(SampleActivity_gui.class));
                ad.show();
            }
        });
        auth.setOnClickListener(v -> {
            if(_canStore.getAll().isEmpty()) warnNoCAN();
            else {
                LayoutInflater factory = LayoutInflater.from(SampleActivity_main.this);
                final View entryView = factory.inflate(R.layout.sample_ui_select, null);
                ((TextView)entryView.findViewById(R.id.uiselect)).setText("Seleccione servicio:");
                final AlertDialog ad = new AlertDialog.Builder(SampleActivity_main.this).create();
                ad.setCancelable(true);
                ad.setIcon(R.drawable.alert_dialog_icon);
                ad.setView(entryView);
                ad.setButton(AlertDialog.BUTTON_NEGATIVE, "AEAT", (dialog, which) -> showCanList(SampleActivity_connect_gui.class));
                ad.setButton(AlertDialog.BUTTON_POSITIVE, "Cl@ve", (dialog, which) -> showCanList(SampleActivity_connect_gui_clave.class));
                ad.show();
            }
        });

        setCan.setOnClickListener(v -> {
            LayoutInflater factory = LayoutInflater.from(SampleActivity_main.this);
            final View canEntryView = factory.inflate(R.layout.sample_can, null);
            final AlertDialog ad = new AlertDialog.Builder(SampleActivity_main.this).create();
            ad.setCancelable(true);
            ad.setIcon(R.drawable.alert_dialog_icon);
            ad.setView(canEntryView);
            ad.setButton(AlertDialog.BUTTON_POSITIVE, "Aceptar", (dialog, which) -> {
                EditText text = (EditText) ad.findViewById(R.id.can_edit);
                _canStore.save(new CANSpecDO(text.getText().toString(), "", ""));
            });
            ad.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancelar", (dialog, which) -> ad.dismiss());
            ad.show();
        });
    }

    /**
     *
     * @param intentClass
     */
    private void showCanList(final Class intentClass ){
        LayoutInflater factory = LayoutInflater.from(SampleActivity_main.this);
        final View canListView = factory.inflate(R.layout.can_list, null);
        final AlertDialog ad = new AlertDialog.Builder(this).create();
        ad.setCancelable(true);
        ad.setIcon(R.drawable.dnie_logo);
        ad.setView(canListView);
        ListView listW = (ListView) canListView.findViewById(R.id.canList);
        SampleAdapter adapter = new SampleAdapter(getApplicationContext(), listW);
        listW.setAdapter(adapter);
        listW.setOnItemClickListener((parent, view, position, id) -> {
            CANSpecDO item = (CANSpecDO)parent.getItemAtPosition(position);
            Intent intent = new Intent(SampleActivity_main.this, intentClass);
            intent.putExtra("CAN", item.getCanNumber());
            startActivity(intent);
            ad.dismiss();
        });
        ad.show();
    }

    /**
     *
     */
    private void warnNoCAN(){
        Toast warn = Toast.makeText(SampleActivity_main.this, "¡Añada un CAN primero!", Toast.LENGTH_SHORT );
        warn.setGravity(Gravity.CENTER_VERTICAL,0,0);
        warn.show();
    }

    /**
     *
     */
    public class SampleAdapter extends ArrayAdapter<CANSpecDO> {
        private Vector<CANSpecDO> items;
        private final LayoutInflater vi;
        private final ListView parentView;

        public SampleAdapter(Context context, ListView parent) {
            super(context,0, _canStore.getAll());
            this.items = _canStore.getAll();
            parentView = parent;
            vi = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;

            final CANSpecDO ei = items.get(position);
            if (ei != null)
            {
                v = vi.inflate(R.layout.list_mrtd_row, null);
                final TextView title = (TextView)v.findViewById(R.id.row_can);
                final TextView name = (TextView)v.findViewById(R.id.row_name);
                final TextView nif = (TextView)v.findViewById(R.id.row_nif);

                if(title != null) {
                    title.setText(ei.getCanNumber());
                }
                if(name != null && !ei.getUserName().isEmpty() ) {
                    name.setText(ei.getUserName());
                }
                if(nif != null && !ei.getUserNif().isEmpty()) {
                    nif.setText("DNI " + ei.getUserNif());
                }

                Button deleteImageView = (Button)  v.findViewById(R.id.Btn_DESTROYENTRY);
                deleteImageView.setOnClickListener(v1 -> {
                    RelativeLayout vwParentRow = (RelativeLayout) v1.getParent();
                    int position1 = parentView.getPositionForView(vwParentRow);
                    _canStore.delete(items.get(position1));
                    SampleAdapter.this.remove(items.get(position1));
                    items = _canStore.getAll();
                });
            }
            return v;
        }
    }
}