package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.CleaningReport.RejectedRow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubCsvCleanerTest {

    private static final String HEADER = "hub_id, Province ,sorting_center,active\n";

    private static CleaningReport clean(String rows) throws IOException {
        return cleanWithHeader(HEADER, rows);
    }

    private static CleaningReport cleanWithHeader(String header, String rows) throws IOException {
        return new HubCsvCleaner().clean(new StringReader(header + rows));
    }

    private static CleanHub only(CleaningReport report) {
        assertEquals(1, report.hubs().size(), () -> "expected one hub, got " + report.hubs());
        return report.hubs().get(0);
    }

    @Test
    void cleansPaddingCasingAndFlags() throws IOException {
        CleanHub hub = only(clean("h-505 ,western cape , cape town  PORT,TRUE\n"));

        assertEquals("H-505", hub.hubId());
        assertEquals("Western Cape", hub.province());
        assertEquals("Cape Town Port", hub.sortingCenter());
        assertEquals(true, hub.active());
        assertEquals(List.of(), hub.notes());
    }

    @Test
    void mapsProvinceSpellingVariantsToTheOfficialNameAndSaysSo() throws IOException {
        CleanHub hub = only(clean("H-506,Kwa-Zulu Natal,Durban Harbour,1\n"));

        assertEquals("KwaZulu-Natal", hub.province());
        assertTrue(hub.notes().contains("province 'Kwa-Zulu Natal' read as 'KwaZulu-Natal'"), hub.notes()::toString);
    }

    @Test
    void missingValuesAreNullAndFlaggedNotDroppedOrGuessed() throws IOException {
        CleanHub hub = only(clean("H-517,,Kimberley Hub,N/A\n"));

        assertNull(hub.province());
        assertNull(hub.active());
        assertTrue(hub.notes().contains("province missing in the source"));
        assertTrue(hub.notes().contains("active was 'N/A' in the source, so it is unknown"));
    }

    @Test
    void anIdThatIsNotShapedLikeTheOthersIsKeptButNoted() throws IOException {
        CleanHub hub = only(clean("hub 501,Gauteng,Somewhere Hub,Y\n"));

        assertEquals("HUB501", hub.hubId());
        assertTrue(hub.notes().contains("hub_id 'HUB501' does not look like H-<number>"), hub.notes()::toString);
    }

    @Test
    void mergesRowsForTheSameSortingCenterUnderTheLowestId() throws IOException {
        CleanHub hub = only(clean("""
                H-510,Gauteng,johannesburg central,FALSE
                H-500, Gauteng ,Johannesburg Central,Y
                H-504,Gauteng,Johannesburg Central,true
                """));

        assertEquals("H-500", hub.hubId());
        assertEquals(List.of("H-504", "H-510"), hub.aliases());
        assertEquals(true, hub.active(), "two of three rows say active");
        assertTrue(hub.notes().stream().anyMatch(n -> n.startsWith("active disagrees across rows")), hub.notes()::toString);
    }

    @Test
    void aTieOnActiveStaysUnknown() throws IOException {
        CleanHub hub = only(clean("""
                H-502 ,gauteng,Pretoria North,0
                H-508,,Pretoria North,yes
                """));

        assertNull(hub.active());
        assertEquals("Gauteng", hub.province(), "the row with no province joins the only province on offer");
    }

    @Test
    void sameNameInDifferentProvincesIsNotMerged() throws IOException {
        CleaningReport report = clean("""
                H-600,Gauteng,Central Depot,Y
                H-601,Limpopo,Central Depot,Y
                H-602,,Central Depot,Y
                """);

        assertEquals(3, report.hubs().size());
        CleanHub unplaced = report.hubs().get(2);
        assertEquals("H-602", unplaced.hubId());
        assertTrue(unplaced.notes().stream().anyMatch(n -> n.contains("was not merged")));
    }

    @Test
    void aMalformedRowIsRejectedWithoutStoppingTheRest() throws IOException {
        CleaningReport report = clean("""
                H-507,Free State,Bloemfontein Hub,N
                H-999,Gauteng
                ,Gauteng,Nowhere Hub,Y
                H-513,North West,Rustenburg Hub,no
                """);

        assertEquals(4, report.rowsRead());
        assertEquals(List.of("H-507", "H-513"), report.hubs().stream().map(CleanHub::hubId).toList());
        assertEquals(2, report.rejected().size());
        assertEquals("expected 4 fields, found 2", report.rejected().get(0).reason());
        assertEquals("no hub_id", report.rejected().get(1).reason());
    }

    @Test
    void aRejectedRowNamesItsLineInTheFileCountingBlankLines() throws IOException {
        CleaningReport report = clean("""
                H-507,Free State,Bloemfontein Hub,N

                H-999,Gauteng
                """);

        assertEquals(2, report.rowsRead(), "a blank line is not a row");
        RejectedRow rejected = report.rejected().get(0);
        assertEquals(4, rejected.line(), "header is line 1, then H-507, then the blank line");
        assertEquals("H-999,Gauteng", rejected.raw());
    }

    @Test
    void lineNumbersStayRightAfterAQuotedFieldThatSpansLines() throws IOException {
        CleaningReport report = clean("""
                H-1,Gauteng,"Two
                Line Hub",Y
                H-999,Gauteng
                """);

        assertEquals("Two Line Hub", report.hubs().get(0).sortingCenter());
        assertEquals(4, report.rejected().get(0).line(), "H-1 takes lines 2 and 3");
    }

    @Test
    void anUnknownProvinceIsKeptAndSaidSoRatherThanMapped() throws IOException {
        CleanHub hub = only(clean("H-1,Atlantis,Somewhere Hub,Y\n"));

        assertEquals("Atlantis", hub.province());
        assertTrue(hub.notes().contains("province 'Atlantis' is not one of the nine provinces, so it was kept, not mapped"),
                hub.notes()::toString);
    }

    @Test
    void columnsTheModelDoesNotUseAreReportedNotSilentlyDropped() throws IOException {
        CleaningReport report = cleanWithHeader(
                "hub_id,province,sorting_center,active, opened_date \n",
                "H-1,Gauteng,Somewhere Hub,Y,2024-01-31\n");

        assertEquals(List.of("opened_date"), report.ignoredColumns());
        assertEquals("H-1", only(report).hubId());
    }

    @Test
    void aByteOrderMarkBeforeTheHeaderIsIgnored() throws IOException {
        CleaningReport report = cleanWithHeader(
                "﻿hub_id,province,sorting_center,active\n",
                "H-1,Gauteng,Somewhere Hub,Y\n");

        assertEquals("H-1", only(report).hubId());
        assertEquals(List.of(), report.ignoredColumns());
    }

    @Test
    void aHeaderWithoutTheExpectedColumnsFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> new HubCsvCleaner().clean(new StringReader("id,where,active\nH-1,Gauteng,Y\n")));
    }

    @Test
    void anEmptyFileFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> new HubCsvCleaner().clean(new StringReader("")));
    }

    @Test
    void idsSortNumerically() {
        assertTrue(HubCsvCleaner.compareIds("H-9", "H-10") < 0);
    }
}
