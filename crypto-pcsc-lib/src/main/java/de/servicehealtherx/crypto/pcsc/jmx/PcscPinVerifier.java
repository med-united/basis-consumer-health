package de.servicehealtherx.crypto.pcsc.jmx;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.smartcardio.CommandAPDU;

import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardPinVerifier;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.crypto.pcsc.PcscCardReaderPort;
import de.servicehealtherx.crypto.pcsc.PcscCryptoProvider;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * {@link PcscPinVerifierMBean} implementation. Self-registers on the platform
 * MBean server (mirroring
 * {@code de.servicehealtherx.crypto.jmx.CryptoProviderManagement}) and drives
 * the inserted card via
 * the {@link PcscCryptoProvider} that owns it.
 *
 * <p>
 * The two {@code verifyPin} operations share PIN-reference parsing and
 * status-word interpretation;
 * they differ only in where the secret comes from — a method argument (software
 * VERIFY) or the
 * reader's secure PIN pad ({@code FEATURE_VERIFY_PIN_DIRECT}). The PIN-pad path
 * and the QES SELECT
 * mirror the gematik reference flow in
 * {@code ehba-cades-qes-sign/EHBACard.java}.
 */
@ApplicationScoped
@Startup
public class PcscPinVerifier implements PcscPinVerifierMBean {

    private static final Logger LOG = Logger.getLogger(PcscPinVerifier.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-pcsc-lib,name=PcscPinVerifier";

    @Inject
    PcscCryptoProvider provider;

    @PostConstruct
    void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                LOG.infof("[JMX] registered %s", OBJECT_NAME);
            }
        } catch (Exception e) {
            LOG.errorf(e, "[JMX] failed to register %s", OBJECT_NAME);
        }
    }

    @PreDestroy
    void deregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String verifyPin(String cardHandle, String pinKeyRef, String pin) {
        if (pin == null || pin.isEmpty()) {
            throw new IllegalArgumentException("pin must not be empty");
        }
        PinRef ref = PinRef.parse(pinKeyRef);
        CardObject card = provider.cardForHandle(cardHandle);
        PcscCardReaderPort port = provider.portForHandle(cardHandle);
        try {
            if (ref.selectAid != null) {
                // A dfSpecific PIN (PIN.QES) must be verified with its application selected.
                // The card
                // is left powered between transmits, so the SELECT context carries into the
                // VERIFY.
                port.transmit(card.slotNo(), selectApdu(ref.selectAid));
            }
            CardPinVerifier.Result result = new CardPinVerifier(port, card.slotNo()).verify(ref.value, pin);
            return describe("VERIFY (software)", cardHandle, ref, result.sw());
        } catch (CardTransportException e) {
            throw new IllegalStateException("verifyPin failed for card " + cardHandle + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String verifyPin(String cardHandle, String pinKeyRef) {
        PinRef ref = PinRef.parse(pinKeyRef);
        CardObject card = provider.cardForHandle(cardHandle);
        PcscCardReaderPort port = provider.portForHandle(cardHandle);
        CommandAPDU selectApdu = ref.selectAid == null ? null : selectApdu(ref.selectAid);
        try {
            int sw = port.terminal().verifyPinOnPad(selectApdu, ref.value);
            return describe("VERIFY (PIN pad)", cardHandle, ref, sw);
        } catch (CardTransportException e) {
            throw new IllegalStateException("verifyPin (PIN pad) failed for card " + cardHandle + ": "
                    + e.getMessage(), e);
        }
    }

    /** Build a {@code SELECT by DF name} APDU for the given application AID. */
    private static CommandAPDU selectApdu(byte[] aid) {
        return new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_DF_NAME, 0x0C, aid);
    }

    /**
     * Turn a VERIFY status word into a success message, or throw a descriptive
     * failure.
     */
    private static String describe(String operation, String cardHandle, PinRef ref, int sw) {
        if (sw == GematikISO7816.SW_SUCCESS) {
            return operation + " OK: PIN " + ref.label + " verified on card " + cardHandle;
        }
        if (GematikISO7816.isPinWrongTriesRemaining(sw)) {
            throw new IllegalStateException(operation + " rejected: wrong PIN " + ref.label + ", "
                    + GematikISO7816.pinTriesRemaining(sw) + " tries remaining on card " + cardHandle);
        }
        if (sw == GematikISO7816.SW_AUTH_METHOD_BLOCKED) {
            throw new IllegalStateException(operation + " rejected: PIN " + ref.label
                    + " is blocked on card " + cardHandle);
        }
        if (sw == GematikISO7816.SW_PIN_TRANSPORT) {
            throw new IllegalStateException(operation + " rejected: PIN " + ref.label
                    + " is still a transport PIN on card " + cardHandle);
        }
        throw new IllegalStateException(operation + " failed: PIN " + ref.label + " on card " + cardHandle
                + " returned SW=" + String.format("%04X", sw));
    }

    /**
     * A parsed PIN reference: the password reference byte, an optional application
     * to SELECT, a label.
     */
    private static final class PinRef {
        final int value;
        final byte[] selectAid;
        final String label;

        private PinRef(int value, byte[] selectAid, String label) {
            this.value = value;
            this.selectAid = selectAid;
            this.label = label;
        }

        /**
         * Accept a gematik PIN name ({@code PIN.SMC}/{@code PIN.CH}/{@code PIN.QES}) or
         * a raw reference
         * ({@code 0x81}, {@code 81}, {@code 1}). The dfSpecific QES reference
         * {@code 0x81} implies a
         * SELECT of DF.QES; the global references need no SELECT (the MF is implicitly
         * selected).
         */
        static PinRef parse(String pinKeyRef) {
            if (pinKeyRef == null || pinKeyRef.isBlank()) {
                throw new IllegalArgumentException("pinKeyRef must not be empty");
            }
            String s = pinKeyRef.trim();
            switch (s.toUpperCase()) {
                case "PIN.SMC", "PIN.CH":
                    return new PinRef(0x01, null, s.toUpperCase());
                case "PIN.QES":
                    return new PinRef(0x81, GematikISO7816.AID_DF_QES, "PIN.QES");
                default:
                    int ref = parseNumeric(s);
                    byte[] aid = ref == 0x81 ? GematikISO7816.AID_DF_QES : null;
                    return new PinRef(ref, aid, "0x" + String.format("%02X", ref));
            }
        }

        private static int parseNumeric(String s) {
            try {
                int ref = s.toLowerCase().startsWith("0x")
                        ? Integer.parseInt(s.substring(2), 16)
                        : Integer.parseInt(s, 16);
                if (ref < 0 || ref > 0xFF) {
                    throw new IllegalArgumentException("pinKeyRef out of range (0x00..0xFF): " + s);
                }
                return ref;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unsupported pinKeyRef: " + s
                        + " (expected PIN.SMC / PIN.CH / PIN.QES or a hex reference like 0x81)");
            }
        }
    }
}
