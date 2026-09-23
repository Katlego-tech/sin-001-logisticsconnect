package co.wethinkcode.logisticsconnect;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each hub's Transit Delay Stage, 0 (normal) to 8 (shut down), kept under the hub's canonical
 * ID. A hub whose stage was never set is at stage 0.
 */
final class DelayStages {

    static final int MIN_STAGE = 0;
    static final int MAX_STAGE = 8;

    /** A hub's current stage. {@code updatedAt} is null if it was never set. */
    record Stage(String hubId, int stage, String updatedAt) {
    }

    /** What a change request did. {@code changed} is false if the hub was already at that stage. */
    record Change(String hubId, int stage, int previousStage, String updatedAt, boolean changed) {
    }

    private final Map<String, Stage> stages = new ConcurrentHashMap<>();
    private final Clock clock;

    DelayStages(Clock clock) {
        this.clock = clock;
    }

    Stage current(String hubId) {
        return stages.getOrDefault(hubId, new Stage(hubId, MIN_STAGE, null));
    }

    /** Only the hubs whose stage has been set, in ID order. */
    List<Stage> all() {
        return stages.values().stream().sorted(Comparator.comparing(Stage::hubId)).toList();
    }

    /** Synchronized so two concurrent changes can't both claim the same previous stage. */
    synchronized Change set(Hub hub, int stage) {
        if (stage < MIN_STAGE || stage > MAX_STAGE) {
            throw new IllegalArgumentException("stage must be between " + MIN_STAGE + " and " + MAX_STAGE);
        }
        Stage before = current(hub.hubId());
        if (before.stage() == stage) {
            return new Change(before.hubId(), stage, stage, before.updatedAt(), false);
        }
        String now = clock.instant().toString();
        stages.put(before.hubId(), new Stage(before.hubId(), stage, now));
        return new Change(before.hubId(), stage, before.stage(), now, true);
    }
}
