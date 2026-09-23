package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayStagesTest {

    static final Hub JOBURG = new Hub("H-500", "Gauteng", "Johannesburg Central");
    static final Hub DURBAN = new Hub("H-503", "KwaZulu-Natal", "Durban Harbour");
    private static final String NOW = "2026-07-18T10:15:00Z";
    private static final Clock CLOCK = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);

    private final DelayStages stages = new DelayStages(CLOCK);

    @Test
    void aHubThatWasNeverSetIsAtStageZeroWithNoTime() {
        DelayStages.Stage stage = stages.current("H-500");

        assertEquals(new DelayStages.Stage("H-500", 0, null), stage);
    }

    @Test
    void aChangeRecordsTheStageItMovedFromAndWhen() {
        stages.set(JOBURG, 3);
        DelayStages.Change change = stages.set(JOBURG, 5);

        assertEquals(new DelayStages.Change("H-500", 5, 3, NOW, true), change);
        assertEquals(new DelayStages.Stage("H-500", 5, NOW), stages.current("H-500"));
    }

    @Test
    void settingTheSameStageAgainIsNotAChange() {
        stages.set(JOBURG, 4);
        DelayStages.Change repeat = stages.set(JOBURG, 4);

        assertFalse(repeat.changed());
        assertEquals(4, repeat.previousStage());
    }

    @Test
    void rejectsAStageOutsideZeroToEight() {
        assertThrows(IllegalArgumentException.class, () -> stages.set(JOBURG, 9));
        assertThrows(IllegalArgumentException.class, () -> stages.set(JOBURG, -1));
        assertEquals(0, stages.current("H-500").stage());
    }

    @Test
    void listsOnlyTheHubsWhoseStageWasSetInIdOrder() {
        stages.set(DURBAN, 2);
        stages.set(JOBURG, 1);

        List<String> ids = stages.all().stream().map(DelayStages.Stage::hubId).toList();

        assertEquals(List.of("H-500", "H-503"), ids);
        assertTrue(stages.all().stream().allMatch(s -> s.updatedAt() != null));
    }
}
