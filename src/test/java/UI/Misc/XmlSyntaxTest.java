package UI.Misc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class XmlSyntaxTest {

    @Test
    void indentKeepsEverythingButWhitespaceBetweenTags() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!-- order --><order id=\"A-1\" currency=\"EUR\" xmlns=\"urn:shop\">"
                + "<customer type=\"b\"><name>Rossi &amp; Figli</name></customer><lines><line sku=\"W\" qty=\"3\"/></lines>"
                + "<notes><![CDATA[after 14:00 <ring>]]></notes><empty></empty></order>";
        String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!-- order -->\n"
                + "<order id=\"A-1\" currency=\"EUR\" xmlns=\"urn:shop\">\n"
                + "  <customer type=\"b\">\n"
                + "    <name>Rossi &amp; Figli</name>\n"
                + "  </customer>\n"
                + "  <lines>\n"
                + "    <line sku=\"W\" qty=\"3\"/>\n"
                + "  </lines>\n"
                + "  <notes><![CDATA[after 14:00 <ring>]]></notes>\n"
                + "  <empty></empty>\n"
                + "</order>";
        assertEquals(expected, XmlSyntax.Indent(xml, "  "));
    }

    @Test
    void indentIsStableOnFormattedXml() {
        String once = XmlSyntax.Indent("<a><b>1</b><c x=\"a > b\"/></a>", "  ");
        assertEquals("<a>\n  <b>1</b>\n  <c x=\"a > b\"/>\n</a>", once);
        assertEquals(once, XmlSyntax.Indent(once, "  "));
    }

    @Test
    void mixedContentIsNotChanged() {
        String xml = "<p>Hello <b>world</b> again</p>";
        assertEquals(xml, XmlSyntax.Indent(xml, "  "));
    }

    @Test
    void kindsOfCharacters() {
        String xml = "<a id=\"1\">x&amp;</a><!--c-->";
        byte[] k = XmlSyntax.Kinds(xml);
        assertEquals(XmlSyntax.PUNCTUATION, k[0]);
        assertEquals(XmlSyntax.ELEMENT, k[1]);
        assertEquals(XmlSyntax.ATTRIBUTE, k[3]);
        assertEquals(XmlSyntax.VALUE, k[6]);
        assertEquals(XmlSyntax.TEXT, k[10]);
        assertEquals(XmlSyntax.ENTITY, k[11]);
        assertEquals(XmlSyntax.COMMENT, k[xml.length() - 1]);
    }

    @Test
    void brokenXmlDoesNotFail() {
        assertDoesNotThrow(() -> XmlSyntax.Kinds("<a b=\"unterminated <c"));
        assertDoesNotThrow(() -> XmlSyntax.Indent("<a><b", "  "));
    }
}
