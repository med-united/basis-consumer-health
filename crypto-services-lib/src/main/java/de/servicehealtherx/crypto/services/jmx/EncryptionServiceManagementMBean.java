package de.servicehealtherx.crypto.services.jmx;

public interface EncryptionServiceManagementMBean {

    String encryptDocument(String alias, String algorithm, String documentBase64, String recipientCertBase64);

    String decryptDocument(String alias, String ciphertextBase64);
}
