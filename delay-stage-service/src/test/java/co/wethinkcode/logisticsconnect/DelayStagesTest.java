package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.StagePublisher.PublishFailed;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    private final List<StageChanged> published = new ArrayList<>();
    private final DelayStages stages = new DelayStages(published::add, CLOCK);

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
    void aChangeIsPublishedWithTheHubsNameAndThePreviousStage() {
        stages.set(JOBURG, 3);
        stages.set(JOBURG, 5);

        assertEquals(List.of(
                new StageChanged("H-500", "Johannesburg Central", "Gauteng", 3, 0, NOW),
                new StageChanged("H-500", "Johannesburg Central", "Gauteng", 5, 3, NOW)), published);
    }

    @Test
    void theEventIsPublishedBeforeTheStageIsRecorded() {
        List<Integer> storedWhilePublishing = new ArrayList<>();
        DelayStages[] holder = new DelayStages[1];
        holder[0] = new DelayStages(event -> storedWhilePublishing.add(holder[0].current("H-500").stage()), CLOCK);

        holder[0].set(JOBURG, 6);

        assertEquals(List.of(0), storedWhilePublishing, "still the old stage while the event goes out");
        assertEquals(6, holder[0].current("H-500").stage());
    }

    @Test
    void ifTheEventCannotBePublishedTheStageIsNotChanged() {
        DelayStages brokerDown = new DelayStages(event -> {
            throw new PublishFailed("broker unreachable", null);
        }, CLOCK);

        assertThrows(PublishFailed.class, () -> brokerDown.set(JOBURG, 6));
        assertEquals(new DelayStages.Stage("H-500", 0, null), brokerDown.current("H-500"));
    }

    @Test
    void settingTheSameStageAgainIsNotAChangeAndPublishesNothing() {
        stages.set(JOBURG, 4);
        DelayStages.Change repeat = stages.set(JOBURG, 4);

        assertFalse(repeat.changed());
        assertEquals(4, repeat.previousStage());
        assertEquals(1, published.size());
    }

    @Test
    void rejectsAStageOutsideZeroToEightAndPublishesNothing() {
        assertThrows(IllegalArgumentException.class, () -> stages.set(JOBURG, 9));
        assertThrows(IllegalArgumentException.class, () -> stages.set(JOBURG, -1));
        assertEquals(0, stages.current("H-500").stage());
        assertTrue(published.isEmpty());
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
