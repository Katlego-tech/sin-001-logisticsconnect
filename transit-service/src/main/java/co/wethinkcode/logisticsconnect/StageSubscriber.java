package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TopicSubscriber;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A <b>durable</b> subscription to {@code package-status-topic}.
 *
 * <p>Durable because transit-service's view has to converge on the true stages: while this
 * service is down the broker keeps the events for it, and delivers them when it reconnects.
 * (Compare alertbot, which subscribes non-durably on purpose.) A durable subscription is only
 * remembered from the first time it is made, though, so on its very first start this service
 * knows no stages until the next change of each one.
 *
 * <p>Connects over {@code failover:} on a background thread, so the HTTP API is up even when
 * the broker isn't, and a broker restart is survived without code of our own.
 */
final class StageSubscriber implements AutoCloseable {

    static final String CLIENT_ID = "transit-service";
    static final String SUBSCRIPTION_NAME = "transit-service-stages";

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
            connection.setClientID(CLIENT_ID); // the broker keeps a durable subscription per client ID + name
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            TopicSubscriber subscriber =
                    session.createDurableSubscriber(session.createTopic(MqConfig.TOPIC), SUBSCRIPTION_NAME);
            subscriber.setMessageListener(this::onMessage);
            connection.start();
            subscribed.countDown();
            log.info("Subscribed durably to {} as {}/{}", MqConfig.TOPIC, CLIENT_ID, SUBSCRIPTION_NAME);
        } catch (JMSException e) {
            log.error("Could not subscribe to {}; ETAs will assume stage 0", MqConfig.TOPIC, e);
        }
    }

    private void onMessage(Message message) {
        try {
            StageChanged event = StageChanged.fromJson(((TextMessage) message).getText());
            onEvent.accept(event);
            log.info("Stage update from {}: {} is now stage {}", MqConfig.TOPIC, event.hubId(), event.stage());
        } catch (Exception e) {
            // Retrying won't make a malformed or invalid event readable, so it is logged and
            // dropped rather than thrown (which would make the broker redeliver it).
            log.warn("Ignoring an unreadable or invalid message on {}: {}", MqConfig.TOPIC, e.toString());
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
