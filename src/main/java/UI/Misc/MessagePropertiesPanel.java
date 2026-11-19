package UI.Misc;

import MQApi.Utilities.MQUtility;
import MQApi.Utilities.MessageFile;
import UI.Helpers.IconManager;
import UI.Overview.Widgets;
import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Message properties (JMS and usr properties, e.g. set with setStringProperty) of a message: shown with
 * their type and changed before the message is put again.
 */
public class MessagePropertiesPanel extends JPanel {
    private static final String[] TYPES = {"String", "Integer", "Long", "Short", "Byte", "Boolean", "Float", "Double", "Bytes", "Null"};

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Name", "Type", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return editable && !(column == 2 && "Null".equals(getValueAt(row, 1)));
        }
    };
    private final JTable table = new JTable(model);
    private final JButton add = new JButton("Add", new IconManager().AddNewIcon());
    private final JButton remove = new JButton("Remove");
    private final List<String> loaded = new ArrayList<>();
    private boolean editable = true;

    public MessagePropertiesPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout());
        top.add(Widgets.Muted("Message properties (JMS, usr…): Bytes as hexadecimal"), BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        add.addActionListener(e -> {
            model.addRow(new Object[]{"", "String", ""});
            int row = model.getRowCount() - 1;
            table.setRowSelectionInterval(row, row);
            table.editCellAt(row, 0);
            table.getEditorComponent().requestFocusInWindow();
        });
        remove.addActionListener(e -> {
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                model.removeRow(rows[i]);
            }
        });
        buttons.add(add);
        buttons.add(remove);
        top.add(buttons, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(TYPES)));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    /** Shows the properties of the message. */
    public void Load(MQMessage message) {
        model.setRowCount(0);
        loaded.clear();
        for (Map.Entry<String, Object> property : MessageFile.Properties(message).entrySet()) {
            loaded.add(property.getKey());
            Object value = property.getValue();
            model.addRow(new Object[]{property.getKey(), type(value), text(value)});
        }
    }

    public int Count() {
        return model.getRowCount();
    }

    public void SetEditable(boolean editable) {
        this.editable = editable;
        add.setEnabled(editable);
        remove.setEnabled(editable);
    }

    /** Why the properties cannot be set (empty name, value not of its type), null when they can. */
    public String Problem() {
        stopEditing();
        for (int row = 0; row < model.getRowCount(); row++) {
            String name = String.valueOf(model.getValueAt(row, 0)).trim();
            if (name.isEmpty()) {
                return "A message property has no name";
            }
            try {
                value(row);
            } catch (IllegalArgumentException ex) {
                return "The value of property " + name + " is not a " + model.getValueAt(row, 1) + ": " + model.getValueAt(row, 2);
            }
        }
        return null;
    }

    /** Gives the message the properties of the table: the removed ones are deleted. */
    public void Apply(MQMessage message) throws MQException {
        stopEditing();
        for (String name : loaded) {
            try {
                message.deleteProperty(name);
            } catch (MQException ex) {
                // Already without it
            }
        }
        for (int row = 0; row < model.getRowCount(); row++) {
            message.setObjectProperty(String.valueOf(model.getValueAt(row, 0)).trim(), value(row));
        }
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private Object value(int row) {
        String type = String.valueOf(model.getValueAt(row, 1));
        String text = String.valueOf(model.getValueAt(row, 2) == null ? "" : model.getValueAt(row, 2)).trim();
        switch (type) {
            case "Integer": return Integer.valueOf(text);
            case "Long": return Long.valueOf(text);
            case "Short": return Short.valueOf(text);
            case "Byte": return Byte.valueOf(text);
            case "Float": return Float.valueOf(text);
            case "Double": return Double.valueOf(text);
            case "Boolean":
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(text);
                }
                return Boolean.valueOf(text);
            case "Bytes": return hex(text);
            case "Null": return null;
            default: return String.valueOf(model.getValueAt(row, 2) == null ? "" : model.getValueAt(row, 2));
        }
    }

    private static byte[] hex(String text) {
        String digits = text.replace(" ", "");
        if (digits.length() % 2 != 0 || !digits.matches("[0-9A-Fa-f]*")) {
            throw new IllegalArgumentException(text);
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static String type(Object value) {
        if (value == null) return "Null";
        if (value instanceof Integer) return "Integer";
        if (value instanceof Long) return "Long";
        if (value instanceof Short) return "Short";
        if (value instanceof Byte) return "Byte";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Float) return "Float";
        if (value instanceof Double) return "Double";
        if (value instanceof byte[]) return "Bytes";
        return "String";
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof byte[]) return MQUtility.BytesToHex((byte[]) value);
        return value.toString();
    }
}
