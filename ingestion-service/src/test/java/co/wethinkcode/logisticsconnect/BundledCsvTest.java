package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real {@code hubs-global.csv}, end to end. */
class BundledCsvTest {

    private static final Set<String> OFFICIAL_PROVINCES = Set.of(
            "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
            "Mpumalanga", "North West", "Northern Cape", "Western Cape");

    private static CleaningReport report;
    private static Map<String, CleanHub> hubs;

    @BeforeAll
    static void cleanTheRealFile() throws IOException {
        report = IngestionServiceApp.cleanBundledCsv();
        hubs = report.hubs().stream().collect(Collectors.toMap(CleanHub::hubId, Function.identity()));
    }

    @Test
    void eighteenRowsAreTenHubs() {
        assertEquals(18, report.rowsRead());
        assertEquals(List.of(), report.rejected());
        assertEquals(List.of(), report.ignoredColumns());
        assertEquals(10, report.hubs().size());
    }

    @Test
    void johannesburgCentralIsOneHubWithThreeAliases() {
        CleanHub joburg = hubs.get("H-500");
        assertEquals("Johannesburg Central", joburg.sortingCenter());
        assertEquals(List.of("H-504", "H-510", "H-515"), joburg.aliases());
        assertEquals(true, joburg.active(), "three rows say active, one says not");
    }

    @Test
    void capeTownPortWithItsDoubleSpaceAndLowerCaseIdsIsOneHub() {
        CleanHub capeTown = hubs.get("H-501");
        assertEquals("Cape Town Port", capeTown.sortingCenter());
        assertEquals("Western Cape", capeTown.province());
        assertEquals(List.of("H-505", "H-512"), capeTown.aliases());
    }

    @Test
    void durbanHarboursThreeProvinceSpellingsAreOneProvince() {
        CleanHub durban = hubs.get("H-503");
        assertEquals("KwaZulu-Natal", durban.province());
        assertEquals(List.of("H-506", "H-516"), durban.aliases());
    }

    @Test
    void pretoriaNorthIsATieSoActiveIsUnknown() {
        CleanHub pretoria = hubs.get("H-502");
        assertEquals(List.of("H-508"), pretoria.aliases());
        assertEquals("Gauteng", pretoria.province(), "H-508 had no province; Gauteng is the only one on offer");
        assertNull(pretoria.active());
    }

    @Test
    void placeholderFlagsAreUnknown() {
        assertNull(hubs.get("H-511").active(), "'unknown'");
        assertNull(hubs.get("H-517").active(), "'N/A'");
    }

    @Test
    void everyProvinceIsAnOfficialName() {
        report.hubs().forEach(hub ->
                assertTrue(OFFICIAL_PROVINCES.contains(hub.province()), () -> hub.hubId() + ": " + hub.province()));
    }
}
