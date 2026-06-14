package de.servicehealtherx.apdu.card;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;

import javax.naming.ldap.LdapName;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Reads on-card attributes for TUC_KON_001 (US1 task T016) via a {@link CardReaderPort}.
 * Transport-neutral — it only transmits ISO 7816 APDUs through the port, so it serves both the
 * PC/SC and SICCT providers.
 *
 * <p>Implemented here:
 * <ul>
 *   <li>{@link #readIccsn} — SELECT EF.GDO (0x2F02) + READ BINARY, extract ICCSN from DO tag 0x5A
 *       (BCD), standard across card types.</li>
 *   <li>{@link #readCardVersion} — SELECT EF.Version2 + READ BINARY, tolerant TLV walk into the
 *       eight CARDVERSION sub-fields; unreadable → empty (FR-007).</li>
 *   <li>{@link #parseAutCertificate} — parse a DER AUT certificate into cardholder name
 *       (subject CN) and expiry (notAfter) (FR-009, FR-011).</li>
 * </ul>
 *
 * <p>Per FR-005/FR-007 every read is best-effort: a field that cannot be read is returned as
 * {@code null} rather than failing handle creation. Selecting the AUT certificate file itself is
 * object-system specific (DF.ESIGN path) and is supplied by the caller to
 * {@link #read(CardReaderPort, int, CardType, byte[])}; the {@link #read(CardReaderPort, int, CardType)}
 * overload populates ICCSN + version and leaves cert-derived fields null.
 */
public final class CardAttributeReader {

    /** gematik EF.Version2 file identifier. */
    static final short FID_EF_VERSION2 = (short) 0x2F11;

    /** DO tag carrying the ICCSN in EF.GDO. */
    private static final int TAG_ICCSN = 0x5A;

    /** Read ICCSN + card version from the card; cert-derived fields left null. */
    public CardObjectFactory.CardAttributes read(CardReaderPort port, int slotNo, CardType type) {
        return read(port, slotNo, type, null);
    }

    /**
     * Read ICCSN + card version from the card and, if {@code autCertDer} is supplied, derive
     * cardholder name / expiry (and KVNR for eGK) from it.
     */
    public CardObjectFactory.CardAttributes read(CardReaderPort port, int slotNo, CardType type, byte[] autCertDer) {
        String iccsn = readIccsn(port, slotNo);
        CardVersionInfo version = readCardVersion(port, slotNo);

        String cardHolderName = null;
        LocalDate expiry = null;
        String kvnr = null;
        if (autCertDer != null) {
            CertInfo info = parseAutCertificate(autCertDer);
            if (info != null) {
                cardHolderName = info.cardHolderName();
                expiry = info.certExpirationDate();
                // KVNR (unveränderbarer Teil) for eGK lives in the VSD (EF.PD), not the AUT cert;
                // left null here pending VSD reading. Documented limitation (FR-010).
            }
        }
        return new CardObjectFactory.CardAttributes(iccsn, version, cardHolderName, kvnr, expiry);
    }

    /** SELECT EF.GDO + READ BINARY, return ICCSN digit string, or {@code null} if unreadable. */
    public String readIccsn(CardReaderPort port, int slotNo) {
        try {
            byte[] gdo = selectAndReadBinary(port, slotNo, GematikISO7816.FID_EF_GDO);
            byte[] iccsn = extractDo(gdo, TAG_ICCSN);
            return iccsn == null ? null : bcdToDigits(iccsn);
        } catch (CardTransportException | RuntimeException e) {
            return null; // FR-005: unreadable ICCSN must not fail handle creation
        }
    }

    /** SELECT EF.Version2 + READ BINARY, tolerant parse into the 8 CARDVERSION sub-fields. */
    public CardVersionInfo readCardVersion(CardReaderPort port, int slotNo) {
        try {
            byte[] data = selectAndReadBinary(port, slotNo, FID_EF_VERSION2);
            String[] v = new String[8];
            int idx = 0;
            int pos = 0;
            // Tolerant TLV walk: collect primitive value bytes (skip constructed wrappers) as
            // dotted version strings, in order, into the eight fields.
            while (pos < data.length && idx < v.length) {
                int tag = data[pos++] & 0xFF;
                if (pos >= data.length) {
                    break;
                }
                int len = data[pos++] & 0xFF;
                if (len < 0 || pos + len > data.length) {
                    break;
                }
                boolean constructed = (tag & 0x20) != 0;
                if (!constructed) {
                    v[idx++] = toVersionString(data, pos, len);
                }
                pos += len;
            }
            return new CardVersionInfo(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
        } catch (CardTransportException | RuntimeException e) {
            return CardVersionInfo.empty();
        }
    }

    /** Parse a DER-encoded AUT certificate into cardholder name (CN) and expiry. */
    public CertInfo parseAutCertificate(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            String cn = commonName(cert.getSubjectX500Principal().getName());
            LocalDate notAfter = cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            return new CertInfo(cn, notAfter);
        } catch (Exception e) {
            return null;
        }
    }

    /** Cardholder name + certificate expiry derived from an AUT certificate. */
    public record CertInfo(String cardHolderName, LocalDate certExpirationDate) {}

    // --- ISO 7816 helpers ---------------------------------------------------------------------

    private byte[] selectAndReadBinary(CardReaderPort port, int slotNo, short fid) throws CardTransportException {
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        CommandAPDU select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes);
        ResponseAPDU selResp = port.transmit(slotNo, select);
        if (selResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardTransportException("SELECT " + String.format("%04X", fid & 0xFFFF)
                    + " failed: SW=" + Integer.toHexString(selResp.getSW()));
        }
        CommandAPDU readBinary = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 0x00);
        ResponseAPDU readResp = port.transmit(slotNo, readBinary);
        if (readResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardTransportException("READ BINARY failed: SW=" + Integer.toHexString(readResp.getSW()));
        }
        return readResp.getData();
    }

    /** Extract the value of a single-byte primitive/constructed DO tag from a flat TLV buffer. */
    static byte[] extractDo(byte[] tlv, int tag) {
        int pos = 0;
        while (pos + 1 < tlv.length) {
            int t = tlv[pos++] & 0xFF;
            int len = tlv[pos++] & 0xFF;
            if (len < 0 || pos + len > tlv.length) {
                return null;
            }
            if (t == tag) {
                byte[] value = new byte[len];
                System.arraycopy(tlv, pos, value, 0, len);
                return value;
            }
            pos += len;
        }
        return null;
    }

    /** Decode BCD-packed bytes into a digit string, stopping at an 'F' filler nibble. */
    static String bcdToDigits(byte[] bcd) {
        StringBuilder sb = new StringBuilder(bcd.length * 2);
        for (byte b : bcd) {
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            if (hi == 0x0F) {
                break;
            }
            sb.append((char) ('0' + hi));
            if (lo == 0x0F) {
                break;
            }
            sb.append((char) ('0' + lo));
        }
        return sb.toString();
    }

    private static String toVersionString(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(data[off + i] & 0xFF);
        }
        return sb.toString();
    }

    private static String commonName(String dn) {
        try {
            LdapName ldap = new LdapName(dn);
            return ldap.getRdns().stream()
                    .filter(r -> r.getType().equalsIgnoreCase("CN"))
                    .map(r -> String.valueOf(r.getValue()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
