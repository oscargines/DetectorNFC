package com.fnmt.sample_dnie_app.utils.pki;

import android.app.Activity;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import es.gob.fnmt.dniedroid.gui.CertificateUI;
import es.gob.fnmt.dniedroid.policy.KeyManagerPolicy;

/**
 * Clase auxiliar.
 */
public class Tool {
    public static String getCN(X509Certificate certificate) throws CertificateEncodingException {
        X500Name subject = new JcaX509CertificateHolder(certificate).getSubject();
        return IETFUtils.valueToString(subject.getRDNs(BCStyle.CN)[0].getFirst().getValue());
    }
    public static String getNIF(X509Certificate certificate) throws CertificateEncodingException {
        X500Name subject = new JcaX509CertificateHolder(certificate).getSubject();
        return IETFUtils.valueToString(subject.getRDNs(BCStyle.SERIALNUMBER)[0].getFirst().getValue());
    }

    /**
     *
     * @param activity
     * @param keyStore
     * @return
     * @throws KeyStoreException
     */
    public static SignatureCertificateBean selectCertificate(Activity activity, KeyStore keyStore) throws KeyStoreException {
        KeyManagerPolicy keyManagerPolicy = KeyManagerPolicy.getBuilder()
                .addKeyUsage(KeyManagerPolicy.KeyUsage.digitalSignature)
                .addKeyUsage(KeyManagerPolicy.KeyUsage.nonRepudiation)
                .build();

        Enumeration<String> aliases = keyStore.aliases();
        List<SignatureCertificateBean> certificateList = new ArrayList<>();
        while(aliases.hasMoreElements()){
            String alias = aliases.nextElement();
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);
            if (keyManagerPolicy.doFilter(certificate, alias)){
                certificateList.add(new SignatureCertificateBean(certificate, alias));
            }
        }

        if (!certificateList.isEmpty()) {
            if (certificateList.size() > 1) {
                SignatureCertificateBean[] certificateBeans = certificateList.toArray(new SignatureCertificateBean[certificateList.size()]);
                X509Certificate[] certificates = getX509Certificates(certificateBeans);
                int certificateIndex = new CertificateUI(activity).showCertificateSelectionDialog(certificates);
                if(certificateIndex == -1) return null;
                else return certificateBeans[certificateIndex];
            } else {
                return certificateList.get(0);
            }
        }
        else{
            new CertificateUI(activity).noCertificateAvailableAlert();
        }

        return null;
    }

    /**
     *
     * @param certificates
     * @return
     */
    private static X509Certificate[] getX509Certificates(SignatureCertificateBean[] certificates){
        List<X509Certificate> list = new ArrayList<>();
        for(SignatureCertificateBean cert : certificates){
            list.add(cert._certificate);
        }
        return list.toArray(new X509Certificate[list.size()]);
    }

    /**
     *
     */
    public static class SignatureCertificateBean{
        private final X509Certificate _certificate;
        private final String _alias;

        public SignatureCertificateBean(X509Certificate certificate, String alias){
            _certificate = certificate;
            _alias = alias;
        }

        public String getAlias(){
            return _alias;
        }

        public X509Certificate getCertificate(){
            return _certificate;
        }
    }
}
