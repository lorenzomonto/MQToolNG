package UI.Overview;

import MQApi.Overview.MQFormat;
import MQApi.Overview.QueueManagerAttributes;
import MQApi.Overview.QueueManagerAttributes.Attribute;
import MQApi.Overview.QueueManagerAttributes.Section;
import MQApi.Overview.QueueManagerOverview;
import UI.Helpers.IconManager;
import com.formdev.flatlaf.FlatClientProperties;
import com.ibm.mq.constants.MQConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.Scrollable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/**
 * All the properties of a queue manager, grouped in tabs, with a search field.
 */
public class QueueManagerPropertiesDialog extends JDialog {

    private final QueueManagerOverview overview;
    private final JTextField search = new JTextField();
    private final JPanel configurationTab = new ColumnsPanel();
    private final JPanel statusTab = new ColumnsPanel();
    private final JPanel eventsTab = new ColumnsPanel();
    private final DefaultTableModel allModel = new DefaultTableModel(new Object[]{"Attribute", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final TableRowSorter<DefaultTableModel> allSorter = new TableRowSorter<>(allModel);

    public QueueManagerPropertiesDialog(Frame owner, String queueManagerName, QueueManagerOverview overview) {
        super(owner, queueManagerName + " properties", false);
        this.overview = overview;
        IconManager iconManager = new IconManager();
        setIconImage(iconManager.PropertiesIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel(new BorderLayout(12, 8));
        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        title.add(Widgets.Styled(queueManagerName, "font: bold +6"));
        Integer status = overview.StatusInt(MQConstants.MQIACF_Q_MGR_STATUS);
        if (status != null) {
            title.add(Box.createHorizontalStrut(12));
            title.add(new Widgets.Pill(MQFormat.Constant(status, "MQQMSTA_"),
                    status == MQConstants.MQQMSTA_RUNNING ? Widgets.GREEN : Widgets.ORANGE));
        }
        top.add(title, BorderLayout.NORTH);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Find a property, e.g. dead-letter, CCSID, MAXMSGL");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, iconManager.SearchIcon());
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });
        top.add(search, BorderLayout.CENTER);
        JButton copy = new JButton("Copy");
        copy.setToolTipText("Copy the properties shown to the clipboard");
        copy.addActionListener(e -> copyToClipboard());
        top.add(copy, BorderLayout.EAST);
        content.add(top, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configuration", scroll(configurationTab));
        tabs.addTab("Status", scroll(statusTab));
        tabs.addTab("Events", scroll(eventsTab));
        for (Map.Entry<String, String> attribute : overview.AllAttributes.entrySet()) {
            allModel.addRow(new Object[]{attribute.getKey(), attribute.getValue()});
        }
        JTable allTable = new JTable(allModel);
        allTable.setRowSorter(allSorter);
        allTable.getColumnModel().getColumn(0).setPreferredWidth(320);
        allTable.getColumnModel().getColumn(1).setPreferredWidth(480);
        tabs.addTab("All attributes", new JScrollPane(allTable));
        content.add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        bottom.add(close);
        content.add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        refresh();
        setSize(new Dimension(1000, 720));
        setLocationRelativeTo(owner);
    }

    private void refresh() {
        String filter = search.getText().trim().toLowerCase(Locale.ROOT);
        fill(configurationTab, QueueManagerAttributes.Configuration(), filter);
        fill(statusTab, QueueManagerAttributes.Status(), filter);
        fill(eventsTab, QueueManagerAttributes.Events(), filter);
        allSorter.setRowFilter(filter.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(filter)));
    }

    private boolean matches(Section section, Attribute attribute, String value, String filter) {
        return filter.isEmpty()
                || attribute.Label.toLowerCase(Locale.ROOT).contains(filter)
                || attribute.Mqsc.toLowerCase(Locale.ROOT).contains(filter)
                || value.toLowerCase(Locale.ROOT).contains(filter)
                || section.Title.toLowerCase(Locale.ROOT).contains(filter);
    }

    /** Two columns of section cards, sections alternated by size so the columns stay balanced. */
    private void fill(JPanel tab, List<Section> sections, String filter) {
        tab.removeAll();
        JPanel left = column();
        JPanel right = column();
        int leftRows = 0;
        int rightRows = 0;
        for (Section section : sections) {
            Widgets.Card card = Widgets.Card(section.Title);
            Widgets.KeyValueGrid grid = new Widgets.KeyValueGrid();
            int rows = 0;
            for (Attribute attribute : section.Attributes) {
                String value = attribute.Value(overview);
                if (value == null || !matches(section, attribute, value, filter)) {
                    continue;
                }
                JLabel valueLabel = new JLabel(value);
                valueLabel.setToolTipText("MQSC: " + attribute.Mqsc);
                grid.Add(attribute.Label, valueLabel);
                rows++;
            }
            if (rows == 0) {
                continue;
            }
            card.add(grid, BorderLayout.CENTER);
            card.setAlignmentX(LEFT_ALIGNMENT);
            JPanel target = leftRows <= rightRows ? left : right;
            if (target.getComponentCount() > 0) {
                target.add(Widgets.Spacer(12));
            }
            target.add(card);
            if (target == left) {
                leftRows += rows + 2;
            } else {
                rightRows += rows + 2;
            }
        }
        if (left.getComponentCount() == 0) {
            JPanel empty = column();
            empty.add(Widgets.Muted("No property matches \"" + search.getText().trim() + "\""));
            tab.add(empty);
        } else {
            tab.add(left);
            tab.add(right);
        }
        tab.revalidate();
        tab.repaint();
    }

    private static JPanel column() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        return column;
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void copyToClipboard() {
        String filter = search.getText().trim().toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder();
        List<List<Section>> groups = new ArrayList<>();
        groups.add(QueueManagerAttributes.Configuration());
        groups.add(QueueManagerAttributes.Status());
        groups.add(QueueManagerAttributes.Events());
        for (List<Section> sections : groups) {
            for (Section section : sections) {
                StringBuilder lines = new StringBuilder();
                for (Attribute attribute : section.Attributes) {
                    String value = attribute.Value(overview);
                    if (value != null && matches(section, attribute, value, filter)) {
                        lines.append("  ").append(attribute.Label).append(" (").append(attribute.Mqsc).append("): ").append(value).append('\n');
                    }
                }
                if (lines.length() > 0) {
                    text.append(section.Title).append('\n').append(lines).append('\n');
                }
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    /** Two equal columns that follow the width of the scroll pane. */
    private static class ColumnsPanel extends JPanel implements Scrollable {
        ColumnsPanel() {
            super(new GridLayout(1, 2, 12, 0));
            setBorder(BorderFactory.createEmptyBorder(12, 2, 12, 2));
        }

        @Override
        public void doLayout() {
            // Columns are top-aligned: GridLayout would stretch both to the tallest
            for (java.awt.Component column : getComponents()) {
                ((JComponent) column).setMaximumSize(column.getPreferredSize());
            }
            super.doLayout();
            for (java.awt.Component column : getComponents()) {
                column.setSize(column.getWidth(), Math.min(column.getHeight(), column.getPreferredSize().height));
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
