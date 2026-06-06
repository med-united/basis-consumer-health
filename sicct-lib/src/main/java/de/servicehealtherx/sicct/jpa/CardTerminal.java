package de.servicehealtherx.sicct.jpa;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "CARD_TERMINAL")
public class CardTerminal extends PanacheEntityBase {

    @Id
    @Column(name = "TERMINAL_ID", nullable = false, length = 128)
    public String terminalId;

    @Column(name = "HOST", nullable = false, length = 256)
    public String host;

    @Column(name = "PORT", nullable = false)
    public int port;

    @Column(name = "PAIRING_STATUS", nullable = false, length = 32)
    public String pairingStatus;

    @Column(name = "SEALED_SHARED_SECRET", length = 4096)
    public byte[] sealedSharedSecret;

    @Column(name = "BACKUP_ENCRYPTED_SHARED_SECRET", length = 4096)
    public byte[] backupEncryptedSharedSecret;

    @Column(name = "CONNECT_TIMEOUT_MS", nullable = false)
    public int connectTimeoutMs = 5000;

    @Column(name = "APDU_TIMEOUT_MS", nullable = false)
    public int apduTimeoutMs = 30000;

    @Column(name = "MAX_RETRIES", nullable = false)
    public int maxRetries = 3;

    @Column(name = "INITIAL_BACKOFF_MS", nullable = false)
    public int initialBackoffMs = 1000;

    @Column(name = "MAX_BACKOFF_MS", nullable = false)
    public int maxBackoffMs = 30000;

    public static CardTerminal findByTerminalId(String terminalId) {
        return findById(terminalId);
    }
}
