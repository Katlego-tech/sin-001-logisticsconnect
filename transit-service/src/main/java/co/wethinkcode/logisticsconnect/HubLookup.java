package co.wethinkcode.logisticsconnect;

import java.util.Optional;

/** Resolves a hub ID, or any alias of it, to the hub it names. Empty if there is no such hub. */
@FunctionalInterface
interface HubLookup {

    Optional<Hub> find(String hubId);
}
