/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *  https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package org.entur.ukur.camelroute;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.IMap;
import org.apache.camel.*;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.xml.Namespaces;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.language.XPathExpression;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spring.SpringRouteBuilder;
import org.apache.commons.lang3.StringUtils;
import org.entur.ukur.camelroute.policy.InterruptibleHazelcastRoutePolicy;
import org.entur.ukur.camelroute.status.RouteStatus;
import org.entur.ukur.service.MetricsService;
import org.entur.ukur.setup.UkurConfiguration;
import org.entur.ukur.subscription.Subscription;
import org.entur.ukur.xml.SiriMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import uk.org.siri.siri20.*;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.entur.ukur.camelroute.policy.SingletonRoutePolicyFactory.SINGLETON_ROUTE_DEFINITION_GROUP_NAME;

@Component
public class UkurCamelRouteBuilder extends SpringRouteBuilder {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    static final String ROUTE_ET_RETRIEVER = "seda:retrieveAnsharET";
    static final String ROUTE_SX_RETRIEVER = "seda:retrieveAnsharSX";
    private static final String SIRI_VERSION = "2.0";
    private static final String ROUTE_FLUSHJOURNEYS = "seda:flushOldJourneys";
    private static final String ROUTE_TIAMAT_MAP = "seda:getStopPlacesAndQuays";
    private static final String ROUTE_ANSHAR_SUBSRENEWER = "seda:ansharSubscriptionRenewer";
    private static final String ROUTE_ANSHAR_SUBSCHECKER = "seda:ansharSubscriptionChecker";
    private static final String ROUTEID_SX_RETRIEVER = "SX Retriever";
    private static final String ROUTEID_ET_RETRIEVER = "ET Retriever";
    private static final String ROUTEID_TIAMAT_MAP = "Tiamat StopPlacesAndQuays";
    private static final String ROUTEID_FLUSHJOURNEYS = "Flush Old Journeys Asynchronously";
    private static final String ROUTEID_ANSHAR_SUBSRENEWER = "Anshar Subscription Renewer";
    private static final String ROUTEID_ANSHAR_SUBSCHECKER = "Anshar Subscription Checker";
    private static final String ROUTEID_FLUSHJOURNEYS_TRIGGER = "Flush Old Journeys";
    private static final String ROUTEID_ET_TRIGGER = "ET trigger";
    private static final String ROUTEID_SX_TRIGGER = "SX trigger";
    private static final String ROUTEID_TIAMAT_MAP_TRIGGER = "Tiamat trigger";
    private static final String ROUTEID_ANSHAR_SUBSRENEWER_TRIGGER = "Anshar Subscription Renewer Trigger";
    private static final String ROUTEID_ANSHAR_SUBSCHECKER_TRIGGER = "Anshar Subscription Checker Trigger";

    private static final String MORE_DATA = "MoreData";
    private final UkurConfiguration config;
    private final NsbETSubscriptionProcessor nsbETSubscriptionProcessor;
    private final NsbSXSubscriptionProcessor nsbSXSubscriptionProcessor;
    private final IMap<String, String> sharedProperties;
    private final MetricsService metricsService;
    private final String nodeStarted;
    private final TiamatStopPlaceQuaysProcessor tiamatStopPlaceQuaysProcessor;
    private final Namespaces siriNamespace = new Namespaces("s", "http://www.siri.org.uk/siri");
    private final int HEARTBEAT_INTERVAL_MS = 60_000;
    private final int SUBSCRIPTION_DURATION_MIN = 12*60;

    @Autowired
    public UkurCamelRouteBuilder(UkurConfiguration config,
                                 NsbETSubscriptionProcessor nsbETSubscriptionProcessor,
                                 NsbSXSubscriptionProcessor nsbSXSubscriptionProcessor,
                                 TiamatStopPlaceQuaysProcessor tiamatStopPlaceQuaysProcessor,
                                 @Qualifier("sharedProperties") IMap<String, String> sharedProperties,
                                 MetricsService metricsService) {
        this.config = config;
        this.nsbETSubscriptionProcessor = nsbETSubscriptionProcessor;
        this.nsbSXSubscriptionProcessor = nsbSXSubscriptionProcessor;
        this.tiamatStopPlaceQuaysProcessor = tiamatStopPlaceQuaysProcessor;
        this.sharedProperties = sharedProperties;
        this.metricsService = metricsService;
        nodeStarted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public void configure() {
        createWorkerRoutes(config.getTiamatStopPlaceQuaysURL());
        createRestRoutes(config.getRestPort(), config.isEtEnabled(), config.isSxEnabled(), config.useAnsharSubscription());
        createQuartzRoutes(config.getPollingInterval(), config.isTiamatStopPlaceQuaysEnabled(), config.getTiamatStopPlaceQuaysInterval());
        createSiriProcessingRoutes();

        String proposedValue = "ukur-" + UUID.randomUUID();
        String requestorId = sharedProperties.putIfAbsent("AnsharRequestorId", proposedValue);
        requestorId = (requestorId == null) ? proposedValue : requestorId;
        logger.debug("Uses requestorId: '{}' - proposed value was {}", requestorId, proposedValue);
        if (config.useAnsharSubscription()) {
            configureAnsharSubscriptionRoutes(config.isEtEnabled(), config.isSxEnabled(), requestorId);
        } else {
            String siriETurl = config.getAnsharETCamelUrl(requestorId);
            String siriSXurl = config.getAnsharSXCamelUrl(requestorId);
            createAnsharPollingRoutes(config.isEtEnabled(), config.isSxEnabled(), config.getPollingInterval(), siriETurl, siriSXurl);
        }
    }

    private void createWorkerRoutes(String tiamatStopPlaceQuaysURL) {

        from("activemq:queue:" + UkurConfiguration.ET_QUEUE)
                .routeId("ET ActiveMQ Listener")
                .process(nsbETSubscriptionProcessor)
                .end();

        from("activemq:queue:" + UkurConfiguration.SX_QUEUE)
                .routeId("SX ActiveMQ Listener")
                .process(nsbSXSubscriptionProcessor)
                .end();

        from(ROUTE_FLUSHJOURNEYS)
                .routeId(ROUTEID_FLUSHJOURNEYS)
                .to("bean:liveRouteManager?method=flushOldJourneys()");

        from(ROUTE_TIAMAT_MAP)
                .routeId(ROUTEID_TIAMAT_MAP)
                .to("metrics:timer:" + MetricsService.TIMER_TIAMAT + "?action=start")
                .log(LoggingLevel.DEBUG, "About to call Tiamat with url: " + tiamatStopPlaceQuaysURL)
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("ET-Client-Name", constant("Ukur"))
                .setHeader("ET-Client-ID", constant(getHostName()))
                .to(tiamatStopPlaceQuaysURL)
                .process(tiamatStopPlaceQuaysProcessor)
                .to("metrics:timer:" + MetricsService.TIMER_TIAMAT + "?action=stop")
                .end();
    }

    private void createRestRoutes(int jettyPort, boolean etEnabled, boolean sxEnabled, boolean createSubscriptionReceievers) {
        restConfiguration()
                .component("jetty")
                .bindingMode(RestBindingMode.json)
                .dataFormatProperty("prettyPrint", "true")
                .port(jettyPort);

        rest("/health")
                .get("/subscriptions").to("bean:subscriptionManager?method=listAll")
                .get("/routes").to("direct:routeStatus")
                .get("/live").to("direct:OK")
                .get("/ready").to("direct:OK");

        rest("/journeys")
                .get("/").to("bean:liveRouteManager?method=getJourneys()")
                .get("/{lineref}/").to("bean:liveRouteManager?method=getJourneys(${header.lineref})");

        rest("/subscription")
                .post().type(Subscription.class).outType(Subscription.class).to("bean:subscriptionManager?method=add(${body})")
                .delete("{id}").to("bean:subscriptionManager?method=remove(${header.id})");

        from("direct:OK")
                .routeId("OK response")
                .log(LoggingLevel.TRACE, "Return hardcoded 'OK' on uri '${header." + Exchange.HTTP_URI + "}'")
                .setBody(simple("OK    \n\n"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant("200"));

        from("direct:routeStatus")
                .routeId("Route Status")
                .process(exchange -> {
                    RouteStatus status = new RouteStatus();
                    status.setNodeStartTime(nodeStarted);
                    status.setHostname(getHostName());
                    status.setStatusJourneyFlush(routeStatus(ROUTEID_FLUSHJOURNEYS_TRIGGER));
                    status.setStatusET(routeStatus(ROUTEID_ET_TRIGGER, etEnabled, createSubscriptionReceievers));
                    status.setStatusSX(routeStatus(ROUTEID_SX_TRIGGER, sxEnabled, createSubscriptionReceievers));
                    status.setStatusSubscriptionRenewer(routeStatus(ROUTEID_ANSHAR_SUBSRENEWER_TRIGGER));
                    status.setStatusSubscriptionChecker(routeStatus(ROUTEID_ANSHAR_SUBSCHECKER_TRIGGER));
                    for (Map.Entry<String, Meter> entry : metricsService.getMeters().entrySet()) {
                        status.addMeter(entry.getKey(), entry.getValue());
                    }
                    for (Map.Entry<String, Timer> entry : metricsService.getTimers().entrySet()) {
                        status.addTimer(entry.getKey(), entry.getValue());
                    }
                    for (Map.Entry<String, Gauge> entry : metricsService.getGauges().entrySet()) {
                        status.addGauge(entry.getKey(), entry.getValue());
                    }
                    exchange.getOut().setBody(status);
                });

    }

    private void createQuartzRoutes(int repatInterval, boolean stopPlaceToQuayEnabled, int tiamatRepatInterval) {

        createSingletonQuartz2Route("flushOldJourneys", repatInterval, ROUTEID_FLUSHJOURNEYS_TRIGGER, ROUTEID_FLUSHJOURNEYS, ROUTE_FLUSHJOURNEYS);

        if (stopPlaceToQuayEnabled) {
            from("quartz2://ukur/getStopPlacesFromTiamat?trigger.repeatInterval=" + tiamatRepatInterval + "&fireNow=true")
                    .routeId(ROUTEID_TIAMAT_MAP_TRIGGER)
                    .filter(e -> isNotRunning(ROUTEID_TIAMAT_MAP))
                    .log(LoggingLevel.DEBUG, "getStopPlacesFromTiamat triggered by timer")
                    .to(ROUTE_TIAMAT_MAP);
        }
    }

    private void createSiriProcessingRoutes() {
        from("direct:processPtSituationElements")
                .routeId("processPtSituationElements")
                .split(siriNamespace.xpath("//s:PtSituationElement[s:ParticipantRef/text()='NSB']")) //TODO: this only selects elements with NSB as operator
                .bean(metricsService, "registerSentMessage('PtSituationElement')")
                .to("activemq:queue:" + UkurConfiguration.SX_QUEUE);

        from("direct:processEstimatedVehicleJourneys")
                .routeId("processEstimatedVehicleJourneys")
                .split(siriNamespace.xpath("//s:EstimatedVehicleJourney[s:OperatorRef/text()='NSB']")) //TODO: this only selects elements with NSB as operator
                .bean(metricsService, "registerSentMessage('EstimatedVehicleJourney')")
                .to("activemq:queue:" + UkurConfiguration.ET_QUEUE);
    }

    private void configureAnsharSubscriptionRoutes(boolean etEnabled, boolean sxEnabled, String requestorId) {
        if (!etEnabled && !sxEnabled) {
            logger.warn("No point in setting up required routes for Anshar-subscriptions since both SX and ET is disabled!");
            return;
        }

        rest("siriMessages")
                .consumes("application/xml")
                .bindingMode(RestBindingMode.off)
                .post("/{requestorId}/{type}")
                .to("direct:checkRequestorId");

        from("direct:checkRequestorId")
                .routeId("Check requestorId")
                .bean(metricsService, "registerReceivedSubscribedMessage(${header.requestorId}, ${header.type} )")
                .choice()
                .when(header("requestorId").isNotEqualTo(requestorId))
                    .log(LoggingLevel.WARN, "Received unknown requestorId ('${header.requestorId}')")
                    .to("direct:FORBIDDEN")
                    .endChoice()
                .when(PredicateBuilder.and(exchange -> sxEnabled, header("type").isEqualTo("sx")))
                    .wireTap("seda:handleSubscribedSituationExchange")
                    .setBody(simple("OK\n\n"))
                    .endChoice()
                .when(PredicateBuilder.and(exchange -> etEnabled, header("type").isEqualTo("et")))
                    .wireTap("seda:handleSubscribedEstimatedTimetable")
                    .setBody(simple("OK\n\n"))
                    .endChoice()
                .otherwise()
                    .log(LoggingLevel.WARN, "Unhandled type ('${header.type}') - sxEnabled="+sxEnabled+", etEnabled="+etEnabled)
                    .to("direct:FORBIDDEN")
                    .endChoice()
                .end();

        from("direct:FORBIDDEN")
                .routeId("FORBIDDEN response")
                .log(LoggingLevel.INFO, "Return 'FORBIDDEN' on uri '${header." + Exchange.HTTP_URI + "}'")
                .setBody(simple("FORBIDDEN    \n\n"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant("403"));

        Processor heartbeatRegistrer = exchange -> {
            String type = exchange.getIn().getHeader("type", String.class);
            String key = "AnsharLastReceived-" + type;
            String value = Long.toString(System.currentTimeMillis());
            logger.trace("register received notification of type '{}' with key '{}' and value '{}'", type, key, value);
            sharedProperties.set(key, value);
        };

        from("seda:handleSubscribedSituationExchange")
                .routeId("Handle subscribed SX message")
                .convertBodyTo(Document.class)
                .process(heartbeatRegistrer)
                .to("direct:processPtSituationElements");

        from("seda:handleSubscribedEstimatedTimetable")
                .routeId("Handle subscribed ET message")
                .convertBodyTo(Document.class)
                .process(heartbeatRegistrer)
                .to("direct:processEstimatedVehicleJourneys");

        //renew subscriptions:
        createSingletonQuartz2Route("AnsharSubscriptionRenewer", SUBSCRIPTION_DURATION_MIN * 60_000, ROUTEID_ANSHAR_SUBSRENEWER_TRIGGER, ROUTEID_ANSHAR_SUBSRENEWER, ROUTE_ANSHAR_SUBSRENEWER);
        //re-create subscriptions if nothing is received from Anshar for some time (3 x heartbeat):
        createSingletonQuartz2Route("AnsharSubscriptionChecker", HEARTBEAT_INTERVAL_MS, ROUTEID_ANSHAR_SUBSCHECKER_TRIGGER, ROUTEID_ANSHAR_SUBSCHECKER, ROUTE_ANSHAR_SUBSCHECKER);

        //TODO: Separate ET and SX subscription handling (using same parameter controlled method...?)
        from(ROUTE_ANSHAR_SUBSRENEWER)
                .routeId(ROUTEID_ANSHAR_SUBSRENEWER)
                .process(exchange -> {
                    if (etEnabled) {
                        SubscriptionRequest request = createSubscriptionRequest(requestorId, "et");
                        EstimatedTimetableRequestStructure etRequest = new EstimatedTimetableRequestStructure();
                        etRequest.setRequestTimestamp(ZonedDateTime.now());
                        etRequest.setVersion(SIRI_VERSION);
                        etRequest.setMessageIdentifier(request.getMessageIdentifier());
                        EstimatedTimetableSubscriptionStructure etSubscriptionReq = new EstimatedTimetableSubscriptionStructure();
                        etSubscriptionReq.setEstimatedTimetableRequest(etRequest);
                        SubscriptionQualifierStructure subscriptionIdentifier = new SubscriptionQualifierStructure();
                        subscriptionIdentifier.setValue(requestorId + "-ET");
                        etSubscriptionReq.setSubscriptionIdentifier(subscriptionIdentifier);
                        etSubscriptionReq.setInitialTerminationTime(ZonedDateTime.now().plusMinutes(SUBSCRIPTION_DURATION_MIN));
                        etSubscriptionReq.setSubscriberRef(request.getRequestorRef());
                        request.getEstimatedTimetableSubscriptionRequests().add(etSubscriptionReq);
                        logger.info("Sets up subscription for ET messages with duration of {} minutes", SUBSCRIPTION_DURATION_MIN);
                        postSubscriptionRequest(request);
                    }

                    if (sxEnabled) {
                        SubscriptionRequest request = createSubscriptionRequest(requestorId, "sx");
                        SituationExchangeRequestStructure sxRequest = new SituationExchangeRequestStructure();
                        sxRequest.setRequestTimestamp(ZonedDateTime.now());
                        sxRequest.setVersion(SIRI_VERSION);
                        sxRequest.setMessageIdentifier(request.getMessageIdentifier());
                        SituationExchangeSubscriptionStructure sxSubscriptionReq = new SituationExchangeSubscriptionStructure();
                        sxSubscriptionReq.setSituationExchangeRequest(sxRequest);
                        SubscriptionQualifierStructure subscriptionIdentifier = new SubscriptionQualifierStructure();
                        subscriptionIdentifier.setValue(requestorId + "-SX");
                        sxSubscriptionReq.setSubscriptionIdentifier(subscriptionIdentifier);
                        sxSubscriptionReq.setInitialTerminationTime(ZonedDateTime.now().plusMinutes(SUBSCRIPTION_DURATION_MIN));
                        sxSubscriptionReq.setSubscriberRef(request.getRequestorRef());
                        request.getSituationExchangeSubscriptionRequests().add(sxSubscriptionReq);
                        logger.info("Sets up subscription for SX messages with duration of {} minutes", SUBSCRIPTION_DURATION_MIN);
                        postSubscriptionRequest(request);
                    }
                });

        from(ROUTE_ANSHAR_SUBSCHECKER)
                .routeId(ROUTEID_ANSHAR_SUBSCHECKER)
                .filter(exchange -> {
                    String lastReceivedET = sharedProperties.get("AnsharLastReceived-et");
                    String lastReceivedSX = sharedProperties.get("AnsharLastReceived-sx");
                    long current = System.currentTimeMillis();
                    if (etEnabled && StringUtils.isNotBlank(lastReceivedET)) {
                        long last = Long.parseLong(lastReceivedET);
                        logger.trace("Last ET message was received {} ms ago", last);
                        if (current - last > (3 * HEARTBEAT_INTERVAL_MS)) {
                            logger.info("Renews subscription as the last ET message was received {} ms ago", last);
                            return true;
                        }
                    }
                    if (sxEnabled && StringUtils.isNotBlank(lastReceivedSX)) {
                        long last = Long.parseLong(lastReceivedSX);
                        logger.trace("Last SX message was received {} ms ago", last);
                        if (current - last > (3 * HEARTBEAT_INTERVAL_MS)) {
                            logger.info("Renews subscription as the last SX message was received {} ms ago", last);
                            return true;
                        }
                    }

                    return false;
                })
                .to(ROUTE_ANSHAR_SUBSRENEWER);

    }

    private void createAnsharPollingRoutes(boolean etPollingEnabled, boolean sxPollingEnabled, int repatInterval, String siriETurl, String siriSXurl) {

        Predicate splitComplete = exchangeProperty(Exchange.SPLIT_COMPLETE).isEqualTo(true);
        Predicate moreData = exchangeProperty(MORE_DATA).isEqualToIgnoreCase("true");
        Predicate callAnsharAgain = PredicateBuilder.and(splitComplete, moreData);

        XPathExpression moreDataExpression = siriNamespace.xpath("/s:Siri/s:ServiceDelivery/s:MoreData/text()", String.class);

        from(ROUTE_ET_RETRIEVER)
                .routeId(ROUTEID_ET_RETRIEVER)
                .to("metrics:timer:" + MetricsService.TIMER_ET_PULL + "?action=start")
                .log(LoggingLevel.DEBUG, "About to call Anshar with url: " + siriETurl)
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("ET-Client-Name", constant("Ukur"))
                .setHeader("ET-Client-ID", constant(getHostName()))
                .to(siriETurl)
                .convertBodyTo(Document.class)
                .setProperty(MORE_DATA, moreDataExpression)
                .to("metrics:timer:" + MetricsService.TIMER_ET_PULL + "?action=stop")
                .to("direct:processEstimatedVehicleJourneys")
                .choice()
                .when(callAnsharAgain)
                .log(LoggingLevel.DEBUG, "Call Anshar again since there are more ET data")
                .to(ROUTE_ET_RETRIEVER)
                .end();

        from(ROUTE_SX_RETRIEVER)
                .routeId(ROUTEID_SX_RETRIEVER)
                .to("metrics:timer:" + MetricsService.TIMER_SX_PULL + "?action=start")
                .log(LoggingLevel.DEBUG, "About to call Anshar with url: " + siriSXurl)
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("ET-Client-Name", constant("Ukur"))
                .setHeader("ET-Client-ID", constant(getHostName()))
                .to(siriSXurl)
                .convertBodyTo(Document.class)
                .setProperty(MORE_DATA, moreDataExpression)
                .to("metrics:timer:" + MetricsService.TIMER_SX_PULL + "?action=stop")
                .to("direct:processPtSituationElements")
                .choice()
                .when(callAnsharAgain)
                .log(LoggingLevel.DEBUG, "Call Anshar again since there are more SX data")
                .to(ROUTE_SX_RETRIEVER)
                .end();

        if (etPollingEnabled) {
            createSingletonQuartz2Route("pollAnsharET", repatInterval, ROUTEID_ET_TRIGGER, ROUTEID_ET_RETRIEVER, ROUTE_ET_RETRIEVER);
        } else {
            logger.warn("ET polling is disabled");
        }

        if (sxPollingEnabled) {
            createSingletonQuartz2Route("pollAnsharSX", repatInterval, ROUTEID_SX_TRIGGER, ROUTEID_SX_RETRIEVER, ROUTE_SX_RETRIEVER);
        } else {
            logger.warn("SX polling is disabled");
        }

    }

    private void postSubscriptionRequest(SubscriptionRequest request) {
        Siri siri = new Siri();
        siri.setVersion(SIRI_VERSION);
        siri.setSubscriptionRequest(request);
        try {
            SiriMarshaller siriMarshaller = new SiriMarshaller();
            HttpURLConnection connection = (HttpURLConnection) new URL(config.getAnsharSubscriptionUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.writeBytes(siriMarshaller.marshall(siri));
            out.flush();
            out.close();
            int responseCode = connection.getResponseCode();
            if (200 == responseCode) {
                logger.info("Successfully created subscription to Anshar!");
            } else {
                logger.error("Unexpected response code from Anshar when subscribing: {}", responseCode);
            }
        } catch (Exception e) {
            logger.error("Could not subscribe to Anshar", e);
        }
    }

    private SubscriptionRequest createSubscriptionRequest(String requestorId, String type) {
        RequestorRef requestorRef = new RequestorRef();
        requestorRef.setValue("Ukur");
        DatatypeFactory datatypeFactory;
        try {
            datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException(e);
        }
        MessageQualifierStructure messageIdentifier = new MessageQualifierStructure();
        messageIdentifier.setValue("required-by-siri-spec-"+System.currentTimeMillis());
        SubscriptionRequest request = new SubscriptionRequest();
        request.setRequestorRef(requestorRef);
        request.setMessageIdentifier(messageIdentifier);
        request.setAddress(config.getOwnSubscriptionURL()+"siriMessages/"+requestorId+"/"+type);
        request.setRequestTimestamp(ZonedDateTime.now());
        SubscriptionContextStructure ctx = new SubscriptionContextStructure();
        ctx.setHeartbeatInterval(datatypeFactory.newDuration(HEARTBEAT_INTERVAL_MS));
        request.setSubscriptionContext(ctx);
        return request;
    }

    private void createSingletonQuartz2Route(String timerName, int repatInterval, String triggerRouteId, String toRouteId, String toRoute) {
        String uri = "quartz2://ukur/" + timerName + "?trigger.repeatInterval=" + repatInterval + "&startDelayedSeconds=5&fireNow=true";
        singletonFrom(uri, triggerRouteId)
                .filter(e -> isLeader(e.getFromRouteId()))
                .filter(e -> isNotRunning(toRouteId))
                .log(LoggingLevel.DEBUG, timerName + " triggered by timer")
                .to(toRoute);
    }

    private String getHostName(){
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "Ukur-UnknownHost";
        }
    }

    private String routeStatus(String triggerRoute, boolean enabled, boolean subscriptionBased) {
        String s;
        if (subscriptionBased) {
            s = "Subscribing";
        } else {
            s = "Polling " + routeStatus(triggerRoute);
        }
        if (!enabled) {
            s += " (disabled)";
        }
        return s;
    }

    private String routeStatus(String triggerRoute) {
        return isLeader(triggerRoute) ? "LEADER" : "NOT LEADER";
    }

    /**
     * Create a new singleton camelroute definition from URI. Only one such camelroute should be active throughout the cluster at any time.
     */
    private RouteDefinition singletonFrom(String uri, String routeId) {
        return this.from(uri)
                .group(SINGLETON_ROUTE_DEFINITION_GROUP_NAME)
                .routeId(routeId)
                .autoStartup(true);
    }


    private boolean isLeader(String routeId) {
        Route route = getContext().getRoute(routeId);
        if (route != null) {
            RouteContext routeContext = route.getRouteContext();
            List<RoutePolicy> routePolicyList = routeContext.getRoutePolicyList();
            if (routePolicyList != null) {
                for (RoutePolicy routePolicy : routePolicyList) {
                    if (routePolicy instanceof InterruptibleHazelcastRoutePolicy) {
                        return ((InterruptibleHazelcastRoutePolicy) (routePolicy)).isLeader();
                    }
                }
            }
        }
        return false;
    }

    private boolean isNotRunning(String routeId) {
        int size = getContext().getInflightRepository().size(routeId);
        boolean notRunning = size == 0;
        logger.trace("Number of running instances of camelroute '{}' is {} - returns {}", routeId, size, notRunning);
        return notRunning;
    }

    @Bean(name = "json-jackson")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public JacksonDataFormat jacksonDataFormat(ObjectMapper objectMapper) {
        return new JacksonDataFormat(objectMapper, HashMap.class);
    }
}