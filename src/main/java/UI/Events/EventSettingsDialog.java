package UI.Events;

import MQApi.Events.QueueManagerEvents;
import MQApi.Events.QueueManagerEvents.Switch;
import MQApi.Logs.LogWriter;
import UI.Overview.Widgets;
import com.ibm.mq.MQQueueManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

/** Which events the queue manager records (AUTHOREV, CHLEV, CONFIGEV… of ALTER QMGR). */
public class EventSettingsDialog extends JDialog {
    private final MQQueueManager queueManager;
    private final JPanel rows = new JPanel(new GridBagLayout());
    private final JLabel status = Widgets.Muted("Reading the settings…");
    private final JButton apply = new JButton("Apply");
    private final Map<Switch, JComboBox<String>> choices = new EnumMap<>(Switch.class);
    private Map<Switch, Integer> values;
    private boolean changed;

    public EventSettingsDialog(Window owner, MQQueueManager queueManager, String queueManagerName) {
        super(owner, "Event settings – " + queueManagerName, ModalityType.APPLICATION_MODAL);
        this.queueManager = queueManager;
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(Widgets.Styled("Events recorded by " + queueManagerName, "font: bold +2"), BorderLayout.NORTH);
        top.add(Widgets.Muted("<html>Each kind of event is written to its queue only when turned on.<br>"
                + "Performance events must also be turned on for each queue (e.g. QDPMAXEV, QDPHIEV and QDEPTHHI).<br>"
                + "Command events can leave out the DISPLAY commands, which tools like this one send often.</html>"), BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);
        content.add(rows, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        apply.setEnabled(false);
        apply.addActionListener(e -> apply());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(apply);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(860, 600);
        setLocationRelativeTo(owner);
        load();
    }

    /** True when settings were applied. */
    public boolean Changed() {
        return changed;
    }

    private void load() {
        new SwingWorker<Map<Switch, Integer>, Void>() {
            @Override
            protected Map<Switch, Integer> doInBackground() throws Exception {
                return QueueManagerEvents.ReadSwitches(queueManager);
            }

            @Override
            protected void done() {
                try {
                    values = get();
                    showRows();
                    status.setText(" ");
                } catch (Exception ex) {
                    LogWriter.WriteToLog("EventSettingsDialog", "load", ex);
                    status.setText("The settings could not be read: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                }
            }
        }.execute();
    }

    private void showRows() {
        rows.removeAll();
        choices.clear();
        int row = 0;
        for (Switch eventSwitch : Switch.values()) {
            Integer value = values.get(eventSwitch);
            if (value == null) {
                continue; // not on this platform
            }
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(4, 0, 4, 12);
            c.gridx = 0;
            JLabel name = Widgets.Styled(eventSwitch.Text, "font: bold");
            name.setToolTipText(eventSwitch.Mqsc);
            rows.add(name, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            rows.add(Widgets.Muted(eventSwitch.Description), c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(4, 0, 4, 0);
            JComboBox<String> choice = new JComboBox<>(eventSwitch.Choices());
            // All as wide as the longest choice
            choice.setPrototypeDisplayValue("On, except DISPLAY");
            int[] options = eventSwitch.Values();
            for (int i = 0; i < options.length; i++) {
                if (options[i] == value) {
                    choice.setSelectedIndex(i);
                }
            }
            choice.addActionListener(e -> apply.setEnabled(!edited().isEmpty()));
            choices.put(eventSwitch, choice);
            rows.add(choice, c);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        rows.add(new JPanel(), filler);
        rows.revalidate();
        rows.repaint();
    }

    // The switches whose choice differs from the queue manager's value
    private Map<Switch, Integer> edited() {
        Map<Switch, Integer> edited = new EnumMap<>(Switch.class);
        for (Map.Entry<Switch, JComboBox<String>> entry : choices.entrySet()) {
            int value = entry.getKey().Values()[entry.getValue().getSelectedIndex()];
            if (value != values.get(entry.getKey())) {
                edited.put(entry.getKey(), value);
            }
        }
        return edited;
    }

    private void apply() {
        Map<Switch, Integer> edited = edited();
        if (edited.isEmpty()) {
            return;
        }
        apply.setEnabled(false);
        status.setText("Applying…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return QueueManagerEvents.WriteSwitches(queueManager, edited);
            }

            @Override
            protected void done() {
                String error;
                try {
                    error = get();
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                if (error == null) {
                    changed = true;
                    values.putAll(edited);
                    status.setText("Applied: " + mqsc(edited));
                } else {
                    if (edited.containsKey(Switch.LOGGER)) {
                        error += " (logger events need a queue manager with linear logging)";
                    }
                    status.setText("<html>Not applied: " + error + "</html>");
                    apply.setEnabled(true);
                }
            }
        }.execute();
    }

    // "ALTER QMGR CHLEV(ENABLED) …", what was done in MQSC terms
    private static String mqsc(Map<Switch, Integer> edited) {
        StringBuilder text = new StringBuilder("ALTER QMGR");
        for (Map.Entry<Switch, Integer> entry : edited.entrySet()) {
            int value = entry.getValue();
            String keyword = value == com.ibm.mq.constants.MQConstants.MQEVR_DISABLED ? "DISABLED"
                    : value == com.ibm.mq.constants.MQConstants.MQEVR_EXCEPTION ? "EXCEPTION"
                    : value == com.ibm.mq.constants.MQConstants.MQEVR_NO_DISPLAY ? "NODISPLAY" : "ENABLED";
            text.append(' ').append(entry.getKey().Mqsc).append('(').append(keyword).append(')');
        }
        return text.toString();
    }
}
