package co.wethinkcode.logisticsconnect;

import java.util.Optional;

/**
 * Decides which stage changes are worth a public post: crossing the threshold, getting worse
 * while over it, reaching stage 8, leaving stage 8, and dropping back under. A hub moving from
 * stage 1 to 2, or easing from 6 to 5, is not news.
 *
 * <p>Needs no memory of its own: each event carries the stage it moved from.
 */
final class AlertPolicy {

    static final int DEFAULT_THRESHOLD = 4;
    static final int SHUTDOWN_STAGE = 8;

    private final int threshold;

    AlertPolicy(int threshold) {
        this.threshold = threshold;
    }

    /**
     * Reads {@code ALERT_THRESHOLD}; unset means 4. It must be 1 to 8: 0 would make every change
     * news, and 9 or more would never alert, so anything else stops startup.
     */
    static int threshold(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            int threshold = Integer.parseInt(value.strip());
            if (threshold >= 1 && threshold <= SHUTDOWN_STAGE) {
                return threshold;
            }
        } catch (NumberFormatException e) {
            // reported below, with the same message as a number out of range
        }
        throw new IllegalArgumentException("ALERT_THRESHOLD must be a whole number from 1 to 8, not '" + value + "'");
    }

    int threshold() {
        return threshold;
    }

    Optional<String> postFor(StageChanged event) {
        String hub = describe(event);
        int stage = event.stage();
        int previous = event.previousStage();

        if (stage >= SHUTDOWN_STAGE && previous < SHUTDOWN_STAGE) {
            return Optional.of("SUSPENDED: deliveries via " + hub + " are suspended (delay stage "
                    + SHUTDOWN_STAGE + "). We'll post again when they resume.");
        }
        if (stage < threshold) {
            return previous >= threshold
                    ? Optional.of("RESOLVED: " + hub + " is back to delay stage " + stage
                            + ". Deliveries are returning to normal.")
                    : Optional.empty();
        }
        // From here on the hub is over the threshold, and not at stage 8.
        if (previous >= SHUTDOWN_STAGE) {
            // The suspension post promised another when deliveries resume.
            return Optional.of("UPDATE: deliveries via " + hub + " have resumed but are still delayed, delay stage "
                    + stage + " of " + SHUTDOWN_STAGE + ".");
        }
        if (previous < threshold) {
            return Optional.of("DELAY ALERT: parcels via " + hub + " are delayed, delay stage "
                    + stage + " of " + SHUTDOWN_STAGE + ". Please allow extra time.");
        }
        if (stage > previous) {
            return Optional.of("UPDATE: delays via " + hub + " are getting worse, now stage "
                    + stage + " (was " + previous + ").");
        }
        return Optional.empty();
    }

    private static String describe(StageChanged event) {
        if (event.sortingCenter() == null || event.sortingCenter().isBlank()) {
            return "hub " + event.hubId();
        }
        boolean hasProvince = event.province() != null && !event.province().isBlank();
        return event.sortingCenter() + (hasProvince ? " (" + event.province() + ")" : "");
    }
}
