/**
 * gematik TI-ECIES transport encryption (gemSpec_Krypt §4.7 / A_17220).
 *
 * <p>A document is encrypted once with a random 256-bit AES transport key (AES-256-GCM,
 * packaged as a CMS {@code AuthEnvelopedData}); the transport key is wrapped per recipient
 * with the gemSpec_COS ELC scheme (§6.8.1.4 / §6.8.2.3) and carried in a
 * {@code KeyTransRecipientInfo} whose {@code keyEncryptionAlgorithm} is
 * {@code oid_ti_ecies_transport_encryption} (1.2.276.0.76.4.222) and whose {@code encryptedKey}
 * is the card-compatible {@code (PO, C, T)} tuple ({@link de.servicehealtherx.crypto.ecies.ElcCryptogram}).
 *
 * <p>See {@code specs/009-ecies-encryption-service/} for the plan, research, and contracts.
 */
package de.servicehealtherx.crypto.ecies;
