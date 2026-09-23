package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * hub-service's own copy of ingestion-service's cleaned record. It is read tolerantly (unknown
 * fields are ignored), so ingestion-service can add fields without breaking this service.
 */
public record Hub(
        String hubId,
        String province,
        String sortingCenter,
        Boolean active,
        List<String> aliases,
        List<String> notes) {

    public Hub {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
