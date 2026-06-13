package de.servicehealtherx.consumer.soap;

import de.gematik.ws.consumer.certificateservice.v3.ReadCertificate;
import de.gematik.ws.consumer.certificateservice.v3.ReadCertificateResponse;
import de.gematik.ws.consumer.certificateservice.v3.VerifyCertificate;
import de.gematik.ws.consumer.certificateservice.v3.VerifyCertificateResponse;
import de.gematik.ws.consumer.certificateservice.v3.VerificationResultType;
import de.gematik.ws.consumer.certificateservicecommon.v2.CertRefEnum;
import de.gematik.ws.consumer.certificateservicecommon.v2.X509DataInfoListType;
import de.gematik.ws.consumer.certificateservice.wsdl.v3_0.CertificateServicePortType;
import de.gematik.ws.consumer.certificateservice.wsdl.v3_0.FaultMessage;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.CertificateService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

import java.security.cert.X509Certificate;

import static de.servicehealtherx.consumer.soap.ConsumerServiceHelper.*;

@CXFEndpoint(value = "/consumer/CertificateService")
@WebService(portName = "CertificateServicePort", serviceName = "CertificateService", targetNamespace = "http://ws.gematik.de/consumer/CertificateService/WSDL/v3.0", wsdlLocation = "classpath:/consumer/CertificateService.wsdl", endpointInterface = "de.gematik.ws.consumer.certificateservice.wsdl.v3_0.CertificateServicePortType")
public class ConsumerCertificateService implements CertificateServicePortType {

    @PostConstruct
    public void init() {
        System.out.println("ConsumerCertificateService initialized");
    }

    @Inject
    CertificateService certificateService;

    @Override
    public VerifyCertificateResponse verifyCertificate(VerifyCertificate parameter) throws FaultMessage {
        try {
            X509Certificate cert = parseCertificate(parameter.getX509Certificate());
            CertificateService.VerifyCertResult result = certificateService.verifyCertificate(cert, "consumer-soap");

            VerifyCertificateResponse response = new VerifyCertificateResponse();
            response.setStatus(okStatus());

            VerifyCertificateResponse.VerificationStatus verStatus = new VerifyCertificateResponse.VerificationStatus();
            verStatus.setVerificationResult(toVerificationResultType(result.result()));
            response.setVerificationStatus(verStatus);

            VerifyCertificateResponse.RoleList roleList = new VerifyCertificateResponse.RoleList();
            response.setRoleList(roleList);

            return response;
        } catch (FaultMessage e) {
            throw e;
        } catch (Exception e) {
            throw new FaultMessage("VerifyCertificate failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public ReadCertificateResponse readCertificate(ReadCertificate parameter) throws FaultMessage {
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
                    alias, certRef, crypt, "consumer-soap");

            byte[] certDer = certificateService.readCertificate(req);

            ReadCertificateResponse response = new ReadCertificateResponse();
            response.setStatus(okStatus());
            response.setX509DataInfoList(buildX509DataInfoList(certDer));
            return response;
        } catch (Exception e) {
            throw new FaultMessage("ReadCertificate failed: " + e.getMessage(), buildError(e.getMessage()));
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
        if (ref == null)
            return CertificateService.CertRef.C_AUT;
        return switch (ref.value()) {
            case "C.OSIG" -> CertificateService.CertRef.C_OSIG;
            default -> CertificateService.CertRef.C_AUT;
        };
    }

    private static CertificateService.CryptAlgorithm toCryptAlgorithm(String crypt) {
        if ("ECC".equalsIgnoreCase(crypt))
            return CertificateService.CryptAlgorithm.ECC;
        return CertificateService.CryptAlgorithm.RSA;
    }

    private static X509DataInfoListType buildX509DataInfoList(byte[] certDer) {
        X509DataInfoListType list = new X509DataInfoListType();
        X509DataInfoListType.X509DataInfo info = new X509DataInfoListType.X509DataInfo();
        info.setCertRef(CertRefEnum.C_AUT);
        X509DataInfoListType.X509DataInfo.X509Data data = new X509DataInfoListType.X509DataInfo.X509Data();
        data.setX509Certificate(certDer);
        info.setX509Data(data);
        list.getX509DataInfo().add(info);
        return list;
    }
}
