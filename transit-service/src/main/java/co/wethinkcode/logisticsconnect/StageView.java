package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * transit-service's own copy of every hub's delay stage, built from the events on
 * {@code package-status-topic}. An ETA request reads this map and calls nobody.
 *
 * <p>The copy is eventually consistent: it is exactly as current as the last event that
 * arrived. A hub with no event yet reads as {@link Reading#UNKNOWN}, which the ETA reports as
 * an assumption rather than a fact.
 *
 * <p>It is saved to a file after every change and loaded at startup. The durable subscription
 * only replays events this service <i>missed</i>; the ones it already consumed live here. Kept
 * only in memory, a restart would forget every stage it had been told about.
 */
final class StageView implements StageSource {

    private static final Logger log = LoggerFactory.getLogger(StageView.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Reading> byHub = new ConcurrentHashMap<>();
    private final Optional<Path> file;

    /** In memory only. */
    StageView() {
        this.file = Optional.empty();
    }

    /** Loads the view saved at {@code file}, if there is one, and saves every change back to it. */
    StageView(Path file) {
        this.file = Optional.of(file);
        if (Files.exists(file)) {
            try {
                byHub.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Reading>>() {
                }));
                log.info("Loaded the delay stages of {} hub(s) from {}", byHub.size(), file);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read the saved stage view " + file, e);
            }
        }
    }

    /**
     * Applies an event unless it is older than the one already applied for that hub, so a
     * redelivered or reordered message can never roll a hub back to a stale stage.
     *
     * <p>Called from the message listener, so the file is written before the message is
     * acknowledged: a crash in between means a redelivery, which this method shrugs off.
     */
    synchronized void apply(StageChanged event) {
        Instant at = Instant.parse(event.timestamp());
        Reading current = byHub.get(event.hubId());
        if (current != null && at.isBefore(Instant.parse(current.asOf()))) {
            return;
        }
        byHub.put(event.hubId(), new Reading(event.stage(), event.timestamp(), true));
        file.ifPresent(this::save);
    }

    @Override
    public Reading stageFor(String hubId) {
        return byHub.getOrDefault(hubId, Reading.UNKNOWN);
    }

    /** Write-then-rename, so a crash mid-write leaves the previous file intact, never half a file. */
    private void save(Path target) {
        try {
            Path dir = target.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "stage-view", ".tmp");
            JSON.writeValue(tmp.toFile(), new TreeMap<>(byHub));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not save the stage view to {}; it will be lost on restart", target, e);
        }
    }
}
