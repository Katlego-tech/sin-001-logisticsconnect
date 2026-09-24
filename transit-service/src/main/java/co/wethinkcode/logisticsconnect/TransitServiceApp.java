package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;

public class TransitServiceApp {

    static final int PORT = 7053;
    private static final Logger log = LoggerFactory.getLogger(TransitServiceApp.class);

    /** Where stages come from: the topic subscription (the default), or a REST call per ETA. */
    enum StageMode { MQ, REST }

    /**
     * {@code STAGE_SOURCE=rest} puts back the REST call to delay-stage-service on every ETA; by
     * default stages come from the {@code package-status-topic} subscription and no call is made.
     * Both are kept so the swap is visible in one place.
     */
    public static void main(String[] args) {
        HubClient hubs = new HubClient(System.getenv().getOrDefault("HUB_SERVICE_URL", "http://localhost:7051"));

        if (stageMode(System.getenv("STAGE_SOURCE")) == StageMode.REST) {
            String delayStageUrl = System.getenv().getOrDefault("DELAY_STAGE_URL", "http://localhost:7052");
            create(hubs, new RestStageSource(delayStageUrl), Clock.systemUTC()).start(PORT);
            log.info("Stage source: REST calls to {}", delayStageUrl);
            return;
        }

        StageView view = new StageView(Path.of(System.getenv().getOrDefault("STAGE_VIEW_FILE", "data/stage-view.json")));
        // Bind the port before subscribing. If the port is taken, startup fails here, before a
        // subscriber thread exists that would keep a half-started process alive.
        create(hubs, view, Clock.systemUTC()).start(PORT);
        StageSubscriber subscriber = new StageSubscriber(MqConfig.BROKER_URL, view::apply);
        subscriber.start();
        Runtime.getRuntime().addShutdownHook(new Thread(subscriber::close));
        log.info("Stage source: durable subscription to {} at {}", MqConfig.TOPIC, MqConfig.BROKER_URL);
    }

    /** Unset means the topic. Anything but "rest" or "mq" stops startup: a typo must not quietly pick one. */
    static StageMode stageMode(String value) {
        if (value == null || value.isBlank()) {
            return StageMode.MQ;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "mq" -> StageMode.MQ;
            case "rest" -> StageMode.REST;
            default -> throw new IllegalArgumentException("STAGE_SOURCE must be 'rest' or 'mq', not '" + value + "'");
        };
    }

    static Javalin create(HubLookup hubs, StageSource stages, Clock clock) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // The hub is resolved first (an alias answers for its hub), then its stage is looked up
        // under the canonical ID.
        app.get("/eta/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId");
            hubs.find(hubId).ifPresentOrElse(
                    hub -> ctx.json(EtaCalculator.estimate(hubId, hub, stages.stageFor(hub.hubId()), clock.instant())),
                    () -> ctx.status(404).json(Map.of("error", "no hub with ID '" + hubId + "'")));
        });

        app.exception(UpstreamUnavailable.class,
                (e, ctx) -> ctx.status(503).json(Map.of("error", e.getMessage())));

        return app;
    }
}
