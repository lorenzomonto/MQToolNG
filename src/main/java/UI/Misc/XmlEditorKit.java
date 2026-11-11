package UI.Misc;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.util.Map;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.ViewFactory;

/**
 * Plain text editor kit for the message payload, with XML syntax colors that follow the light or
 * dark theme. The text is always antialiased: with the desktop settings (e.g. ClearType) when there
 * are, grayscale otherwise (JEditorPane would draw it jagged).
 */
public class XmlEditorKit extends DefaultEditorKit {

    // Larger payloads are shown without colors: coloring them would make typing slow
    private static final int MAX_COLORED_LENGTH = 2_000_000;
    private static final String KINDS = "XmlEditorKit.kinds";

    private static final Color[] LIGHT = {
        null,                   // text: the editor's foreground
        new Color(0x7A7F87),    // punctuation
        new Color(0x1F5BC4),    // element
        new Color(0x9C3FB5),    // attribute
        new Color(0x0B7A3B),    // value
        new Color(0x8C8C8C),    // comment
        new Color(0x6A6F77),    // CDATA
        new Color(0xB35A00),    // entity
    };
    private static final Color[] DARK = {
        null,
        new Color(0x9AA0A6),
        new Color(0x6CA7F5),
        new Color(0xC792EA),
        new Color(0x8BC77A),
        new Color(0x7F848E),
        new Color(0xA9AFB7),
        new Color(0xE0A060),
    };

    private final boolean colored;
    private final ViewFactory factory;

    /** colored = XML syntax colors; false for plain text. */
    public XmlEditorKit(boolean colored) {
        this.colored = colored;
        this.factory = element -> new XmlView(element, colored);
    }

    @Override
    public String getContentType() {
        return colored ? "text/xml" : "text/plain";
    }

    @Override
    public ViewFactory getViewFactory() {
        return factory;
    }

    @Override
    public Object clone() {
        return new XmlEditorKit(colored);
    }

    /** Character kinds of the document, computed again only after it changes. */
    private static byte[] kinds(Document document) {
        Object cached = document.getProperty(KINDS);
        if (cached instanceof byte[]) {
            return (byte[]) cached;
        }
        if (cached == null) {
            // First use: forget the kinds whenever the text changes
            document.addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    document.putProperty(KINDS, Boolean.FALSE);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    document.putProperty(KINDS, Boolean.FALSE);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
        }
        byte[] kinds;
        try {
            int length = document.getLength();
            kinds = length > MAX_COLORED_LENGTH ? new byte[length] : XmlSyntax.Kinds(document.getText(0, length));
        } catch (BadLocationException ex) {
            kinds = new byte[document.getLength()];
        }
        document.putProperty(KINDS, kinds);
        return kinds;
    }

    private static class XmlView extends PlainView {
        private final Segment segment = new Segment();
        private final boolean colored;

        XmlView(Element element, boolean colored) {
            super(element);
            this.colored = colored;
        }

        @Override
        public void paint(Graphics g, Shape allocation) {
            Graphics2D g2 = (Graphics2D) g;
            Object desktop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (desktop instanceof Map) {
                g2.addRenderingHints((Map<?, ?>) desktop);
            }
            Object antialiasing = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
            if (antialiasing == null || antialiasing == RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                    || antialiasing == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            super.paint(g, allocation);
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1) throws BadLocationException {
            if (!colored) {
                return super.drawUnselectedText(g, x, y, p0, p1);
            }
            Document document = getDocument();
            byte[] kinds = kinds(document);
            Color text = getContainer().isEnabled() ? getContainer().getForeground() : getContainer().getBackground().darker();
            Color[] palette = FlatLaf.isLafDark() ? DARK : LIGHT;
            int start = p0;
            while (start < p1) {
                byte kind = start < kinds.length ? kinds[start] : XmlSyntax.TEXT;
                int end = start + 1;
                while (end < p1 && end < kinds.length && kinds[end] == kind) {
                    end++;
                }
                Color color = palette[kind];
                g.setColor(color != null ? color : text);
                document.getText(start, end - start, segment);
                x = Utilities.drawTabbedText(segment, x, y, g, this, start);
                start = end;
            }
            return x;
        }
    }
}
