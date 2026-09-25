package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * alertbot's copy of the event delay-stage-service publishes to {@code package-status-topic}.
 *
 * <p>Read tolerantly, but not credulously. Fields this service doesn't know are ignored, and the
 * hub's name and province may be missing (a post then names the hub by ID). But the hub ID, both
 * stages and the timestamp must be present and valid, or the event can't be built at all:
 * otherwise a missing stage would be read as 0, and a public post could announce "stage 42".
 */
public record StageChanged(
        String hubId,
        String sortingCenter,
        String province,
        // Required, or Jackson would read a missing stage as 0.
        @JsonProperty(required = true) int stage,
        @JsonProperty(required = true) int previousStage,
        String timestamp) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // A null stage would otherwise also become 0.
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    public StageChanged {
        if (hubId == null || hubId.isBlank()) {
            throw new IllegalArgumentException("the event names no hub");
        }
        requireStage("stage", stage);
        requireStage("previousStage", previousStage);
        try {
            Instant.parse(timestamp);
        } catch (NullPointerException | DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp '" + timestamp + "' is not an ISO-8601 instant");
        }
    }

    private static void requireStage(String name, int value) {
        if (value < 0 || value > AlertPolicy.SHUTDOWN_STAGE) {
            throw new IllegalArgumentException(name + " " + value + " is not between 0 and " + AlertPolicy.SHUTDOWN_STAGE);
        }
    }

    /** Parses one message body; throws if it isn't an event this service can trust. */
    static StageChanged fromJson(String json) throws JsonProcessingException {
        return JSON.readValue(json, StageChanged.class);
    }
}
