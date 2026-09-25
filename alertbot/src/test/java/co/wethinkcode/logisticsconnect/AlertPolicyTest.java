package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One test per edge of the threshold state machine (threshold 4 unless said otherwise). */
class AlertPolicyTest {

    private final AlertPolicy policy = new AlertPolicy(4);

    private static StageChanged change(int from, int to) {
        return new StageChanged("H-500", "Johannesburg Central", "Gauteng", to, from, "2026-07-18T10:00:00Z");
    }

    @Test
    void crossingTheThresholdPostsAnAlert() {
        assertEquals(Optional.of("DELAY ALERT: parcels via Johannesburg Central (Gauteng) are delayed, "
                + "delay stage 4 of 8. Please allow extra time."), policy.postFor(change(2, 4)));
    }

    @Test
    void changesBelowTheThresholdPostNothing() {
        assertEquals(Optional.empty(), policy.postFor(change(1, 3)));
        assertEquals(Optional.empty(), policy.postFor(change(3, 1)));
    }

    @Test
    void worseningWhileAboveTheThresholdPostsAnUpdate() {
        assertEquals(Optional.of("UPDATE: delays via Johannesburg Central (Gauteng) are getting worse, "
                + "now stage 6 (was 4)."), policy.postFor(change(4, 6)));
    }

    @Test
    void easingWhileStillAboveTheThresholdPostsNothing() {
        assertEquals(Optional.empty(), policy.postFor(change(6, 5)));
    }

    @Test
    void droppingBelowTheThresholdPostsThatItIsResolved() {
        assertEquals(Optional.of("RESOLVED: Johannesburg Central (Gauteng) is back to delay stage 2. "
                + "Deliveries are returning to normal."), policy.postFor(change(5, 2)));
    }

    @ParameterizedTest
    @CsvSource({"3, 8", "6, 8"})
    void reachingStageEightIsASuspensionFromBelowOrAbove(int from, int to) {
        assertEquals(Optional.of("SUSPENDED: deliveries via Johannesburg Central (Gauteng) are suspended "
                + "(delay stage 8). We'll post again when they resume."), policy.postFor(change(from, to)));
    }

    @Test
    void leavingStageEightForBelowTheThresholdIsResolved() {
        assertTrue(policy.postFor(change(8, 1)).orElseThrow().startsWith("RESOLVED:"));
    }

    @Test
    void leavingStageEightButStayingDelayedStillPostsBecauseTheSuspensionPromisedTo() {
        assertEquals(Optional.of("UPDATE: deliveries via Johannesburg Central (Gauteng) have resumed but are "
                + "still delayed, delay stage 5 of 8."), policy.postFor(change(8, 5)));
    }

    @Test
    void anEventWithoutTheHubsNameStillNamesTheHub() {
        StageChanged bare = new StageChanged("H-503", null, null, 5, 0, "2026-07-18T10:00:00Z");

        assertTrue(policy.postFor(bare).orElseThrow().contains("via hub H-503 are delayed"));
    }

    @Test
    void theThresholdIsWhatDecides() {
        AlertPolicy strict = new AlertPolicy(2);

        assertTrue(strict.postFor(change(1, 2)).orElseThrow().startsWith("DELAY ALERT:"));
    }

    @Test
    void thresholdIsReadFromTheEnvironmentWithFourAsTheDefault() {
        assertEquals(4, AlertPolicy.threshold(null));
        assertEquals(4, AlertPolicy.threshold(" "));
        assertEquals(6, AlertPolicy.threshold(" 6 "));
    }

    @ParameterizedTest
    @CsvSource({"0", "9", "-1", "four"})
    void aThresholdOutsideOneToEightStopsStartup(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AlertPolicy.threshold(value));

        assertEquals("ALERT_THRESHOLD must be a whole number from 1 to 8, not '" + value + "'", e.getMessage());
    }
}
