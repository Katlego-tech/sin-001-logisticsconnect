package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client against a real HTTP server standing in for hub-service. */
class HubClientTest {

    private Javalin stub;

    @AfterEach
    void stop() {
        stub.stop();
    }

    /** A hub-service that knows H-500 (also by " h 500", to prove the ID is encoded, not mangled). */
    private HubClient hubService() {
        stub = Javalin.create()
                .get("/hubs/{hubId}", ctx -> {
                    String id = ctx.pathParam("hubId");
                    if (id.equals("H-500") || id.equals(" h 500")) {
                        ctx.contentType("application/json").result("""
                                {"hubId":"H-500","province":"Gauteng","sortingCenter":"Johannesburg Central",
                                 "active":true,"aliases":["H-504"],"notes":[]}""");
                    } else {
                        ctx.status(404).result("{\"error\":\"no hub\"}");
                    }
                })
                .start(0);
        return new HubClient("http://localhost:" + stub.port());
    }

    private HubClient answering(int status, String body) {
        stub = Javalin.create().get("/hubs/{hubId}", ctx -> ctx.status(status).result(body)).start(0);
        return new HubClient("http://localhost:" + stub.port());
    }

    @Test
    void readsTheHubIgnoringFieldsThisServiceDoesNotUse() {
        assertEquals(Optional.of(new Hub("H-500", "Gauteng", "Johannesburg Central")), hubService().find("H-500"));
    }

    @Test
    void an404IsNoSuchHub() {
        assertEquals(Optional.empty(), hubService().find("H-999"));
    }

    @Test
    void anIdWithSpacesIsSentEncoded() {
        assertEquals("H-500", hubService().find(" h 500").orElseThrow().hubId());
    }

    @Test
    void anyOtherStatusIsUpstreamUnavailableNotNoSuchHub() {
        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> answering(500, "{}").find("H-500"));

        assertTrue(e.getMessage().startsWith("hub-service answered 500"), e.getMessage());
    }

    @Test
    void anUnreadableBodyIsUpstreamUnavailable() {
        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> answering(200, "not json").find("H-500"));

        assertTrue(e.getMessage().contains("could not read"), e.getMessage());
    }

    @Test
    void nothingListeningIsUpstreamUnavailable() {
        HubClient client = hubService();
        stub.stop();

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> client.find("H-500"));
        assertTrue(e.getMessage().startsWith("hub-service is unreachable"), e.getMessage());
    }
}
