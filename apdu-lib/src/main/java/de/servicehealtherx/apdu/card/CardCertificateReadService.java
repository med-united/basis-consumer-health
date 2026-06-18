package de.servicehealtherx.apdu.card;

import java.security.cert.X509Certificate;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.model.CertificateRef;

/**
 * Transport-neutral orchestration for the card-backed certificate read (gemSpec_Kon
 * ReadCardCertificate). Resolves the addressed {@link CardObject} in a provider's {@link CmCardList},
 * obtains its live {@link CardReaderPort} via a {@link CardReaderPortResolver}, and delegates the
 * APDU dialogue to {@link CardCertificateReader} (TUC_KON_216). Shared by the PC/SC and SICCT
 * providers so both transports read certificates identically.
 */
public final class CardCertificateReadService {

    private final CmCardList cardList;
    private final CardReaderPortResolver portResolver;
    private final CardCertificateReader reader = new CardCertificateReader();

    public CardCertificateReadService(CmCardList cardList, CardReaderPortResolver portResolver) {
        this.cardList = cardList;
        this.portResolver = portResolver != null ? portResolver : CardReaderPortResolver.NONE;
    }

    /** Whether a card with the given handle is currently present in this provider's list. */
    public boolean hasCard(String cardHandle) {
        return cardList.findByHandle(cardHandle).isPresent();
    }

    /**
     * Read the certificate addressed by {@code certRefId} ({@code C.AUT} / {@code C.SIG}) and crypto
     * algorithm ({@code ECC} / {@code RSA}) from the card identified by {@code cardHandle}.
     *
     * @throws CardCertificateException if the handle is unknown, no live terminal is bound for the
     *                                  card, or the certificate cannot be read
     */
    public X509Certificate readCertificate(String cardHandle, String certRefId, String crypt) {
        CardObject card = cardList.findByHandle(cardHandle)
                .orElseThrow(() -> new CardCertificateException("Unknown card handle: " + cardHandle));
        CardReaderPort port = portResolver.portFor(card.ctid())
                .orElseThrow(() -> new CardCertificateException(
                        "No card terminal bound for card " + cardHandle + " (ctid " + card.ctid() + ")"));
        CertificateRef certRef = CertificateRef.fromId(certRefId);
        boolean ecc = !"RSA".equalsIgnoreCase(crypt);
        return reader.readCertificate(port, card.slotNo(), cardHandle, card.type(), certRef, ecc);
    }
}
