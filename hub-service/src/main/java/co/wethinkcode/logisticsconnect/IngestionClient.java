package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Fetches the cleaned hub records from ingestion-service ({@code GET /hubs}). */
final class IngestionClient implements Supplier<List<Hub>> {

    // Tolerant reader: fields this service doesn't know about are ignored, not fatal.
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI hubsUri;

    IngestionClient(String baseUrl) {
        this.hubsUri = URI.create(baseUrl + "/hubs");
    }

    @Override
    public List<Hub> get() {
        HttpRequest request = HttpRequest.newBuilder(hubsUri).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UpstreamUnavailable("ingestion-service answered " + response.statusCode() + " for " + hubsUri);
            }
            return JSON.readValue(response.body(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new UpstreamUnavailable("ingestion-service sent hub data this service could not read", e);
        } catch (IOException e) {
            throw new UpstreamUnavailable("ingestion-service is unreachable at " + hubsUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailable("interrupted while calling ingestion-service", e);
        }
    }
}
