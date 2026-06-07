package de.servicehealtherx.sicct.jmx;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
public class BackupRestoreManagement implements BackupRestoreManagementMBean {

    private static final Logger LOG = Logger.getLogger(BackupRestoreManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=sicct-lib,name=BackupRestoreManagement";
    private static final Logger CRITICAL_AUDIT = Logger.getLogger("CRITICAL_AUDIT");

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
            if (server.isRegistered(name))
                server.unregisterMBean(name);
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    @Transactional
    public String exportBackup() {
        try {
            // Generate random 256-bit backup password
            byte[] passwordBytes = new byte[32];
            new SecureRandom().nextBytes(passwordBytes);
            String backupPassword = Base64.getEncoder().encodeToString(passwordBytes);

            List<CardTerminal> terminals = CardTerminal.listAll();
            for (CardTerminal terminal : terminals) {
                if (terminal.sealedSharedSecret == null)
                    continue;
                byte[] encrypted = encryptWithPassword(terminal.sealedSharedSecret,
                        backupPassword, terminal.hostname);
                terminal.backupEncryptedSharedSecret = encrypted;
                terminal.persist();
                LOG.infof("[BACKUP] exported backup for terminalId=%s", terminal.hostname);
            }

            // Password returned exactly once — MUST NOT be stored by the system
            return backupPassword;
        } catch (Exception e) {
            LOG.errorf(e, "[BACKUP] export failed");
            throw new RuntimeException("Backup export failed: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public String importBackup(String backupPassword) {
        try {
            List<CardTerminal> terminals = CardTerminal.listAll();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (CardTerminal terminal : terminals) {
                if (!first)
                    sb.append(",");
                first = false;
                try {
                    if (terminal.backupEncryptedSharedSecret == null) {
                        sb.append("{\"terminalId\":\"").append(terminal.hostname)
                                .append("\",\"result\":\"failed: no backup\"}");
                        continue;
                    }
                    byte[] restored = decryptWithPassword(terminal.backupEncryptedSharedSecret,
                            backupPassword, terminal.hostname);
                    terminal.sealedSharedSecret = restored;
                    terminal.backupEncryptedSharedSecret = null;
                    terminal.persist();
                    // CRITICAL audit entry per terminal per FR-233
                    CRITICAL_AUDIT.infof("[CRITICAL][BACKUP_RESTORE] terminalId=%s restoredAt=%s",
                            terminal.hostname, Instant.now());
                    sb.append("{\"terminalId\":\"").append(terminal.hostname).append("\",\"result\":\"restored\"}");
                } catch (Exception e) {
                    sb.append("{\"terminalId\":\"").append(terminal.hostname)
                            .append("\",\"result\":\"failed: ").append(e.getMessage().replace("\"", "'")).append("\"}");
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf(e, "[BACKUP] import failed");
            throw new RuntimeException("Backup import failed: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public String getBackupStatus() {
        List<CardTerminal> terminals = CardTerminal.listAll();
        long paired = terminals.stream().filter(t -> t.sealedSharedSecret != null).count();
        long withBackup = terminals.stream().filter(t -> t.backupEncryptedSharedSecret != null).count();
        return "{\"pairedTerminals\":" + paired +
                ",\"terminalsWithBackup\":" + withBackup +
                ",\"retentionWindowExpiry\":null}";
    }

    private byte[] encryptWithPassword(byte[] data, String password, String salt) throws Exception {
        SecretKey key = deriveKey(password, salt);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(data);
        byte[] result = new byte[12 + encrypted.length];
        System.arraycopy(iv, 0, result, 0, 12);
        System.arraycopy(encrypted, 0, result, 12, encrypted.length);
        return result;
    }

    private byte[] decryptWithPassword(byte[] data, String password, String salt) throws Exception {
        SecretKey key = deriveKey(password, salt);
        byte[] iv = new byte[12];
        System.arraycopy(data, 0, iv, 0, 12);
        byte[] encrypted = new byte[data.length - 12];
        System.arraycopy(data, 12, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private SecretKey deriveKey(String password, String saltStr) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), saltStr.getBytes("UTF-8"), 310000, 256);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
