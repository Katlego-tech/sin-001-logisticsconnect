package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reading the event off the topic: tolerant of extra fields, strict about the ones the policy uses. */
class StageChangedTest {

    @Test
    void readsTheWholeEventAndIgnoresFieldsItDoesNotKnow() throws Exception {
        StageChanged event = StageChanged.fromJson("""
                {"hubId":"H-500","sortingCenter":"Johannesburg Central","province":"Gauteng",
                 "stage":5,"previousStage":3,"timestamp":"2026-07-18T10:15:00Z","extra":true}""");

        assertEquals(new StageChanged("H-500", "Johannesburg Central", "Gauteng", 5, 3, "2026-07-18T10:15:00Z"), event);
    }

    @Test
    void theHubsNameAndProvinceMayBeMissing() throws Exception {
        StageChanged event = StageChanged.fromJson(
                "{\"hubId\":\"H-503\",\"stage\":5,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}");

        assertEquals(new StageChanged("H-503", null, null, 5, 0, "2026-07-18T10:15:00Z"), event);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"hubId\":\"H-500\",\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":null,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":5,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":42,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":-3,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":5,\"previousStage\":9,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\" \",\"stage\":2,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"stage\":2,\"previousStage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":2,\"previousStage\":0,\"timestamp\":\"yesterday\"}",
            "{\"hubId\":\"H-500\",\"stage\":2,\"previousStage\":0}",
            "not json"})
    void anEventThePolicyCannotTrustCannotBeRead(String json) {
        assertThrows(Exception.class, () -> StageChanged.fromJson(json));
    }

    @Test
    void anEventBuiltInCodeIsCheckedTheSameWay() {
        assertThrows(IllegalArgumentException.class,
                () -> new StageChanged("H-500", null, null, 9, 0, "2026-07-18T10:15:00Z"));
    }
}
