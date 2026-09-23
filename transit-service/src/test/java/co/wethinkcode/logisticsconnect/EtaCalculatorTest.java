package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.EtaCalculator.Eta;
import co.wethinkcode.logisticsconnect.EtaCalculator.Status;
import co.wethinkcode.logisticsconnect.StageSource.Reading;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtaCalculatorTest {

    static final Hub JOBURG = new Hub("H-500", "Gauteng", "Johannesburg Central", true);
    static final Hub POLOKWANE = new Hub("H-511", "Limpopo", "Polokwane Hub", true);
    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    private static Reading stage(int stage) {
        return new Reading(stage, "2026-07-18T09:00:00Z", true);
    }

    @Test
    void aMetroHubAtStageZeroIsOnTimeInOneToTwoDays() {
        Eta eta = EtaCalculator.estimate("H-500", JOBURG, stage(0), NOW);

        assertEquals(Status.ON_TIME, eta.status());
        assertEquals(24, eta.earliestHours());
        assertEquals(48, eta.latestHours());
        assertEquals("2026-07-19T10:00:00Z", eta.earliestArrival());
        assertEquals("2026-07-20T10:00:00Z", eta.latestArrival());
        assertTrue(eta.warnings().isEmpty(), eta.warnings()::toString);
    }

    @Test
    void eachStagePushesTheWindowLaterAndWider() {
        Eta eta = EtaCalculator.estimate("H-511", POLOKWANE, stage(3), NOW);

        assertEquals(Status.DELAYED, eta.status());
        assertEquals(48 + 3 * 12, eta.earliestHours());
        assertEquals(96 + 3 * 24, eta.latestHours());
    }

    @Test
    void stageEightIsAShutdownWithNoWindow() {
        Eta eta = EtaCalculator.estimate("H-500", JOBURG, stage(8), NOW);

        assertEquals(Status.SUSPENDED, eta.status());
        assertNull(eta.earliestHours());
        assertNull(eta.latestArrival());
    }

    @Test
    void anInactiveHubGetsNoWindow() {
        Hub closed = new Hub("H-507", "Free State", "Bloemfontein Hub", false);

        Eta eta = EtaCalculator.estimate("H-507", closed, stage(0), NOW);

        assertEquals(Status.HUB_INACTIVE, eta.status());
        assertNull(eta.earliestHours());
    }

    @Test
    void anUnknownStageIsAssumedZeroAndSaysSo() {
        Eta eta = EtaCalculator.estimate("H-500", JOBURG, Reading.UNKNOWN, NOW);

        assertEquals(Status.ON_TIME, eta.status());
        assertEquals(false, eta.stageKnown());
        assertTrue(eta.warnings().get(0).contains("stage 0 is assumed"));
    }

    @Test
    void askingByAnAliasIsAnsweredForTheCanonicalHubWithAWarning() {
        Eta eta = EtaCalculator.estimate("h-504", JOBURG, stage(0), NOW);

        assertEquals("H-500", eta.hubId());
        assertEquals("h-504 is a duplicate ID for H-500 in the legacy data", eta.warnings().get(0));
    }

    @Test
    void anUnknownActiveFlagStillGetsAWindowWithAWarning() {
        Hub pretoria = new Hub("H-502", "Gauteng", "Pretoria North", null);

        Eta eta = EtaCalculator.estimate("H-502", pretoria, stage(1), NOW);

        assertEquals(Status.DELAYED, eta.status());
        assertTrue(eta.warnings().contains("the source data doesn't say whether this hub is active"));
    }

    @Test
    void anUnknownProvinceGetsTheRegionalWindowWithAWarning() {
        Hub nowhere = new Hub("H-1", null, "Somewhere Hub", true);

        Eta eta = EtaCalculator.estimate("H-1", nowhere, stage(0), NOW);

        assertEquals(48, eta.earliestHours());
        assertTrue(eta.warnings().contains("province unknown, so the regional service level is used"));
    }
}
