package UI.Events;

import MQApi.Events.QueueManagerEvents;
import MQApi.Events.QueueManagerEvents.Change;
import MQApi.Events.QueueManagerEvents.Event;
import MQApi.Events.QueueManagerEvents.Field;
import MQApi.Events.QueueManagerEvents.Kind;
import MQApi.Events.QueueManagerEvents.Level;
import MQApi.Events.QueueManagerEvents.Result;
import MQApi.Events.QueueManagerEvents.Switch;
import MQApi.Logs.LogWriter;
import MQApi.Overview.MQFormat;
import UI.Helpers.IconManager;
import UI.Misc.AutoRefreshButton;
import UI.Overview.Widgets;
import com.formdev.flatlaf.FlatClientProperties;
import com.ibm.mq.MQQueueManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * The events of a queue manager as a log: authority failures, channels started and stopped, full queues,
 * configuration changes, commands… read from the event queues without removing them. The events that
 * are not recorded are shown, and can be turned on (Event settings).
 */
public class QueueManagerEventsDialog extends JDialog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] LEVELS = {"All levels", "Errors and warnings", "Errors"};

    private final MQQueueManager queueManager;
    private final String queueManagerName;
    private final EventTableModel model = new EventTableModel();
    private final JTable table = new JTable(model);
    private final JEditorPane detail = new JEditorPane("text/html", "");
    private final JComboBox<String> kind = new JComboBox<>();
    private final JComboBox<String> level = new JComboBox<>(LEVELS);
    private final JCheckBox bySet = new JCheckBox("Changes by applications");
    private final JTextField filter = new JTextField(18);
    private final JLabel notice = new JLabel();
    private final JPanel noticePanel = new JPanel(new BorderLayout(8, 0));
    private final JLabel status = Widgets.Muted(" ");
    private final JButton refresh;
    private final AutoRefreshButton autoRefresh;
    private final Timer timer;
    private int autoRefreshSeconds;
    private boolean reading;
    private List<Event> events = new ArrayList<>();
    private Result lastResult;
    private Map<Switch, Integer> switches;

    public QueueManagerEventsDialog(Frame owner, MQQueueManager queueManager, String queueManagerName) {
        super(owner, "Events – " + queueManagerName, false);
        this.queueManager = queueManager;
        this.queueManagerName = queueManagerName;
        IconManager iconManager = new IconManager();
        setIconImage(iconManager.EventsIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = Widgets.Styled("Events of " + queueManagerName, "font: bold +4");
        JLabel subtitle = Widgets.Muted("Read from the event queues (SYSTEM.ADMIN.*.EVENT) without removing them");
        noticePanel.add(notice, BorderLayout.CENTER);
        noticePanel.putClientProperty(FlatClientProperties.STYLE, "background: tint($Component.warning.focusedBorderColor,85%)");
        noticePanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
        noticePanel.setVisible(false);

        JPanel bar = new JPanel(new BorderLayout(12, 0));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        kind.addItem("All types");
        for (Kind each : Kind.values()) {
            kind.addItem(each.Text);
        }
        kind.addActionListener(e -> fill());
        level.addActionListener(e -> fill());
        bySet.setToolTipText("Configuration changes made by applications with MQSET, e.g. the channel initiator "
                + "turning triggering on and off on transmission queues");
        bySet.addActionListener(e -> fill());
        refresh = new JButton(iconManager.RefreshIcon());
        refresh.setToolTipText("Read the events again");
        refresh.addActionListener(e -> read());
        autoRefresh = new AutoRefreshButton(new AutoRefreshButton.Setting() {
            @Override
            public int Seconds() {
                return autoRefreshSeconds;
            }

            @Override
            public void SetSeconds(int seconds) {
                autoRefreshSeconds = seconds;
                autoRefresh.Update();
                restartTimer();
            }
        }, true);
        JButton settings = new JButton("Event settings…");
        settings.setToolTipText("Choose which events the queue manager records");
        settings.addActionListener(e -> showSettings());
        filters.add(kind);
        filters.add(level);
        filters.add(bySet);
        filters.add(refresh);
        filters.add(autoRefresh);
        filters.add(settings);
        bar.add(filters, BorderLayout.WEST);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by event, object, user…");
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, iconManager.SearchIcon());
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fill();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fill();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fill();
            }
        });
        bar.add(filter, BorderLayout.EAST);

        for (JPanel row : new JPanel[]{wrap(title), wrap(subtitle)}) {
            top.add(row);
        }
        top.add(Box.createVerticalStrut(10));
        top.add(noticePanel);
        top.add(Box.createVerticalStrut(10));
        top.add(bar);
        for (Component component : top.getComponents()) {
            ((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        content.add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        TableRowSorter<EventTableModel> sorter = new TableRowSorter<>(model);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);
        int[] widths = {150, 80, 115, 210, 170, 330};
        // The time as wide as its text in the font of the table (larger in some themes)
        widths[0] = table.getFontMetrics(table.getFont()).stringWidth("2026-11-25 00:00:00") + 24;
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setMinWidth(widths[0]);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof Instant ? TIME.format((Instant) value) : "");
            }
        });
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value instanceof Level ? ((Level) value).Text : "", isSelected, hasFocus, row, column);
                setIcon(value instanceof Level ? new Widgets.Dot(color((Level) value), 8) : null);
                setIconTextGap(6);
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });

        detail.setEditable(false);
        detail.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        detail.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setPreferredSize(new Dimension(0, 240));
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(0, 330));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        content.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton export = new JButton("Save CSV…");
        export.setToolTipText("Save the events shown as CSV");
        export.addActionListener(e -> saveCsv());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(export);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        timer = new Timer(10000, e -> read());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
            }
        });
        setContentPane(content);
        setPreferredSize(new Dimension(1160, 780));
        pack();
        setLocationRelativeTo(owner);
        showDetail();
        read();
    }

    /** Shows only the events of the event queue (e.g. SYSTEM.ADMIN.CHANNEL.EVENT). */
    public void ShowQueue(String eventQueue) {
        for (Kind each : Kind.values()) {
            if (each.Queue.equals(eventQueue)) {
                kind.setSelectedIndex(each.ordinal() + 1);
            }
        }
    }

    private static JPanel wrap(Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(component, BorderLayout.WEST);
        return panel;
    }

    private void restartTimer() {
        timer.stop();
        if (autoRefreshSeconds > 0) {
            timer.setDelay(autoRefreshSeconds * 1000);
            timer.setInitialDelay(autoRefreshSeconds * 1000);
            timer.start();
        }
    }

    // ---- Reading ----

    private void read() {
        if (reading) {
            return;
        }
        reading = true;
        refresh.setEnabled(false);
        status.setText("Reading the events…");
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                Result result = QueueManagerEvents.Read(queueManager);
                // Read once (and after the settings change), not at every refresh: with command events on, each
                // inquiry would add an event of its own
                Map<Switch, Integer> read = switches;
                if (read == null) {
                    try {
                        read = QueueManagerEvents.ReadSwitches(queueManager);
                    } catch (Exception ex) {
                        LogWriter.WriteToLog("QueueManagerEventsDialog", "ReadSwitches", ex);
                    }
                }
                return new Object[]{result, read};
            }

            @SuppressWarnings("unchecked")
            @Override
            protected void done() {
                reading = false;
                refresh.setEnabled(true);
                try {
                    Object[] read = get();
                    lastResult = (Result) read[0];
                    switches = (Map<Switch, Integer>) read[1];
                    events = lastResult.Events;
                    fill();
                    updateNotice();
                } catch (Exception ex) {
                    LogWriter.WriteToLog("QueueManagerEventsDialog", "read", ex);
                    status.setText("The events could not be read: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                }
            }
        }.execute();
    }

    /** What is worth knowing about the events read: events turned off, full queues, events not kept. */
    private void updateNotice() {
        List<String> lines = new ArrayList<>();
        if (switches != null) {
            List<String> off = new ArrayList<>(QueueManagerEvents.Off(switches));
            // Logger events exist only with linear logging: off is the usual state
            off.remove(Switch.LOGGER.Text);
            if (off.size() == switches.size() - (switches.containsKey(Switch.LOGGER) ? 1 : 0)) {
                lines.add("The queue manager records no events: turn them on in the event settings.");
            } else if (!off.isEmpty()) {
                lines.add("Not recorded: " + String.join(", ", off) + " events (Event settings).");
            }
        }
        for (Kind each : Kind.values()) {
            Integer depth = lastResult.Depth.get(each);
            Integer max = lastResult.MaxDepth.get(each);
            if (depth != null && max != null && max > 0 && depth >= max) {
                lines.add(each.Queue + " is full: new " + each.Text.toLowerCase(Locale.ROOT) + " events are lost.");
            }
            Integer skipped = lastResult.Skipped.get(each);
            if (skipped != null) {
                lines.add("Only the newest " + MQFormat.Number(QueueManagerEvents.MAX_PER_QUEUE) + " of "
                        + MQFormat.Number(depth) + " messages of " + each.Queue + " are shown.");
            }
            String error = lastResult.Errors.get(each);
            if (error != null) {
                lines.add(each.Queue + " could not be read: " + error + ".");
            }
        }
        notice.setText(lines.isEmpty() ? "" : "<html>" + String.join("<br>", escape(lines)) + "</html>");
        noticePanel.setVisible(!lines.isEmpty());
    }

    private static List<String> escape(List<String> lines) {
        List<String> escaped = new ArrayList<>();
        for (String line : lines) {
            escaped.add(html(line));
        }
        return escaped;
    }

    // ---- Filtering ----

    private void fill() {
        Event selected = selected();
        String text = filter.getText().trim().toLowerCase(Locale.ROOT);
        Kind wantedKind = kind.getSelectedIndex() <= 0 ? null : Kind.values()[kind.getSelectedIndex() - 1];
        int wantedLevel = level.getSelectedIndex();
        List<Event> shown = new ArrayList<>();
        int hiddenBySet = 0;
        for (Event event : events) {
            if (wantedKind != null && event.Kind != wantedKind) {
                continue;
            }
            if (wantedLevel == 1 && event.Level == Level.INFO || wantedLevel == 2 && event.Level != Level.ERROR) {
                continue;
            }
            if (event.BySet && !bySet.isSelected()) {
                hiddenBySet++;
                continue;
            }
            if (!text.isEmpty() && !(event.Title + " " + event.Object + " " + event.Summary + " " + event.Kind.Text)
                    .toLowerCase(Locale.ROOT).contains(text)) {
                continue;
            }
            shown.add(event);
        }
        model.setEvents(shown);
        // The same event stays selected after a new reading
        if (selected != null) {
            for (int row = 0; row < shown.size(); row++) {
                if (shown.get(row).Id.equals(selected.Id) && shown.get(row).Kind == selected.Kind) {
                    int view = table.convertRowIndexToView(row);
                    table.getSelectionModel().setSelectionInterval(view, view);
                    break;
                }
            }
        }
        StringBuilder text2 = new StringBuilder();
        text2.append(shown.size() == events.size() ? MQFormat.Number(events.size()) + " events"
                : MQFormat.Number(shown.size()) + " of " + MQFormat.Number(events.size()) + " events");
        if (hiddenBySet > 0) {
            text2.append(" · ").append(MQFormat.Number(hiddenBySet)).append(" changes by applications hidden");
        }
        if (lastResult != null) {
            text2.append(" · read ").append(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()));
        }
        status.setText(text2.toString());
        if (table.getSelectedRow() < 0) {
            showDetail();
        }
    }

    private Event selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.get(table.convertRowIndexToModel(row));
    }

    // ---- Detail of the selected event ----

    private void showDetail() {
        Event event = selected();
        StringBuilder text = new StringBuilder("<html><body style='margin:0'>");
        if (event == null) {
            text.append("<span style='color:").append(muted()).append("'>Select an event to see all its details.</span>");
        } else {
            text.append("<div style='font-size:110%'><b>").append(html(event.Title)).append("</b>");
            if (!event.Object.isEmpty()) {
                text.append(" &nbsp;").append(html(event.Object));
            }
            text.append("</div><div style='color:").append(muted()).append("; margin-top:2px'>")
                    .append(TIME.format(event.Time)).append(" · ").append(event.Level.Text).append(" · ")
                    .append(html(event.Kind.Text)).append(" event · ").append(html(event.Kind.Queue)).append("</div>");
            if (!event.Changes.isEmpty()) {
                text.append("<p style='margin-top:10px; margin-bottom:4px'><b>Changed</b></p><table cellspacing='0' cellpadding='2'>");
                text.append("<tr><td style='color:").append(muted()).append("; padding-right:16px'>Attribute</td><td style='color:")
                        .append(muted()).append("; padding-right:16px'>Before</td><td style='color:").append(muted()).append("'>After</td></tr>");
                for (Change change : event.Changes) {
                    text.append("<tr><td style='padding-right:16px'>").append(html(change.Name)).append("</td><td style='padding-right:16px'>")
                            .append(html(change.Before)).append("</td><td>").append(html(change.After)).append("</td></tr>");
                }
                text.append("</table>");
            }
            text.append("<p style='margin-top:10px; margin-bottom:4px'><b>").append(event.Changes.isEmpty() ? "Details" : "Object after the change")
                    .append("</b></p><table cellspacing='0' cellpadding='2'>");
            for (Field field : event.Fields) {
                if (field.Value == null) {
                    text.append("<tr><td colspan='2' style='padding-top:6px'><i>").append(html(field.Name)).append("</i></td></tr>");
                } else if (!field.Value.isEmpty()) {
                    text.append("<tr><td style='color:").append(muted()).append("; padding-right:16px; padding-left:")
                            .append(field.Depth * 14).append("px' title='").append(html(field.MQName)).append("'>")
                            .append(html(field.Name)).append("</td><td>").append(html(field.Value)).append("</td></tr>");
                }
            }
            text.append("</table>");
        }
        text.append("</body></html>");
        detail.setText(text.toString());
        detail.setCaretPosition(0);
    }

    private static String muted() {
        Color color = UIManager.getColor("Label.disabledForeground");
        return color == null ? "#808080" : String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String html(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    static Color color(Level level) {
        switch (level) {
            case ERROR:
                return Widgets.RED;
            case WARNING:
                return Widgets.ORANGE;
            default:
                return Widgets.BLUE;
        }
    }

    // ---- Settings and saving ----

    private void showSettings() {
        EventSettingsDialog dialog = new EventSettingsDialog(this, queueManager, queueManagerName);
        dialog.setVisible(true);
        if (dialog.Changed()) {
            switches = null;
            read();
        }
    }

    private void saveCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV file (*.csv)", "csv"));
        chooser.setSelectedFile(new File(queueManagerName + "-events.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getPath() + ".csv");
        }
        StringBuilder csv = new StringBuilder("Time,Level,Type,Event,Object,Details\n");
        for (int row = 0; row < table.getRowCount(); row++) {
            Event event = model.get(table.convertRowIndexToModel(row));
            csv.append(TIME.format(event.Time)).append(',').append(event.Level.Text).append(',').append(csvText(event.Kind.Text))
                    .append(',').append(csvText(event.Title)).append(',').append(csvText(event.Object)).append(',')
                    .append(csvText(event.Summary)).append('\n');
        }
        try {
            Files.writeString(file.toPath(), csv.toString(), StandardCharsets.UTF_8);
            status.setText("Saved to " + file.getPath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "The file could not be saved: " + ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csvText(String text) {
        return text.contains(",") || text.contains("\"") || text.contains("\n") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /** The events shown: time, level, type, event, object, details. */
    private static class EventTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Time", "Level", "Type", "Event", "Object", "Details"};
        private List<Event> events = new ArrayList<>();

        void setEvents(List<Event> events) {
            this.events = events;
            fireTableDataChanged();
        }

        Event get(int row) {
            return events.get(row);
        }

        @Override
        public int getRowCount() {
            return events.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Instant.class : column == 1 ? Level.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Event event = events.get(row);
            switch (column) {
                case 0:
                    return event.Time;
                case 1:
                    return event.Level;
                case 2:
                    return event.Kind.Text;
                case 3:
                    return event.Title;
                case 4:
                    return event.Object;
                default:
                    return event.Summary;
            }
        }
    }
}
