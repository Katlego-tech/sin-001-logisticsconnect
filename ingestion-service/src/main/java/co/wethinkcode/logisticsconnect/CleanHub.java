package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * One real-world hub after cleaning.
 *
 * @param hubId         the ID this hub is known by from now on
 * @param province      official province name, or null if the source never said
 * @param sortingCenter display name, e.g. "Cape Town Port"
 * @param active        true/false, or null when the source was missing or contradicted itself
 * @param aliases       other IDs the legacy export used for this same hub
 * @param notes         everything a consumer should know about how this record was derived
 */
public record CleanHub(
        String hubId,
        String province,
        String sortingCenter,
        Boolean active,
        List<String> aliases,
        List<String> notes) {
}
