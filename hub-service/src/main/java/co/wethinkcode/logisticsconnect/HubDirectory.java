package co.wethinkcode.logisticsconnect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The place-name source of truth: every hub, looked up by its ID or by any alias the legacy
 * export gave it.
 *
 * <p>Loads lazily from ingestion-service and keeps the result. If ingestion-service is down,
 * requests fail with {@link UpstreamUnavailable} (503) and the next request tries again, so
 * the order the services are started in doesn't matter.
 */
final class HubDirectory {

    private static final Logger log = LoggerFactory.getLogger(HubDirectory.class);

    /** A province and the sorting centers in it. */
    record Province(String province, List<String> sortingCenters) {
    }

    private record Index(List<Hub> hubs, Map<String, Hub> byId) {
    }

    private final Supplier<List<Hub>> source;
    private volatile Index index;

    HubDirectory(Supplier<List<Hub>> source) {
        this.source = source;
    }

    List<Hub> all() {
        return index().hubs();
    }

    /** By ID or alias, ignoring case and stray spaces: {@code " h-504"} finds H-500. */
    Optional<Hub> find(String hubId) {
        return Optional.ofNullable(index().byId().get(normalise(hubId)));
    }

    List<Province> provinces() {
        Map<String, List<String>> byProvince = index().hubs().stream()
                .filter(hub -> hub.province() != null)
                .collect(Collectors.groupingBy(Hub::province, TreeMap::new,
                        Collectors.mapping(Hub::sortingCenter, Collectors.toList())));
        return byProvince.entrySet().stream()
                .map(e -> new Province(e.getKey(), e.getValue().stream()
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList()))
                .toList();
    }

    static String normalise(String hubId) {
        return hubId.strip().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private Index index() {
        Index current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                index = build(source.get());
            }
            return index;
        }
    }

    /**
     * Keeps ingestion-service's order (numeric by ID), which a text sort would break: H-10 would
     * come before H-9. Real IDs are indexed before aliases, so an alias can never shadow another
     * hub's own ID. If two different hubs claim one ID, the first keeps it and it is logged.
     */
    private static Index build(List<Hub> hubs) {
        Map<String, Hub> byId = new HashMap<>();
        for (Hub hub : hubs) {
            Hub earlier = byId.putIfAbsent(normalise(hub.hubId()), hub);
            if (earlier != null) {
                log.warn("Two hubs claim ID {}: '{}' keeps it, '{}' can't be looked up by it",
                        hub.hubId(), earlier.sortingCenter(), hub.sortingCenter());
            }
        }
        hubs.forEach(hub -> hub.aliases().forEach(alias -> byId.putIfAbsent(normalise(alias), hub)));
        return new Index(List.copyOf(hubs), byId);
    }
}
