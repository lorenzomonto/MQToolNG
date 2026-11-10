package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameFilterTest {

    @Test
    void containsIgnoringCase() {
        NameFilter filter = NameFilter.Compile("tmp");
        assertTrue(filter.Matches("APP.TMP.QUEUE"));
        assertTrue(filter.Matches("TMP"));
        assertTrue(filter.Matches("QUEUE.TMP"));
        assertFalse(filter.Matches("APP.QUEUE"));
    }

    @Test
    void exactNameAlsoMatchesLongerNames() {
        NameFilter filter = NameFilter.Compile("TEST.MSG.Q");
        assertTrue(filter.Matches("TEST.MSG.Q"));
        assertTrue(filter.Matches("TEST.MSG.Q2"));
    }

    @Test
    void leadingAndTrailingAsterisksAreIgnored() {
        assertTrue(NameFilter.Compile("TEST.*").Matches("TEST.PERF.Q0001"));
        assertTrue(NameFilter.Compile("*tmp*").Matches("APP.TMP.QUEUE"));
        assertFalse(NameFilter.Compile("TEST.*").Matches("DEV.QUEUE.1"));
    }

    @Test
    void emptyFilterMatchesEverything() {
        for (String search : new String[]{"", "  ", "*", "**", null}) {
            NameFilter filter = NameFilter.Compile(search);
            assertTrue(filter.IsEmpty());
            assertTrue(filter.Matches("ANY.NAME"));
        }
    }

    @Test
    void objectsWithoutNameAreKept() {
        assertTrue(NameFilter.Compile("tmp").Matches(null));
    }
}
