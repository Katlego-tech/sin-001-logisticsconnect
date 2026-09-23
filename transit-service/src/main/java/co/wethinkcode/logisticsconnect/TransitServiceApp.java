package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.time.Clock;
import java.util.Map;

public class TransitServiceApp {

    static final int PORT = 7053;

    public static void main(String[] args) {
        HubClient hubs = new HubClient(System.getenv().getOrDefault("HUB_SERVICE_URL", "http://localhost:7051"));
        RestStageSource stages = new RestStageSource(System.getenv().getOrDefault("DELAY_STAGE_URL", "http://localhost:7052"));
        create(hubs, stages, Clock.systemUTC()).start(PORT);
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
