package co.wethinkcode.logisticsconnect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * South Africa's nine provinces, and the spellings the legacy export uses for them.
 */
final class Provinces {

    private static final List<String> OFFICIAL = List.of(
            "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
            "Mpumalanga", "North West", "Northern Cape", "Western Cape");

    private static final Map<String, String> ABBREVIATIONS = Map.of(
            "ec", "Eastern Cape", "fs", "Free State", "gp", "Gauteng", "kzn", "KwaZulu-Natal",
            "lp", "Limpopo", "mp", "Mpumalanga", "nw", "North West", "nc", "Northern Cape",
            "wc", "Western Cape");

    private static final Map<String, String> BY_KEY = new HashMap<>(ABBREVIATIONS);

    static {
        OFFICIAL.forEach(name -> BY_KEY.put(Values.matchKey(name), name));
    }

    private Provinces() {
    }

    /** The official name for a spelling variant, or empty if it isn't a province we know. */
    static Optional<String> official(String cleaned) {
        return Optional.ofNullable(BY_KEY.get(Values.matchKey(cleaned)));
    }
}
