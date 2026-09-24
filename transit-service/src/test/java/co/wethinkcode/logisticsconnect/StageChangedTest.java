package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reading the event off the topic: tolerant of extra fields, strict about the ones it uses. */
class StageChangedTest {

    @Test
    void readsTheFieldsItUsesAndIgnoresTheRest() throws Exception {
        StageChanged event = StageChanged.fromJson("""
                {"hubId":"H-500","sortingCenter":"Johannesburg Central","province":"Gauteng",
                 "stage":5,"previousStage":3,"timestamp":"2026-07-18T10:15:00Z"}""");

        assertEquals(new StageChanged("H-500", 5, "2026-07-18T10:15:00Z"), event);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"hubId\":\"H-500\",\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":null,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":42,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":-3,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\" \",\"stage\":2,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"stage\":2,\"timestamp\":\"2026-07-18T10:15:00Z\"}",
            "{\"hubId\":\"H-500\",\"stage\":2,\"timestamp\":\"yesterday\"}",
            "{\"hubId\":\"H-500\",\"stage\":2}",
            "not json"})
    void anEventThisServiceCannotTrustCannotBeRead(String json) {
        assertThrows(Exception.class, () -> StageChanged.fromJson(json));
    }

    @Test
    void anEventBuiltInCodeIsCheckedTheSameWay() {
        assertThrows(IllegalArgumentException.class, () -> new StageChanged("H-500", 9, "2026-07-18T10:15:00Z"));
    }
}
