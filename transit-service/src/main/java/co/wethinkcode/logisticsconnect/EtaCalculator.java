package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StageSource.Reading;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Estimated arrival window for a parcel at a hub, from the hub's location and delay stage.
 *
 * <p>The legacy data has no transit times, so the service levels below are assumptions, kept
 * here in one place: metro hubs deliver in 1-2 days, regional hubs in 2-4. Each delay stage
 * pushes the earliest arrival back 12 hours and the latest back 24, because a delay makes the
 * window both later and less certain. Stage 8 is a shutdown: no estimate at all.
 */
final class EtaCalculator {

    enum Status { ON_TIME, DELAYED, SUSPENDED, HUB_INACTIVE }

    /**
     * @param earliestHours null when no window can be given (status SUSPENDED or HUB_INACTIVE)
     * @param warnings      every assumption the estimate rests on
     */
    record Eta(
            String hubId,
            String sortingCenter,
            String province,
            Status status,
            int stage,
            boolean stageKnown,
            String stageAsOf,
            Integer earliestHours,
            Integer latestHours,
            String earliestArrival,
            String latestArrival,
            List<String> warnings) {
    }

    static final Set<String> METRO_PROVINCES = Set.of("Gauteng", "Western Cape", "KwaZulu-Natal");
    static final int SHUTDOWN_STAGE = 8;

    private EtaCalculator() {
    }

    static Eta estimate(String requestedId, Hub hub, Reading stage, Instant now) {
        List<String> warnings = new ArrayList<>();
        if (!requestedId.strip().equalsIgnoreCase(hub.hubId())) {
            warnings.add(requestedId.strip() + " is a duplicate ID for " + hub.hubId() + " in the legacy data");
        }
        if (!stage.known()) {
            warnings.add("no delay-stage update received for this hub yet, so stage 0 is assumed");
        }

        if (Boolean.FALSE.equals(hub.active())) {
            return noWindow(hub, Status.HUB_INACTIVE, stage, warnings);
        }
        if (hub.active() == null) {
            warnings.add("the source data doesn't say whether this hub is active");
        }
        if (stage.stage() >= SHUTDOWN_STAGE) {
            return noWindow(hub, Status.SUSPENDED, stage, warnings);
        }

        // Checked for null first: Set.of(...).contains(null) throws rather than answering false.
        boolean metro = hub.province() != null && METRO_PROVINCES.contains(hub.province());
        if (hub.province() == null) {
            warnings.add("province unknown, so the regional service level is used");
        }
        int earliest = (metro ? 24 : 48) + 12 * stage.stage();
        int latest = (metro ? 48 : 96) + 24 * stage.stage();
        Instant from = now.truncatedTo(ChronoUnit.SECONDS);
        return new Eta(hub.hubId(), hub.sortingCenter(), hub.province(),
                stage.stage() == 0 ? Status.ON_TIME : Status.DELAYED,
                stage.stage(), stage.known(), stage.asOf(),
                earliest, latest,
                from.plus(Duration.ofHours(earliest)).toString(),
                from.plus(Duration.ofHours(latest)).toString(),
                warnings);
    }

    private static Eta noWindow(Hub hub, Status status, Reading stage, List<String> warnings) {
        return new Eta(hub.hubId(), hub.sortingCenter(), hub.province(), status,
                stage.stage(), stage.known(), stage.asOf(), null, null, null, null, warnings);
    }
}
