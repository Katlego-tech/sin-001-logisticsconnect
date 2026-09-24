package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;

/**
 * Publishes {@link StageChanged} events as JSON text messages to {@code package-status-topic}.
 *
 * <p>Connects lazily and plainly over {@code tcp://}, not {@code failover:}: a failover
 * connection would block the HTTP request until the broker came back, and the caller should get
 * a prompt 503 instead. After a failure the connection is dropped and rebuilt on the next publish.
 *
 * <p>Every wait is bounded, so a broker that is up but not answering fails the request in
 * seconds rather than hanging it: the TCP connect (default 30 seconds), the OpenWire handshake
 * (default 15 seconds), the broker's reply to the connection, and each send. A broken connection
 * is closed in the background, because closing one to a hung broker waits for a reply that won't
 * come (default 15 seconds).
 *
 * <p>A connection the broker drops, when it restarts say, is discarded as soon as the client
 * notices, so the next publish reconnects instead of failing on a dead session.
 */
final class JmsStagePublisher implements StagePublisher, AutoCloseable {

    static final int TIMEOUT_MILLIS = 3_000;

    private static final Logger log = LoggerFactory.getLogger(JmsStagePublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ActiveMQConnectionFactory factory;
    private Connection connection;
    private Session session;
    private MessageProducer producer;

    JmsStagePublisher(String brokerUrl) {
        factory = new ActiveMQConnectionFactory(brokerUrl + (brokerUrl.contains("?") ? "&" : "?")
                + "connectionTimeout=" + TIMEOUT_MILLIS + "&negotiateTimeout=" + TIMEOUT_MILLIS);
        factory.setConnectResponseTimeout(TIMEOUT_MILLIS);
        factory.setSendTimeout(TIMEOUT_MILLIS);
        factory.setCloseTimeout(TIMEOUT_MILLIS);
    }

    @Override
    public synchronized void publish(StageChanged event) {
        try {
            if (producer == null) {
                connect();
            }
            producer.send(session.createTextMessage(JSON.writeValueAsString(event)));
            log.info("Published to {}: {}", MqConfig.TOPIC, event);
        } catch (JMSException | JsonProcessingException e) {
            discard(connection);
            throw new PublishFailed("could not publish to " + MqConfig.TOPIC + ": " + e.getMessage(), e);
        }
    }

    private void connect() throws JMSException {
        Connection fresh = factory.createConnection();
        connection = fresh;
        fresh.setExceptionListener(e -> {
            log.warn("Lost the connection to {}: {}", factory.getBrokerURL(), e.toString());
            discard(fresh);
        });
        session = fresh.createSession(false, Session.AUTO_ACKNOWLEDGE);
        producer = session.createProducer(session.createTopic(MqConfig.TOPIC));
        // Persistent, so the broker holds events for a durable subscriber (transit-service) that is offline.
        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
        log.info("Connected to {}", factory.getBrokerURL());
    }

    /**
     * Forgets {@code broken} if it is still the current connection, so the next publish
     * reconnects, and closes it on a background thread rather than the caller's.
     */
    private synchronized void discard(Connection broken) {
        if (broken == null || broken != connection) {
            return;
        }
        connection = null;
        session = null;
        producer = null;
        Thread closer = new Thread(() -> closeQuietly(broken), "stage-publisher-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** At shutdown: closes the current connection, waiting at most the close timeout. */
    @Override
    public synchronized void close() {
        Connection current = connection;
        connection = null;
        session = null;
        producer = null;
        closeQuietly(current);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (JMSException e) {
            log.debug("Ignoring error while closing the connection", e);
        }
    }
}
