package de.servicehealtherx.sicct.jpa;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.servicehealtherx.sicct.models.CardHandle;

@Entity
@Table(name = "CARD_TERMINAL")
public class CardTerminal extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "CTID", nullable = false, updatable = false)
    public UUID ctid;

    @Column(name = "IS_PHYSICAL", nullable = false)
    public boolean physical = true;

    @Column(name = "NAME", nullable = false, length = 128, unique = true)
    public String name;

    @Column(name = "MAC_ADDRESS", nullable = false, length = 17, unique = true)
    public String macAddress;

    /** SICCT terminal name / FriendlyName */
    @Column(name = "HOSTNAME", nullable = false, length = 128)
    public String hostname;

    /** IP address used for the TCP/TLS connection */
    @Column(name = "IP_ADDRESS", nullable = false, length = 45)
    public String ipAddress;

    @Column(name = "TCP_PORT", nullable = false)
    public int tcpPort;

    @Column(name = "SLOT_COUNT", nullable = false)
    public int slotCount = 0;

    /** Comma-separated list of occupied slot numbers */
    @Column(name = "SLOTS_USED", length = 256)
    public String slotsUsed;

    @Column(name = "PRODUCT_INFORMATION", length = 4096)
    public String productInformation;

    /** Version string from SICCT GET STATUS VER element */
    @Column(name = "EHEALTH_INTERFACE_VERSION", length = 32)
    public String ehealthInterfaceVersion;

    /** True if the interface version is supported by this Konnektor */
    @Column(name = "VALID_VERSION", nullable = false)
    public boolean validVersion = false;

    /** Per SICCT §5.5.10.17 */
    @Column(name = "DISPLAY_CAPABILITIES", length = 1024)
    public String displayCapabilities;

    /** X.509 C.SMKT.AUT certificate received during pairing */
    @Column(name = "SMKT_AUT_CERTIFICATE", length = 4096)
    public byte[] smktAutCertificate;

    /** ShS.KT.AUT shared secret sealed via TPM 2.0 (FR-139) */
    @Column(name = "SEALED_SHARED_SECRET", length = 4096)
    public byte[] sealedSharedSecret;

    /**
     * Correlation state per TUC_KON_053/TUC_KON_050.
     * Values: BEKANNT | ZUGEWIESEN | GEPAIRT | AKTIV | AKTUALISIEREND
     */
    @Column(name = "CORRELATION", nullable = false, length = 32)
    public String correlation = "BEKANNT";

    /** True when a TLS session is established and second-factor auth succeeded */
    @Column(name = "CONNECTED", nullable = false)
    public boolean connected = false;

    /** Current operator role: USER or ADMIN */
    @Column(name = "ACTIVE_ROLE", length = 16)
    public String activeRole;

    @Column(name = "ADMIN_USERNAME", length = 256)
    public String adminUsername;

    @Column(name = "ADMIN_PASSWORD", length = 256)
    public String adminPassword;

    /**
     * AES-256-GCM encrypted backup of ShS.KT.AUT; populated only during backup
     * window (FR-180)
     */
    @Column(name = "BACKUP_ENCRYPTED_SHARED_SECRET", length = 4096)
    public byte[] backupEncryptedSharedSecret;

    @Transient
    public List<CardHandle> slots = new ArrayList<>();

    public static CardTerminal findByMacAddress(String macAddress) {
        return find("macAddress = ?1", macAddress).firstResult();
    }

    public static CardTerminal findByHostname(String hostname) {
        return find("hostname = ?1", hostname).firstResult();
    }

    public String toString() {
        return "CardTerminal{ctid=" + ctid +
                ", physical=" + physical +
                ", name='" + name + '\'' +
                ", macAddress='" + macAddress + '\'' +
                ", hostname='" + hostname + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", tcpPort=" + tcpPort +
                ", slotCount=" + slotCount +
                ", slotsUsed='" + slotsUsed + '\'' +
                ", productInformation='" + productInformation + '\'' +
                ", ehealthInterfaceVersion='" + ehealthInterfaceVersion + '\'' +
                ", validVersion=" + validVersion +
                ", displayCapabilities='" + displayCapabilities + '\'' +
                ", correlation='" + correlation + '\'' +
                ", connected=" + connected +
                ", activeRole='" + activeRole + '\'' +
                '}';
    }

    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CardTerminal))
            return false;
        CardTerminal that = (CardTerminal) o;
        return this.macAddress.equals(that.macAddress);
    }

    public int hashCode() {
        return macAddress.hashCode();
    }
}
