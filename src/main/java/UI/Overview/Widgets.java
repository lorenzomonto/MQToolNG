package UI.Overview;

import MQApi.Overview.HealthCheck.Severity;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
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
    public static final Color PURPLE = new Color(0x8250DF);
    public static final Color TEAL = new Color(0x0F9488);

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

    /** The color with the given alpha (0-255): a light tint over any background, light or dark. */
    public static Color Tint(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Text color for a status color: darker on light themes, lighter on dark ones, so it stays readable. */
    public static Color Ink(Color color) {
        return FlatLaf.isLafDark() ? color.brighter() : color.darker();
    }

    /** Rounded label with a tinted background, e.g. the "Running" status. */
    public static class Pill extends JLabel {
        private final Color color;

        public Pill(String text, Color color) {
            this(text, color, true);
        }

        public Pill(String text, Color color, boolean dot) {
            super(text, dot ? new Dot(color) : null, LEADING);
            this.color = color;
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            setForeground(Ink(color));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tint(color, 36));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Icon on a rounded square tinted with its color, at the top of the key figure cards. */
    public static class IconTile implements Icon {
        private final Icon icon;
        private final Color color;
        private final int size;

        public IconTile(String iconName, Color color) {
            this.icon = UI.Helpers.IconManager.Named(iconName, color);
            this.color = color;
            this.size = Math.max(26, icon.getIconHeight() + 10);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tint(color, 34));
            g2.fillRoundRect(x, y, size, size, 8, 8);
            g2.dispose();
            icon.paintIcon(c, g, x + (size - icon.getIconWidth()) / 2, y + (size - icon.getIconHeight()) / 2);
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

    /** Thin rounded bar filled to a fraction, e.g. how full a queue is. */
    public static class FillBar extends JComponent {
        private final double fraction;
        private final Color color;
        private final int thickness;

        public FillBar(double fraction, Color color, int thickness) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.color = color;
            this.thickness = thickness;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(40, thickness);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, thickness);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = (getHeight() - thickness) / 2;
            Color track = UIManager.getColor("Component.borderColor");
            g2.setColor(track != null ? Tint(track, 150) : new Color(0, 0, 0, 20));
            g2.fillRoundRect(0, y, getWidth(), thickness, thickness, thickness);
            if (fraction > 0) {
                // Even an almost empty queue shows a sliver, so the bar is not mistaken for missing data
                int width = Math.max(thickness, (int) Math.round(getWidth() * fraction));
                g2.setColor(color);
                g2.fillRoundRect(0, y, width, thickness, thickness, thickness);
            }
            g2.dispose();
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
