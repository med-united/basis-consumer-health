package de.servicehealtherx.crypto.services.jmx;

public interface CertificateServiceManagementMBean {

    String readCertificate(String alias, String certRef, String crypt);

    String verifyCertificate(String certificateBase64);
}
