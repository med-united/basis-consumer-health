package de.servicehealtherx.crypto.services.jmx;

public interface SignatureServiceManagementMBean {

    String sign(String alias, String algorithm, String dataBase64);

    boolean verify(String alias, String algorithm, String dataBase64, String signatureBase64);

    String externalAuthenticate(String alias, String signatureType, String hashBase64);

    String getSignatureMode();
}
