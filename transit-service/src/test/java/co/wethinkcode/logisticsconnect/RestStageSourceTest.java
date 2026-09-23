package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StageSource.Reading;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client against a real HTTP server standing in for delay-stage-service. */
class RestStageSourceTest {

    private Javalin stub;

    @AfterEach
    void stop() {
        stub.stop();
    }

    private RestStageSource answering(int status, String body) {
        stub = Javalin.create()
                .get("/delay-stage/{hubId}", ctx -> ctx.status(status).contentType("application/json").result(body))
                .start(0);
        return new RestStageSource("http://localhost:" + stub.port());
    }

    @Test
    void readsTheStageAndWhenItWasSet() {
        RestStageSource source = answering(200, "{\"hubId\":\"H-500\",\"stage\":5,\"updatedAt\":\"2026-07-18T10:15:00Z\"}");

        assertEquals(new Reading(5, "2026-07-18T10:15:00Z", true), source.stageFor("H-500"));
    }

    @Test
    void aStageNeverSetIsStillAKnownAnswer() {
        RestStageSource source = answering(200, "{\"hubId\":\"H-500\",\"stage\":0,\"updatedAt\":null}");

        assertEquals(new Reading(0, null, true), source.stageFor("H-500"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"hubId\":\"H-500\"}", "{\"stage\":\"five\"}", "{\"stage\":9}", "{\"stage\":2.5}",
            "[]", "not json"})
    void aMissingOrMalformedStageIsUpstreamUnavailableNotStageZero(String body) {
        RestStageSource source = answering(200, body);

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> source.stageFor("H-500"));
        assertTrue(e.getMessage().contains("could not read"), e.getMessage());
    }

    @Test
    void anErrorStatusIsUpstreamUnavailable() {
        RestStageSource source = answering(404, "{\"error\":\"no hub\"}");

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> source.stageFor("H-500"));
        assertTrue(e.getMessage().startsWith("delay-stage-service answered 404"), e.getMessage());
    }

    @Test
    void nothingListeningIsUpstreamUnavailable() {
        RestStageSource source = answering(200, "{}");
        stub.stop();

        UpstreamUnavailable e = assertThrows(UpstreamUnavailable.class, () -> source.stageFor("H-500"));
        assertTrue(e.getMessage().startsWith("delay-stage-service is unreachable"), e.getMessage());
    }
}
