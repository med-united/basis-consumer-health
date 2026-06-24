package de.servicehealtherx.konnektor.soap;

import de.gematik.ws._int.version.productinformation.v1.ProductIdentification;
import de.gematik.ws._int.version.productinformation.v1.ProductInformation;
import de.gematik.ws._int.version.productinformation.v1.ProductMiscellaneous;
import de.gematik.ws._int.version.productinformation.v1.ProductTypeInformation;
import de.gematik.ws._int.version.productinformation.v1.ProductVersion;
import de.gematik.ws.conn.servicedirectory.v3.ConnectorServices;
import de.gematik.ws.conn.serviceinformation.v2.EndpointType;
import de.gematik.ws.conn.serviceinformation.v2.ServiceType;
import de.gematik.ws.conn.serviceinformation.v2.ServicesType;
import de.gematik.ws.conn.serviceinformation.v2.VersionType;
import de.gematik.ws.conn.serviceinformation.v2.VersionsType;
import de.gematik.ws.conn.serviceinformation.v2.WSDLLocationType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.net.URI;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Serves the connector's <em>Dienstverzeichnisdienst</em> document (gemSpec_Kon V5.27.0, §4.1.3)
 * at {@code GET /connector.sds}. A primary system fetches this document to discover the SOAP
 * services this connector offers together with their TLS endpoints and WSDL locations.
 *
 * <p>The payload is a {@code ConnectorServices} document (root of
 * {@code api-telematik/conn/ServiceDirectory.xsd} v3.1), wrapping {@code ProductInformation},
 * the {@code TLSMandatory}/{@code ClientAutMandatory} flags and the {@code ServiceInformation}
 * list defined by {@code ServiceInformation.xsd} v2.0.
 *
 * <p>The listed services mirror the {@code @CXFEndpoint}/{@code @WebService} annotations on the
 * {@code Konnektor*Service} classes (the source of truth) — published under
 * {@code <quarkus.cxf.path>/conn/<ServiceName>}. Endpoint and WSDL URLs are derived from the
 * incoming request; {@code connector.sds.base-url} overrides the base for deployments behind a
 * TLS-terminating proxy where the request scheme/host differs from the externally reachable one.
 */
@Path("/connector.sds")
public class ConnectorSdsResource {

    /**
     * The connector's SOAP services, taken verbatim from the {@code @WebService}/{@code @CXFEndpoint}
     * annotations on the {@code Konnektor*Service} implementations. {@code serviceName} doubles as the
     * endpoint path segment ({@code /conn/<serviceName>}).
     */
    private record ServiceCatalogEntry(String serviceName, String version, String targetNamespace,
            String abstractText) {
    }

    private static final List<ServiceCatalogEntry> CATALOG = List.of(
            new ServiceCatalogEntry("CardService", "8.2.1",
                    "http://ws.gematik.de/conn/CardService/WSDL/v8.2",
                    "Kartenbezogene Operationen: PIN-Verifikation, PIN-Änderung, Freischaltung, SMC-Autorisierung."),
            new ServiceCatalogEntry("CardTerminalService", "1.1.0",
                    "http://ws.gematik.de/conn/CardTerminalService/WSDL/v1.1",
                    "Steuerung der Kartenterminals: Karte anfordern und auswerfen."),
            new ServiceCatalogEntry("CertificateService", "6.0.3",
                    "http://ws.gematik.de/conn/CertificateService/WSDL/v6.0",
                    "Auslesen und Prüfen von Zertifikaten der gesteckten Karten."),
            new ServiceCatalogEntry("EncryptionService", "6.1.2",
                    "http://ws.gematik.de/conn/EncryptionService/WSDL/v6.1",
                    "Ver- und Entschlüsselung von Dokumenten."),
            new ServiceCatalogEntry("SignatureService", "7.5.7",
                    "http://ws.gematik.de/conn/SignatureService/WSDL/v7.5",
                    "Erstellung und Prüfung elektronischer Signaturen, Komfortsignatur."),
            new ServiceCatalogEntry("AuthSignatureService", "7.4.1",
                    "http://ws.gematik.de/conn/AuthSignatureService/WSDL/v7.4",
                    "Externe Authentisierung (Signatur von Authentisierungs-Token)."),
            new ServiceCatalogEntry("EventService", "7.2.0",
                    "http://ws.gematik.de/conn/EventService/WSDL/v7.2",
                    "Ereignisdienst: Karten- und Terminalstatus, Ressourceninformationen, Subskriptionen."),
            new ServiceCatalogEntry("VSDService", "5.2.0",
                    "http://ws.gematik.de/conn/vsds/VSDService/v5.2",
                    "Versichertenstammdatenmanagement (ReadVSD)."));

    @ConfigProperty(name = "quarkus.cxf.path", defaultValue = "/ws")
    String cxfPath;

    @ConfigProperty(name = "connector.sds.base-url")
    Optional<String> baseUrlOverride;

    @ConfigProperty(name = "connector.sds.tls-mandatory", defaultValue = "true")
    boolean tlsMandatory;

    @ConfigProperty(name = "connector.sds.client-aut-mandatory", defaultValue = "true")
    boolean clientAutMandatory;

    @ConfigProperty(name = "connector.sds.product.type", defaultValue = "Konnektor")
    String productType;

    @ConfigProperty(name = "connector.sds.product.type-version", defaultValue = "5.27.0")
    String productTypeVersion;

    @ConfigProperty(name = "connector.sds.product.vendor-id", defaultValue = "SHRX")
    String productVendorId;

    @ConfigProperty(name = "connector.sds.product.code", defaultValue = "BCHCONN")
    String productCode;

    @ConfigProperty(name = "connector.sds.product.version", defaultValue = "1.0.0")
    String productVersion;

    @ConfigProperty(name = "connector.sds.product.vendor-name", defaultValue = "ServiceHealtheRX")
    String productVendorName;

    @ConfigProperty(name = "connector.sds.product.name", defaultValue = "Basis Consumer Health Konnektor")
    String productName;

    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response getServiceDirectory(@Context UriInfo uriInfo) {
        ConnectorServices connectorServices = buildConnectorServices(resolveBaseUrl(uriInfo));
        return Response.ok(marshal(connectorServices)).build();
    }

    /**
     * Resolves the externally reachable base URL ({@code scheme://host[:port]}, no trailing slash).
     * The {@code connector.sds.base-url} override wins; otherwise the scheme/authority of the
     * incoming request is used.
     */
    private String resolveBaseUrl(UriInfo uriInfo) {
        if (baseUrlOverride.isPresent() && !baseUrlOverride.get().isBlank()) {
            return stripTrailingSlash(baseUrlOverride.get().trim());
        }
        URI base = uriInfo.getBaseUri();
        StringBuilder sb = new StringBuilder(base.getScheme()).append("://").append(base.getHost());
        if (base.getPort() != -1) {
            sb.append(':').append(base.getPort());
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private ConnectorServices buildConnectorServices(String baseUrl) {
        ConnectorServices connectorServices = new ConnectorServices();
        connectorServices.setProductInformation(buildProductInformation());
        connectorServices.setTLSMandatory(tlsMandatory);
        connectorServices.setClientAutMandatory(clientAutMandatory);
        connectorServices.setServiceInformation(buildServiceInformation(baseUrl));
        return connectorServices;
    }

    private ServicesType buildServiceInformation(String baseUrl) {
        String soapBase = baseUrl + cxfPath + "/conn";
        ServicesType services = new ServicesType();
        for (ServiceCatalogEntry entry : CATALOG) {
            String endpointUrl = soapBase + "/" + entry.serviceName();

            EndpointType endpoint = new EndpointType();
            endpoint.setLocation(endpointUrl);

            EndpointType endpointTls = new EndpointType();
            endpointTls.setLocation(endpointUrl);

            WSDLLocationType wsdl = new WSDLLocationType();
            wsdl.setLocation(endpointUrl + "?wsdl");

            VersionType version = new VersionType();
            version.setAbstract(entry.abstractText());
            version.setTargetNamespace(entry.targetNamespace());
            version.setVersion(entry.version());
            version.setEndpoint(endpoint);
            version.setEndpointTLS(endpointTls);
            version.setWSDL(wsdl);

            VersionsType versions = new VersionsType();
            versions.getVersion().add(version);

            ServiceType service = new ServiceType();
            service.setName(entry.serviceName());
            service.setAbstract(entry.abstractText());
            service.setVersions(versions);

            services.getService().add(service);
        }
        return services;
    }

    private ProductInformation buildProductInformation() {
        ProductTypeInformation typeInformation = new ProductTypeInformation();
        typeInformation.setProductType(productType);
        typeInformation.setProductTypeVersion(productTypeVersion);

        ProductVersion version = new ProductVersion();
        version.setCentral(productVersion);

        ProductIdentification identification = new ProductIdentification();
        identification.setProductVendorID(productVendorId);
        identification.setProductCode(productCode);
        identification.setProductVersion(version);

        ProductMiscellaneous miscellaneous = new ProductMiscellaneous();
        miscellaneous.setProductVendorName(productVendorName);
        miscellaneous.setProductName(productName);

        ProductInformation productInformation = new ProductInformation();
        productInformation.setInformationDate(now());
        productInformation.setProductTypeInformation(typeInformation);
        productInformation.setProductIdentification(identification);
        productInformation.setProductMiscellaneous(miscellaneous);
        return productInformation;
    }

    private static javax.xml.datatype.XMLGregorianCalendar now() {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("Cannot create XML datatype factory", e);
        }
    }

    private static String marshal(ConnectorServices connectorServices) {
        try {
            JAXBContext context = JAXBContext.newInstance(ConnectorServices.class, ServicesType.class,
                    ProductInformation.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            StringWriter writer = new StringWriter();
            marshaller.marshal(connectorServices, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot marshal connector.sds document", e);
        }
    }
}
