package UI.Dialogs;

import MQApi.Models.Query.ConnectionDetailModel;
import UI.Helpers.MQExplorerImport;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/**
 * Imports queue manager connections from a file exported by IBM MQ Explorer: the user picks the file,
 * then which queue managers to add. Connections already present and those that cannot be imported
 * are shown but not selectable.
 */
public class MQExplorerImportDialog {

    private static final String[] COLUMNS = {"", "Queue manager", "Host", "Port", "Channel", "User", "TLS", "Notes"};

    /** Returns the connections to add, empty when the user cancels or nothing is selected. */
    public static List<ConnectionDetailModel> Show(Component owner, List<ConnectionDetailModel> existing) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import from MQ Explorer");
        chooser.setFileFilter(new FileNameExtensionFilter("MQ Explorer settings (*.xml)", "xml"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        File file = chooser.getSelectedFile();
        List<MQExplorerImport.Entry> entries;
        try {
            entries = MQExplorerImport.Read(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner, ex.getMessage(), "Import from MQ Explorer", JOptionPane.ERROR_MESSAGE);
            return List.of();
        }
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "The file contains no queue manager.", "Import from MQ Explorer", JOptionPane.INFORMATION_MESSAGE);
            return List.of();
        }

        Model model = new Model(entries, existing);
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        javax.swing.table.TableCellRenderer header = table.getTableHeader().getDefaultRenderer();
        if (header instanceof JLabel) {
            ((JLabel) header).setHorizontalAlignment(JLabel.LEADING);
        }
        int[] widths = {36, 130, 200, 60, 230, 130, 50, 220};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(1100, Math.min(420, 60 + entries.size() * table.getRowHeight())));
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JLabel("<html>Queue managers found in " + file.getName() + ". "
                + "Passwords are not imported: they are asked when connecting.</html>"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        Object[] options = {"Import", "Cancel"};
        int choice = JOptionPane.showOptionDialog(owner, panel, "Import from MQ Explorer",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice != 0) {
            return List.of();
        }
        List<ConnectionDetailModel> selected = model.selected();
        StringBuilder summary = new StringBuilder(count(selected.size(), "queue manager imported", "queue managers imported"));
        if (model.present > 0) {
            summary.append("\n").append(count(model.present, "already present", "already present"));
        }
        if (model.unsupported > 0) {
            summary.append("\n").append(count(model.unsupported, "not supported", "not supported"));
        }
        int unselected = entries.size() - selected.size() - model.present - model.unsupported;
        if (unselected > 0) {
            summary.append("\n").append(count(unselected, "not selected", "not selected"));
        }
        JOptionPane.showMessageDialog(owner, summary.toString(), "Import from MQ Explorer", JOptionPane.INFORMATION_MESSAGE);
        return selected;
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static class Model extends AbstractTableModel {
        private final List<MQExplorerImport.Entry> entries;
        private final boolean[] checked;
        private final boolean[] enabled;
        private final String[] notes;
        int present;
        int unsupported;

        Model(List<MQExplorerImport.Entry> entries, List<ConnectionDetailModel> existing) {
            this.entries = entries;
            checked = new boolean[entries.size()];
            enabled = new boolean[entries.size()];
            notes = new String[entries.size()];
            List<ConnectionDetailModel> seen = new ArrayList<>(existing);
            for (int i = 0; i < entries.size(); i++) {
                MQExplorerImport.Entry entry = entries.get(i);
                if (entry.Connection == null) {
                    notes[i] = "Not imported: " + entry.Skipped;
                    unsupported++;
                } else if (MQExplorerImport.IsAlreadyPresent(entry.Connection, seen)) {
                    notes[i] = "Already present";
                    present++;
                } else {
                    enabled[i] = true;
                    checked[i] = true;
                    notes[i] = String.join(", ", entry.Notes);
                    seen.add(entry.Connection); // the same queue manager twice in the file: imported once
                }
            }
        }

        List<ConnectionDetailModel> selected() {
            List<ConnectionDetailModel> result = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (enabled[i] && checked[i]) {
                    result.add(entries.get(i).Connection);
                }
            }
            return result;
        }

        @Override
        public int getRowCount() {
            return entries.size();
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
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 && enabled[row];
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            checked[row] = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            MQExplorerImport.Entry entry = entries.get(row);
            ConnectionDetailModel c = entry.Connection;
            switch (column) {
                case 0:
                    return enabled[row] && checked[row];
                case 1:
                    return entry.Name;
                case 2:
                    return c == null ? "" : c.Host;
                case 3:
                    return c == null ? "" : c.Port;
                case 4:
                    return c == null ? "" : c.Channel;
                case 5:
                    return c == null ? "" : c.User;
                case 6:
                    return c == null ? "" : c.IsTlsEnabled() ? "Yes" : "";
                default:
                    return notes[row];
            }
        }
    }
}
