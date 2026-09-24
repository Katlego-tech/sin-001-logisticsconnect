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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a real, in-process ActiveMQ broker. */
class StageSubscriberTest {

    private BrokerService broker;
    private String brokerUrl;
    private Connection producerConnection;
    private MessageProducer producer;
    private Session session;
    private final StageView view = new StageView();
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
        StageSubscriber subscriber = new StageSubscriber(brokerUrl, view::apply);
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
    void anEventOnTheTopicUpdatesTheView() throws Exception {
        subscribe();
        publish("{\"hubId\":\"H-500\",\"sortingCenter\":\"Johannesburg Central\",\"stage\":4,"
                + "\"previousStage\":0,\"timestamp\":\"2026-07-18T10:00:00Z\"}");

        eventually(() -> view.stageFor("H-500").stage() == 4);
    }

    @Test
    void anEventPublishedWhileTransitServiceIsDownIsDeliveredWhenItReturns() throws Exception {
        subscribe().close(); // registers the durable subscription, then "crashes"

        publish("{\"hubId\":\"H-503\",\"stage\":6,\"timestamp\":\"2026-07-18T11:00:00Z\"}");

        subscribe();
        eventually(() -> view.stageFor("H-503").stage() == 6);
    }

    @Test
    void anUnreadableMessageIsSkippedAndTheSubscriptionCarriesOn() throws Exception {
        subscribe();
        publish("this is not JSON");
        publish("{\"hubId\":\"H-501\",\"stage\":2,\"timestamp\":\"2026-07-18T12:00:00Z\"}");

        eventually(() -> view.stageFor("H-501").stage() == 2);
    }

    @Test
    void anInvalidEventIsSkippedRatherThanAppliedAndTheSubscriptionCarriesOn() throws Exception {
        subscribe();
        publish("{\"hubId\":\"H-502\",\"stage\":42,\"timestamp\":\"2026-07-18T12:00:00Z\"}");
        publish("{\"hubId\":\"H-507\",\"timestamp\":\"2026-07-18T12:00:00Z\"}");
        publish("{\"hubId\":\"H-509\",\"stage\":3,\"timestamp\":\"2026-07-18T12:01:00Z\"}");

        eventually(() -> view.stageFor("H-509").stage() == 3);
        assertFalse(view.stageFor("H-502").known(), "stage 42 was not applied");
        assertFalse(view.stageFor("H-507").known(), "a missing stage was not read as 0");
    }
}
