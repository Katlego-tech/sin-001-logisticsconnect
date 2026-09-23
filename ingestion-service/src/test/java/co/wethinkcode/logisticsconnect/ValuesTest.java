package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals("Cape Town Port", Values.clean("  Cape Town  Port "));
        assertEquals("Cape Town Port", Values.clean("Cape\tTown \t Port"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "N/A", "n/a", "TBD", "unknown", "-", "NaN"})
    void placeholdersBecomeNull(String placeholder) {
        assertNull(Values.clean(placeholder));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Y", "yes", "YES", "1", "true", "TRUE"})
    void trueFlags(String raw) {
        assertTrue(Values.flag(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"N", "no", "0", "false", "FALSE"})
    void falseFlags(String raw) {
        assertFalse(Values.flag(raw));
    }

    @Test
    void unrecognisedOrMissingFlagIsUnknownNotGuessed() {
        assertNull(Values.flag("maybe"));
        assertNull(Values.flag(null));
    }

    @Test
    void titleCasesEachWordAndHyphenatedPart() {
        assertEquals("Johannesburg Central", Values.titleCase("johannesburg CENTRAL"));
        assertEquals("Kwa-Zulu Natal", Values.titleCase("kwa-zulu natal"));
    }

    @Test
    void keepsACapitalThatFollowsALowerCaseLetterBecauseOnlyAnAuthorWritesThat() {
        assertEquals("McCarthy Depot", Values.titleCase("McCarthy depot"));
        assertEquals("KwaZulu Natal", Values.titleCase("KwaZulu NATAL"));
    }

    @Test
    void spellingVariantsShareAMatchKey() {
        assertEquals(Values.matchKey("KwaZulu-Natal"), Values.matchKey("Kwa-Zulu Natal"));
        assertEquals(Values.matchKey("Cape Town Port"), Values.matchKey("cape town port"));
    }
}
