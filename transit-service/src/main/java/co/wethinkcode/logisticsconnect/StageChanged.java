package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * transit-service's copy of the event delay-stage-service publishes to
 * {@code package-status-topic}.
 *
 * <p>Read tolerantly, but not credulously. Fields this service doesn't use are ignored, so the
 * publisher can add some without breaking it. But the three it does use must be present and
 * valid, or the event can't be built at all: otherwise a missing stage would be read as 0 (a
 * false "on time"), and a stage of 42 or -3 would give a nonsense arrival window.
 *
 * @param timestamp when the stage changed, as an ISO-8601 instant; used to ignore stale events
 */
public record StageChanged(String hubId, int stage, String timestamp) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Without these, a missing or null "stage" silently becomes 0.
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    public StageChanged {
        if (hubId == null || hubId.isBlank()) {
            throw new IllegalArgumentException("the event names no hub");
        }
        if (stage < 0 || stage > EtaCalculator.SHUTDOWN_STAGE) {
            throw new IllegalArgumentException("stage " + stage + " is not between 0 and " + EtaCalculator.SHUTDOWN_STAGE);
        }
        try {
            Instant.parse(timestamp);
        } catch (NullPointerException | DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp '" + timestamp + "' is not an ISO-8601 instant");
        }
    }

    /** Parses one message body; throws if it isn't an event this service can trust. */
    static StageChanged fromJson(String json) throws JsonProcessingException {
        return JSON.readValue(json, StageChanged.class);
    }
}
