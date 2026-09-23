package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks delay-stage-service for the hub's stage on every ETA request
 * ({@code GET /delay-stage/{hubId}}). Simple and always current, but every ETA then depends on
 * delay-stage-service being up: if it isn't, the ETA is a 503.
 */
final class RestStageSource implements StageSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;

    RestStageSource(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Reading stageFor(String hubId) {
        String segment = URLEncoder.encode(hubId, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(baseUrl + "/delay-stage/" + segment);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UpstreamUnavailable("delay-stage-service answered " + response.statusCode() + " for " + uri);
            }
            JsonNode body = JSON.readTree(response.body());
            JsonNode stage = body.path("stage");
            JsonNode updatedAt = body.path("updatedAt");
            // Checked strictly: asInt() would read a missing or garbled stage as 0, reporting a
            // hub as on time because of a reply this service didn't understand.
            if (!stage.isInt() || stage.intValue() < 0 || stage.intValue() > EtaCalculator.SHUTDOWN_STAGE
                    || !(updatedAt.isTextual() || updatedAt.isNull())) {
                throw new UpstreamUnavailable("delay-stage-service sent a stage this service could not read: "
                        + response.body());
            }
            // delay-stage-service is the authority, so even "never set" (stage 0) is a known answer.
            return new Reading(stage.intValue(), updatedAt.isTextual() ? updatedAt.asText() : null, true);
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("delay-stage-service sent a stage this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("delay-stage-service is unreachable at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling delay-stage-service", e);
        }
    }
}
