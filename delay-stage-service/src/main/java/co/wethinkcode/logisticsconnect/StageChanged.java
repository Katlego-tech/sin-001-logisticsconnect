package co.wethinkcode.logisticsconnect;

/**
 * The event published to {@code package-status-topic} when a hub's delay stage changes.
 *
 * <p>It carries the hub's name and province as well as its ID, so a subscriber like alertbot
 * can describe the hub without calling hub-service. {@code previousStage} lets a subscriber see
 * a threshold being crossed without remembering anything itself.
 *
 * @param timestamp ISO-8601 instant of the change
 */
public record StageChanged(
        String hubId,
        String sortingCenter,
        String province,
        int stage,
        int previousStage,
        String timestamp) {
}
