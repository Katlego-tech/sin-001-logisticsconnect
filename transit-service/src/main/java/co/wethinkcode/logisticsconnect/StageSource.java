package co.wethinkcode.logisticsconnect;

/**
 * Where transit-service gets a hub's current delay stage. Kept behind an interface so the ETA
 * endpoint doesn't care how the stage is found: {@link RestStageSource} asks delay-stage-service
 * on every request, and {@link StageView} answers from the events it has received on
 * {@code package-status-topic}, calling nobody.
 */
@FunctionalInterface
interface StageSource {

    /**
     * @param stage  0 to 8
     * @param asOf   ISO-8601 instant the stage was set, or null if it never was
     * @param known  false when this service has no information and is assuming stage 0
     */
    record Reading(int stage, String asOf, boolean known) {

        static final Reading UNKNOWN = new Reading(0, null, false);
    }

    Reading stageFor(String hubId);
}
