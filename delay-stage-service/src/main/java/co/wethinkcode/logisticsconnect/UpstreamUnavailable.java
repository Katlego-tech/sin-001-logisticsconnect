package co.wethinkcode.logisticsconnect;

/** A service this one depends on could not be reached, or answered with something unusable. */
public class UpstreamUnavailable extends RuntimeException {

    public UpstreamUnavailable(String message) {
        super(message);
    }

    public UpstreamUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
