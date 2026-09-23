package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client against a real HTTP server standing in for ingestion-service. */
class IngestionClientTest {

    private Javalin stub;

    @AfterEach
    void stop() {
        stub.stop();
    }

    private IngestionClient clientFor(int status, String body) {
        stub = Javalin.create()
                .get("/hubs", ctx -> ctx.status(status).contentType("application/json").result(body))
                .start(0);
        return new IngestionClient("http://localhost:" + stub.port());
    }

    @Test
    void readsTheHubsAndIgnoresFieldsItDoesNotKnow() {
        IngestionClient client = clientFor(200, """
                [{"hubId":"H-500","province":"Gauteng","sortingCenter":"Johannesburg Central","active":true,
                  "aliases":["H-504"],"notes":[],"addedLater":"ignored"}]""");

        List<Hub> hubs = client.get();

        assertEquals(List.of(new Hub("H-500", "Gauteng", "Johannesburg Central", true, List.of("H-504"), List.of())), hubs);
    }

    @Test
    void missingListsAreEmptyNotNull() {
        Hub hub = clientFor(200, "[{\"hubId\":\"H-1\"}]").get().get(0);

        assertEquals(List.of(), hub.aliases());
        assertEquals(List.of(), hub.notes());
    }

    @Test
    void anErrorStatusIsUpstreamUnavailableNamingTheService() {
        IngestionClient client = clientFor(500, "{}");

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, client::get);
        assertTrue(e.getMessage().startsWith("ingestion-service answered 500"), e.getMessage());
    }

    @Test
    void anUnreadableBodyIsUpstreamUnavailable() {
        IngestionClient client = clientFor(200, "not json");

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, client::get);
        assertTrue(e.getMessage().contains("could not read"), e.getMessage());
    }

    @Test
    void nothingListeningIsUpstreamUnavailable() {
        IngestionClient client = clientFor(200, "[]");
        stub.stop();

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, client::get);
        assertTrue(e.getMessage().startsWith("ingestion-service is unreachable"), e.getMessage());
    }
}
