package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubDirectoryTest {

    static final Hub JOBURG = new Hub("H-500", "Gauteng", "Johannesburg Central", true,
            List.of("H-504", "H-510"), List.of());
    static final Hub PRETORIA = new Hub("H-502", "Gauteng", "Pretoria North", null, List.of("H-508"), List.of());
    static final Hub DURBAN = new Hub("H-503", "KwaZulu-Natal", "Durban Harbour", true, List.of(), List.of());

    private final HubDirectory directory = new HubDirectory(() -> List.of(JOBURG, PRETORIA, DURBAN));

    private static Hub hub(String id, String sortingCenter, String... aliases) {
        return new Hub(id, "Gauteng", sortingCenter, true, List.of(aliases), List.of());
    }

    @Test
    void findsAHubByItsOwnIdIgnoringCaseAndPadding() {
        assertEquals(JOBURG, directory.find(" h-500 ").orElseThrow());
    }

    @Test
    void anAliasResolvesToTheHubItWasMergedInto() {
        assertEquals(JOBURG, directory.find("H-510").orElseThrow());
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertTrue(directory.find("H-999").isEmpty());
    }

    @Test
    void keepsIngestionServicesNumericOrderRatherThanSortingIdsAsText() {
        Hub nine = hub("H-9", "Nine Hub");
        Hub ten = hub("H-10", "Ten Hub");

        assertEquals(List.of(nine, ten), new HubDirectory(() -> List.of(nine, ten)).all());
    }

    @Test
    void whenTwoDifferentHubsClaimOneIdTheFirstWins() {
        Hub first = hub("H-700", "Alpha Hub");
        Hub second = hub("H-700", "Beta Hub");

        assertEquals(first, new HubDirectory(() -> List.of(first, second)).find("H-700").orElseThrow());
    }

    @Test
    void anAliasNeverShadowsAnotherHubsOwnId() {
        Hub withAlias = hub("H-800", "Alpha Hub", "H-801");
        Hub owner = hub("H-801", "Beta Hub");

        assertEquals(owner, new HubDirectory(() -> List.of(withAlias, owner)).find("H-801").orElseThrow());
    }

    @Test
    void groupsSortingCentersByProvince() {
        assertEquals(List.of(
                new HubDirectory.Province("Gauteng", List.of("Johannesburg Central", "Pretoria North")),
                new HubDirectory.Province("KwaZulu-Natal", List.of("Durban Harbour"))),
                directory.provinces());
    }

    @Test
    void aFailedLoadIsRetriedOnTheNextRequest() {
        AtomicInteger calls = new AtomicInteger();
        HubDirectory flaky = new HubDirectory(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new UpstreamUnavailable("ingestion-service is down");
            }
            return List.of(DURBAN);
        });

        assertThrows(UpstreamUnavailable.class, flaky::all);
        assertEquals(List.of(DURBAN), flaky.all());
        flaky.all();
        assertEquals(2, calls.get(), "loaded once it succeeded, not on every request");
    }
}
