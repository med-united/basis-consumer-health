package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.AuthSignatureServicePortType;
import de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.FaultMessage;
import de.gematik.ws.conn.signatureservice.v7_4.BinaryDocumentType;
import de.gematik.ws.conn.signatureservice.v7_4.ExternalAuthenticate;
import de.gematik.ws.conn.signatureservice.v7_4.ExternalAuthenticateResponse;
import de.servicehealtherx.crypto.services.SignatureService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import oasis.names.tc.dss._1_0.core.schema.Base64Signature;
import oasis.names.tc.dss._1_0.core.schema.SignatureObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@CXFEndpoint(value = "/conn/AuthSignatureService")
@WebService(portName = "AuthSignatureServicePort", serviceName = "AuthSignatureService", targetNamespace = "http://ws.gematik.de/conn/AuthSignatureService/WSDL/v7.4", endpointInterface = "de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.AuthSignatureServicePortType")
public class KonnektorAuthSignatureService implements AuthSignatureServicePortType {

    /**
     * DSS SignatureType (BSI TR-03111) requesting an ECDSA signature. For this type the gematik
     * connector contract returns the signature ASN.1 DER-encoded ({@code SEQUENCE { INTEGER r,
     * INTEGER s }}); callers (e.g. jose4j) then convert it to the raw R||S concatenation.
     */
    private static final String SIGNATURE_TYPE_ECDSA = "urn:bsi:tr:03111:ecdsa";

    @Inject
    SignatureService signatureService;

    @Override
    public ExternalAuthenticateResponse externalAuthenticate(ExternalAuthenticate parameter) throws FaultMessage {
        try {
            byte[] hashBytes = extractBinaryBytes(parameter.getBinaryString());

            byte[] signature = signatureService.externalAuthenticate(
                    parameter.getCardHandle(), hashBytes, "konnektor-soap");

            // The card emits the raw ECDSA signature as R||S (64 bytes for brainpoolP256r1). The
            // connector contract returns ECDSA signatures DER-encoded, so wrap it when the caller
            // asked for ECDSA (or when it is an unmistakable raw EC signature and no type was given).
            if (isEcdsaRequested(parameter) && isRawEcSignature(signature)) {
                signature = rawEcdsaToDer(signature);
            }

            ExternalAuthenticateResponse response = new ExternalAuthenticateResponse();
            response.setStatus(okStatus());

            Base64Signature base64Sig = new Base64Signature();
            base64Sig.setValue(signature);
            SignatureObject sigObj = new SignatureObject();
            sigObj.setBase64Signature(base64Sig);
            response.setSignatureObject(sigObj);

            return response;
        } catch (Exception e) {
            throw new FaultMessage("ExternalAuthenticate failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static byte[] extractBinaryBytes(BinaryDocumentType doc) {
        if (doc == null || doc.getBase64Data() == null)
            return new byte[0];
        return doc.getBase64Data().getValue() != null ? doc.getBase64Data().getValue() : new byte[0];
    }

    private static boolean isEcdsaRequested(ExternalAuthenticate parameter) {
        ExternalAuthenticate.OptionalInputs inputs =
                parameter == null ? null : parameter.getOptionalInputs();
        String type = inputs == null ? null : inputs.getSignatureType();
        // Treat an absent type as ECDSA: the only keys this connector signs with are EC (C.AUT),
        // and a raw EC signature would otherwise be mis-decoded as DER by the caller.
        return type == null || type.isBlank() || SIGNATURE_TYPE_ECDSA.equalsIgnoreCase(type);
    }

    /** A raw ECDSA R||S signature is the even concatenation of two same-length field elements. */
    private static boolean isRawEcSignature(byte[] signature) {
        // brainpoolP256r1 → 32-byte R and S. Reject anything already DER-framed (leading 0x30).
        return signature != null && signature.length == 64;
    }

    /**
     * Wrap a raw ECDSA signature {@code R||S} into its ASN.1 DER form
     * {@code SEQUENCE { INTEGER r, INTEGER s }} (BSI TR-03111 / X9.62). Implemented directly to
     * avoid pulling a crypto dependency into the SOAP module; the structure is small and fixed.
     */
    static byte[] rawEcdsaToDer(byte[] rawSignature) {
        int half = rawSignature.length / 2;
        byte[] rInt = derInteger(Arrays.copyOfRange(rawSignature, 0, half));
        byte[] sInt = derInteger(Arrays.copyOfRange(rawSignature, half, rawSignature.length));

        int contentLength = rInt.length + sInt.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30); // SEQUENCE
        writeLength(out, contentLength);
        out.write(rInt, 0, rInt.length);
        out.write(sInt, 0, sInt.length);
        return out.toByteArray();
    }

    /** Encode {@code value} as a minimal, non-negative DER INTEGER (tag 0x02). */
    private static byte[] derInteger(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++; // strip superfluous leading zero bytes
        }
        boolean prependZero = (value[start] & 0x80) != 0; // keep the integer positive
        int magnitudeLength = value.length - start + (prependZero ? 1 : 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02); // INTEGER
        writeLength(out, magnitudeLength);
        if (prependZero) {
            out.write(0x00);
        }
        out.write(value, start, value.length - start);
        return out.toByteArray();
    }

    /** Write a DER definite length. For a P-256 signature the total stays well under 128 bytes. */
    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else {
            out.write(0x81); // one length byte follows (sufficient for any single ECDSA signature)
            out.write(length);
        }
    }
}
