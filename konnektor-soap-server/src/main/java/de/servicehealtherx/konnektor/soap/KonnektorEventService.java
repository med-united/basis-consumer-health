package de.servicehealtherx.konnektor.soap;

import de.gematik.ws._int.version.productinformation.v1.ProductInformation;
import de.gematik.ws.conn.cardservice.v8.Cards;
import de.gematik.ws.conn.cardterminalinfo.v8.CardTerminalInfoType;
import de.gematik.ws.conn.cardterminalinfo.v8.CardTerminals;
import de.gematik.ws.conn.eventservice.v7.GetCards;

import de.gematik.ws.conn.eventservice.v7.GetCardsResponse;
import de.gematik.ws.conn.cardservicecommon.v2.CardTypeType;
import de.gematik.ws.conn.cardservice.v8.CardInfoType;
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

    @Override
    public SubscribeResponse subscribe(Subscribe parameter) throws FaultMessage {
        try {
            SubscribeResponse response = new SubscribeResponse();
            response.setStatus(okStatus());
            response.setSubscriptionID(UUID.randomUUID().toString());
            return response;
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
            response.setSubscriptions(new GetSubscriptionResponse.Subscriptions());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetSubscription failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public UnsubscribeResponse unsubscribe(Unsubscribe parameter) throws FaultMessage {
        try {
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
            response.setSubscribeRenewals(new RenewSubscriptionsResponse.SubscribeRenewals());
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
        if (card.iccsn() != null) {
            info.setIccsn(card.iccsn());
        }
        if (card.cardHolderName() != null) {
            info.setCardHolderName(card.cardHolderName());
        }
        if (card.kvnr() != null) {
            info.setKvnr(card.kvnr());
        }
        return info;
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
