package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.AuthEnvelopedData;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.EncryptedContentInfo;
import org.bouncycastle.asn1.cms.GCMParameters;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.KeyTransRecipientInfo;
import org.bouncycastle.asn1.cms.RecipientIdentifier;
import org.bouncycastle.asn1.cms.RecipientInfo;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.List;

/**
 * gematik TI-ECIES transport encryption (gemSpec_Krypt §4.7 / A_17220): builds and parses the CMS
 * {@code AuthEnvelopedData} that encrypts a document once with an AES-256-GCM transport key and
 * wraps that key per recipient via ELC ({@link ElcKeyWrapper}) under
 * {@code oid_ti_ecies_transport_encryption}.
 */
public final class EciesTransportEncryption {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;

    private final ElcKeyWrapper keyWrapper = new ElcKeyWrapper();
    private final SecureRandom random = new SecureRandom();

    /** Encrypt {@code document} for one or more elliptic-curve recipients. */
    public byte[] encrypt(byte[] document, List<X509Certificate> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("no recipient");
        }

        byte[] transportKey = new byte[32];
        random.nextBytes(transportKey);
        try {
            EncryptedContentInfo content = encryptContent(transportKey, document);

            ASN1EncodableVector recipientInfos = new ASN1EncodableVector();
            byte[] mac = null;
            for (X509Certificate cert : recipients) {
                recipientInfos.add(buildRecipientInfo(cert, transportKey));
            }
            mac = extractMac(content);

            AuthEnvelopedData aed = new AuthEnvelopedData(
                    null, new DERSet(recipientInfos), stripMac(content), null,
                    new DEROctetString(mac), null);
            return new ContentInfo(CMSObjectIdentifiers.authEnvelopedData, aed).getEncoded("DER");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ECIES encryption failed", e);
        } finally {
            Arrays.fill(transportKey, (byte) 0);
        }
    }

    /**
     * Decrypt for the recipient identified by {@code recipientCert}, unwrapping the transport key
     * with {@code privateKey} through the JCE {@code "ELC"} cipher (software or card-backed).
     */
    public byte[] decrypt(byte[] envelope, X509Certificate recipientCert, PrivateKey privateKey, Provider elcProvider) {
        byte[] transportKey = null;
        try {
            ContentInfo ci = ContentInfo.getInstance(envelope);
            if (!CMSObjectIdentifiers.authEnvelopedData.equals(ci.getContentType())) {
                throw new IllegalArgumentException("unsupported content type: " + ci.getContentType());
            }
            AuthEnvelopedData aed = AuthEnvelopedData.getInstance(ci.getContent());

            KeyTransRecipientInfo ktri = selectRecipient(aed, recipientCert);
            if (!EciesOids.TI_ECIES_TRANSPORT_ENCRYPTION.equals(ktri.getKeyEncryptionAlgorithm().getAlgorithm())) {
                throw new IllegalArgumentException("unsupported algorithm: "
                        + ktri.getKeyEncryptionAlgorithm().getAlgorithm());
            }

            byte[] elcBlob = ktri.getEncryptedKey().getOctets();
            Cipher elc = Cipher.getInstance("ELC", elcProvider);
            elc.init(Cipher.DECRYPT_MODE, privateKey);
            transportKey = elc.doFinal(elcBlob);

            return decryptContent(aed, transportKey);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ECIES decryption failed", e);
        } finally {
            if (transportKey != null) {
                Arrays.fill(transportKey, (byte) 0);
            }
        }
    }

    /**
     * Decrypt without a recipient certificate: try each ELC recipient entry with the supplied
     * unwrapper (the caller's key) until one entry's transport key authenticates. The ELC MAC
     * inside each {@code (PO,C,T)} identifies the matching entry, so no recipient identifier is
     * needed (the caller-supplied key selects its own entry, per the spec clarification).
     *
     * @param transportKeyUnwrapper maps an ELC {@code (PO,C,T)} blob to the transport key, throwing
     *                              on a non-matching entry (e.g. via the JCE {@code "ELC"} cipher or
     *                              a {@code CryptoProvider})
     */
    public byte[] decrypt(byte[] envelope, java.util.function.Function<byte[], byte[]> transportKeyUnwrapper) {
        AuthEnvelopedData aed = parseEnvelope(envelope);
        boolean sawElcRecipient = false;
        for (int i = 0; i < aed.getRecipientInfos().size(); i++) {
            RecipientInfo ri = RecipientInfo.getInstance(aed.getRecipientInfos().getObjectAt(i));
            if (!(ri.getInfo() instanceof KeyTransRecipientInfo ktri)) {
                continue;
            }
            if (!EciesOids.TI_ECIES_TRANSPORT_ENCRYPTION.equals(ktri.getKeyEncryptionAlgorithm().getAlgorithm())) {
                continue;
            }
            sawElcRecipient = true;
            byte[] transportKey;
            try {
                transportKey = transportKeyUnwrapper.apply(ktri.getEncryptedKey().getOctets());
            } catch (RuntimeException notThisEntry) {
                continue; // wrong recipient entry for this key (ELC MAC failed) — try the next
            }
            try {
                return decryptContent(aed, transportKey);
            } catch (Exception e) {
                throw new IllegalArgumentException("integrity failure", e);
            } finally {
                Arrays.fill(transportKey, (byte) 0);
            }
        }
        throw new IllegalArgumentException(sawElcRecipient ? "no matching key" : "unsupported algorithm");
    }

    private static AuthEnvelopedData parseEnvelope(byte[] envelope) {
        ContentInfo ci;
        try {
            ci = ContentInfo.getInstance(envelope);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed input", e);
        }
        if (!CMSObjectIdentifiers.authEnvelopedData.equals(ci.getContentType())) {
            throw new IllegalArgumentException("unsupported content type: " + ci.getContentType());
        }
        return AuthEnvelopedData.getInstance(ci.getContent());
    }

    private RecipientInfo buildRecipientInfo(X509Certificate cert, byte[] transportKey) throws Exception {
        ASN1ObjectIdentifier curveOid = curveOidOf(cert);
        ElcCryptogram cryptogram =
                keyWrapper.wrap(transportKey, (ECPublicKey) cert.getPublicKey(), curveOid);

        JcaX509CertificateHolder holder = new JcaX509CertificateHolder(cert);
        RecipientIdentifier rid = new RecipientIdentifier(
                new IssuerAndSerialNumber(holder.getIssuer(), holder.getSerialNumber()));
        AlgorithmIdentifier keyEncAlg = new AlgorithmIdentifier(EciesOids.TI_ECIES_TRANSPORT_ENCRYPTION);
        KeyTransRecipientInfo ktri =
                new KeyTransRecipientInfo(rid, keyEncAlg, new DEROctetString(cryptogram.toAsn1()));
        return new RecipientInfo(ktri);
    }

    private static ASN1ObjectIdentifier curveOidOf(X509Certificate cert) {
        if (!(cert.getPublicKey() instanceof ECPublicKey)) {
            throw new IllegalArgumentException("unsuitable recipient: certificate is not elliptic-curve");
        }
        ASN1ObjectIdentifier curveOid;
        try {
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(cert.getPublicKey().getEncoded());
            curveOid = ASN1ObjectIdentifier.getInstance(spki.getAlgorithm().getParameters());
        } catch (Exception e) {
            throw new IllegalArgumentException("unsupported curve: explicit parameters not allowed (A_23511)", e);
        }
        if (!ElcParameters.isSupportedCurve(curveOid)) {
            throw new IllegalArgumentException("unsupported curve: " + curveOid);
        }
        return curveOid;
    }

    /** AES-256-GCM the document; produce an EncryptedContentInfo whose encryptedContent still carries the tag. */
    private EncryptedContentInfo encryptContent(byte[] transportKey, byte[] document) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(transportKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ctAndTag = gcm.doFinal(document);

        AlgorithmIdentifier ceAlg =
                new AlgorithmIdentifier(NISTObjectIdentifiers.id_aes256_GCM, new GCMParameters(iv, GCM_TAG_BYTES));
        // Temporarily store ciphertext||tag; split into encryptedContent + mac in strip/extract below.
        return new EncryptedContentInfo(CMSObjectIdentifiers.data, ceAlg, new DEROctetString(ctAndTag));
    }

    private static byte[] extractMac(EncryptedContentInfo content) {
        byte[] ctAndTag = content.getEncryptedContent().getOctets();
        return Arrays.copyOfRange(ctAndTag, ctAndTag.length - GCM_TAG_BYTES, ctAndTag.length);
    }

    private static EncryptedContentInfo stripMac(EncryptedContentInfo content) {
        byte[] ctAndTag = content.getEncryptedContent().getOctets();
        byte[] ciphertext = Arrays.copyOfRange(ctAndTag, 0, ctAndTag.length - GCM_TAG_BYTES);
        return new EncryptedContentInfo(
                content.getContentType(), content.getContentEncryptionAlgorithm(), new DEROctetString(ciphertext));
    }

    private byte[] decryptContent(AuthEnvelopedData aed, byte[] transportKey) throws Exception {
        EncryptedContentInfo eci = aed.getAuthEncryptedContentInfo();
        GCMParameters gcmParams =
                GCMParameters.getInstance(eci.getContentEncryptionAlgorithm().getParameters());
        byte[] iv = gcmParams.getNonce();
        byte[] ciphertext = eci.getEncryptedContent().getOctets();
        byte[] mac = aed.getMac().getOctets();

        byte[] ctAndTag = new byte[ciphertext.length + mac.length];
        System.arraycopy(ciphertext, 0, ctAndTag, 0, ciphertext.length);
        System.arraycopy(mac, 0, ctAndTag, ciphertext.length, mac.length);

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(transportKey, "AES"),
                new GCMParameterSpec(gcmParams.getIcvLen() * 8, iv));
        return gcm.doFinal(ctAndTag);
    }

    private static KeyTransRecipientInfo selectRecipient(AuthEnvelopedData aed, X509Certificate recipientCert) {
        try {
            JcaX509CertificateHolder holder = new JcaX509CertificateHolder(recipientCert);
            X500Name issuer = holder.getIssuer();
            for (int i = 0; i < aed.getRecipientInfos().size(); i++) {
                RecipientInfo ri = RecipientInfo.getInstance(aed.getRecipientInfos().getObjectAt(i));
                if (!(ri.getInfo() instanceof KeyTransRecipientInfo ktri)) {
                    continue;
                }
                if (ktri.getRecipientIdentifier().getId() instanceof IssuerAndSerialNumber isn
                        && isn.getName().equals(issuer)
                        && isn.getSerialNumber().getValue().equals(holder.getSerialNumber())) {
                    return ktri;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed input: cannot match recipient", e);
        }
        throw new IllegalArgumentException("no matching key for recipient");
    }
}
