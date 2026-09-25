package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StagePublisher.PublishFailed;
import co.wethinkcode.logisticsconnect.StagePublisher.PublishOutcomeUnknown;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            PublishFailed e = assertThrows(PublishFailed.class, () -> publisher.publish(EVENT));
            assertFalse(e instanceof PublishOutcomeUnknown, "no connection, so it certainly wasn't sent");

            broker = startBroker(brokerUrl);
            publisher.publish(EVENT);
        }
    }

    private static void awaitNoConnection(JmsStagePublisher publisher) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (publisher.isConnected()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the publisher never noticed the broker had gone");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void aBrokerRestartBetweenPublishesDoesNotFailTheNextOne() throws Exception {
        try (JmsStagePublisher publisher = new JmsStagePublisher(brokerUrl)) {
            publisher.publish(EVENT);

            for (int restart = 1; restart <= 25; restart++) {
                broker.stop();
                broker.waitUntilStopped();
                // The client notices a lost connection asynchronously. A publish that races it is
                // sent on the old socket, and then its outcome really is unknown, as with a hung
                // broker. This test is about what happens once the loss has been noticed.
                awaitNoConnection(publisher);
                broker = startBroker(brokerUrl);

                publisher.publish(EVENT); // the broker is up, so this must not fail on the old, dead session
            }
        }
    }

    @Test
    void aBrokerThatHangsMidConnectionFailsTheCallWithinSecondsWithTheOutcomeUnknown() throws Exception {
        int brokerPort = broker.getTransportConnectors().get(0).getConnectUri().getPort();
        try (FreezableProxy proxy = new FreezableProxy(brokerPort);
             JmsStagePublisher publisher = new JmsStagePublisher("tcp://localhost:" + proxy.port())) {
            publisher.publish(EVENT); // connected, through the proxy
            assertNotNull(subscriber.receive(2_000));

            proxy.freeze();
            assertTimeoutPreemptively(Duration.ofSeconds(6),
                    () -> assertThrows(PublishOutcomeUnknown.class, () -> publisher.publish(EVENT)));

            // Why "unknown" and not "failed": once the broker recovers, the send that timed out
            // can still be delivered.
            proxy.thaw();
            assertNotNull(subscriber.receive(5_000), "the timed-out event arrived after all");
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
                PublishFailed e = assertTimeoutPreemptively(Duration.ofSeconds(8),
                        () -> assertThrows(PublishFailed.class, () -> publisher.publish(EVENT)));
                assertFalse(e instanceof PublishOutcomeUnknown, "never connected, so it certainly wasn't sent");
            }
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    /**
     * A TCP proxy to the broker that can freeze: its connections stay open but nothing gets
     * through any more, which is what a hung broker looks like to a client already connected.
     */
    private static final class FreezableProxy implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0);
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private volatile boolean frozen;

        FreezableProxy(int brokerPort) throws IOException {
            daemon(() -> {
                try {
                    while (true) {
                        Socket client = server.accept();
                        Socket upstream = new Socket("localhost", brokerPort);
                        sockets.add(client);
                        sockets.add(upstream);
                        daemon(() -> pump(client, upstream));
                        daemon(() -> pump(upstream, client));
                    }
                } catch (IOException closed) {
                    // the proxy was closed
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        void freeze() {
            frozen = true;
        }

        void thaw() {
            frozen = false;
        }

        private void pump(Socket from, Socket to) {
            byte[] buffer = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    while (frozen) {
                        Thread.sleep(50); // hold the bytes: nothing gets through
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException | InterruptedException closed) {
                // one side went away
            }
        }

        private static void daemon(Runnable task) {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
