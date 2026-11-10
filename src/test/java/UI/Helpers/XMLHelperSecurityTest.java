package UI.Helpers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XMLHelperSecurityTest {

    @TempDir
    Path dir;

    /** A message containing an external entity must not make the tool read local files (XXE). */
    @Test
    void externalEntitiesInMessagesAreNotResolved() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String message = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE order [<!ENTITY leak SYSTEM \"" + secret.toUri() + "\">]>"
                + "<order><note>&leak;</note></order>";
        String formatted = XMLHelper.XMLStringFormat(message);
        assertFalse(formatted.contains("TOP-SECRET-CONTENT"), "The external entity was resolved: " + formatted);
    }

    @Test
    void ordinaryXmlIsStillRecognizedAndFormatted() {
        String message = "<order id=\"1\"><amount>12.50</amount></order>";
        assertTrue(XMLHelper.IsXML(message));
        String formatted = XMLHelper.XMLStringFormat(message);
        assertTrue(formatted.contains("<amount>12.50</amount>"));
        assertTrue(formatted.contains("\n"), "pretty printed");
        assertFalse(XMLHelper.IsXML("plain text message"));
    }
}
