package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StageSource.Reading;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitServiceAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC);
    private final List<String> stagesAskedFor = new ArrayList<>();
    private Javalin app;

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> get(HubLookup hubs, StageSource stages, String path) throws Exception {
        app = TransitServiceApp.create(hubs, stages, clock).start(0);
        URI uri = URI.create("http://localhost:" + app.port() + path);
        return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private StageSource stageTwo() {
        return hubId -> {
            stagesAskedFor.add(hubId);
            return new Reading(2, "2026-07-18T09:00:00Z", true);
        };
    }

    @Test
    void combinesTheHubAndItsStageIntoAnEta() throws Exception {
        HttpResponse<String> response = get(id -> Optional.of(EtaCalculatorTest.JOBURG), stageTwo(), "/eta/H-500");

        assertEquals(200, response.statusCode());
        JsonNode eta = JSON.readTree(response.body());
        assertEquals("DELAYED", eta.get("status").asText());
        assertEquals(2, eta.get("stage").asInt());
        assertEquals(48, eta.get("earliestHours").asInt());
        assertEquals(96, eta.get("latestHours").asInt());
        assertEquals("2026-07-20T10:00:00Z", eta.get("earliestArrival").asText());
    }

    @Test
    void anAliasIsAnsweredForTheCanonicalHubWhoseStageIsLookedUp() throws Exception {
        HttpResponse<String> response = get(id -> Optional.of(EtaCalculatorTest.JOBURG), stageTwo(), "/eta/h-504");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("H-500"), stagesAskedFor, "the stage is kept under the canonical ID");
        JsonNode eta = JSON.readTree(response.body());
        assertEquals("H-500", eta.get("hubId").asText());
        assertEquals("h-504 is a duplicate ID for H-500 in the legacy data", eta.get("warnings").get(0).asText());
    }

    @Test
    void anUnknownHubIs404AndNoStageIsAskedFor() throws Exception {
        HttpResponse<String> response = get(id -> Optional.empty(), stageTwo(), "/eta/H-999");

        assertEquals(404, response.statusCode());
        assertEquals("no hub with ID 'H-999'", JSON.readTree(response.body()).get("error").asText());
        assertEquals(List.of(), stagesAskedFor);
    }

    @Test
    void hubServiceDownIs503() throws Exception {
        HubLookup down = id -> {
            throw new UpstreamUnavailable("hub-service is unreachable at http://localhost:7051");
        };

        HttpResponse<String> response = get(down, stageTwo(), "/eta/H-500");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("hub-service is unreachable"), response.body());
    }

    @Test
    void delayStageServiceDownIs503NamingIt() throws Exception {
        StageSource down = id -> {
            throw new UpstreamUnavailable("delay-stage-service is unreachable at http://localhost:7052");
        };

        HttpResponse<String> response = get(id -> Optional.of(EtaCalculatorTest.JOBURG), down, "/eta/H-500");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("delay-stage-service is unreachable"), response.body());
    }
}
