package de.servicehealtherx.apdu.c2c;

/**
 * The fields of a gematik card-verifiable certificate (CVC) needed to order a chain and select key
 * references for C2C. Parsed from the 7F21 structure by {@link CvcChainParser}.
 *
 * @param raw          the full CVC bytes (7F21 …) as read from the card
 * @param car          Certification Authority Reference (issuer)
 * @param chr          Certificate Holder Reference (subject; CHR = role-id ‖ ICCSN)
 * @param chat         Certificate Holder Authorization Template (role bits; null for the root anchor form)
 * @param publicPoint  the holder's public key point bytes (5F37/86 content), if present
 */
public record CvCertificate(byte[] raw, byte[] car, byte[] chr, byte[] chat, byte[] publicPoint) {

    /** Root-CA CVCs are recognised by their CHAT role bits b0,b1 = 11 (gemSpec_COS N095.900). */
    public boolean isRootAnchor() {
        return chat != null && chat.length > 0 && (chat[chat.length - 1] & 0x03) == 0x03;
    }
}
