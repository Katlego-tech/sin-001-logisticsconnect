package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class HubServiceApp {

    static final int PORT = 7051;
    private static final Logger log = LoggerFactory.getLogger(HubServiceApp.class);

    public static void main(String[] args) {
        String ingestionUrl = System.getenv().getOrDefault("INGESTION_URL", "http://localhost:7050");
        HubDirectory directory = new HubDirectory(new IngestionClient(ingestionUrl));
        try {
            log.info("Loaded {} hubs from {}", directory.all().size(), ingestionUrl);
        } catch (UpstreamUnavailable e) {
            log.warn("Starting without hub data ({}); will retry on the next request", e.getMessage());
        }
        create(directory).start(PORT);
    }

    static Javalin create(HubDirectory directory) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs", ctx -> ctx.json(directory.all()));

        // An alias (a duplicate ID from the legacy export) resolves to the hub it was merged into.
        app.get("/hubs/{hubId}", ctx -> {
            String hubId = ctx.pathParam("hubId");
            directory.find(hubId).ifPresentOrElse(
                    ctx::json,
                    () -> ctx.status(404).json(Map.of("error", "no hub with ID '" + hubId + "'")));
        });

        app.get("/provinces", ctx -> ctx.json(directory.provinces()));

        app.exception(UpstreamUnavailable.class,
                (e, ctx) -> ctx.status(503).json(Map.of("error", e.getMessage())));

        return app;
    }
}
