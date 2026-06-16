package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.BERTags;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;

import java.io.IOException;
import java.util.Arrays;

/**
 * The card-compatible {@code (PO, C, T)} ELC cryptogram that carries an ECIES-wrapped transport
 * key, as defined by gemSpec_Krypt §4.7 and consumed by a TI card's {@code PSO:DECIPHER}
 * (gemSpec_COS §6.8.2.3). This blob is placed verbatim as the CMS {@code encryptedKey} of each
 * {@code KeyTransRecipientInfo}.
 *
 * <pre>
 * [6] {                                   -- 0xA6 context-6 constructed
 *   OBJECT IDENTIFIER  &lt;named curve&gt;     -- e.g. brainpoolP256r1
 *   [APPLICATION 73] { [6] PO }           -- 0x7F49 explicit; PO = 04||X||Y (uncompressed point)
 *   [6] C                                 -- 0x86 AES-CBC ciphertext of the transport key
 *   [14] T                                -- 0x8E AES-CMAC tag
 * }
 * </pre>
 *
 * @see contracts/ecies-asn1.md
 */
public final class ElcCryptogram {

    private static final int APP_TAG_PUBLIC_KEY = 73; // 0x7F49 public-key data object
    private static final int CTX_TAG_FIELD = 6;       // 0x86 used for PO and C
    private static final int CTX_TAG_MAC = 14;        // 0x8E used for T
    private static final int CTX_TAG_OUTER = 6;       // 0xA6 outer wrapper

    private final ASN1ObjectIdentifier curveOid;
    private final byte[] po;
    private final byte[] c;
    private final byte[] t;

    public ElcCryptogram(ASN1ObjectIdentifier curveOid, byte[] po, byte[] c, byte[] t) {
        this.curveOid = curveOid;
        this.po = po.clone();
        this.c = c.clone();
        this.t = t.clone();
    }

    public ASN1ObjectIdentifier curveOid() {
        return curveOid;
    }

    public byte[] po() {
        return po.clone();
    }

    public byte[] c() {
        return c.clone();
    }

    public byte[] t() {
        return t.clone();
    }

    /** DER-encode the {@code (PO, C, T)} structure exactly as a TI card expects it. */
    public byte[] toAsn1() {
        try {
            ASN1TaggedObject poField =
                    new DERTaggedObject(false, BERTags.CONTEXT_SPECIFIC, CTX_TAG_FIELD, new DEROctetString(po));
            ASN1TaggedObject publicKeyDo =
                    new DERTaggedObject(true, BERTags.APPLICATION, APP_TAG_PUBLIC_KEY, poField);
            ASN1TaggedObject cField =
                    new DERTaggedObject(false, BERTags.CONTEXT_SPECIFIC, CTX_TAG_FIELD, new DEROctetString(c));
            ASN1TaggedObject tField =
                    new DERTaggedObject(false, BERTags.CONTEXT_SPECIFIC, CTX_TAG_MAC, new DEROctetString(t));

            ASN1EncodableVector content = new ASN1EncodableVector();
            content.add(curveOid);
            content.add(publicKeyDo);
            content.add(cField);
            content.add(tField);

            // Implicitly tag the SEQUENCE so its 0x30 tag becomes the 0xA6 outer wrapper.
            ASN1TaggedObject outer =
                    new DERTaggedObject(false, BERTags.CONTEXT_SPECIFIC, CTX_TAG_OUTER, new DERSequence(content));
            return outer.getEncoded("DER");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode ELC cryptogram", e);
        }
    }

    /** Parse a card-compatible {@code (PO, C, T)} structure. */
    public static ElcCryptogram parse(byte[] der) {
        try {
            ASN1TaggedObject outer = ASN1TaggedObject.getInstance(der);
            ASN1Sequence seq = ASN1Sequence.getInstance(outer, false);
            if (seq.size() != 4) {
                throw new IllegalArgumentException("ELC cryptogram must have 4 fields, was " + seq.size());
            }
            ASN1ObjectIdentifier curveOid = ASN1ObjectIdentifier.getInstance(seq.getObjectAt(0));

            ASN1TaggedObject publicKeyDo = ASN1TaggedObject.getInstance(seq.getObjectAt(1));
            ASN1TaggedObject poField = ASN1TaggedObject.getInstance(publicKeyDo.getExplicitBaseObject());
            byte[] po = ASN1OctetString.getInstance(poField, false).getOctets();

            ASN1TaggedObject cField = ASN1TaggedObject.getInstance(seq.getObjectAt(2));
            byte[] c = ASN1OctetString.getInstance(cField, false).getOctets();

            ASN1TaggedObject tField = ASN1TaggedObject.getInstance(seq.getObjectAt(3));
            byte[] t = ASN1OctetString.getInstance(tField, false).getOctets();

            return new ElcCryptogram(curveOid, po, c, t);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed ELC cryptogram", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ElcCryptogram other)) {
            return false;
        }
        return curveOid.equals(other.curveOid)
                && Arrays.equals(po, other.po)
                && Arrays.equals(c, other.c)
                && Arrays.equals(t, other.t);
    }

    @Override
    public int hashCode() {
        int result = curveOid.hashCode();
        result = 31 * result + Arrays.hashCode(po);
        result = 31 * result + Arrays.hashCode(c);
        result = 31 * result + Arrays.hashCode(t);
        return result;
    }
}
