package de.servicehealtherx.sicct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Erzeugt C-APDUs für das EHEALTH TERMINAL AUTHENTICATE Kommando (CLA=0x81,
 * INS=0xAA)
 * aus Sicht des Konnektors gemäß gemSpec_KT V3.17.0 (CMD_KT_0001).
 *
 * <p>
 * Das Kommando dient dem Pairing zwischen Konnektor und Kartenterminal und hat
 * vier Ausprägungen (P2-Qualifier):
 * <ul>
 * <li>P2=0x01 CREATE – initialer Pairing-Aufbau, übergibt neues Shared
 * Secret</li>
 * <li>P2=0x02 VALIDATE – prüft per Shared-Secret-Challenge, ob Pairing korrekt
 * ist</li>
 * <li>P2=0x03 ADD Phase 1 – fordert Challenge vom Kartenterminal an
 * (ADD-Vorgang, Schritt 1)</li>
 * <li>P2=0x04 ADD Phase 2 – liefert Challenge-Response an das Kartenterminal
 * (ADD-Vorgang, Schritt 2)</li>
 * </ul>
 *
 * <p>
 * TLV-Tags (gemSpec_KT DO-Definitionen):
 * <ul>
 * <li>0xD4 – Shared Secret DO (SS DO, 16 Byte Payload) → DO_KT_0003</li>
 * <li>0xD5 – Shared Secret Challenge DO (SSC DO, 16–127 Byte) → DO_KT_0004</li>
 * <li>0xD6 – Shared Secret Response DO (SSR DO, 32 Byte SHA-256) →
 * DO_KT_0005</li>
 * <li>0x50 – Application Label DO (Display-Text, SICCT 5.5.10.19)</li>
 * <li>0x62 – SICCT Message To Be Displayed DO (constructed, SICCT
 * 5.5.10.21)</li>
 * <li>0x84 – FUI DO ('84 02 00 00' für Direct-Adressierung)</li>
 * </ul>
 *
 * <p>
 * APDU-Struktur: CLA=0x81, INS=0xAA, P1=0x00 (Direct Coding, Address
 * Cardterminal)
 *
 * @see <a href="https://www.gematik.de">gemSpec_KT V3.17.0, Kap. 3.7.2</a>
 */
public final class EhealthTerminalAuthenticate {

    // -------------------------------------------------------------------------
    // Konstanten – APDU Header
    // -------------------------------------------------------------------------

    /** CLA-Byte: Cardterminal Command Class */
    public static final byte CLA = (byte) 0x81;

    /** INS-Byte: EHEALTH TERMINAL AUTHENTICATE */
    public static final byte INS = (byte) 0xAA;

    /** P1-Byte: Direct Coding, Address Cardterminal (P1=0x00) */
    public static final byte P1_DIRECT = (byte) 0x00;

    // -------------------------------------------------------------------------
    // Konstanten – P2 Command Qualifier
    // -------------------------------------------------------------------------

    /** P2 CREATE (0x01): neues Shared Secret übergeben, Pairing-Block anlegen */
    public static final byte P2_CREATE = (byte) 0x01;

    /** P2 VALIDATE (0x02): Pairing mit Challenge-Hash verifizieren */
    public static final byte P2_VALIDATE = (byte) 0x02;

    /** P2 ADD Phase 1 (0x03): Challenge vom KT anfordern */
    public static final byte P2_ADD_PH1 = (byte) 0x03;

    /** P2 ADD Phase 2 (0x04): Challenge-Response an KT liefern */
    public static final byte P2_ADD_PH2 = (byte) 0x04;

    // -------------------------------------------------------------------------
    // Konstanten – TLV Tags
    // -------------------------------------------------------------------------

    /** TAG Shared Secret DO (SS DO) – 0xD4 */
    private static final byte TAG_SS_DO = (byte) 0xD4;

    /** TAG Shared Secret Challenge DO (SSC DO) – 0xD5 */
    private static final byte TAG_SSC_DO = (byte) 0xD5;

    /** TAG Shared Secret Response DO (SSR DO) – 0xD6 */
    private static final byte TAG_SSR_DO = (byte) 0xD6;

    /** TAG Application Label DO (Display-Text) – 0x50 */
    private static final byte TAG_APP_LABEL = (byte) 0x50;

    /**
     * TAG SICCT Message To Be Displayed DO (constructed) – 0x62
     * Gemäß SICCT 5.5.10.21 ein constructed TLV-DO, das Character-Set und
     * Application-Label enthält.
     */
    private static final byte TAG_MSG_DISPLAY = (byte) 0x62;

    /** TAG Character Set DO – 0x87 (SICCT 5.5.10.20, ISO 8859-1 = 0x03) */
    private static final byte TAG_CHARSET = (byte) 0x87;

    /** Wert für Character-Set DO: ISO 8859-1 */
    private static final byte CHARSET_ISO8859_1 = (byte) 0x03;

    // -------------------------------------------------------------------------
    // Konstanten – Le-Werte
    // -------------------------------------------------------------------------

    /**
     * Le für CREATE (P2=0x01): 0x00 → erwartet bis zu 256 Byte Signatur
     * (RSA-2048 liefert 256 Byte; ECDSA liefert r||s im Plain-Format nach TR-03111)
     */
    public static final byte LE_CREATE = (byte) 0x00;

    /** Le für VALIDATE (P2=0x02): 0x20 → erwartet 32 Byte SHA-256-Hash-Wert */
    public static final byte LE_VALIDATE = (byte) 0x20;

    /**
     * Standard-Le für ADD Phase 1 (P2=0x03): 0x20 (32 Byte Challenge).
     * Zulässig: 0x10..0x7F (16–127 Byte).
     */
    public static final byte LE_ADD_PH1_DEFAULT = (byte) 0x20;

    // -------------------------------------------------------------------------
    // Längenvorgaben (gemSpec_KT CMD_KT_0001)
    // -------------------------------------------------------------------------

    /** Erwartete Länge des Shared Secret (SS DO): exakt 16 Byte */
    public static final int SS_LENGTH = 16;

    /** Erwartete Länge der SSR DO-Payload (SHA-256-Hashwert): exakt 32 Byte */
    public static final int SSR_LENGTH = 32;

    /** Minimale Länge der SSC DO-Payload: 16 Byte */
    public static final int SSC_MIN_LENGTH = 16;

    /** Maximale Länge der SSC DO-Payload: 127 Byte */
    public static final int SSC_MAX_LENGTH = 127;

    // -------------------------------------------------------------------------
    // Hilfsmethoden – TLV
    // -------------------------------------------------------------------------

    /**
     * Erzeugt ein primitives TLV-DO mit Short-Length-Encoding (1-Byte LEN ≤ 0x7F).
     */
    private static byte[] tlv(byte tag, byte[] value) {
        if (value.length > 0x7F) {
            throw new IllegalArgumentException(
                    String.format("TLV value length %d exceeds short-form limit 127 for tag 0x%02X",
                            value.length, tag & 0xFF));
        }
        byte[] tlv = new byte[2 + value.length];
        tlv[0] = tag;
        tlv[1] = (byte) value.length;
        System.arraycopy(value, 0, tlv, 2, value.length);
        return tlv;
    }

    /**
     * Konkateniert mehrere Byte-Arrays.
     */
    private static byte[] concat(byte[]... arrays) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (byte[] arr : arrays) {
                out.write(arr);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream#write should never throw", e);
        }
    }

    /**
     * Baut eine vollständige C-APDU (Case 3 oder Case 4).
     *
     * @param p2   Command-Qualifier-Byte
     * @param data Command-Data-Feld (kann leer/null sein → Case 2)
     * @param le   Le-Byte; -1 = kein Le (Case 3)
     */
    private static byte[] buildApdu(byte p2, byte[] data, int le) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(CLA & 0xFF);
            out.write(INS & 0xFF);
            out.write(P1_DIRECT & 0xFF);
            out.write(p2 & 0xFF);
            if (data != null && data.length > 0) {
                out.write(data.length); // Lc
                out.write(data); // Data
            }
            if (le >= 0) {
                out.write(le); // Le
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected IO error building APDU", e);
        }
    }

    // -------------------------------------------------------------------------
    // Hilfsmethode – Display-Message-DO erzeugen (SICCT 5.5.10.21)
    // -------------------------------------------------------------------------

    /**
     * Erzeugt das SICCT Message To Be Displayed DO (TAG 0x62) gemäß SICCT
     * 5.5.10.21.
     *
     * <p>
     * Struktur:
     * 
     * <pre>
     *   62 [LEN]
     *     87 01 [charset]       ← Character Set DO (ISO 8859-1 = 0x03)
     *     50 [LEN] [text bytes] ← Application Label DO
     * </pre>
     *
     * @param displayText Anzeigetext (ISO 8859-1 empfohlen, max 127 Byte nach
     *                    Codierung)
     * @return TLV-kodiertes Display-DO
     */
    private static byte[] buildDisplayDo(String displayText) {
        byte[] textBytes = displayText.getBytes(StandardCharsets.ISO_8859_1);
        byte[] charsetDo = tlv(TAG_CHARSET, new byte[] { CHARSET_ISO8859_1 });
        byte[] labelDo = tlv(TAG_APP_LABEL, textBytes);
        byte[] inner = concat(charsetDo, labelDo);
        return tlv(TAG_MSG_DISPLAY, inner);
    }

    // -------------------------------------------------------------------------
    // Öffentliche Factory-Methoden
    // -------------------------------------------------------------------------

    /**
     * CREATE (P2=0x01): Konnektor übergibt dem Kartenterminal ein neues Shared
     * Secret
     * und eine Anzeigenachricht zur Benutzerbestätigung am PIN-Pad.
     *
     * <p>
     * Gemäß SEQ_KT_0001-01 / TIP1-A_3125-03:
     * <ul>
     * <li>Das Shared Secret MUSS genau 16 Byte lang sein (vom Konnektor generierte
     * Zufallszahl).</li>
     * <li>Display-Text und Shared Secret DO müssen beide vorhanden sein.</li>
     * <li>Le=0x00 → erwartet bis zu 256 Byte Signatur über das Shared Secret.</li>
     * </ul>
     *
     * <p>
     * Command-Data-Feld (Direct Coding, P2=01):
     * 
     * <pre>
     *   D4 10 [16 Byte Shared Secret]
     *   62 [LEN]
     *     87 01 03              ← Character Set: ISO 8859-1
     *     50 [LEN] [Text]       ← Application Label (Display-Text)
     * </pre>
     *
     * @param sharedSecret 16-Byte-Zufallszahl (Shared Secret), die der Konnektor
     *                     generiert hat
     * @param displayText  Text, der am Kartenterminal-Display angezeigt werden soll
     * @return vollständige C-APDU (Case 4)
     * @throws IllegalArgumentException wenn sharedSecret nicht exakt 16 Byte lang
     *                                  ist
     */
    public static byte[] buildCreate(byte[] sharedSecret, String displayText) {
        if (sharedSecret == null || sharedSecret.length != SS_LENGTH) {
            throw new IllegalArgumentException(
                    "Shared Secret muss exakt 16 Byte lang sein (gemSpec_KT DO_KT_0003).");
        }
        if (displayText == null || displayText.isEmpty()) {
            throw new IllegalArgumentException(
                    "Display-Text darf nicht leer sein (gemSpec_KT SEQ_KT_0001-01 Schritt 2).");
        }

        byte[] ssDo = tlv(TAG_SS_DO, sharedSecret);
        byte[] displayDo = buildDisplayDo(displayText);
        byte[] data = concat(ssDo, displayDo);

        return buildApdu(P2_CREATE, data, LE_CREATE & 0xFF);
    }

    /**
     * VALIDATE (P2=0x02): Konnektor prüft, ob das Kartenterminal das korrekte
     * Shared Secret
     * kennt, indem es eine Challenge (Zufallsbytes) sendet und den SHA-256-Hash
     * validiert.
     *
     * <p>
     * Gemäß SEQ_KT_0002 / TIP1-A_3126:
     * <ul>
     * <li>Shared Secret Challenge DO (TAG 0xD5) muss mindestens 16 Byte lang
     * sein.</li>
     * <li>Das Kartenterminal hängt das gespeicherte Shared Secret an die Challenge
     * und berechnet SHA-256(Challenge || SharedSecret).</li>
     * <li>Le=0x20 → erwartet 32 Byte SHA-256-Hash-Wert.</li>
     * </ul>
     *
     * <p>
     * Command-Data-Feld (Direct Coding, P2=02):
     * 
     * <pre>
     *   D5 [LEN] [16..127 Byte Challenge / Random Bytes]
     * </pre>
     *
     * @param challenge Zufallsbytes (16–127 Byte), die der Konnektor für die
     *                  Challenge generiert
     * @return vollständige C-APDU (Case 4)
     * @throws IllegalArgumentException wenn challenge außerhalb des Längenbereichs
     *                                  liegt
     */
    public static byte[] buildValidate(byte[] challenge) {
        if (challenge == null
                || challenge.length < SSC_MIN_LENGTH
                || challenge.length > SSC_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Shared Secret Challenge muss 16–127 Byte lang sein (gemSpec_KT DO_KT_0004)," +
                            " war: %d Byte", challenge == null ? 0 : challenge.length));
        }

        byte[] sscDo = tlv(TAG_SSC_DO, challenge);
        return buildApdu(P2_VALIDATE, sscDo, LE_VALIDATE & 0xFF);
    }

    /**
     * ADD Phase 1 (P2=0x03): Konnektor fordert eine Challenge vom Kartenterminal
     * an.
     *
     * <p>
     * Gemäß SEQ_KT_0003 / TIP1-A_3127:
     * <ul>
     * <li>Es gibt kein Command-Data-Feld (Case 2: nur Le).</li>
     * <li>Le bestimmt die gewünschte Länge der Challenge (16–127 Byte, d.h.
     * 0x10–0x7F).</li>
     * <li>Das Kartenterminal wechselt in den Zustand
     * EHEALTH_EXPECT_CHALLENGE_RESPONSE.</li>
     * <li>Der Zustand verliert sich automatisch nach 30 Sekunden oder bei jedem
     * anderen Kommando.</li>
     * </ul>
     *
     * <p>
     * C-APDU (Case 2: kein Data-Feld):
     * 
     * <pre>
     *   81 AA 00 03 [Le]
     * </pre>
     *
     * @param challengeLength gewünschte Länge der Challenge in Byte (16–127)
     * @return vollständige C-APDU (Case 2)
     * @throws IllegalArgumentException wenn challengeLength außerhalb 16–127
     */
    public static byte[] buildAddPhase1(int challengeLength) {
        if (challengeLength < SSC_MIN_LENGTH || challengeLength > SSC_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("challengeLength muss 16–127 sein (0x10–0x7F), war: %d", challengeLength));
        }
        return buildApdu(P2_ADD_PH1, null, challengeLength);
    }

    /**
     * ADD Phase 1 mit Standard-Le=0x20 (32 Byte Challenge).
     *
     * @return vollständige C-APDU (Case 2)
     */
    public static byte[] buildAddPhase1() {
        return buildAddPhase1(LE_ADD_PH1_DEFAULT & 0xFF);
    }

    /**
     * ADD Phase 2 (P2=0x04): Konnektor liefert die Challenge-Response an das
     * Kartenterminal.
     *
     * <p>
     * Gemäß SEQ_KT_0004 / TIP1-A_3128:
     * <ul>
     * <li>Das Kartenterminal MUSS sich im Zustand EHEALTH_EXPECT_CHALLENGE_RESPONSE
     * befinden.</li>
     * <li>Die Response ist SHA-256(challenge || sharedSecret) – der Konnektor
     * berechnet
     * denselben Hash wie das Kartenterminal und liefert diesen als Nachweis.</li>
     * <li>Shared Secret Response DO (TAG 0xD6) enthält exakt 32 Byte.</li>
     * <li>Kein Le-Byte (Case 3): Keine Response-Daten bei Erfolg.</li>
     * </ul>
     *
     * <p>
     * Command-Data-Feld (Direct Coding, P2=04):
     * 
     * <pre>
     *   D6 20 [32 Byte SHA-256(challenge || sharedSecret)]
     * </pre>
     *
     * @param challengeResponse 32-Byte-SHA-256-Hashwert = SHA-256(challenge ||
     *                          sharedSecret)
     * @return vollständige C-APDU (Case 3)
     * @throws IllegalArgumentException wenn challengeResponse nicht exakt 32 Byte
     *                                  lang ist
     */
    public static byte[] buildAddPhase2(byte[] challengeResponse) {
        if (challengeResponse == null || challengeResponse.length != SSR_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Challenge-Response (SSR DO) muss exakt 32 Byte lang sein (gemSpec_KT DO_KT_0005)," +
                            " war: %d Byte", challengeResponse == null ? 0 : challengeResponse.length));
        }

        byte[] ssrDo = tlv(TAG_SSR_DO, challengeResponse);
        return buildApdu(P2_ADD_PH2, ssrDo, -1); // kein Le
    }

    /**
     * ADD Phase 2 mit automatischer SHA-256-Berechnung aus Challenge und Shared
     * Secret.
     *
     * <p>
     * Berechnet intern SHA-256(challenge || sharedSecret) und ruft
     * {@link #buildAddPhase2(byte[])} auf.
     *
     * @param challenge    vom Kartenterminal empfangene Challenge (aus Phase 1)
     * @param sharedSecret das gespeicherte Shared Secret des Pairing-Blocks
     * @return vollständige C-APDU (Case 3)
     */
    public static byte[] buildAddPhase2(byte[] challenge, byte[] sharedSecret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(challenge);
            sha256.update(sharedSecret);
            byte[] hash = sha256.digest();
            return buildAddPhase2(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden für den Konnektor-Ablauf
    // -------------------------------------------------------------------------

    /**
     * Generiert ein kryptographisch zufälliges Shared Secret (16 Byte).
     *
     * <p>
     * Gemäß gemSpec_KT SEQ_KT_0001-01 Schritt 3 ist das Shared Secret
     * eine vom Konnektor generierte Zufallszahl mit exakt 16 Byte.
     *
     * @return neues 16-Byte-Shared-Secret
     */
    public static byte[] generateSharedSecret() {
        byte[] secret = new byte[SS_LENGTH];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /**
     * Generiert eine kryptographisch zufällige Challenge für VALIDATE (16 Byte).
     *
     * @param length gewünschte Challenge-Länge (16–127 Byte)
     * @return Zufallsbytes der gewünschten Länge
     */
    public static byte[] generateChallenge(int length) {
        if (length < SSC_MIN_LENGTH || length > SSC_MAX_LENGTH) {
            throw new IllegalArgumentException("Challenge-Länge muss 16–127 Byte sein.");
        }
        byte[] challenge = new byte[length];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }

    /**
     * Berechnet SHA-256(challenge || sharedSecret) für die VALIDATE-Überprüfung auf
     * Konnektor-Seite.
     *
     * <p>
     * Der Konnektor verwendet diesen Wert, um die Antwort des Kartenterminals zu
     * prüfen:
     * Wenn der vom KT zurückgelieferte Hash mit diesem Wert übereinstimmt, ist das
     * Pairing valide.
     *
     * @param challenge    gesendete Challenge (Zufallsbytes)
     * @param sharedSecret das gespeicherte Shared Secret
     * @return SHA-256-Hashwert (32 Byte)
     */
    public static byte[] computeExpectedValidateHash(byte[] challenge, byte[] sharedSecret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(challenge);
            sha256.update(sharedSecret);
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    /**
     * Gibt eine lesbare Hex-Darstellung einer APDU zurück (für Debugging/Logging).
     *
     * @param apdu Byte-Array der APDU
     * @return Hex-String mit Leerzeichen-Separator
     */
    public static String toHexString(byte[] apdu) {
        if (apdu == null)
            return "(null)";
        StringBuilder sb = new StringBuilder(apdu.length * 3);
        for (byte b : apdu) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Demo / Smoke-Test
    // -------------------------------------------------------------------------

    /**
     * Einfacher Smoke-Test, der alle vier Kommandovarianten erzeugt und ausgibt.
     */
    public static void main(String[] args) {
        System.out.println("=== EHEALTH TERMINAL AUTHENTICATE – Konnektor-Kommandos ===\n");

        // ---- CREATE ----
        byte[] sharedSecret = generateSharedSecret();
        byte[] createApdu = buildCreate(sharedSecret, "Bitte bestaetigen Sie das Pairing");
        System.out.println("CREATE (P2=01):");
        System.out.println("  SharedSecret : " + toHexString(sharedSecret));
        System.out.println("  C-APDU       : " + toHexString(createApdu));
        System.out.printf("  Lc           : 0x%02X (%d Byte Data)%n",
                createApdu[4], createApdu[4]);
        System.out.println();

        // ---- VALIDATE ----
        byte[] challenge = generateChallenge(16);
        byte[] validateApdu = buildValidate(challenge);
        System.out.println("VALIDATE (P2=02):");
        System.out.println("  Challenge    : " + toHexString(challenge));
        System.out.println("  C-APDU       : " + toHexString(validateApdu));
        byte[] expectedHash = computeExpectedValidateHash(challenge, sharedSecret);
        System.out.println("  Erwarteter Hash (SHA-256(challenge||SS)): " + toHexString(expectedHash));
        System.out.println();

        // ---- ADD Phase 1 ----
        byte[] addPh1Apdu = buildAddPhase1(32);
        System.out.println("ADD Phase 1 (P2=03) – Challenge anfordern:");
        System.out.println("  C-APDU : " + toHexString(addPh1Apdu));
        System.out.println("  Le     : 0x20 (32 Byte Challenge erwartet)");
        System.out.println();

        // ---- ADD Phase 2 ----
        // Simuliert: KT hat eine Challenge zurückgeliefert
        byte[] ktChallenge = generateChallenge(32);
        byte[] addPh2Apdu = buildAddPhase2(ktChallenge, sharedSecret);
        System.out.println("ADD Phase 2 (P2=04) – Challenge-Response senden:");
        System.out.println("  KT-Challenge : " + toHexString(ktChallenge));
        System.out.println("  C-APDU       : " + toHexString(addPh2Apdu));
        System.out.println();

        // ---- Strukturvalidierung ----
        System.out.println("=== Strukturprüfung ===");
        System.out.printf("CREATE   Header: CLA=%02X INS=%02X P1=%02X P2=%02X%n",
                createApdu[0], createApdu[1], createApdu[2], createApdu[3]);
        System.out.printf("VALIDATE Header: CLA=%02X INS=%02X P1=%02X P2=%02X%n",
                validateApdu[0], validateApdu[1], validateApdu[2], validateApdu[3]);
        System.out.printf("ADD PH1  Header: CLA=%02X INS=%02X P1=%02X P2=%02X Le=%02X%n",
                addPh1Apdu[0], addPh1Apdu[1], addPh1Apdu[2], addPh1Apdu[3], addPh1Apdu[4]);
        System.out.printf("ADD PH2  Header: CLA=%02X INS=%02X P1=%02X P2=%02X%n",
                addPh2Apdu[0], addPh2Apdu[1], addPh2Apdu[2], addPh2Apdu[3]);

        // Assertions
        assert createApdu[0] == (byte) 0x81 : "CLA falsch";
        assert createApdu[1] == (byte) 0xAA : "INS falsch";
        assert createApdu[3] == (byte) 0x01 : "P2 CREATE falsch";
        assert validateApdu[3] == (byte) 0x02 : "P2 VALIDATE falsch";
        assert addPh1Apdu[3] == (byte) 0x03 : "P2 ADD PH1 falsch";
        assert addPh2Apdu[3] == (byte) 0x04 : "P2 ADD PH2 falsch";
        // SS DO Tag prüfen (Direct Coding: createApdu[5] = TAG_SS_DO)
        assert createApdu[5] == (byte) 0xD4 : "SS DO Tag falsch";
        // SSC DO Tag prüfen
        assert validateApdu[5] == (byte) 0xD5 : "SSC DO Tag falsch";
        // SSR DO Tag in Phase 2 prüfen
        assert addPh2Apdu[5] == (byte) 0xD6 : "SSR DO Tag falsch";
        System.out.println("\nAlle Assertions bestanden.");
    }
}