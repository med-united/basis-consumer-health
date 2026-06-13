package de.servicehealtherx.crypto.p12;

public interface P12CertManagementMBean {

    void uploadCertificate(String alias, byte[] p12Data, String password);

    String listCertificates();

    void reloadCertificates();
}
