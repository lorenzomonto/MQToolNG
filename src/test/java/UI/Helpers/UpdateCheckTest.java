package UI.Helpers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {

    @Test
    void comparesVersionsNumerically() {
        assertTrue(UpdateCheck.Compare("1.3.0", "1.2.0") > 0);
        assertTrue(UpdateCheck.Compare("1.10.0", "1.9.2") > 0);
        assertTrue(UpdateCheck.Compare("2.0", "1.99.99") > 0);
        assertEquals(0, UpdateCheck.Compare("1.2", "1.2.0"));
        assertTrue(UpdateCheck.Compare("1.2.0", "1.2.1") < 0);
    }

    @Test
    void readsTheTagOfTheRelease() {
        String json = "{\"url\":\"https://api.github.com/x\",\"tag_name\": \"v1.3.0\",\"name\":\"MQToolNG 1.3.0\"}";
        assertEquals("1.3.0", UpdateCheck.ParseVersion(json));
        assertEquals("2.0", UpdateCheck.ParseVersion("{\"tag_name\":\"2.0\"}"));
    }

    @Test
    void ignoresTagsThatAreNotVersions() {
        assertNull(UpdateCheck.ParseVersion("{\"tag_name\":\"latest\"}"));
        assertNull(UpdateCheck.ParseVersion("{\"tag_name\":\"v1.3.0\\\"><script>\"}"));
        assertNull(UpdateCheck.ParseVersion("{\"message\":\"Not Found\"}"));
    }
}
