package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.wethinkcode.logisticsconnect.StagePublisher.PublishFailed;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayStageServiceAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StageChanged> published = new ArrayList<>();
    private final DelayStages stages = new DelayStages(published::add, Clock.systemUTC());
    private Javalin app;

    @AfterEach
    void stop() {
        app.stop();
    }

    /** hub-service as this service sees it: H-500 and its alias H-504, and nothing else. */
    private static Optional<Hub> onlyJoburg(String hubId) {
        return List.of("H-500", "H-504").contains(hubId.toUpperCase(Locale.ROOT))
                ? Optional.of(DelayStagesTest.JOBURG)
                : Optional.empty();
    }

    private static final HubLookup HUB_SERVICE_DOWN = hubId -> {
        throw new UpstreamUnavailable("hub-service is unreachable at http://localhost:7051");
    };

    private void start(HubLookup hubs) {
        start(hubs, stages);
    }

    private void start(HubLookup hubs, DelayStages withStages) {
        app = DelayStageServiceApp.create(withStages, hubs).start(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + app.port() + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aChangeByAliasIsRecordedUnderTheCanonicalId() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);

        HttpResponse<String> response = post("/delay-stage/h-504", "{\"stage\": 5}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode change = JSON.readTree(response.body());
        assertEquals("H-500", change.get("hubId").asText());
        assertEquals(5, change.get("stage").asInt());
        assertEquals(0, change.get("previousStage").asInt());
        assertTrue(change.get("changed").asBoolean());
        assertEquals(5, stages.current("H-500").stage());
        assertEquals(1, published.size());
        assertEquals("H-500", published.get(0).hubId(), "the event carries the canonical ID too");
        assertEquals("Johannesburg Central", published.get(0).sortingCenter());
    }

    @Test
    void readingByAliasAnswersForTheCanonicalHub() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);
        stages.set(DelayStagesTest.JOBURG, 5);

        HttpResponse<String> response = get("/delay-stage/H-504");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode stage = JSON.readTree(response.body());
        assertEquals("H-500", stage.get("hubId").asText());
        assertEquals(5, stage.get("stage").asInt());
    }

    @Test
    void readingAHubThatDoesNotExistIs404NotStageZero() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);

        HttpResponse<String> response = get("/delay-stage/H-999");

        assertEquals(404, response.statusCode(), response.body());
        assertEquals("no hub with ID 'H-999'", JSON.readTree(response.body()).get("error").asText());
    }

    @Test
    void aHubNeverSetReadsAsStageZeroWithNoTime() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);

        JsonNode stage = JSON.readTree(get("/delay-stage/H-500").body());

        assertEquals(0, stage.get("stage").asInt());
        assertTrue(stage.get("updatedAt").isNull());
    }

    @Test
    void listsTheHubsWhoseStageWasSet() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);
        post("/delay-stage/H-500", "{\"stage\": 2}");

        JsonNode all = JSON.readTree(get("/delay-stage").body());

        assertEquals(1, all.size());
        assertEquals("H-500", all.get(0).get("hubId").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"stage\": 9}", "{\"stage\": -1}", "{\"stage\": 2.5}", "{\"stage\": \"3\"}",
            "{}", "not json", ""})
    void anInvalidStageIs400AndNothingChanges(String body) throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);

        HttpResponse<String> response = post("/delay-stage/H-500", body);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).get("error").asText().startsWith("body must be JSON like"));
        assertEquals(List.of(), stages.all());
        assertTrue(published.isEmpty());
    }

    @Test
    void changingAHubThatDoesNotExistIs404() throws Exception {
        start(DelayStageServiceAppTest::onlyJoburg);

        HttpResponse<String> response = post("/delay-stage/H-999", "{\"stage\": 2}");

        assertEquals(404, response.statusCode());
        assertEquals(List.of(), stages.all());
        assertTrue(published.isEmpty());
    }

    @Test
    void hubServiceDownIs503ForChangesAndReads() throws Exception {
        start(HUB_SERVICE_DOWN);

        HttpResponse<String> change = post("/delay-stage/H-500", "{\"stage\": 2}");
        HttpResponse<String> read = get("/delay-stage/H-500");

        assertEquals(503, change.statusCode());
        assertEquals(503, read.statusCode());
        assertTrue(read.body().contains("hub-service is unreachable"), read.body());
        assertEquals(List.of(), stages.all());
        assertTrue(published.isEmpty());
    }

    @Test
    void brokerDownIs503StageNotChangedAndTheStageStaysAsItWas() throws Exception {
        DelayStages brokerDown = new DelayStages(event -> {
            throw new PublishFailed("could not publish to package-status-topic: Connection refused", null);
        }, Clock.systemUTC());
        start(DelayStageServiceAppTest::onlyJoburg, brokerDown);

        HttpResponse<String> change = post("/delay-stage/H-500", "{\"stage\": 6}");

        assertEquals(503, change.statusCode());
        assertEquals("stage not changed: could not publish to package-status-topic: Connection refused",
                JSON.readTree(change.body()).get("error").asText());
        assertEquals(0, JSON.readTree(get("/delay-stage/H-500").body()).get("stage").asInt());
    }
}
