package UI.Misc;

import MQApi.QueryModel.QueueDepthStatusModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.NumberFormat;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;

/**
 * Queue depth cell: "95 / 100   95%".
 * The number of messages is bold (muted when the queue is empty), the maximum depth is muted,
 * and the fill percentage is shown, in orange or red, only when the queue is more than 60% full.
 */
public class DepthCellRender extends JComponent implements TableCellRenderer {

    private static final Color WARNING = new Color(0xE8833A);
    private static final Color CRITICAL = new Color(0xE5484D);
    private static final int WARNING_PERCENT = 60;
    private static final int CRITICAL_PERCENT = 90;
    private static final int PADDING = 10;

    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance();
    private boolean hasValue;
    private int current;
    private int max;
    private int percent;
    private boolean selected;

    public DepthCellRender() {
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        setFont(table.getFont());
        selected = isSelected;
        setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
        setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
        hasValue = value instanceof QueueDepthStatusModel && ((QueueDepthStatusModel) value).CurrentDepth != null;
        if (hasValue) {
            QueueDepthStatusModel model = (QueueDepthStatusModel) value;
            current = model.CurrentDepth;
            max = model.MaxDepth != null ? model.MaxDepth : 0;
            percent = max > 0 ? (int) Math.round(current * 100.0 / max) : 0;
            setToolTipText(numberFormat.format(current) + " of " + numberFormat.format(max) + " messages (" + percent + "% full)");
        } else {
            setToolTipText(null);
        }
        return this;
    }

    private String countText() {
        return numberFormat.format(current);
    }

    private String maxText() {
        return " / " + numberFormat.format(max);
    }

    private String percentText() {
        return percent > WARNING_PERCENT ? "   " + percent + "%" : "";
    }

    private Font boldFont() {
        return getFont().deriveFont(Font.BOLD);
    }

    // On a strong selection color (accent background) the warning colors would not be readable
    private boolean strongSelection() {
        return selected && UI.Helpers.SelectionStyle.IsStrong(getForeground(), UIManager.getColor("Table.foreground"));
    }

    private Color muted() {
        // On a strong selection color the gray would not be readable: a blend of the selection colors
        if (strongSelection()) {
            Color fg = getForeground();
            Color bg = getBackground();
            return new Color((fg.getRed() * 3 + bg.getRed()) / 4, (fg.getGreen() * 3 + bg.getGreen()) / 4, (fg.getBlue() * 3 + bg.getBlue()) / 4);
        }
        Color color = UIManager.getColor("Label.disabledForeground");
        return color != null ? color : Color.GRAY;
    }

    @Override
    public Dimension getPreferredSize() {
        // Used to size the column
        FontMetrics fm = getFontMetrics(getFont());
        int width = hasValue
                ? getFontMetrics(boldFont()).stringWidth(countText() + percentText()) + fm.stringWidth(maxText())
                : 0;
        return new Dimension(width + 2 * PADDING, fm.getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics fm = g2.getFontMetrics(getFont());
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x = PADDING;
            if (!hasValue) {
                return; // queues that hold no messages (alias, remote, model): empty cell
            }
            // Number of messages: bold, muted when the queue is empty
            Font countFont = current == 0 ? getFont() : boldFont();
            g2.setFont(countFont);
            g2.setColor(current == 0 ? muted() : getForeground());
            g2.drawString(countText(), x, baseline);
            x += g2.getFontMetrics().stringWidth(countText());
            // Maximum depth
            g2.setFont(getFont());
            g2.setColor(muted());
            g2.drawString(maxText(), x, baseline);
            x += fm.stringWidth(maxText());
            // Fill percentage, only when the queue is filling up
            if (percent > WARNING_PERCENT) {
                g2.setFont(boldFont());
                g2.setColor(strongSelection() ? getForeground() : percent > CRITICAL_PERCENT ? CRITICAL : WARNING);
                g2.drawString(percentText(), x, baseline);
            }
        } finally {
            g2.dispose();
        }
    }
}
