package co.wethinkcode.logisticsconnect;

/**
 * The part of hub-service's record this service uses. Read tolerantly: hub-service's other
 * fields are ignored.
 *
 * @param active null when the source data doesn't say
 */
public record Hub(String hubId, String province, String sortingCenter, Boolean active) {
}
