package com.fnmt.sample_dnie_app.utils.pki;

import android.util.Log;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import es.gob.fnmt.dniedroid.net.tool.ToolBox;

/**
 * Clase que procesa una petición OCSP para comprobar el estado de revocación del certificado.
 */
public class OCSP {
	private static final String TAG = OCSP.class.getSimpleName();

	private static final int timeoutConnection = 6000;
	private static boolean redirectsEnabled = true;
	private static final List<Integer> acceptedHttpStatus = Arrays.asList(HttpURLConnection.HTTP_OK);

	/**
	 * Comprobación del estado de revocación del certificado digital.
	 */
	public static CertificateStatus checkCertificateStatus(X509Certificate signCert, X509Certificate issuerCert, String ocspURL)
			throws CertificateEncodingException, OperatorCreationException, IOException, OCSPException {

		CertificateStatus status = null;

	    final CertificateID certId = getOCSPCertificateID(signCert, issuerCert);
	    
	    OCSPReqBuilder ocspReqBuilder = new OCSPReqBuilder();
	    ocspReqBuilder.addRequest(certId);
	    
		DEROctetString encodedNonceValue = new DEROctetString(new DEROctetString(getNonce().toByteArray()).getEncoded());
		Extension extension = new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false, encodedNonceValue);
		Extensions extensions = new Extensions(extension);
		
		ocspReqBuilder.addRequest(certId,extensions);
	    
		final OCSPReq ocspReq = ocspReqBuilder.build();
		final byte[] ocspReqData = ocspReq.getEncoded();
		
		byte[] authInfoAccessExtensionValue=null;

		String ocspAccessLocation=null;

		try{
			authInfoAccessExtensionValue = signCert.getExtensionValue(Extension.authorityInfoAccess.getId());
		}catch (UnsupportedOperationException uoe){
			ocspAccessLocation = ocspURL;
		}

		if(ocspAccessLocation==null) {
			ASN1InputStream ais = new ASN1InputStream(authInfoAccessExtensionValue);
			DEROctetString octetString = (DEROctetString) ais.readObject();
			ais.close();
			ais = new ASN1InputStream(octetString.getOctets());
			ASN1Sequence asn1Sequence = (ASN1Sequence) ais.readObject();
			ais.close();
			AuthorityInformationAccess authorityInformationAccess = AuthorityInformationAccess.getInstance(asn1Sequence);
			AccessDescription[] accessDescriptions = authorityInformationAccess.getAccessDescriptions();
			for (AccessDescription accessDescription : accessDescriptions) {
				if (X509ObjectIdentifiers.id_ad_ocsp.equals(accessDescription.getAccessMethod())) {
					GeneralName gn = accessDescription.getAccessLocation();
					ASN1String str = (ASN1String) ((DERTaggedObject) gn.toASN1Primitive()).getObject();
					ocspAccessLocation = str.getString();
				}
			}
		}

		final byte[] ocspRespBytes = post(ocspAccessLocation, ocspReqData);

		final OCSPResp ocspResp = new OCSPResp(ocspRespBytes);

		Log.i(TAG, Integer.toString(ocspResp.getStatus()));

		if (ocspResp.getStatus() == OCSPResp.SUCCESSFUL) {
			final BasicOCSPResp basicOCSPResp = (BasicOCSPResp)ocspResp.getResponseObject();
			
			SingleResp[] responses = (basicOCSPResp==null) ? null : basicOCSPResp.getResponses();

            if (responses!=null) {
            	for(SingleResp resp : responses) {
	                status = resp.getCertStatus();
                    if (status == CertificateStatus.GOOD) {
                        Log.i(TAG,"OCSP Status is good!");
                    } else if (status instanceof RevokedStatus) {
						Log.i(TAG,"OCSP Status is revoked!");
                        final RevokedStatus revokedStatus = (RevokedStatus) status;
                        Date revocationDate = revokedStatus.getRevocationTime();
                        int reasonId = 0;
                        if (revokedStatus.hasRevocationReason()) {
                			reasonId = revokedStatus.getRevocationReason();
                		}
						Log.i(TAG,"Revocation date: "+revocationDate);
						Log.i(TAG,"Reason: "+ CRLReasonEnum.fromInt(reasonId).name());
                    }  else if (status instanceof UnknownStatus) {
						Log.i(TAG,"OCSP Status is unknown!");
                    }
            	}
            }
		} 
		else{
			Log.i(TAG,"Service response FAIL!!");
			throw new OCSPException("Service response: "+ocspResp.getStatus());
		}

		return status;
	}
	
	/**
	 * Generación del ID de la petición OCSP.
	 */
	private static CertificateID getOCSPCertificateID(final X509Certificate cert, final X509Certificate issuerCert)
			throws OperatorCreationException, CertificateEncodingException, IOException, OCSPException{
		final BigInteger serialNumber = cert.getSerialNumber();
		final DigestCalculator digestCalculator = getSHA1DigestCalculator();
		final X509CertificateHolder x509CertificateHolder = new X509CertificateHolder(issuerCert.getEncoded());
		return new CertificateID(digestCalculator, x509CertificateHolder, serialNumber);
	}
	
	/**
	 * Genera el motor para la generación de la huella del certificado digital.
	 */
	private static DigestCalculator getSHA1DigestCalculator() throws OperatorCreationException {
		JcaDigestCalculatorProviderBuilder digestCalculatorProviderBuilder = new JcaDigestCalculatorProviderBuilder();
		final DigestCalculatorProvider digestCalculatorProvider = digestCalculatorProviderBuilder.build();
		final DigestCalculator digestCalculator = digestCalculatorProvider.get(CertificateID.HASH_SHA1);
		return digestCalculator;
	}
	
	/**
	 * Generación del nonce.
	 */
	private static BigInteger getNonce() {
		return BigInteger.valueOf(new SecureRandom().nextLong());
	}
	
	/**
	 * Envío de la petición OCSP.
	 */
	private static byte[] post(final String url, final byte[] content) throws IOException{
		HttpURLConnection client = null;
		DataOutputStream dataOut = null;

		try {
			ToolBox.checkHostAvailable(new URL(url).getHost(),0);

			client = getHttpClient(url,"POST");
			
			client.setRequestProperty("Content-type", "application/ocsp-request");
			client.setRequestProperty("Content-length", String.valueOf(content.length));
			client.setDoInput(true);
			
			OutputStream out = client.getOutputStream();
            dataOut = new DataOutputStream(new BufferedOutputStream(out));
            dataOut.write(content);
            dataOut.flush();

			final byte[] returnedBytes = readHttpResponse(url, client);
			return returnedBytes;
		} finally {
			if (client != null) {
				client.disconnect();
			}
		}
	}
	
	/**
	 * Generación del cliente para la petición OCSP.
	 */
	private static HttpURLConnection getHttpClient(final String url, String method) throws IOException {
		URL urlConn = new URL(url);

		HttpURLConnection httpClient = (HttpURLConnection)urlConn.openConnection();

		httpClient.setConnectTimeout(timeoutConnection);
		httpClient.setInstanceFollowRedirects(redirectsEnabled);
		httpClient.setRequestMethod(method);
		httpClient.setDoOutput(true);

		return httpClient;
	}
	
	/**
	 * Lectura de la respuesta devuelta por el servicio OCSP.
	 */
	private static byte[] readHttpResponse(final String url, final HttpURLConnection httpClient) throws IOException {
		final int statusCode = httpClient.getResponseCode();
		final String reasonPhrase = httpClient.getResponseMessage();

		if (!acceptedHttpStatus.contains(statusCode)) {
			String reason = reasonPhrase!=null&&reasonPhrase.equalsIgnoreCase("") ? " / reason : " + reasonPhrase : "";
			throw new IOException("Unable to request '" + url + "' (HTTP status code : " + statusCode + reason + ")");
		}

		return ToolBox.streamToByteArray(httpClient.getInputStream());
	}
	
	/**
	 * Parser para la respuesta OCSP.
	 */
	public enum CRLReasonEnum {

		unspecified(0),

		keyCompromise(1),

		cACompromise(2),

		affiliationChanged(3),

		superseded(4),

		cessationOfOperation(5),

		certificateHold(6),

		unknow(7),

		removeFromCRL(8),

		privilegeWithdrawn(9),

		aACompromise(10);

		private final int value;

		CRLReasonEnum(final int value) {
			this.value = value;
		}

		public static CRLReasonEnum fromInt(final int value) {
			for (CRLReasonEnum reason : CRLReasonEnum.values()) {
				if(reason.value == value) {
					return reason;
				}
			}
			return CRLReasonEnum.unknow;
		}

	}
}
