package co.wethinkcode.logisticsconnect;

/**
 * The part of hub-service's record this service uses. Read tolerantly: hub-service's other
 * fields are ignored.
 */
public record Hub(String hubId, String province, String sortingCenter) {
}
