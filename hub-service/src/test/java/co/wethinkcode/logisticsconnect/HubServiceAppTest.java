package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubServiceAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HubDirectory THREE_HUBS = new HubDirectory(() -> List.of(
            HubDirectoryTest.JOBURG, HubDirectoryTest.PRETORIA, HubDirectoryTest.DURBAN));

    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin app;

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> get(HubDirectory directory, String path) throws IOException, InterruptedException {
        app = HubServiceApp.create(directory).start(0);
        URI uri = URI.create("http://localhost:" + app.port() + path);
        return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listsEveryHub() throws Exception {
        HttpResponse<String> response = get(THREE_HUBS, "/hubs");

        assertEquals(200, response.statusCode());
        JsonNode hubs = JSON.readTree(response.body());
        assertEquals(3, hubs.size());
        assertEquals("H-500", hubs.get(0).get("hubId").asText());
    }

    @Test
    void anAliasAnswersWithTheCanonicalHub() throws Exception {
        HttpResponse<String> response = get(THREE_HUBS, "/hubs/h-504");

        assertEquals(200, response.statusCode());
        assertEquals("H-500", JSON.readTree(response.body()).get("hubId").asText());
    }

    @Test
    void anUnknownHubIs404WithAReason() throws Exception {
        HttpResponse<String> response = get(THREE_HUBS, "/hubs/H-999");

        assertEquals(404, response.statusCode());
        assertEquals("no hub with ID 'H-999'", JSON.readTree(response.body()).get("error").asText());
    }

    @Test
    void groupsSortingCentersByProvince() throws Exception {
        HttpResponse<String> response = get(THREE_HUBS, "/provinces");

        assertEquals(200, response.statusCode());
        JsonNode gauteng = JSON.readTree(response.body()).get(0);
        assertEquals("Gauteng", gauteng.get("province").asText());
        assertEquals(2, gauteng.get("sortingCenters").size());
    }

    @Test
    void noHubDataBecauseIngestionIsDownIs503() throws Exception {
        HubDirectory down = new HubDirectory(() -> {
            throw new UpstreamUnavailable("ingestion-service is unreachable");
        });

        HttpResponse<String> response = get(down, "/hubs/H-500");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("ingestion-service is unreachable"), response.body());
    }
}
