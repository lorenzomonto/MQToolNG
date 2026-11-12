package UI.Overview;

import MQApi.Overview.HealthCheck.Severity;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/**
 * Rows of the "Needs attention" card. Collapsed, it shows only the rows that fit whole in the
 * height it is given and reports how many are left out; expanded, it is as tall as all its rows.
 */
public class AttentionList extends JPanel {

    private static final int GAP = 6;

    private final List<Row> rows = new ArrayList<>();
    private final IntConsumer onHiddenChanged;
    private boolean collapsed = true;
    private int hidden = -1;
    private int naturalHeight;

    /** onHiddenChanged receives the number of rows that do not fit, after each layout that changes it. */
    public AttentionList(IntConsumer onHiddenChanged) {
        super(null);
        setOpaque(false);
        this.onHiddenChanged = onHiddenChanged;
    }

    public void SetCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        revalidate();
    }

    public void AddRow(Severity severity, String title, String detail, JComponent action) {
        Row row = new Row(severity, title, detail, action);
        rows.add(row);
        add(row);
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        int y = 0;
        int notShown = 0;
        int total = 0;
        for (Row row : rows) {
            int height = row.HeightFor(width);
            total += (total == 0 ? 0 : GAP) + height;
            boolean fits = !collapsed || y + height <= getHeight();
            if (fits && notShown == 0) {
                row.setBounds(0, y, width, height);
                row.setVisible(true);
                y += height + GAP;
            } else {
                // Once a row does not fit, the following ones are left out too, so the order is kept
                row.setVisible(false);
                notShown++;
            }
        }
        boolean heightChanged = total != naturalHeight;
        naturalHeight = total;
        if (!collapsed && heightChanged) {
            // The rows wrap differently at the new width: the card must be laid out again at the new height
            SwingUtilities.invokeLater(this::revalidate);
        }
        if (notShown != hidden) {
            hidden = notShown;
            int count = notShown;
            SwingUtilities.invokeLater(() -> onHiddenChanged.accept(count));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (collapsed) {
            return new Dimension(1, 1); // the card decides the height
        }
        int width = getWidth() > 0 ? getWidth() : 400;
        int total = 0;
        for (Row row : rows) {
            total += (total == 0 ? 0 : GAP) + row.HeightFor(width);
        }
        return new Dimension(1, total);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(1, 1);
    }

    /** One check: severity badge, bold title with muted detail, optional action on the right. */
    private static class Row extends JPanel {
        private static final int PAD = 8;
        private static final int BADGE = 20;

        private final Severity severity;
        private final JLabel title;
        private final JLabel detail;
        private final JComponent action;

        Row(Severity severity, String titleText, String detailText, JComponent action) {
            super(null);
            setOpaque(false);
            this.severity = severity;
            this.title = Widgets.Styled(titleText, "font: bold");
            add(title);
            this.detail = detailText == null || detailText.isEmpty() ? null
                    : Widgets.Muted("<html>" + detailText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
            if (detail != null) {
                add(detail);
            }
            this.action = action;
            if (action != null) {
                add(action);
            }
        }

        private int textWidth(int width) {
            int actionWidth = action == null ? 0 : action.getPreferredSize().width + PAD;
            return Math.max(40, width - 2 * PAD - BADGE - 10 - actionWidth);
        }

        int HeightFor(int width) {
            int text = title.getPreferredSize().height;
            if (detail != null) {
                text += htmlHeight(detail, textWidth(width));
            }
            int content = Math.max(BADGE, text);
            if (action != null) {
                content = Math.max(content, action.getPreferredSize().height);
            }
            return content + 2 * PAD - 2;
        }

        @Override
        public void doLayout() {
            int width = getWidth();
            int textX = PAD + BADGE + 10;
            int textWidth = textWidth(width);
            int titleHeight = title.getPreferredSize().height;
            int detailHeight = detail == null ? 0 : htmlHeight(detail, textWidth);
            int top = (getHeight() - titleHeight - detailHeight) / 2;
            title.setBounds(textX, top, textWidth, titleHeight);
            if (detail != null) {
                detail.setBounds(textX, top + titleHeight, textWidth, detailHeight);
            }
            if (action != null) {
                Dimension size = action.getPreferredSize();
                action.setBounds(width - PAD - size.width, (getHeight() - size.height) / 2, size.width, size.height);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            java.awt.Color color = Widgets.SeverityColor(severity);
            // Light tint of the severity color behind the row
            g2.setColor(Widgets.Tint(color, 16));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            int y = (getHeight() - BADGE) / 2;
            g2.setColor(color);
            g2.fillOval(PAD, y, BADGE, BADGE);
            g2.setColor(java.awt.Color.WHITE);
            g2.setFont(getFont().deriveFont(java.awt.Font.BOLD));
            String mark = severity == Severity.OK ? "✓" : severity == Severity.NOTE ? "i" : "!";
            java.awt.FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(mark, PAD + (BADGE - metrics.stringWidth(mark)) / 2, y + (BADGE - metrics.getHeight()) / 2 + metrics.getAscent());
            g2.dispose();
        }

        @Override
        public Insets getInsets() {
            return new Insets(0, 0, 0, 0);
        }
    }

    /** Height of an HTML label when its text wraps at the given width. */
    private static int htmlHeight(JLabel label, int width) {
        Object view = label.getClientProperty(BasicHTML.propertyKey);
        if (!(view instanceof View)) {
            return label.getPreferredSize().height;
        }
        View html = (View) view;
        html.setSize(width, 0);
        return (int) Math.ceil(html.getPreferredSpan(View.Y_AXIS));
    }
}
