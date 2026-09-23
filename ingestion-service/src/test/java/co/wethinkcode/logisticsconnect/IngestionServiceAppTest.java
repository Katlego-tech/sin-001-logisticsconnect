package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cleaned records over HTTP, the way hub-service will read them. */
class IngestionServiceAppTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Javalin app;

    @BeforeAll
    static void start() throws IOException {
        app = IngestionServiceApp.create(IngestionServiceApp.cleanBundledCsv()).start(0);
    }

    @AfterAll
    static void stop() {
        app.stop();
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + app.port() + path);
        return HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheCleanedHubsAsJson() throws Exception {
        HttpResponse<String> response = get("/hubs");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode hubs = JSON.readTree(response.body());
        assertEquals(10, hubs.size());
        JsonNode first = hubs.get(0);
        assertEquals("H-500", first.get("hubId").asText());
        assertEquals("Johannesburg Central", first.get("sortingCenter").asText());
        assertEquals(3, first.get("aliases").size());
    }

    @Test
    void anUnknownValueIsSentAsNullNotLeftOut() throws Exception {
        JsonNode hubs = JSON.readTree(get("/hubs").body());

        JsonNode polokwane = null;
        for (JsonNode hub : hubs) {
            if (hub.get("hubId").asText().equals("H-511")) {
                polokwane = hub;
            }
        }
        assertTrue(polokwane != null && polokwane.has("active") && polokwane.get("active").isNull(),
                () -> "H-511 should carry \"active\": null");
    }

    @Test
    void theReportSaysHowTheRecordsWereDerived() throws Exception {
        HttpResponse<String> response = get("/report");

        assertEquals(200, response.statusCode());
        JsonNode report = JSON.readTree(response.body());
        assertEquals(18, report.get("rowsRead").asInt());
        assertEquals(0, report.get("rejected").size());
        assertEquals(0, report.get("ignoredColumns").size());
        assertEquals(10, report.get("hubs").size());
    }

    @Test
    void healthStillAnswers() throws Exception {
        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }
}
