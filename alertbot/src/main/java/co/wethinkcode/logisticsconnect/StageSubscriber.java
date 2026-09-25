package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A <b>non-durable</b> subscription to {@code package-status-topic}, on purpose.
 *
 * <p>transit-service subscribes durably because its view must catch up after downtime.
 * alertbot is the opposite case: a change that happened while it was down would be posted
 * late, possibly after the hub had already recovered, and a stale public alert is worse than
 * none. So while alertbot is down the broker keeps nothing for it.
 *
 * <p>Connects over {@code failover:} on a background thread, so the HTTP API is up even when
 * the broker isn't, and a broker restart is survived without code of our own.
 */
final class StageSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StageSubscriber.class);

    private final String brokerUrl;
    private final Consumer<StageChanged> onEvent;
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private volatile Connection connection;

    StageSubscriber(String brokerUrl, Consumer<StageChanged> onEvent) {
        this.brokerUrl = brokerUrl;
        this.onEvent = onEvent;
    }

    void start() {
        Thread thread = new Thread(this::subscribe, "stage-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    boolean awaitSubscribed(Duration timeout) throws InterruptedException {
        return subscribed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void subscribe() {
        try {
            ActiveMQConnectionFactory factory =
                    new ActiveMQConnectionFactory("failover:(" + brokerUrl + ")?maxReconnectDelay=2000");
            connection = factory.createConnection();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createTopic(MqConfig.TOPIC));
            consumer.setMessageListener(this::onMessage);
            connection.start();
            subscribed.countDown();
            log.info("Subscribed to {} (non-durable)", MqConfig.TOPIC);
        } catch (JMSException e) {
            log.error("Could not subscribe to {}; no alerts will be posted", MqConfig.TOPIC, e);
        }
    }

    private void onMessage(Message message) {
        StageChanged event;
        try {
            event = StageChanged.fromJson(((TextMessage) message).getText());
        } catch (Exception e) {
            // Retrying won't make a malformed or invalid event readable, so it is logged and
            // dropped rather than thrown (which would make the broker redeliver it).
            log.warn("Ignoring an unreadable or invalid message on {}: {}", MqConfig.TOPIC, e.toString());
            return;
        }
        try {
            onEvent.accept(event);
        } catch (RuntimeException e) {
            log.error("Could not react to {} moving to stage {}", event.hubId(), event.stage(), e);
        }
    }

    @Override
    public void close() {
        Connection current = connection;
        if (current != null) {
            try {
                current.close();
            } catch (JMSException e) {
                log.debug("Ignoring error while closing the connection", e);
            }
        }
    }
}
