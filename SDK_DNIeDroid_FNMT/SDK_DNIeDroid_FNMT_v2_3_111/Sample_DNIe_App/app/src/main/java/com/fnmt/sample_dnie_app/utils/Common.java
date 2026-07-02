package com.fnmt.sample_dnie_app.utils;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.nfc.NfcAdapter;
import android.os.Bundle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;

import es.gob.jmulticard.jse.provider.DnieProvider;

public class Common {
    private static final String EXAMPLE_TEXT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
            "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
            "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. " +
            "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";

    /**
     *
     * @param activity
     * @return
     */
    public static NfcAdapter EnableReaderMode (Activity activity)
    {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        Bundle options = new Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000);
        nfcAdapter.enableReaderMode(activity,
                (NfcAdapter.ReaderCallback) activity,
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK 	|
                        NfcAdapter.FLAG_READER_NFC_A 	|
                        NfcAdapter.FLAG_READER_NFC_B,
                options);

        return nfcAdapter;
    }

    /**
     *
     * @param privateKey
     * @return
     * @throws NoSuchAlgorithmException
     * @throws SignatureException
     * @throws InvalidKeyException
     */
    public static byte[] getSignature(PrivateKey privateKey)
            throws NoSuchAlgorithmException, SignatureException, InvalidKeyException {
        String algorithm = "SHA256withRSA";
        Signature signatureEngine = Signature.getInstance(algorithm,new DnieProvider());
        signatureEngine.initSign(privateKey);
        signatureEngine.update(EXAMPLE_TEXT.getBytes());
        return signatureEngine.sign();
    }

    /**
     *
     * @param title
     * @param message
     */
    public static void showDialog(Context context, String title, String message){
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Aceptar", (dialog, which) -> dialog.dismiss())
                .show();
    }
}
