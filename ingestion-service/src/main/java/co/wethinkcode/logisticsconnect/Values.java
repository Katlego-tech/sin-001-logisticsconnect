package co.wethinkcode.logisticsconnect;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation rules that apply to every column of the legacy export.
 */
final class Values {

    private static final Set<String> PLACEHOLDERS =
            Set.of("", "n/a", "na", "tbd", "unknown", "-", "nan", "null", "none");
    private static final Set<String> TRUE_FLAGS = Set.of("y", "yes", "1", "true");
    private static final Set<String> FALSE_FLAGS = Set.of("n", "no", "0", "false");

    /** A word: the text between spaces and hyphens. */
    private static final Pattern WORD = Pattern.compile("[^ -]+");
    /** A capital somewhere after a lower-case letter, as in McCarthy or KwaZulu. */
    private static final Pattern INNER_CAPITAL = Pattern.compile("\\p{Ll}.*\\p{Lu}");

    private Values() {
    }

    /**
     * Trims, collapses internal runs of whitespace to one space, and turns placeholder values
     * ({@code N/A}, {@code unknown}, blank, ...) into {@code null} so "missing" has exactly one
     * representation downstream.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.strip().replaceAll("\\s+", " ");
        return PLACEHOLDERS.contains(collapsed.toLowerCase(Locale.ROOT)) ? null : collapsed;
    }

    /**
     * {@code Y/yes/1/true} in any casing is true, {@code N/no/0/false} is false. Anything else,
     * including a missing value, is {@code null}: unknown, not guessed.
     */
    static Boolean flag(String cleaned) {
        if (cleaned == null) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (TRUE_FLAGS.contains(lower)) {
            return true;
        }
        if (FALSE_FLAGS.contains(lower)) {
            return false;
        }
        return null;
    }

    /**
     * {@code "johannesburg CENTRAL"} becomes {@code "Johannesburg Central"}, word by word (a
     * hyphen starts a word too). A word with a capital after a lower-case letter, like
     * {@code McCarthy}, is kept as written: careless casing never produces that, an author does,
     * and rewriting it to {@code Mccarthy} would invent a spelling. All-caps words are
     * title-cased, so an acronym like {@code OR} becomes {@code Or}; there are none in this data.
     */
    static String titleCase(String cleaned) {
        return WORD.matcher(cleaned).replaceAll(match -> {
            String word = match.group();
            String cased = INNER_CAPITAL.matcher(word).find()
                    ? word
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
            return Matcher.quoteReplacement(cased);
        });
    }

    /**
     * Letters and digits only, lower-cased. Two spellings of one name that differ only in
     * casing, spacing or punctuation ({@code Kwa-Zulu Natal} / {@code KwaZulu Natal}) share a key.
     */
    static String matchKey(String cleaned) {
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
