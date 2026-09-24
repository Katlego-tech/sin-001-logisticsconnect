package co.wethinkcode.logisticsconnect;

/** Broadcasts stage changes. Throws {@link PublishFailed} if the event did not reach the broker. */
@FunctionalInterface
interface StagePublisher {

    void publish(StageChanged event);

    class PublishFailed extends RuntimeException {
        PublishFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
