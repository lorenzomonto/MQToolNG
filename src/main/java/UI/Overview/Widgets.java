package UI.Overview;

import MQApi.Overview.HealthCheck.Severity;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatLineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * Small building blocks of the queue manager page and properties dialog.
 * Colors are read from the current theme when painted or when the theme changes.
 */
public class Widgets {

    public static final Color GREEN = new Color(0x22A06B);
    public static final Color ORANGE = new Color(0xE8833A);
    public static final Color RED = new Color(0xE5484D);
    public static final Color BLUE = new Color(0x2F6FED);

    public static Color SeverityColor(Severity severity) {
        switch (severity) {
            case PROBLEM:
                return RED;
            case WARNING:
                return ORANGE;
            case NOTE:
                return BLUE;
            default:
                return GREEN;
        }
    }

    /** Rounded card with a thin border, on the table background (white in light themes). */
    public static class Card extends JPanel {
        public Card() {
            super(new BorderLayout(0, 10));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            putClientProperty(FlatClientProperties.STYLE, "arc: 12; background: $Table.background");
            Color line = UIManager.getColor("Component.borderColor");
            setBorder(new FlatLineBorder(new Insets(14, 16, 14, 16), line != null ? line : Color.LIGHT_GRAY, 1, 12));
        }
    }

    public static Card Card(String title) {
        Card card = new Card();
        if (title != null) {
            JLabel heading = new JLabel(title);
            heading.putClientProperty(FlatClientProperties.STYLE, "font: bold");
            card.add(heading, BorderLayout.NORTH);
        }
        return card;
    }

    public static JLabel Muted(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }

    public static JLabel Styled(String text, String style) {
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE, style);
        return label;
    }

    /** Filled circle used as status indicator. */
    public static class Dot implements Icon {
        private final Color color;
        private final int size;

        public Dot(Color color) {
            this(color, 8);
        }

        public Dot(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, size, size);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    /** Rounded label with a tinted background, e.g. the "Running" status. */
    public static class Pill extends JLabel {
        private final Color color;

        public Pill(String text, Color color) {
            super(text, new Dot(color), LEADING);
            this.color = color;
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            setForeground(color.darker());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 36));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Button that looks like a link. */
    public static JButton Link(String text, Runnable action) {
        JButton button = new JButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor; margin: 0,4,0,4");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Two-column grid of muted labels and values. */
    public static class KeyValueGrid extends JPanel {
        private int row;

        public KeyValueGrid() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        public void Add(String key, Component value) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.insets = new Insets(row == 1 ? 0 : 6, 0, 0, 12);
            c.gridx = 0;
            c.weightx = 0;
            add(Muted(key), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(row == 1 ? 0 : 6, 0, 0, 0);
            add(value, c);
        }

        public void Add(String key, String value) {
            Add(key, new JLabel(value));
        }
    }

    /** A component with a fixed preferred height and a width that follows the container. */
    public static JComponent Spacer(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, height));
        return spacer;
    }
}
