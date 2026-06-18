package de.servicehealtherx.konnektor.soap;

import de.gematik.ws._int.version.productinformation.v1.ProductInformation;
import de.gematik.ws.conn.cardservice.v8.Cards;
import de.gematik.ws.conn.cardterminalinfo.v8.CardTerminalInfoType;
import de.gematik.ws.conn.cardterminalinfo.v8.CardTerminals;
import de.gematik.ws.conn.eventservice.v7.GetCards;

import de.gematik.ws.conn.eventservice.v7.GetCardsResponse;
import de.gematik.ws.conn.cardservicecommon.v2.CardTypeType;
import de.gematik.ws.conn.cardservice.v8.CardInfoType;
import de.gematik.ws.conn.cardservice.v8.VersionInfoType;
import de.gematik.ws.conn.eventservice.v7.GetCardTerminals;
import de.gematik.ws.conn.eventservice.v7.GetCardTerminalsResponse;
import de.gematik.ws.conn.eventservice.v7.GetResourceInformation;
import de.gematik.ws.conn.eventservice.v7.GetResourceInformationResponse;
import de.gematik.ws.conn.eventservice.v7.GetSubscription;
import de.gematik.ws.conn.eventservice.v7.GetSubscriptionResponse;
import de.gematik.ws.conn.eventservice.v7.RenewSubscriptions;
import de.gematik.ws.conn.eventservice.v7.RenewSubscriptionsResponse;
import de.gematik.ws.conn.eventservice.v7.Subscribe;
import de.gematik.ws.conn.eventservice.v7.SubscribeResponse;
import de.gematik.ws.conn.eventservice.v7.Unsubscribe;
import de.gematik.ws.conn.eventservice.v7.UnsubscribeResponse;
import de.gematik.ws.conn.eventservice.wsdl.v7_2.EventServicePortType;
import de.gematik.ws.conn.eventservice.wsdl.v7_2.FaultMessage;
import de.gematik.ws.conn.connectorcontext.v2.ContextType;
import de.gematik.ws.conn.eventservice.v7.SubscriptionRenewal;
import de.gematik.ws.conn.eventservice.v7.SubscriptionType;
import de.servicehealtherx.cetp.subscription.SubscriptionService;
import de.servicehealtherx.cetp.subscription.SubscriptionService.Renewal;
import de.servicehealtherx.cetp.subscription.SubscriptionService.SubscriptionView;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.apdu.card.CardListAggregator;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardVersionInfo;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@CXFEndpoint(value = "/conn/EventService")
@WebService(portName = "EventServicePort", serviceName = "EventService", targetNamespace = "http://ws.gematik.de/conn/EventService/WSDL/v7.2", endpointInterface = "de.gematik.ws.conn.eventservice.wsdl.v7_2.EventServicePortType")
public class KonnektorEventService implements EventServicePortType {

    @Inject
    SicctTerminalManager sicctTerminalManager;

    @Inject
    Instance<CryptoProvider> cryptoProviderInstances;

    @Inject
    SubscriptionService subscriptionService;

    @Override
    public SubscribeResponse subscribe(Subscribe parameter) throws FaultMessage {
        try {
            ContextType context = parameter.getContext();
            SubscriptionType subscription = parameter.getSubscription();
            // checkArguments / saveSubscription (gemSpec_Kon TIP1-A_4608): persist via cetp-client-lib.
            SubscriptionService.SubscribeResult result = subscriptionService.subscribe(
                    context.getMandantId(), context.getClientSystemId(), context.getWorkplaceId(),
                    subscription.getEventTo(), subscription.getTopic(), subscription.getFilter());

            SubscribeResponse response = new SubscribeResponse();
            response.setStatus(okStatus());
            response.setSubscriptionID(result.subscriptionId().toString());
            response.setTerminationTime(toXmlDateTime(result.terminationTime()));
            return response;
        } catch (IllegalArgumentException e) {
            // Syntax error 4000 (invalid EventTo etc.)
            throw new FaultMessage("Subscribe failed: " + e.getMessage(), buildError(e.getMessage()));
        } catch (Exception e) {
            throw new FaultMessage("Subscribe failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetResourceInformationResponse getResourceInformation(GetResourceInformation parameter)
            throws FaultMessage {
        try {
            GetResourceInformationResponse response = new GetResourceInformationResponse();
            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetResourceInformation failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetSubscriptionResponse getSubscription(GetSubscription parameter) throws FaultMessage {
        try {
            GetSubscriptionResponse response = new GetSubscriptionResponse();
            response.setStatus(okStatus());
            GetSubscriptionResponse.Subscriptions subscriptions = new GetSubscriptionResponse.Subscriptions();
            for (SubscriptionView view : subscriptionService.getSubscriptions()) {
                SubscriptionType type = new SubscriptionType();
                type.setSubscriptionID(view.subscriptionId().toString());
                type.setEventTo(view.eventTo());
                type.setTopic(view.topic());
                type.setFilter(view.filter());
                subscriptions.getSubscription().add(type);
            }
            response.setSubscriptions(subscriptions);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetSubscription failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public UnsubscribeResponse unsubscribe(Unsubscribe parameter) throws FaultMessage {
        try {
            UUID subscriptionId = parameter.getSubscriptionID() != null && !parameter.getSubscriptionID().isBlank()
                    ? UUID.fromString(parameter.getSubscriptionID())
                    : null;
            subscriptionService.unsubscribe(subscriptionId, parameter.getEventTo());

            UnsubscribeResponse response = new UnsubscribeResponse();
            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("Unsubscribe failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetCardTerminalsResponse getCardTerminals(GetCardTerminals parameter) throws FaultMessage {
        try {
            GetCardTerminalsResponse response = new GetCardTerminalsResponse();

            response.setCardTerminals(new CardTerminals());

            sicctTerminalManager.listAllTerminals().stream().map(t -> {
                CardTerminalInfoType ct = new CardTerminalInfoType();
                // TODO: Map other fields as needed
                ct.setProductInformation(new ProductInformation());
                ct.setCtId(t.ctid.toString());
                ct.setName(t.name);
                return ct;
            }).forEach(ct -> response.getCardTerminals().getCardTerminal().add(ct));

            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetCardTerminals failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public RenewSubscriptionsResponse renewSubscriptions(RenewSubscriptions parameter) throws FaultMessage {
        try {
            RenewSubscriptionsResponse response = new RenewSubscriptionsResponse();
            response.setStatus(okStatus());
            RenewSubscriptionsResponse.SubscribeRenewals renewals = new RenewSubscriptionsResponse.SubscribeRenewals();
            for (Renewal renewal : subscriptionService.renew()) {
                SubscriptionRenewal sr = new SubscriptionRenewal();
                sr.setSubscriptionID(renewal.subscriptionId().toString());
                sr.setTerminationTime(toXmlDateTime(renewal.terminationTime()));
                renewals.getSubscriptionRenewal().add(sr);
            }
            response.setSubscribeRenewals(renewals);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("RenewSubscriptions failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetCardsResponse getCards(GetCards parameter) throws FaultMessage {
        try {
            GetCardsResponse response = new GetCardsResponse();
            response.setCards(new Cards());

            // Unified, transport-spanning view: aggregate every provider's own CM_CARD_LIST
            // (PC/SC + SICCT), de-duplicated by cardHandle (feature 002-card-handle,
            // FR-062/FR-064).
            CardListAggregator aggregator = new CardListAggregator();
            for (CryptoProvider cryptoProvider : cryptoProviderInstances) {
                if (cryptoProvider instanceof CardListProvider clp) {
                    aggregator.addSource(clp.cmCardList());
                } else {
                    // Non-PC/SC providers (e.g. SICCT) can still contribute via a custom adapter.
                    aggregator.addCryptoProvider(cryptoProvider);
                }
            }
            aggregator.findAll()
                    .forEach(card -> response.getCards().getCard().add(toCardInfoType(card)));

            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetCards failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    /**
     * Map a transport-neutral CM_CARD_LIST entry to the gematik CardInfoType
     * (FR-064).
     */
    public CardInfoType toCardInfoType(CardObject card) {
        CardInfoType info = new CardInfoType();
        info.setCardHandle(card.cardHandle());
        info.setCardType(toCardTypeType(card.type()));
        if (card.ctid() != null) {
            info.setCtId(card.ctid().toString());
        }
        // SlotId is a required positiveInteger; CardObject guarantees slotNo >= 1.
        info.setSlotId(BigInteger.valueOf(card.slotNo()));
        if (card.iccsn() != null) {
            info.setIccsn(card.iccsn());
        }
        if (card.insertTime() != null) {
            info.setInsertTime(toXmlDateTime(card.insertTime()));
        }
        if (card.cardHolderName() != null) {
            info.setCardHolderName(card.cardHolderName());
        }
        if (card.kvnr() != null) {
            info.setKvnr(card.kvnr());
        }
        if (card.certExpirationDate() != null) {
            info.setCertificateExpirationDate(toXmlDate(card.certExpirationDate()));
        }
        CardInfoType.CardVersion version = toCardVersion(card.cardVersion());
        if (version != null) {
            info.setCardVersion(version);
        }
        return info;
    }

    /**
     * Map the eight CARDVERSION sub-fields to the gematik {@code CardVersion} structure. Returns
     * {@code null} when no sub-field is readable so the optional element is simply omitted rather
     * than emitted with its required COSVersion/ObjectSystemVersion missing.
     */
    private static CardInfoType.CardVersion toCardVersion(CardVersionInfo v) {
        if (v == null) {
            return null;
        }
        VersionInfoType cos = toVersionInfo(v.cosVersion());
        VersionInfoType objectSystem = toVersionInfo(v.objectSystemVersion());
        VersionInfoType cardPTPers = toVersionInfo(v.cardPersonalizationVersion());
        VersionInfoType dataStructure = toVersionInfo(v.dataStructureVersion());
        VersionInfoType logging = toVersionInfo(v.loggingVersion());
        VersionInfoType atr = toVersionInfo(v.atrVersion());
        VersionInfoType gdo = toVersionInfo(v.gdoVersion());
        VersionInfoType keyInfo = toVersionInfo(v.keyInfoVersion());
        if (cos == null && objectSystem == null && cardPTPers == null && dataStructure == null
                && logging == null && atr == null && gdo == null && keyInfo == null) {
            return null;
        }
        CardInfoType.CardVersion version = new CardInfoType.CardVersion();
        version.setCOSVersion(cos);
        version.setObjectSystemVersion(objectSystem);
        version.setCardPTPersVersion(cardPTPers);
        version.setDataStructureVersion(dataStructure);
        version.setLoggingVersion(logging);
        version.setATRVersion(atr);
        version.setGDOVersion(gdo);
        version.setKeyInfoVersion(keyInfo);
        return version;
    }

    /**
     * Parse a dotted-decimal version string (as produced by CardAttributeReader, e.g. "3.0.0")
     * into Major/Minor/Revision. Missing components default to 0; non-numeric input yields
     * {@code null} so the field is omitted.
     */
    private static VersionInfoType toVersionInfo(String dotted) {
        if (dotted == null || dotted.isBlank()) {
            return null;
        }
        String[] parts = dotted.split("\\.");
        try {
            VersionInfoType info = new VersionInfoType();
            info.setMajor(parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0);
            info.setMinor(parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0);
            info.setRevision(parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0);
            return info;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static XMLGregorianCalendar toXmlDateTime(Instant instant) {
        GregorianCalendar cal = GregorianCalendar.from(instant.atZone(ZoneId.systemDefault()));
        return datatypeFactory().newXMLGregorianCalendar(cal);
    }

    private static XMLGregorianCalendar toXmlDate(LocalDate date) {
        return datatypeFactory().newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                DatatypeConstants.FIELD_UNDEFINED);
    }

    private static DatatypeFactory datatypeFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("Cannot create DatatypeFactory", e);
        }
    }

    private static CardTypeType toCardTypeType(de.servicehealtherx.apdu.model.CardType type) {
        return switch (type) {
            case EGK -> CardTypeType.EGK;
            case HBA -> CardTypeType.HBA;
            case HBAX -> CardTypeType.HB_AX;
            case SMC_B -> CardTypeType.SMC_B;
            case KVK -> CardTypeType.KVK;
            case UNKNOWN -> CardTypeType.UNKNOWN;
        };
    }
}
