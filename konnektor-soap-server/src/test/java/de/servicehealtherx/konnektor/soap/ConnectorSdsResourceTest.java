package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.gematik.ws.conn.servicedirectory.v3.ConnectorServices;
import de.gematik.ws.conn.serviceinformation.v2.ServiceType;
import de.gematik.ws.conn.serviceinformation.v2.VersionType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.xml.bind.JAXBContext;
import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the connector.sds Dienstverzeichnisdienst document (gemSpec_Kon 4.1.3): it must
 * validate against ServiceDirectory.xsd and link every published SOAP service to its TLS
 * endpoint and WSDL. The resource is driven directly (no Quarkus boot) — config fields are set
 * explicitly, mirroring the plain JUnit + Mockito style of the other tests in this module.
 */
class ConnectorSdsResourceTest {

    private ConnectorSdsResource resource;

    @BeforeEach
    void setUp() {
        resource = new ConnectorSdsResource();
        resource.cxfPath = "/ws";
        resource.baseUrlOverride = Optional.empty();
        resource.tlsMandatory = true;
        resource.clientAutMandatory = true;
        resource.productType = "Konnektor";
        resource.productTypeVersion = "5.27.0";
        resource.productVendorId = "SHRX";
        resource.productCode = "BCHCONN";
        resource.productVersion = "1.0.0";
        resource.productVendorName = "ServiceHealtheRX";
        resource.productName = "Basis Consumer Health Konnektor";
    }

    private String invoke(String baseUri) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create(baseUri));
        Response response = resource.getServiceDirectory(uriInfo);
        assertEquals(200, response.getStatus());
        return (String) response.getEntity();
    }

    @Test
    void document_validates_against_service_directory_schema() throws Exception {
        String xml = invoke("http://localhost:8080/");

        URI schemaUrl = getClass().getResource("/conn/ServiceDirectory.xsd").toURI();
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(schemaUrl.toURL());
        Validator validator = schema.newValidator();
        // Throws SAXException if the payload does not conform — the core correctness check.
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    @Test
    void lists_all_services_with_tls_endpoint_and_wsdl_links() throws Exception {
        String xml = invoke("http://localhost:8080/");

        JAXBContext context = JAXBContext.newInstance(ConnectorServices.class);
        ConnectorServices services = (ConnectorServices) context.createUnmarshaller()
                .unmarshal(new StringReader(xml));

        assertNotNull(services.getProductInformation());
        assertTrue(services.isTLSMandatory());
        assertTrue(services.isClientAutMandatory());

        List<ServiceType> list = services.getServiceInformation().getService();
        assertEquals(8, list.size(), "expected all eight CXF conn services");

        ServiceType card = list.stream().filter(s -> s.getName().equals("CardService")).findFirst()
                .orElseThrow();
        VersionType version = card.getVersions().getVersion().get(0);
        assertEquals("http://ws.gematik.de/conn/CardService/WSDL/v8.1", version.getTargetNamespace());
        assertEquals("http://localhost:8080/ws/conn/CardService",
                version.getEndpointTLS().getLocation());
        assertEquals("http://localhost:8080/ws/conn/CardService?wsdl", version.getWSDL().getLocation());

        // Every service carries an abstract, a TLS endpoint and a WSDL link.
        for (ServiceType service : list) {
            VersionType v = service.getVersions().getVersion().get(0);
            assertNotNull(service.getAbstract());
            assertTrue(v.getEndpointTLS().getLocation().endsWith("/ws/conn/" + service.getName()));
            assertTrue(v.getWSDL().getLocation().endsWith("/ws/conn/" + service.getName() + "?wsdl"));
        }
    }

    @Test
    void base_url_override_is_used_for_endpoint_urls() throws Exception {
        resource.baseUrlOverride = Optional.of("https://konnektor.example.com");
        String xml = invoke("http://internal-host:8080/");

        JAXBContext context = JAXBContext.newInstance(ConnectorServices.class);
        ConnectorServices services = (ConnectorServices) context.createUnmarshaller()
                .unmarshal(new StringReader(xml));

        VersionType version = services.getServiceInformation().getService().get(0).getVersions()
                .getVersion().get(0);
        assertTrue(version.getEndpointTLS().getLocation()
                .startsWith("https://konnektor.example.com/ws/conn/"));
    }
}
