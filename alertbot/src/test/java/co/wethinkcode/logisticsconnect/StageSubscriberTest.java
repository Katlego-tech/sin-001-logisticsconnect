package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a real, in-process ActiveMQ broker. */
class StageSubscriberTest {

    private static final String JOBURG_TO_5 = """
            {"hubId":"H-500","sortingCenter":"Johannesburg Central","province":"Gauteng",
             "stage":5,"previousStage":0,"timestamp":"2026-07-18T10:15:00Z"}""";
    private static final String DURBAN_TO_6 = """
            {"hubId":"H-503","sortingCenter":"Durban Harbour","province":"KwaZulu-Natal",
             "stage":6,"previousStage":1,"timestamp":"2026-07-18T10:16:00Z"}""";

    private BrokerService broker;
    private String brokerUrl;
    private Connection producerConnection;
    private MessageProducer producer;
    private Session session;
    private final List<StageChanged> received = new CopyOnWriteArrayList<>();
    private final List<StageSubscriber> subscribers = new ArrayList<>();

    @BeforeEach
    void startBroker() throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector("tcp://localhost:0");
        broker.start();
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();

        producerConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        session = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        producer = session.createProducer(session.createTopic(MqConfig.TOPIC));
        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
    }

    @AfterEach
    void stopBroker() throws Exception {
        subscribers.forEach(StageSubscriber::close);
        producerConnection.close();
        broker.stop();
    }

    private void publish(String json) throws JMSException {
        producer.send(session.createTextMessage(json));
    }

    private StageSubscriber subscribe() throws InterruptedException {
        StageSubscriber subscriber = new StageSubscriber(brokerUrl, received::add);
        subscribers.add(subscriber);
        subscriber.start();
        assertTrue(subscriber.awaitSubscribed(Duration.ofSeconds(5)), "never subscribed");
        return subscriber;
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void anEventOnTheTopicReachesTheBot() throws Exception {
        subscribe();

        publish(JOBURG_TO_5);

        eventually(() -> received.size() == 1);
        assertEquals(new StageChanged("H-500", "Johannesburg Central", "Gauteng", 5, 0, "2026-07-18T10:15:00Z"),
                received.get(0));
    }

    @Test
    void aChangeMadeWhileTheBotWasDownIsNeverPostedLate() throws Exception {
        subscribe().close();
        publish(JOBURG_TO_5); // nobody is listening: the bot is "down"

        subscribe();
        publish(DURBAN_TO_6);

        eventually(() -> received.size() == 1);
        Thread.sleep(200); // give a (wrongly) retained message the chance to arrive too
        assertEquals(List.of("H-503"), received.stream().map(StageChanged::hubId).toList());
    }

    @Test
    void unreadableAndInvalidEventsAreSkippedAndTheSubscriptionCarriesOn() throws Exception {
        subscribe();

        publish("not json");
        publish(JOBURG_TO_5.replace("\"stage\":5", "\"stage\":42"));
        publish(JOBURG_TO_5.replace(",\"previousStage\":0", ""));
        publish(DURBAN_TO_6);

        eventually(() -> received.size() == 1);
        Thread.sleep(200);
        assertEquals(List.of("H-503"), received.stream().map(StageChanged::hubId).toList());
    }

    @Test
    void aFailureWhileReactingDoesNotStopTheSubscription() throws Exception {
        StageSubscriber subscriber = new StageSubscriber(brokerUrl, event -> {
            if (event.hubId().equals("H-500")) {
                throw new IllegalStateException("posting blew up");
            }
            received.add(event);
        });
        subscribers.add(subscriber);
        subscriber.start();
        assertTrue(subscriber.awaitSubscribed(Duration.ofSeconds(5)));

        publish(JOBURG_TO_5);
        publish(DURBAN_TO_6);

        eventually(() -> received.size() == 1);
        assertEquals("H-503", received.get(0).hubId());
    }
}
