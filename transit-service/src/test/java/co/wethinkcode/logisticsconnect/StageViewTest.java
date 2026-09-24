package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StageSource.Reading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageViewTest {

    private final StageView view = new StageView();

    @Test
    void aHubWithNoEventYetIsUnknown() {
        assertEquals(Reading.UNKNOWN, view.stageFor("H-500"));
    }

    @Test
    void theLatestEventWins() {
        view.apply(new StageChanged("H-500", 3, "2026-07-18T10:00:00Z"));
        view.apply(new StageChanged("H-500", 5, "2026-07-18T10:05:00Z"));

        assertEquals(new Reading(5, "2026-07-18T10:05:00Z", true), view.stageFor("H-500"));
    }

    @Test
    void anOlderEventArrivingLateDoesNotRollTheStageBack() {
        view.apply(new StageChanged("H-500", 5, "2026-07-18T10:05:00Z"));
        view.apply(new StageChanged("H-500", 3, "2026-07-18T10:00:00Z"));

        assertEquals(5, view.stageFor("H-500").stage());
    }

    @Test
    void whatWasAlreadyReceivedSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("data/stage-view.json");
        new StageView(file).apply(new StageChanged("H-500", 5, "2026-07-18T10:05:00Z"));

        StageView afterRestart = new StageView(file);

        assertEquals(new Reading(5, "2026-07-18T10:05:00Z", true), afterRestart.stageFor("H-500"));
    }

    @Test
    void aCorruptSavedViewStopsStartupRatherThanStartingBlank(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("stage-view.json");
        Files.writeString(file, "{ half a fi");

        assertThrows(UncheckedIOException.class, () -> new StageView(file));
    }
}
