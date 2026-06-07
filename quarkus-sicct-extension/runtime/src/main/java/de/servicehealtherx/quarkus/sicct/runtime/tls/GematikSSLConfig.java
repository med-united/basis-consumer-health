package de.servicehealtherx.quarkus.sicct.runtime.tls;

import java.util.Arrays;
import java.util.List;

public class GematikSSLConfig {
    // A_22450 - TLS-Ciphersuiten
    // A_17089-01 - eHealth-Kartenterminals: TLS-Verbindungen (ECC-Migration)
    public static final List<String> CIPHER_SUITE_LIST = Arrays.asList(
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            // This is cipher suite is required to talk to an ORGA6100-01410000021FB1
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA");

    public static final String PROTOCOL = "TLSv1.2";

}