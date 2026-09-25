package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The simulated social-media page. Every post is logged and kept (newest first) for
 * {@code GET /posts}. If a webhook URL is configured, each post is also sent there as
 * {@code {"text": "..."}}, the shape Slack/Discord-style incoming webhooks accept.
 */
final class SocialFeed {

    /** @param delivery how the post went out: logged only, or the webhook's outcome */
    record Post(String hubId, int stage, String text, String postedAt, String delivery) {
    }

    private static final Logger log = LoggerFactory.getLogger(SocialFeed.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int KEEP = 50;

    private final Deque<Post> posts = new ArrayDeque<>();
    private final Optional<URI> webhook;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    SocialFeed(Optional<URI> webhook, Clock clock) {
        this.webhook = webhook;
        this.clock = clock;
    }

    void post(StageChanged event, String text) {
        String delivery = webhook.map(uri -> send(uri, text)).orElse("simulated: logged only");
        log.info("[SOCIAL POST] {}  ({})", text, delivery);
        synchronized (posts) {
            posts.addFirst(new Post(event.hubId(), event.stage(), text, clock.instant().toString(), delivery));
            if (posts.size() > KEEP) {
                posts.removeLast();
            }
        }
    }

    List<Post> recent() {
        synchronized (posts) {
            return List.copyOf(posts);
        }
    }

    private String send(URI uri, String text) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("text", text))))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 ? "webhook " + status : "webhook failed: HTTP " + status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "webhook failed: interrupted";
        } catch (Exception e) {
            log.warn("Webhook {} failed: {}", uri, e.toString());
            return "webhook failed: " + e.getClass().getSimpleName();
        }
    }
}
