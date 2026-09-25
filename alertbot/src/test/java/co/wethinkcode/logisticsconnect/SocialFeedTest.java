package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocialFeedTest {

    private static final StageChanged EVENT =
            new StageChanged("H-500", "Johannesburg Central", "Gauteng", 5, 2, "2026-07-18T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T10:00:01Z"), ZoneOffset.UTC);

    @Test
    void withoutAWebhookThePostIsOnlySimulated() {
        SocialFeed feed = new SocialFeed(Optional.empty(), CLOCK);

        feed.post(EVENT, "DELAY ALERT: ...");

        assertEquals(List.of(new SocialFeed.Post("H-500", 5, "DELAY ALERT: ...", "2026-07-18T10:00:01Z",
                "simulated: logged only")), feed.recent());
    }

    @Test
    void withAWebhookThePostIsSentThereAsText() {
        List<String> received = new CopyOnWriteArrayList<>();
        Javalin webhook = Javalin.create().post("/hook", ctx -> received.add(ctx.body())).start(0);
        try {
            SocialFeed feed = new SocialFeed(Optional.of(URI.create("http://localhost:" + webhook.port() + "/hook")), CLOCK);

            feed.post(EVENT, "DELAY ALERT: \"quoted\"");

            assertEquals(List.of("{\"text\":\"DELAY ALERT: \\\"quoted\\\"\"}"), received);
            assertEquals("webhook 200", feed.recent().get(0).delivery());
        } finally {
            webhook.stop();
        }
    }

    @Test
    void aWebhookThatRefusesThePostIsRecordedAsFailed() {
        Javalin webhook = Javalin.create().post("/hook", ctx -> ctx.status(500)).start(0);
        try {
            SocialFeed feed = new SocialFeed(Optional.of(URI.create("http://localhost:" + webhook.port() + "/hook")), CLOCK);

            feed.post(EVENT, "DELAY ALERT: ...");

            assertEquals("webhook failed: HTTP 500", feed.recent().get(0).delivery());
        } finally {
            webhook.stop();
        }
    }

    @Test
    void anUnreachableWebhookIsRecordedNotThrown() {
        SocialFeed feed = new SocialFeed(Optional.of(URI.create("http://localhost:1/hook")), CLOCK);

        feed.post(EVENT, "DELAY ALERT: ...");

        assertEquals("webhook failed: ConnectException", feed.recent().get(0).delivery());
    }

    @Test
    void keepsTheFiftyNewestPostsNewestFirst() {
        SocialFeed feed = new SocialFeed(Optional.empty(), CLOCK);

        for (int i = 1; i <= 51; i++) {
            feed.post(EVENT, "post " + i);
        }

        List<SocialFeed.Post> recent = feed.recent();
        assertEquals(50, recent.size());
        assertEquals("post 51", recent.get(0).text());
        assertEquals("post 2", recent.get(49).text());
    }
}
