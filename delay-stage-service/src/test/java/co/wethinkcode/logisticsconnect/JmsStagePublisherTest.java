package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StagePublisher.PublishFailed;
import co.wethinkcode.logisticsconnect.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Against a real, in-process ActiveMQ broker. */
class JmsStagePublisherTest {

    private static final StageChanged EVENT =
            new StageChanged("H-500", "Johannesburg Central", "Gauteng", 5, 3, "2026-07-18T10:15:00Z");

    private BrokerService broker;
    private String brokerUrl;
    private Connection subscriberConnection;
    private MessageConsumer subscriber;

    @BeforeEach
    void startBrokerAndSubscribe() throws Exception {
        broker = startBroker("tcp://localhost:0");
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();

        subscriberConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        subscriberConnection.start();
        Session session = subscriberConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        subscriber = session.createConsumer(session.createTopic(MqConfig.TOPIC));
    }

    @AfterEach
    void stopBroker() throws Exception {
        subscriberConnection.close();
        broker.stop();
    }

    private static BrokerService startBroker(String connector) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector(connector);
        broker.start();
        return broker;
    }

    @Test
    void publishesTheEventAsJsonToThePackageStatusTopic() throws Exception {
        try (JmsStagePublisher publisher = new JmsStagePublisher(brokerUrl)) {
            publisher.publish(EVENT);
        }

        TextMessage received = (TextMessage) subscriber.receive(2_000);
        assertNotNull(received, "nothing arrived on " + MqConfig.TOPIC);
        assertEquals("{\"hubId\":\"H-500\",\"sortingCenter\":\"Johannesburg Central\",\"province\":\"Gauteng\","
                + "\"stage\":5,\"previousStage\":3,\"timestamp\":\"2026-07-18T10:15:00Z\"}", received.getText());
    }

    @Test
    void messagesArePersistentSoTheBrokerKeepsThemForAnOfflineDurableSubscriber() throws Exception {
        try (JmsStagePublisher publisher = new JmsStagePublisher(brokerUrl)) {
            publisher.publish(EVENT);
        }

        assertEquals(DeliveryMode.PERSISTENT, subscriber.receive(2_000).getJMSDeliveryMode());
    }

    @Test
    void aBrokerThatIsDownFailsTheCallAndTheNextPublishReconnects() throws Exception {
        try (JmsStagePublisher publisher = new JmsStagePublisher(brokerUrl)) {
            publisher.publish(EVENT);

            broker.stop();
            broker.waitUntilStopped();
            assertThrows(PublishFailed.class, () -> publisher.publish(EVENT));

            broker = startBroker(brokerUrl);
            publisher.publish(EVENT);
        }
    }

    @Test
    void aBrokerThatAcceptsTheConnectionButNeverAnswersFailsWithinSeconds() throws Exception {
        List<Socket> held = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(silent.accept()); // accept, then say nothing, like a hung broker
                    }
                } catch (IOException closed) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            try (JmsStagePublisher publisher = new JmsStagePublisher("tcp://localhost:" + silent.getLocalPort())) {
                assertTimeoutPreemptively(Duration.ofSeconds(8),
                        () -> assertThrows(PublishFailed.class, () -> publisher.publish(EVENT)));
            }
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }
}
