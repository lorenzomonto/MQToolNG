package UI.Config;

import MQApi.Config.QueueManagerConfig;
import MQApi.Logs.LogWriter;
import UI.Helpers.IconManager;
import UI.Overview.Widgets;
import com.ibm.mq.MQQueueManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The definitions of a queue manager as an MQSC script (like dmpmqcfg): shown, copied or saved, to
 * keep a copy of the configuration or to create the same objects on another queue manager.
 */
public class ConfigExportDialog extends JDialog {

    private final String queueManagerName;
    private final String program;
    private final JTextArea script = new JTextArea();
    private final JCheckBox system = new JCheckBox("SYSTEM objects");
    private final JCheckBox authorities = new JCheckBox("Authority records", true);
    private final JLabel status = Widgets.Muted("Reading the definitions…");
    private final JButton copy = new JButton("Copy", new IconManager().CopyIcon());
    private final JButton save = new JButton("Save…");
    private QueueManagerConfig config;

    public ConfigExportDialog(Frame owner, MQQueueManager queueManager, String queueManagerName, String program) {
        super(owner, "Export configuration – " + queueManagerName, false);
        this.queueManagerName = queueManagerName;
        this.program = program;
        setIconImage(new IconManager().ExportConfigIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(Widgets.Styled("MQSC definitions of " + queueManagerName, "font: bold +4"), BorderLayout.NORTH);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.add(Widgets.Muted("Include:"));
        options.add(Box.createHorizontalStrut(8));
        options.add(system);
        options.add(Box.createHorizontalStrut(12));
        options.add(authorities);
        system.setToolTipText("Objects of the queue manager itself (SYSTEM.*): usually already on the target queue manager");
        authorities.setToolTipText("SET AUTHREC commands: the users and groups must exist on the target queue manager");
        system.addActionListener(e -> render());
        authorities.addActionListener(e -> render());
        top.add(options, BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);

        script.setEditable(false);
        script.setFont(new Font(Font.MONOSPACED, Font.PLAIN, script.getFont().getSize()));
        script.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        content.add(new JScrollPane(script), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        copy.setToolTipText("Copy the script to the clipboard");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(script.getText()), null));
        save.addActionListener(e -> save());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(copy);
        buttons.add(save);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);
        copy.setEnabled(false);
        save.setEnabled(false);

        setContentPane(content);
        setSize(new Dimension(900, 680));
        setLocationRelativeTo(owner);
        read(queueManager);
    }

    private void read(MQQueueManager queueManager) {
        new SwingWorker<QueueManagerConfig, String>() {
            @Override
            protected QueueManagerConfig doInBackground() throws Exception {
                return QueueManagerConfig.Read(queueManager, queueManagerName, kind -> publish(kind));
            }

            @Override
            protected void process(java.util.List<String> kinds) {
                status.setText("Reading the definitions… " + kinds.get(kinds.size() - 1));
            }

            @Override
            protected void done() {
                try {
                    config = get();
                    render();
                    copy.setEnabled(true);
                    save.setEnabled(true);
                } catch (Exception ex) {
                    LogWriter.WriteToLog("ConfigExportDialog", "read", ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("The definitions could not be read: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void render() {
        if (config == null) {
            return;
        }
        script.setText(config.Script(system.isSelected(), authorities.isSelected(), program));
        script.setCaretPosition(0);
        int count = config.Selected(system.isSelected(), authorities.isSelected()).size();
        status.setText(count + " definitions" + (config.Problems.isEmpty() ? ""
                : " · " + config.Problems.size() + " kinds could not be read (see the start of the script)"));
    }

    private void save() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save the MQSC script");
        chooser.setFileFilter(new FileNameExtensionFilter("MQSC script (*.mqsc, *.txt)", "mqsc", "txt"));
        chooser.setSelectedFile(new File(queueManagerName + ".mqsc"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getPath() + ".mqsc");
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?", "Save",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.writeString(file.toPath(), script.getText(), StandardCharsets.UTF_8);
            status.setText("Saved to " + file.getPath());
        } catch (IOException ex) {
            LogWriter.WriteToLog("ConfigExportDialog", "save", ex);
            JOptionPane.showMessageDialog(this, "The file could not be saved: " + ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Opens the dialog for the queue manager. */
    public static void Show(Frame owner, MQQueueManager queueManager, String queueManagerName, String program) {
        SwingUtilities.invokeLater(() -> new ConfigExportDialog(owner, queueManager, queueManagerName, program).setVisible(true));
    }
}
