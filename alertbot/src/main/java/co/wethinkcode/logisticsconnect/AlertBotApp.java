package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

public class AlertBotApp {

    static final int PORT = 7054;
    private static final Logger log = LoggerFactory.getLogger(AlertBotApp.class);

    public static void main(String[] args) {
        AlertPolicy policy = new AlertPolicy(AlertPolicy.threshold(System.getenv("ALERT_THRESHOLD")));
        Optional<URI> webhook = Optional.ofNullable(System.getenv("ALERTBOT_WEBHOOK_URL"))
                .filter(url -> !url.isBlank())
                .map(URI::create);
        SocialFeed feed = new SocialFeed(webhook, Clock.systemUTC());

        // Bind the port before subscribing. If the port is taken, startup fails here, before a
        // subscriber thread exists that would keep a half-started process alive (and posting).
        create(feed, policy).start(PORT);

        StageSubscriber subscriber = new StageSubscriber(MqConfig.BROKER_URL, event -> react(event, policy, feed));
        subscriber.start();
        Runtime.getRuntime().addShutdownHook(new Thread(subscriber::close));
        log.info("Posting when a hub reaches delay stage {} or more; webhook: {}",
                policy.threshold(), webhook.map(URI::toString).orElse("none (simulated)"));
    }

    static void react(StageChanged event, AlertPolicy policy, SocialFeed feed) {
        policy.postFor(event).ifPresentOrElse(
                text -> feed.post(event, text),
                () -> log.info("{} moved from stage {} to {}: not news at threshold {}, nothing to post",
                        event.hubId(), event.previousStage(), event.stage(), policy.threshold()));
    }

    static Javalin create(SocialFeed feed, AlertPolicy policy) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // What would have gone to the social pages, newest first.
        app.get("/posts", ctx -> ctx.json(Map.of("threshold", policy.threshold(), "posts", feed.recent())));

        return app;
    }
}
