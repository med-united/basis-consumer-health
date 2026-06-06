package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.cardservice.v8.Cards;
import de.gematik.ws.conn.eventservice.v7.GetCards;

import de.gematik.ws.conn.eventservice.v7.GetCardsResponse;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

import java.util.UUID;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@ApplicationScoped
@WebService(
    portName = "EventServicePort",
    serviceName = "EventService",
    targetNamespace = "http://ws.gematik.de/conn/EventService/WSDL/v7.2",
    endpointInterface = "de.gematik.ws.conn.eventservice.wsdl.v7_2.EventServicePortType"
)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorEventService implements EventServicePortType {

    @Inject
    SicctTerminalManager sicctTerminalManager;

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
            response.setStatus(okStatus());
            response.setCards(new Cards());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetCards failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }
}
