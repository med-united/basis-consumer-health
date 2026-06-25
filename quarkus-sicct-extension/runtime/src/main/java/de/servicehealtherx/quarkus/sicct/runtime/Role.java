package de.servicehealtherx.quarkus.sicct.runtime;

/**
 * Operator role a card-terminal session is opened for, per TUC_KON_050. A USER
 * session uses an empty INIT CT SESSION username/password; an ADMIN session uses
 * {@code CT.ADMIN_USERNAME}/{@code CT.ADMIN_PASSWORD}.
 */
public enum Role {
    USER,
    ADMIN
}
