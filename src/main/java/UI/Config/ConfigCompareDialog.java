package UI.Config;

import MQApi.Config.ConfigComparison;
import MQApi.Config.ConfigComparison.Difference;
import MQApi.Config.MqscObject;
import MQApi.Config.QueueManagerConfig;
import MQApi.Logs.LogWriter;
import UI.Helpers.IconManager;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Differences between the definitions of two connected queue managers: objects that are only on one
 * of them and attributes with different values. The MQSC commands that give the second one the
 * definitions of the first can be saved.
 */
public class ConfigCompareDialog extends JDialog {

    private final String leftName;
    private final Map<String, MQQueueManager> queueManagers;
    private final Map<String, QueueManagerConfig> configs = new LinkedHashMap<>();
    private final JComboBox<String> other;
    private final JCheckBox system = new JCheckBox("SYSTEM objects");
    private final JCheckBox authorities = new JCheckBox("Authority records", true);
    private final JTextField filter = new JTextField(18);
    private final JButton compare = new JButton("Compare");
    private final JButton save = new JButton("Save MQSC…");
    private final JLabel status = Widgets.Muted(" ");
    private final DefaultTableModel differencesModel = readOnlyModel("Type", "Name", "Difference");
    private final DefaultTableModel detailModel = readOnlyModel("Attribute", "", "");
    private final JTable differencesTable = new JTable(differencesModel);
    private final JTable detailTable = new JTable(detailModel);
    private List<Difference> differences = new ArrayList<>();
    private final List<Difference> shown = new ArrayList<>();
    private String rightName;

    /** queueManagers: the connected queue managers by name, the one compared first included. */
    public ConfigCompareDialog(Frame owner, String leftName, Map<String, MQQueueManager> queueManagers) {
        super(owner, "Compare configuration – " + leftName, false);
        this.leftName = leftName;
        this.queueManagers = queueManagers;
        IconManager iconManager = new IconManager();
        setIconImage(iconManager.CompareIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        JPanel choose = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        choose.add(Widgets.Styled("Compare " + leftName + " with", "font: bold +4"));
        choose.add(Box.createHorizontalStrut(10));
        List<String> names = new ArrayList<>(queueManagers.keySet());
        names.remove(leftName);
        other = new JComboBox<>(names.toArray(new String[0]));
        choose.add(other);
        choose.add(Box.createHorizontalStrut(8));
        choose.add(compare);
        compare.addActionListener(e -> compare());
        top.add(choose, BorderLayout.NORTH);

        JPanel options = new JPanel(new BorderLayout());
        JPanel includes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        includes.add(Widgets.Muted("Include:"));
        includes.add(Box.createHorizontalStrut(8));
        includes.add(system);
        includes.add(Box.createHorizontalStrut(12));
        includes.add(authorities);
        system.addActionListener(e -> recompute());
        authorities.addActionListener(e -> recompute());
        options.add(includes, BorderLayout.WEST);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by name or attribute");
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
        options.add(filter, BorderLayout.EAST);
        top.add(options, BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);

        differencesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        differencesTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        differencesTable.getColumnModel().getColumn(1).setPreferredWidth(300);
        differencesTable.getColumnModel().getColumn(2).setPreferredWidth(380);
        differencesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        differencesTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                if (!selected && value != null && value.toString().startsWith("Only in")) {
                    setForeground(value.toString().endsWith(leftName) ? Widgets.BLUE : Widgets.PURPLE);
                } else if (!selected) {
                    setForeground(table.getForeground());
                }
                return this;
            }
        });
        detailTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        detailTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value == null ? "–" : value, selected, focus, row, column);
                if (!selected) {
                    Color muted = javax.swing.UIManager.getColor("Label.disabledForeground");
                    setForeground(value == null ? muted : table.getForeground());
                }
                return this;
            }
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(differencesTable), new JScrollPane(detailTable));
        split.setResizeWeight(0.6);
        split.setBorder(BorderFactory.createEmptyBorder());
        content.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        save.setEnabled(false);
        save.addActionListener(e -> save());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(save);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        setContentPane(content);
        setSize(new Dimension(1000, 720));
        setLocationRelativeTo(owner);
        if (names.isEmpty()) {
            compare.setEnabled(false);
            status.setText("Connect another queue manager to compare it with " + leftName);
        } else {
            compare();
        }
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private void compare() {
        String right = (String) other.getSelectedItem();
        if (right == null) {
            return;
        }
        compare.setEnabled(false);
        save.setEnabled(false);
        status.setText("Reading the definitions of " + leftName + " and " + right + "…");
        // Read again each time: the definitions may have changed
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (String name : new String[]{leftName, right}) {
                    configs.put(name, QueueManagerConfig.Read(queueManagers.get(name), name, null));
                }
                return null;
            }

            @Override
            protected void done() {
                compare.setEnabled(true);
                try {
                    get();
                    rightName = right;
                    detailModel.setColumnIdentifiers(new Object[]{"Attribute", leftName, rightName});
                    detailTable.getColumnModel().getColumn(0).setPreferredWidth(140);
                    recompute();
                } catch (Exception ex) {
                    LogWriter.WriteToLog("ConfigCompareDialog", "compare", ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("The definitions could not be read: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void recompute() {
        QueueManagerConfig left = configs.get(leftName);
        QueueManagerConfig right = rightName == null ? null : configs.get(rightName);
        if (left == null || right == null) {
            return;
        }
        differences = ConfigComparison.Compare(left.Selected(system.isSelected(), authorities.isSelected()),
                right.Selected(system.isSelected(), authorities.isSelected()));
        save.setEnabled(true);
        fill();
    }

    private void fill() {
        String text = filter.getText().trim().toLowerCase(Locale.ROOT);
        differencesModel.setRowCount(0);
        shown.clear();
        int onlyLeft = 0;
        int onlyRight = 0;
        for (Difference difference : differences) {
            if (difference.Right == null) {
                onlyLeft++;
            } else if (difference.Left == null) {
                onlyRight++;
            }
            MqscObject object = difference.Any();
            String summary = difference.Summary(leftName, rightName);
            if (!text.isEmpty() && !object.Name.toLowerCase(Locale.ROOT).contains(text)
                    && !summary.toLowerCase(Locale.ROOT).contains(text) && !object.KindText().toLowerCase(Locale.ROOT).contains(text)) {
                continue;
            }
            shown.add(difference);
            differencesModel.addRow(new Object[]{object.KindText(), object.Kind.equals("QMGR") ? "(queue manager)" : object.Name, summary});
        }
        int changed = differences.size() - onlyLeft - onlyRight;
        status.setText(differences.isEmpty() ? "The definitions are the same"
                : differences.size() + " differences: " + onlyLeft + " only in " + leftName + ", "
                + onlyRight + " only in " + rightName + ", " + changed + " with different attributes");
        detailModel.setRowCount(0);
        if (!shown.isEmpty()) {
            differencesTable.setRowSelectionInterval(0, 0);
        }
    }

    private void showDetail() {
        detailModel.setRowCount(0);
        int row = differencesTable.getSelectedRow();
        if (row < 0 || row >= shown.size()) {
            return;
        }
        Difference difference = shown.get(row);
        if (difference.Left != null && difference.Right != null) {
            for (String[] attribute : difference.Attributes) {
                detailModel.addRow(new Object[]{attribute[0], attribute[1], attribute[2]});
            }
            return;
        }
        MqscObject object = difference.Any();
        for (Map.Entry<String, String> attribute : object.Attributes.entrySet()) {
            String value = attribute.getValue() == null ? "(set)" : attribute.getValue();
            detailModel.addRow(difference.Left != null ? new Object[]{attribute.getKey(), value, null}
                    : new Object[]{attribute.getKey(), null, value});
        }
    }

    /** MQSC commands for the second queue manager: the definitions of the first one where they differ. */
    private void save() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save the MQSC commands for " + rightName);
        chooser.setFileFilter(new FileNameExtensionFilter("MQSC script (*.mqsc, *.txt)", "mqsc", "txt"));
        chooser.setSelectedFile(new File(leftName + "-to-" + rightName + ".mqsc"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getPath() + ".mqsc");
        }
        StringBuilder script = new StringBuilder();
        script.append("* Commands that give ").append(rightName).append(" the definitions of ").append(leftName).append('\n');
        script.append("* Made by MQToolNG on ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        script.append("* Run with: runmqsc ").append(rightName).append(" < ").append(file.getName()).append('\n');
        script.append("* Objects only in ").append(rightName).append(" are listed at the end and not deleted").append('\n');
        List<String> onlyRight = new ArrayList<>();
        for (Difference difference : differences) {
            if (difference.Left == null) {
                onlyRight.add("* " + difference.Right.KindText() + " " + difference.Right.Name);
                continue;
            }
            script.append('\n').append(QueueManagerConfig.Wrap(difference.Left.Command())).append('\n');
        }
        if (!onlyRight.isEmpty()) {
            script.append("\n* Only in ").append(rightName).append(":\n");
            for (String line : onlyRight) {
                script.append(line).append('\n');
            }
        }
        try {
            Files.writeString(file.toPath(), script.toString(), StandardCharsets.UTF_8);
            status.setText("Saved to " + file.getPath());
        } catch (IOException ex) {
            LogWriter.WriteToLog("ConfigCompareDialog", "save", ex);
            JOptionPane.showMessageDialog(this, "The file could not be saved: " + ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }
}
