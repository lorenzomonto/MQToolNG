package UI.Dialogs;

import MQApi.Connection.SecurePasswordStore;
import MQApi.Connection.SessionPasswords;
import MQApi.Models.Query.ConnectionDetailModel;
import UI.Helpers.SwingThread;
import java.awt.BorderLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.KeyboardFocusManager;

/**
 * Asks the password of a queue manager connection that has a user but no saved password.
 */
public class PasswordPrompt {

    /** Shows the dialog (on the Swing event thread) and returns the password, or null when cancelled. */
    public static SessionPasswords.PromptResult Ask(ConnectionDetailModel connectionDetail) {
        SessionPasswords.PromptResult[] result = new SessionPasswords.PromptResult[1];
        SwingThread.RunOnEdt(() -> result[0] = show(connectionDetail));
        return result[0];
    }

    private static SessionPasswords.PromptResult show(ConnectionDetailModel c) {
        JPasswordField passwordField = new JPasswordField(24);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel info = new JLabel("<html>Password of user <b>" + escape(c.User) + "</b><br>for "
                + escape(c.QueueManager) + " on " + escape(c.Host) + ":" + escape(c.Port) + "</html>");
        panel.add(info, BorderLayout.NORTH);
        panel.add(passwordField, BorderLayout.CENTER);
        JCheckBox saveCheckBox = new JCheckBox("Save password (encrypted for your Windows user)");
        JLabel note = new JLabel(SecurePasswordStore.IsAvailable()
                ? "If not saved, it is kept in memory only while connected."
                : "It is kept in memory only while connected.");
        note.putClientProperty("FlatLaf.styleClass", "small");
        note.setEnabled(false);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        if (SecurePasswordStore.IsAvailable()) {
            south.add(saveCheckBox, BorderLayout.NORTH);
        }
        south.add(note, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
            @Override
            public void selectInitialValue() {
                // JOptionPane gives the focus to its default button when shown: start in the password field
                passwordField.requestFocusInWindow();
            }
        };
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = pane.createDialog(owner, "Connect to " + c.QueueManager);
        // Enter in the password field confirms
        passwordField.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
        dialog.setVisible(true);
        dialog.dispose();

        Object value = pane.getValue();
        char[] password = passwordField.getPassword();
        // An empty password is treated as cancel: it would only fail with "not authorized"
        if (!(value instanceof Integer) || (Integer) value != JOptionPane.OK_OPTION || password.length == 0) {
            java.util.Arrays.fill(password, '\0');
            return null;
        }
        String text = new String(password);
        java.util.Arrays.fill(password, '\0');
        return new SessionPasswords.PromptResult(text, saveCheckBox.isSelected());
    }

    /** Asks for a secret (e.g. a key store password); null when cancelled or empty. */
    public static String AskSecret(String title, String message) {
        String[] result = new String[1];
        SwingThread.RunOnEdt(() -> {
            JPasswordField field = new JPasswordField(24);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JLabel("<html>" + escape(message) + "</html>"), BorderLayout.NORTH);
            panel.add(field, BorderLayout.CENTER);
            JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
                @Override
                public void selectInitialValue() {
                    field.requestFocusInWindow();
                }
            };
            JDialog dialog = pane.createDialog(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(), title);
            field.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
            dialog.setVisible(true);
            dialog.dispose();
            char[] secret = field.getPassword();
            Object value = pane.getValue();
            if (value instanceof Integer && (Integer) value == JOptionPane.OK_OPTION && secret.length > 0) {
                result[0] = new String(secret);
            }
            java.util.Arrays.fill(secret, '\0');
        });
        return result[0];
    }

    private static String escape(String text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
