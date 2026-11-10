/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Misc;

import MQApi.QueryModel.QueueDepthStatusModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;

/**
 * Queue depth cell: a slim fill bar (current / max depth) followed by the values.
 *
 * @author jzhou
 */
    public class ProgressCellRender extends JComponent implements TableCellRenderer {

        private static final Color OK = new Color(0x22A06B);
        private static final Color WARNING = new Color(0xE8833A);
        private static final Color CRITICAL = new Color(0xE5484D);
        private static final int BAR_WIDTH = 64;
        private static final int BAR_HEIGHT = 6;
        private static final int PADDING = 10;

        private String text;
        private int percent;
        private boolean hasValue;
        private boolean selected;

        public ProgressCellRender() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            setFont(table.getFont());
            selected = isSelected;
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            if(value != null){
                QueueDepthStatusModel model = (QueueDepthStatusModel)value;
                percent = model.MaxDepth > 0 ? (int)((long)model.CurrentDepth * 100 / model.MaxDepth) : 0;
                text = model.CurrentDepth + " / " + model.MaxDepth;
                hasValue = true;
            }
            else{
                percent = 0;
                text = "N/A";
                hasValue = false;
            }
            setToolTipText(hasValue ? percent + "% full" : null);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int x = PADDING;
                if(hasValue){
                    int y = (getHeight() - BAR_HEIGHT) / 2;
                    Color track = UIManager.getColor("ProgressBar.background");
                    if(selected || track == null){
                        // A translucent track stays visible on any selection background
                        Color fg = getForeground();
                        track = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 60);
                    }
                    g2.setColor(track);
                    g2.fillRoundRect(x, y, BAR_WIDTH, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
                    int fill = Math.min(BAR_WIDTH, Math.max(percent > 0 ? BAR_HEIGHT : 0, BAR_WIDTH * percent / 100));
                    g2.setColor(percent <= 60 ? OK : percent <= 90 ? WARNING : CRITICAL);
                    g2.fillRoundRect(x, y, fill, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
                    x += BAR_WIDTH + 8;
                }
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(hasValue ? getForeground() : UIManager.getColor("Label.disabledForeground"));
                g2.drawString(text, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
