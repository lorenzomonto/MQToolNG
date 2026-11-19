package UI.Config;

import MQApi.Config.AuthorityRecords;
import MQApi.Config.AuthorityRecords.Record;
import MQApi.Logs.LogWriter;
import UI.Helpers.IconManager;
import UI.Overview.Widgets;
import com.formdev.flatlaf.FlatClientProperties;
import com.ibm.mq.MQQueueManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/**
 * Authority records of a queue manager (like setmqaut and dspmqaut): all of them, or the ones that
 * apply to one object, also through generic profiles. Records can be added, changed and deleted.
 */
public class AuthorityDialog extends JDialog {

    private final MQQueueManager queueManager;
    private final String objectName;
    private final String objectType;
    private final JTextField filter = new JTextField(18);
    private final JLabel status = Widgets.Muted(" ");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Profile", "Object type", "Entity", "Entity type", "Authorities"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JButton edit = new JButton("Edit…");
    private final JButton delete = new JButton("Delete");
    private List<Record> records = new ArrayList<>();
    private final List<Record> shown = new ArrayList<>();

    /** objectName and objectType null: all the records of the queue manager. */
    public AuthorityDialog(Frame owner, MQQueueManager queueManager, String queueManagerName, String objectName, String objectType) {
        super(owner, (objectName == null ? "Authority records" : "Authorities on " + objectName) + " – " + queueManagerName, false);
        this.queueManager = queueManager;
        this.objectName = objectName;
        this.objectType = objectType;
        IconManager iconManager = new IconManager();
        setIconImage(iconManager.AuthorityIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel(new BorderLayout(12, 6));
        top.add(Widgets.Styled(objectName == null ? "Authority records of " + queueManagerName
                : "Authorities on " + objectType.toLowerCase(Locale.ROOT) + " " + objectName, "font: bold +4"), BorderLayout.NORTH);
        if (objectName != null) {
            top.add(Widgets.Muted("Records for this object and the generic profiles that include it (e.g. APP.**)"), BorderLayout.CENTER);
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton add = new JButton("Add…", iconManager.AddNewIcon());
        add.addActionListener(e -> editRecord(null));
        edit.addActionListener(e -> editRecord(selected()));
        delete.addActionListener(e -> deleteRecord(selected()));
        JButton refresh = new JButton(iconManager.RefreshIcon());
        refresh.setToolTipText("Read the records again");
        refresh.addActionListener(e -> load());
        actions.add(add);
        actions.add(edit);
        actions.add(delete);
        actions.add(refresh);
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(actions, BorderLayout.WEST);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by profile, entity or authority");
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
        top.add(bar, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selected() != null) {
                    editRecord(selected());
                }
            }
        });
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        bottom.add(close, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        setContentPane(content);
        setSize(new Dimension(1000, 600));
        setLocationRelativeTo(owner);
        updateButtons();
        load();
    }

    private Record selected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < shown.size() ? shown.get(row) : null;
    }

    private void updateButtons() {
        edit.setEnabled(selected() != null);
        delete.setEnabled(selected() != null);
    }

    private void load() {
        status.setText("Reading the authority records…");
        new SwingWorker<List<Record>, Void>() {
            @Override
            protected List<Record> doInBackground() throws Exception {
                return AuthorityRecords.Read(queueManager, objectName, objectType);
            }

            @Override
            protected void done() {
                try {
                    records = get();
                    fill();
                } catch (Exception ex) {
                    LogWriter.WriteToLog("AuthorityDialog", "load", ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("The authority records could not be read: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void fill() {
        String text = filter.getText().trim().toLowerCase(Locale.ROOT);
        model.setRowCount(0);
        shown.clear();
        for (Record record : records) {
            String authorities = String.join(", ", record.Authorities);
            String all = (record.Profile + " " + record.ObjectType + " " + record.Entity + " " + authorities).toLowerCase(Locale.ROOT);
            if (!text.isEmpty() && !all.contains(text)) {
                continue;
            }
            shown.add(record);
            model.addRow(new Object[]{record.Profile, record.ObjectType, record.Entity,
                record.EntityType.equals("GROUP") ? "Group" : "Principal", authorities.isEmpty() ? "None" : authorities});
        }
        status.setText(records.size() + (records.size() == 1 ? " record" : " records")
                + (shown.size() != records.size() ? " · " + shown.size() + " shown" : ""));
        updateButtons();
    }

    private void editRecord(Record record) {
        RecordEditor editor = new RecordEditor(this, record);
        editor.setVisible(true);
        if (editor.command == null) {
            return;
        }
        run(editor.command, "Saved");
    }

    private void deleteRecord(Record record) {
        if (record == null) {
            return;
        }
        String command = AuthorityRecords.DeleteCommand(record);
        if (JOptionPane.showConfirmDialog(this, "Delete the authorities of " + record.Entity + " on " + record.Profile + "?\n\n" + command,
                "Delete authority record", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        run(command, "Deleted");
    }

    private void run(String command, String done) {
        status.setText("Running " + command);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return AuthorityRecords.Apply(queueManager, command);
            }

            @Override
            protected void done() {
                String error;
                try {
                    error = get();
                } catch (Exception ex) {
                    LogWriter.WriteToLog("AuthorityDialog", "run", ex);
                    error = (ex.getCause() != null ? ex.getCause() : ex).getMessage();
                }
                if (error != null) {
                    status.setText(" ");
                    JOptionPane.showMessageDialog(AuthorityDialog.this, error + "\n\n" + command, "Authority record", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                LogWriter.WriteActivityToLog("AuthorityDialog : " + command);
                load();
                status.setText(done + ": " + command);
            }
        }.execute();
    }

    /** Adds a record or changes the authorities of one; command is the MQSC command to run, null when cancelled. */
    private class RecordEditor extends JDialog {
        private final Record original;
        private final JTextField profile = new JTextField(24);
        private final JComboBox<String> type = new JComboBox<>(AuthorityRecords.AUTHORITIES.keySet().toArray(new String[0]));
        private final JTextField entity = new JTextField(24);
        private final JRadioButton group = new JRadioButton("Group", true);
        private final JRadioButton principal = new JRadioButton("Principal (user)");
        private final JPanel boxes = new JPanel(new GridLayout(0, 3, 12, 4));
        private final List<JCheckBox> checks = new ArrayList<>();
        private final JTextArea preview = new JTextArea(3, 40);
        private final JButton save = new JButton("Save");
        String command;

        RecordEditor(Window owner, Record record) {
            super(owner, record == null ? "Add authority record" : "Change authorities", ModalityType.APPLICATION_MODAL);
            this.original = record;
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

            JPanel form = new JPanel(new GridBagLayout());
            ButtonGroup entityTypes = new ButtonGroup();
            entityTypes.add(group);
            entityTypes.add(principal);
            JPanel entityType = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            entityType.add(group);
            entityType.add(Box.createHorizontalStrut(12));
            entityType.add(principal);
            principal.setToolTipText("On Linux and UNIX the queue manager may give the authority to the primary group of the user");
            row(form, 0, "Object type", type);
            row(form, 1, "Profile", profile);
            row(form, 2, "Entity", entity);
            row(form, 3, "", entityType);
            profile.setToolTipText("Object name or generic profile: * one part, ** any number of parts (e.g. APP.**)");
            entity.setToolTipText("Name of the group or of the user, as the operating system knows it");
            if (record != null) {
                type.setSelectedItem(record.ObjectType);
                profile.setText(record.Profile);
                entity.setText(record.Entity);
                principal.setSelected(record.EntityType.equals("PRINCIPAL"));
                type.setEnabled(false);
                profile.setEnabled(false);
                entity.setEnabled(false);
                group.setEnabled(false);
                principal.setEnabled(false);
            } else if (objectName != null) {
                type.setSelectedItem(objectType);
                profile.setText(objectName);
            } else {
                type.setSelectedItem("QUEUE");
            }
            content.add(form, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(0, 8));
            JPanel boxesHeader = new JPanel(new BorderLayout());
            boxesHeader.add(Widgets.Styled("Authorities", "font: bold"), BorderLayout.WEST);
            JPanel quick = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            quick.add(Widgets.Link("All", () -> setAll(true)));
            quick.add(Widgets.Link("None", () -> setAll(false)));
            boxesHeader.add(quick, BorderLayout.EAST);
            center.add(boxesHeader, BorderLayout.NORTH);
            center.add(boxes, BorderLayout.CENTER);
            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, preview.getFont().getSize()));
            preview.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
            previewPanel.add(Widgets.Muted("MQSC command"), BorderLayout.NORTH);
            previewPanel.add(new JScrollPane(preview), BorderLayout.CENTER);
            center.add(previewPanel, BorderLayout.SOUTH);
            content.add(center, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            save.addActionListener(e -> {
                command = currentCommand();
                dispose();
            });
            buttons.add(save);
            buttons.add(cancel);
            content.add(buttons, BorderLayout.SOUTH);
            getRootPane().setDefaultButton(save);

            type.addActionListener(e -> buildBoxes());
            DocumentListener update = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    updatePreview();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    updatePreview();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    updatePreview();
                }
            };
            profile.getDocument().addDocumentListener(update);
            entity.getDocument().addDocumentListener(update);
            group.addActionListener(e -> updatePreview());
            principal.addActionListener(e -> updatePreview());
            buildBoxes();
            setContentPane(content);
            pack();
            setMinimumSize(new Dimension(560, getHeight()));
            setLocationRelativeTo(owner);
        }

        private void row(JPanel form, int y, String label, JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y;
            c.insets = new Insets(0, 0, 8, 12);
            c.anchor = GridBagConstraints.WEST;
            form.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 8, 0);
            form.add(field, c);
        }

        private void buildBoxes() {
            boxes.removeAll();
            checks.clear();
            String objectTypeName = (String) type.getSelectedItem();
            boolean isQmgr = "QMGR".equals(objectTypeName);
            if (original == null) {
                // The queue manager itself has no profile, its @class profile is the only other one
                profile.setEnabled(!isQmgr);
                if (isQmgr) {
                    profile.setText("");
                }
            }
            for (String authority : AuthorityRecords.AUTHORITIES.get(objectTypeName)) {
                JCheckBox check = new JCheckBox(authority);
                check.setToolTipText(AuthorityRecords.Description(authority));
                check.setSelected(original != null && original.Authorities.contains(authority));
                check.addActionListener(e -> updatePreview());
                checks.add(check);
                boxes.add(check);
            }
            boxes.revalidate();
            boxes.repaint();
            updatePreview();
        }

        private void setAll(boolean selected) {
            for (JCheckBox check : checks) {
                check.setSelected(selected);
            }
            updatePreview();
        }

        private Record edited() {
            Record record = new Record();
            record.ObjectType = (String) type.getSelectedItem();
            record.Profile = profile.getText().trim();
            record.Entity = entity.getText().trim();
            record.EntityType = principal.isSelected() ? "PRINCIPAL" : "GROUP";
            for (JCheckBox check : checks) {
                if (check.isSelected()) {
                    record.Authorities.add(check.getText());
                }
            }
            return record;
        }

        private String currentCommand() {
            Record record = edited();
            if (original != null && record.Authorities.isEmpty()) {
                return AuthorityRecords.DeleteCommand(record);
            }
            return AuthorityRecords.SetCommand(record, original != null ? original.Authorities : new ArrayList<>());
        }

        private void updatePreview() {
            Record record = edited();
            boolean complete = !record.Entity.isEmpty() && (record.ObjectType.equals("QMGR") || !record.Profile.isEmpty())
                    && (original != null || !record.Authorities.isEmpty());
            boolean changed = original == null || !new java.util.HashSet<>(record.Authorities).equals(new java.util.HashSet<>(original.Authorities));
            save.setEnabled(complete && changed);
            preview.setText(complete ? currentCommand() : "");
        }
    }
}
