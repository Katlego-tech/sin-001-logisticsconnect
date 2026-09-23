package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Looks hubs up in hub-service ({@code GET /hubs/{hubId}}). */
final class HubClient implements HubLookup {

    // Tolerant reader: fields this service doesn't know about are ignored, not fatal.
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;

    HubClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<Hub> find(String hubId) {
        String segment = URLEncoder.encode(hubId, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(baseUrl + "/hubs/" + segment);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> Optional.of(JSON.readValue(response.body(), Hub.class));
                case 404 -> Optional.empty();
                default -> throw new UpstreamUnavailable("hub-service answered " + response.statusCode() + " for " + uri);
            };
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("hub-service sent a hub this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("hub-service is unreachable at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling hub-service", e);
        }
    }
}
