package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.CleaningReport.RejectedRow;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns the legacy {@code hubs-global.csv} export into one record per real-world hub.
 *
 * <p>Two passes. First every row is cleaned on its own (padding, casing, placeholders, flags,
 * province spellings). Then rows describing the same sorting center are merged — the export
 * gives Johannesburg Central four different hub IDs, so duplicates are found by name, not by ID.
 *
 * <p>A bad row is rejected and reported; it never stops the rest of the file being cleaned.
 */
public final class HubCsvCleaner {

    private static final Pattern HUB_ID = Pattern.compile("H-(\\d+)");
    private static final List<String> COLUMNS = List.of("hub_id", "province", "sorting_center", "active");
    private static final String BYTE_ORDER_MARK = "\uFEFF";

    /** A row after its fields are cleaned, before duplicates are merged. */
    private record Row(String hubId, String province, String sortingCenter, Boolean active, List<String> notes) {
    }

    private static final class RowRejected extends Exception {
        RowRejected(String reason) {
            super(reason);
        }
    }

    /** Where each column the model uses sits, and the header columns it doesn't use. */
    private record Columns(Map<String, Integer> index, List<String> ignored) {
    }

    public CleaningReport clean(Reader csv) throws IOException {
        // RFC 4180 parsing: quotes work as in a spreadsheet export, and a backslash is just a character.
        try (CSVReader reader = new CSVReaderBuilder(csv)
                .withCSVParser(new RFC4180ParserBuilder().build())
                .build()) {
            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("the CSV is empty: no header row");
            }
            Columns columns = columns(header);

            List<Row> rows = new ArrayList<>();
            List<RejectedRow> rejected = new ArrayList<>();
            int rowsRead = 0;
            String[] fields;
            while ((fields = reader.readNext()) != null) {
                // Lines read so far end at this record; a quoted field can span lines, so step back
                // over those to the line the record starts on.
                long line = reader.getLinesRead() - newlines(fields);
                if (isBlank(fields)) {
                    continue;
                }
                rowsRead++;
                try {
                    rows.add(parse(fields, header.length, columns.index()));
                } catch (RowRejected e) {
                    rejected.add(new RejectedRow(line, e.getMessage(), String.join(",", fields)));
                }
            }
            return new CleaningReport(rowsRead, columns.ignored(), rejected, merge(rows));
        } catch (CsvValidationException e) {
            throw new IOException("the CSV could not be parsed: " + e.getMessage(), e);
        }
    }

    /**
     * Finds each column by its (cleaned) header name, so a reordered export still works. Columns
     * the model doesn't use are collected, not dropped. A byte-order mark, which spreadsheet
     * exports often put before the first header, is not part of the name.
     */
    private static Columns columns(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            String raw = i == 0 && header[i].startsWith(BYTE_ORDER_MARK) ? header[i].substring(1) : header[i];
            String name = Values.clean(raw);
            if (name == null) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (COLUMNS.contains(key)) {
                index.put(key, i);
            } else {
                ignored.add(name);
            }
        }
        List<String> missing = COLUMNS.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the CSV header is missing column(s) " + missing
                    + "; found " + Arrays.toString(header));
        }
        return new Columns(index, ignored);
    }

    private static long newlines(String[] fields) {
        return Arrays.stream(fields).mapToLong(f -> f == null ? 0 : f.chars().filter(c -> c == '\n').count()).sum();
    }

    private static boolean isBlank(String[] fields) {
        return Arrays.stream(fields).allMatch(f -> f == null || f.isBlank());
    }

    // ---- pass 1: one row at a time -------------------------------------------------------------

    private static Row parse(String[] fields, int expectedFields, Map<String, Integer> column)
            throws RowRejected {
        if (fields.length != expectedFields) {
            throw new RowRejected("expected " + expectedFields + " fields, found " + fields.length);
        }
        String hubId = Values.clean(fields[column.get("hub_id")]);
        if (hubId == null) {
            throw new RowRejected("no hub_id");
        }
        hubId = hubId.toUpperCase(Locale.ROOT).replace(" ", "");

        List<String> notes = new ArrayList<>();
        if (!HUB_ID.matcher(hubId).matches()) {
            notes.add("hub_id '" + hubId + "' does not look like H-<number>");
        }
        String province = province(fields[column.get("province")], notes);
        String sortingCenter = Values.clean(fields[column.get("sorting_center")]);
        if (sortingCenter == null) {
            notes.add("sorting_center missing in the source");
        } else {
            sortingCenter = Values.titleCase(sortingCenter);
        }
        String rawActive = fields[column.get("active")].strip();
        Boolean active = Values.flag(Values.clean(rawActive));
        if (active == null) {
            notes.add("active was " + (rawActive.isEmpty() ? "blank" : "'" + rawActive + "'")
                    + " in the source, so it is unknown");
        }
        return new Row(hubId, province, sortingCenter, active, notes);
    }

    private static String province(String raw, List<String> notes) {
        String cleaned = Values.clean(raw);
        if (cleaned == null) {
            notes.add("province missing in the source");
            return null;
        }
        return Provinces.official(cleaned)
                .map(official -> {
                    if (!cleaned.equalsIgnoreCase(official)) {
                        notes.add("province '" + cleaned + "' read as '" + official + "'");
                    }
                    return official;
                })
                .orElseGet(() -> {
                    notes.add("province '" + cleaned + "' is not one of the nine provinces, so it was kept, not mapped");
                    return Values.titleCase(cleaned);
                });
    }

    // ---- pass 2: merge rows that describe the same hub -----------------------------------------

    private static List<CleanHub> merge(List<Row> rows) {
        Map<String, List<Row>> byCenter = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.sortingCenter() == null
                    ? "id:" + row.hubId() // nothing to match on, so it can only be a duplicate of itself
                    : Values.matchKey(row.sortingCenter());
            byCenter.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<CleanHub> hubs = new ArrayList<>();
        byCenter.values().forEach(group -> hubs.addAll(mergeSameName(group)));
        hubs.sort(Comparator.comparing(CleanHub::hubId, HubCsvCleaner::compareIds));
        return flagReusedIds(hubs);
    }

    /**
     * Rows share a sorting-center name. They are one hub unless they name different provinces;
     * a row with no province joins the group only when there is just one province it could mean.
     */
    private static List<CleanHub> mergeSameName(List<Row> group) {
        Set<String> provinces = group.stream()
                .map(Row::province)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        if (provinces.size() <= 1) {
            return List.of(mergeGroup(group, List.of()));
        }

        List<CleanHub> hubs = new ArrayList<>();
        group.stream()
                .filter(row -> row.province() != null)
                .collect(Collectors.groupingBy(Row::province, LinkedHashMap::new, Collectors.toList()))
                .values()
                .forEach(sameProvince -> hubs.add(mergeGroup(sameProvince, List.of())));
        String ambiguity = "a sorting center called '" + group.get(0).sortingCenter() + "' exists in "
                + provinces + ", and this row names no province, so it was not merged";
        group.stream()
                .filter(row -> row.province() == null)
                .forEach(row -> hubs.add(mergeGroup(List.of(row), List.of(ambiguity))));
        return hubs;
    }

    private static CleanHub mergeGroup(List<Row> group, List<String> extraNotes) {
        List<Row> rows = group.stream()
                .sorted(Comparator.comparing(Row::hubId, HubCsvCleaner::compareIds))
                .toList();
        Row keeper = rows.get(0);
        boolean merged = rows.size() > 1;

        List<String> notes = new ArrayList<>();
        if (merged) {
            notes.add("merged " + rows.size() + " rows for this sorting center ("
                    + rows.stream().map(Row::hubId).collect(Collectors.joining(", "))
                    + "); kept the lowest ID, " + keeper.hubId());
        }
        for (Row row : rows) {
            row.notes().forEach(note -> notes.add(merged ? row.hubId() + ": " + note : note));
        }
        Boolean active = resolveActive(rows, notes);
        notes.addAll(extraNotes);

        List<String> aliases = rows.stream()
                .map(Row::hubId)
                .filter(id -> !id.equals(keeper.hubId()))
                .distinct()
                .toList();
        String province = rows.stream().map(Row::province).filter(Objects::nonNull).findFirst().orElse(null);
        return new CleanHub(keeper.hubId(), province, keeper.sortingCenter(), active, aliases, notes);
    }

    /**
     * The export has no timestamps, so "latest row wins" has nothing to stand on. Instead: if the
     * rows agree, use that; if they disagree, the majority wins; a tie stays unknown (null).
     * Every disagreement is written into the notes with each row's value.
     */
    private static Boolean resolveActive(List<Row> rows, List<String> notes) {
        long yes = rows.stream().filter(r -> Boolean.TRUE.equals(r.active())).count();
        long no = rows.stream().filter(r -> Boolean.FALSE.equals(r.active())).count();
        if (yes > 0 && no > 0) {
            Boolean decided = yes == no ? null : yes > no;
            String votes = rows.stream()
                    .map(r -> r.hubId() + "=" + r.active())
                    .collect(Collectors.joining(", "));
            notes.add("active disagrees across rows (" + votes + "); "
                    + (decided == null ? "a tie, so left unknown" : "majority says " + decided));
            return decided;
        }
        return yes > 0 ? Boolean.TRUE : no > 0 ? Boolean.FALSE : null;
    }

    /** Guards the one thing merging can't fix: the same ID used for two different hubs. */
    private static List<CleanHub> flagReusedIds(List<CleanHub> hubs) {
        Map<String, Long> uses = hubs.stream()
                .flatMap(h -> Stream.concat(Stream.of(h.hubId()), h.aliases().stream()))
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        return hubs.stream().map(hub -> {
            List<String> reused = Stream.concat(Stream.of(hub.hubId()), hub.aliases().stream())
                    .filter(id -> uses.get(id) > 1)
                    .toList();
            if (reused.isEmpty()) {
                return hub;
            }
            List<String> notes = new ArrayList<>(hub.notes());
            notes.add("ID(s) " + reused + " are also used for a different sorting center");
            return new CleanHub(hub.hubId(), hub.province(), hub.sortingCenter(), hub.active(), hub.aliases(), notes);
        }).toList();
    }

    /** H-9 sorts before H-10; anything that isn't H-<number> sorts after, alphabetically. */
    static int compareIds(String a, String b) {
        Matcher ma = HUB_ID.matcher(a);
        Matcher mb = HUB_ID.matcher(b);
        boolean aNumeric = ma.matches();
        boolean bNumeric = mb.matches();
        if (aNumeric && bNumeric) {
            return new BigInteger(ma.group(1)).compareTo(new BigInteger(mb.group(1)));
        }
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1;
        }
        return a.compareTo(b);
    }
}
