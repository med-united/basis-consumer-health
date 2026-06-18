package de.servicehealtherx.apdu.card;

/**
 * Raised when a card-backed certificate read (TUC_KON_216 "LeseZertifikat") cannot deliver the
 * requested certificate — unsupported card type, certificate absent on the card (gemSpec_Kon 4258),
 * transport failure, or unparseable content.
 */
public class CardCertificateException extends RuntimeException {

    public CardCertificateException(String message) {
        super(message);
    }

    public CardCertificateException(String message, Throwable cause) {
        super(message, cause);
    }
}
