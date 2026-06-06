package de.servicehealtherx.sicct.jmx;

public interface BackupRestoreManagementMBean {

    String exportBackup();

    String importBackup(String backupPassword);

    String getBackupStatus();
}
