package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.delivery.EventXmlWriter;
import de.servicehealtherx.cetp.subscription.Subscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.List;
import org.w3c.dom.NodeList;

/**
 * XPath filter (TUC_KON_256 step 5c): keep the subscription iff its XPath {@code filter} evaluated
 * against the event XML yields a non-empty result. A missing/blank filter always keeps the
 * subscription. An XPath compile/evaluation error excludes only this subscription (gemSpec_Kon
 * error 4095) and never aborts the event for other subscriptions.
 *
 * <p>The XPath namespace context maps both the default and the {@code EVT} prefix to the
 * EventService v7.2 namespace, per TIP1-A_4608.
 */
@ApplicationScoped
public class XPathExpressionFilter implements EventDeliveryFilter {

    private static final Logger LOG = Logger.getLogger(XPathExpressionFilter.class);
    private static final String EVENT_NS = "http://ws.gematik.de/conn/EventService/v7.2";

    @Inject
    EventXmlWriter eventXmlWriter;

    @Override
    public boolean keep(KonnektorSystemEvent event, Subscription subscription) {
        String filter = subscription.filter;
        if (filter == null || filter.isBlank()) {
            return true;
        }
        try {
            Document document = parse(eventXmlWriter.toEventXml(event, subscription.subscriptionId.toString()));
            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(eventNamespaceContext());
            XPathExpression expression = xpath.compile(filter);
            NodeList result = (NodeList) expression.evaluate(document, XPathConstants.NODESET);
            return result.getLength() > 0;
        } catch (Exception e) {
            // Error 4095 — applies only to this subscription's filter evaluation (FR-002).
            LOG.warnf(e, "XPath filter evaluation failed for subscription %s (topic %s); excluding it",
                    subscription.subscriptionId, subscription.topic);
            return false;
        }
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    private static NamespaceContext eventNamespaceContext() {
        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                if ("EVT".equals(prefix) || XMLConstants.DEFAULT_NS_PREFIX.equals(prefix)) {
                    return EVENT_NS;
                }
                return XMLConstants.NULL_NS_URI;
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return EVENT_NS.equals(namespaceURI) ? "EVT" : null;
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return List.of("EVT").iterator();
            }
        };
    }
}
