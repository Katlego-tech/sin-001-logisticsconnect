package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;

public class DelayStageServiceApp {

    static final int PORT = 7052;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The request body was not a valid stage change. */
    static class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        String hubServiceUrl = System.getenv().getOrDefault("HUB_SERVICE_URL", "http://localhost:7051");
        create(new DelayStages(Clock.systemUTC()), new HubClient(hubServiceUrl)).start(PORT);
    }

    static Javalin create(DelayStages stages, HubLookup hubs) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // Only the hubs whose stage has been set; every other hub is at stage 0.
        app.get("/delay-stage", ctx -> ctx.json(stages.all()));

        // Both endpoints below resolve the hub through hub-service first, so an alias answers for
        // the hub it belongs to, and an ID that names no hub is a 404 rather than a made-up stage 0.
        app.get("/delay-stage/{hubId}", ctx -> withHub(ctx, hubs, hub -> ctx.json(stages.current(hub.hubId()))));

        // The state-change endpoint. The body is checked before hub-service is asked anything.
        app.post("/delay-stage/{hubId}", ctx -> {
            int stage = parseStage(ctx.body());
            withHub(ctx, hubs, hub -> ctx.json(stages.set(hub, stage)));
        });

        app.exception(InvalidRequest.class,
                (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(UpstreamUnavailable.class,
                (e, ctx) -> ctx.status(503).json(Map.of("error", e.getMessage())));

        return app;
    }

    /** Runs {@code found} with the hub the path's ID or alias names, or answers 404. */
    private static void withHub(Context ctx, HubLookup hubs, Consumer<Hub> found) {
        String hubId = ctx.pathParam("hubId");
        hubs.find(hubId).ifPresentOrElse(
                found,
                () -> ctx.status(404).json(Map.of("error", "no hub with ID '" + hubId + "'")));
    }

    static int parseStage(String body) {
        String expected = "body must be JSON like {\"stage\": 3}, with a whole number from "
                + DelayStages.MIN_STAGE + " to " + DelayStages.MAX_STAGE;
        JsonNode stage;
        try {
            JsonNode root = JSON.readTree(body);
            stage = root == null ? null : root.get("stage");
        } catch (JsonProcessingException e) {
            throw new InvalidRequest(expected);
        }
        if (stage == null || !stage.isIntegralNumber() || !stage.canConvertToInt()) {
            throw new InvalidRequest(expected);
        }
        int value = stage.intValue();
        if (value < DelayStages.MIN_STAGE || value > DelayStages.MAX_STAGE) {
            throw new InvalidRequest(expected + "; got " + value);
        }
        return value;
    }
}
