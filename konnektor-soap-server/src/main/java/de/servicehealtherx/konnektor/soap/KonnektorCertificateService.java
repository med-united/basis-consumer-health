package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.certificateservice.v6.CheckCertificateExpiration;
import de.gematik.ws.conn.certificateservice.v6.CheckCertificateExpirationResponse;
import de.gematik.ws.conn.certificateservice.v6.ReadCardCertificate;
import de.gematik.ws.conn.certificateservice.v6.ReadCardCertificateResponse;
import de.gematik.ws.conn.certificateservice.v6.VerificationResultType;
import de.gematik.ws.conn.certificateservice.v6.VerifyCertificate;
import de.gematik.ws.conn.certificateservice.v6.VerifyCertificateResponse;
import de.gematik.ws.conn.certificateservice.wsdl.v6_0.CertificateServicePortType;
import de.gematik.ws.conn.certificateservice.wsdl.v6_0.FaultMessage;
import de.gematik.ws.conn.certificateservicecommon.v2.CertRefEnum;
import de.gematik.ws.conn.certificateservicecommon.v2.X509DataInfoListType;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.CertificateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@ApplicationScoped
@WebService(
    portName = "CertificateServicePort",
    serviceName = "CertificateService",
    targetNamespace = "http://ws.gematik.de/conn/CertificateService/WSDL/v6.0",
    endpointInterface = "de.gematik.ws.conn.certificateservice.wsdl.v6_0.CertificateServicePortType"
)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorCertificateService implements CertificateServicePortType {

    @Inject
    CertificateService certificateService;

    @Override
    public CheckCertificateExpirationResponse checkCertificateExpiration(CheckCertificateExpiration parameter)
            throws FaultMessage {
        CheckCertificateExpirationResponse response = new CheckCertificateExpirationResponse();
        response.setStatus(okStatus());
        return response;
    }

    @Override
    public ReadCardCertificateResponse readCardCertificate(ReadCardCertificate parameter) throws FaultMessage {
        try {
            CertRefEnum firstRef = parameter.getCertRefList() != null
                && !parameter.getCertRefList().getCertRef().isEmpty()
                ? parameter.getCertRefList().getCertRef().get(0)
                : CertRefEnum.C_AUT;

            KeyAlias alias = toKeyAlias(parameter.getCardHandle());
            CertificateService.CertRef certRef = toCertRef(firstRef);
            CertificateService.CryptAlgorithm crypt = toCryptAlgorithm(
                parameter.getCrypt() != null ? parameter.getCrypt().value() : null);

            CertificateService.ReadCertRequest req = new CertificateService.ReadCertRequest(
                alias, certRef, crypt, "konnektor-soap");

            byte[] certDer = certificateService.readCertificate(req);

            ReadCardCertificateResponse response = new ReadCardCertificateResponse();
            response.setStatus(okStatus());
            response.setX509DataInfoList(buildX509DataInfoList(certDer, firstRef));
            return response;
        } catch (Exception e) {
            throw new FaultMessage("ReadCardCertificate failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public VerifyCertificateResponse verifyCertificate(VerifyCertificate parameter) throws FaultMessage {
        try {
            X509Certificate cert = parseCertificate(parameter.getX509Certificate());
            CertificateService.VerifyCertResult result =
                certificateService.verifyCertificate(cert, "konnektor-soap");

            VerifyCertificateResponse response = new VerifyCertificateResponse();
            response.setStatus(okStatus());

            VerifyCertificateResponse.VerificationStatus verStatus =
                new VerifyCertificateResponse.VerificationStatus();
            verStatus.setVerificationResult(toVerificationResultType(result.result()));
            response.setVerificationStatus(verStatus);
            response.setRoleList(new VerifyCertificateResponse.RoleList());

            return response;
        } catch (Exception e) {
            throw new FaultMessage("VerifyCertificate failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static VerificationResultType toVerificationResultType(String result) {
        return switch (result) {
            case "VALID" -> VerificationResultType.VALID;
            case "INVALID" -> VerificationResultType.INVALID;
            default -> VerificationResultType.INCONCLUSIVE;
        };
    }

    private static CertificateService.CertRef toCertRef(CertRefEnum ref) {
        if (ref == null) return CertificateService.CertRef.C_AUT;
        return switch (ref.value()) {
            case "C.OSIG" -> CertificateService.CertRef.C_OSIG;
            default -> CertificateService.CertRef.C_AUT;
        };
    }

    private static CertificateService.CryptAlgorithm toCryptAlgorithm(String crypt) {
        if ("ECC".equalsIgnoreCase(crypt)) return CertificateService.CryptAlgorithm.ECC;
        return CertificateService.CryptAlgorithm.RSA;
    }

    private static X509DataInfoListType buildX509DataInfoList(byte[] certDer, CertRefEnum certRef) {
        X509DataInfoListType list = new X509DataInfoListType();
        X509DataInfoListType.X509DataInfo info = new X509DataInfoListType.X509DataInfo();
        info.setCertRef(certRef);
        X509DataInfoListType.X509DataInfo.X509Data data = new X509DataInfoListType.X509DataInfo.X509Data();
        data.setX509Certificate(certDer);
        info.setX509Data(data);
        list.getX509DataInfo().add(info);
        return list;
    }

    private static X509Certificate parseCertificate(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(der));
    }
}
