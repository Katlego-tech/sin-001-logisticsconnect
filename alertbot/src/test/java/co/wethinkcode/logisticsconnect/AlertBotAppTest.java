package co.wethinkcode.logisticsconnect;

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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertBotAppTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final AlertPolicy policy = new AlertPolicy(4);
    private final SocialFeed feed = new SocialFeed(Optional.empty(),
            Clock.fixed(Instant.parse("2026-07-18T10:00:01Z"), ZoneOffset.UTC));
    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private JsonNode getPosts() throws Exception {
        app = AlertBotApp.create(feed, policy).start(0);
        URI uri = URI.create("http://localhost:" + app.port() + "/posts");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private static StageChanged change(int from, int to) {
        return new StageChanged("H-500", "Johannesburg Central", "Gauteng", to, from, "2026-07-18T10:00:00Z");
    }

    @Test
    void withNothingPostedTheFeedIsEmptyAndShowsTheThreshold() throws Exception {
        JsonNode body = getPosts();

        assertEquals(4, body.get("threshold").asInt());
        assertEquals(0, body.get("posts").size());
    }

    @Test
    void aCrossingIsPostedAndServedWithEveryContractField() throws Exception {
        AlertBotApp.react(change(2, 5), policy, feed);

        JsonNode post = getPosts().get("posts").get(0);
        assertEquals("H-500", post.get("hubId").asText());
        assertEquals(5, post.get("stage").asInt());
        assertTrue(post.get("text").asText().startsWith("DELAY ALERT: parcels via Johannesburg Central"));
        assertEquals("2026-07-18T10:00:01Z", post.get("postedAt").asText());
        assertEquals("simulated: logged only", post.get("delivery").asText());
    }

    @Test
    void aChangeThatIsNotNewsIsNotPosted() throws Exception {
        AlertBotApp.react(change(1, 2), policy, feed);

        assertEquals(0, getPosts().get("posts").size());
    }
}
