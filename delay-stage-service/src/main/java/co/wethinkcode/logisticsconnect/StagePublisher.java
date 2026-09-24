package co.wethinkcode.logisticsconnect;

/**
 * Broadcasts stage changes. Throws {@link PublishFailed} if the event certainly did not reach
 * the broker, and {@link PublishOutcomeUnknown} if it was sent but never confirmed.
 */
@FunctionalInterface
interface StagePublisher {

    void publish(StageChanged event);

    class PublishFailed extends RuntimeException {
        PublishFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The event was sent but the broker didn't confirm it in time. It may still be delivered once
     * the broker recovers, so subscribers may or may not hear of it.
     */
    class PublishOutcomeUnknown extends PublishFailed {
        PublishOutcomeUnknown(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
