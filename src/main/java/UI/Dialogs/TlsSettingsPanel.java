package UI.Dialogs;

import MQApi.Connection.TlsSettings;
import MQApi.Models.Query.ConnectionDetailModel;
import com.formdev.flatlaf.FlatClientProperties;
import com.sun.jna.Platform;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * TLS part of the queue manager connection dialog. TLS is the user's choice:
 * without it the connection works as before, not encrypted.
 */
public class TlsSettingsPanel extends JPanel {

    private static final String TLS12 = "TLS 1.2 or higher (recommended)";
    private static final String TLS13 = "TLS 1.3 only";

    private final JCheckBox useTls = new JCheckBox("Use TLS (encrypt the connection)");
    private final JLabel notEncrypted = new JLabel("Not encrypted: messages and the password travel in clear on the network.");
    private final JComboBox<String> cipher = new JComboBox<>(new String[]{TLS12, TLS13});
    private final JRadioButton systemTrust = new JRadioButton(Platform.isWindows() ? "Windows certificate store" : "Java default certificates");
    private final JRadioButton fileTrust = new JRadioButton("Trust store file");
    private final JTextField trustStore = new JTextField(22);
    private final JPasswordField trustStorePassword = new JPasswordField(12);
    private final JButton trustBrowse = new JButton("Browse…");
    private final JTextField keyStore = new JTextField(22);
    private final JPasswordField keyStorePassword = new JPasswordField(12);
    private final JButton keyBrowse = new JButton("Browse…");
    private final JTextField peerName = new JTextField(22);
    private final JComponent[] tlsFields;
    private final java.util.List<JLabel> labels = new java.util.ArrayList<>();

    public TlsSettingsPanel() {
        super(new GridBagLayout());
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, javax.swing.UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(10, 0, 0, 0)));
        cipher.setEditable(true);
        cipher.setToolTipText("Must match the SSLCIPH of the channel; a Java cipher suite name can also be typed");
        ButtonGroup trust = new ButtonGroup();
        trust.add(systemTrust);
        trust.add(fileTrust);
        trustStore.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ".p12 or .jks file");
        trustStorePassword.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Password");
        keyStore.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Optional: for channels with SSLCAUTH(REQUIRED)");
        keyStorePassword.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Password");
        peerName.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Optional, e.g. CN=QM1");
        peerName.setToolTipText("SSLPEER: the connection is refused when the queue manager certificate does not match");

        int row = 0;
        add(useTls, constraints(0, row++, 3));
        add(notEncrypted, constraints(0, row++, 3));
        addRow(row++, "Encryption", cipher, null);
        JLabel trustLabel = new JLabel("Trusted certificates");
        labels.add(trustLabel);
        add(trustLabel, labelConstraints(row));
        add(systemTrust, constraints(1, row++, 2));
        add(fileTrust, constraints(1, row++, 2));
        addRow(row++, "", withPassword(trustStore, trustBrowse, trustStorePassword), null);
        addRow(row++, "Client certificate", withPassword(keyStore, keyBrowse, keyStorePassword), null);
        addRow(row++, "Server certificate name", peerName, null);

        tlsFields = new JComponent[]{cipher, systemTrust, fileTrust, trustStore, trustStorePassword, trustBrowse,
            keyStore, keyStorePassword, keyBrowse, peerName};
        useTls.addActionListener(e -> updateEnabled());
        systemTrust.addActionListener(e -> updateEnabled());
        fileTrust.addActionListener(e -> updateEnabled());
        trustBrowse.addActionListener(e -> browse(trustStore));
        keyBrowse.addActionListener(e -> browse(keyStore));
        Load(null);
    }

    /** Width of the widest label of this section. */
    public int LabelWidth() {
        return labels.stream().mapToInt(l -> l.getPreferredSize().width).max().orElse(0);
    }

    /** Gives the label column a fixed width, to line it up with the fields above the section. */
    public void SetLabelWidth(int width) {
        for (JLabel label : labels) {
            label.setPreferredSize(new java.awt.Dimension(width, label.getPreferredSize().height));
        }
    }

    private static JPanel withPassword(JTextField file, JButton browse, JPasswordField password) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.add(file, BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout(6, 0));
        east.setOpaque(false);
        east.add(browse, BorderLayout.WEST);
        east.add(password, BorderLayout.CENTER);
        panel.add(east, BorderLayout.EAST);
        return panel;
    }

    private void addRow(int row, String label, JComponent field, JComponent extra) {
        JLabel labelComponent = new JLabel(label);
        labels.add(labelComponent);
        add(labelComponent, labelConstraints(row));
        add(field, constraints(1, row, extra == null ? 2 : 1));
        if (extra != null) {
            add(extra, constraints(2, row, 1));
        }
    }

    private static GridBagConstraints labelConstraints(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 12);
        return c;
    }

    private static GridBagConstraints constraints(int x, int row, int width) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = row;
        c.gridwidth = width;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 0);
        return c;
    }

    private void updateEnabled() {
        boolean on = useTls.isSelected();
        for (JComponent field : tlsFields) {
            field.setEnabled(on);
        }
        boolean file = on && fileTrust.isSelected();
        trustStore.setEnabled(file);
        trustStorePassword.setEnabled(file);
        trustBrowse.setEnabled(file);
        // The line stays in place (no layout jump): a warning without TLS, a reminder with it
        if (on) {
            notEncrypted.setText("The queue manager certificate must be trusted, and the channel must have SSLCIPH set.");
            notEncrypted.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        } else {
            notEncrypted.setText("Not encrypted: messages and the password travel in clear on the network.");
            notEncrypted.putClientProperty(FlatClientProperties.STYLE, "foreground: #B45309");
        }
    }

    private void browse(JTextField target) {
        JFileChooser chooser = new JFileChooser(target.getText().isEmpty() ? null : new File(target.getText()).getParentFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Key stores (*.p12, *.pfx, *.jks)", "p12", "pfx", "jks"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** Shows the TLS settings of a connection; null = a new connection. */
    public void Load(ConnectionDetailModel connection) {
        useTls.setSelected(connection != null && connection.IsTlsEnabled());
        String suite = connection == null ? null : connection.TlsCipherSuite;
        if (suite == null || suite.isEmpty() || TlsSettings.TLS12_OR_HIGHER.equals(suite)) {
            cipher.setSelectedItem(TLS12);
        } else if (TlsSettings.TLS13_OR_HIGHER.equals(suite)) {
            cipher.setSelectedItem(TLS13);
        } else {
            cipher.setSelectedItem(suite);
        }
        String trust = connection == null ? null : connection.TrustStore;
        fileTrust.setSelected(trust != null && !trust.isEmpty());
        systemTrust.setSelected(trust == null || trust.isEmpty());
        trustStore.setText(trust == null ? "" : trust);
        trustStorePassword.setText(connection == null || connection.TrustStorePassword == null ? "" : connection.TrustStorePassword);
        keyStore.setText(connection == null || connection.KeyStore == null ? "" : connection.KeyStore);
        keyStorePassword.setText(connection == null || connection.KeyStorePassword == null ? "" : connection.KeyStorePassword);
        peerName.setText(connection == null || connection.TlsPeerName == null ? "" : connection.TlsPeerName);
        updateEnabled();
    }

    /** Writes the TLS settings into the connection. */
    public void Save(ConnectionDetailModel connection) {
        connection.UseTls = useTls.isSelected() ? "true" : "false";
        Object selected = cipher.getSelectedItem();
        String suite = selected == null ? "" : selected.toString().trim();
        connection.TlsCipherSuite = TLS12.equals(suite) || suite.isEmpty() ? TlsSettings.TLS12_OR_HIGHER
                : TLS13.equals(suite) ? TlsSettings.TLS13_OR_HIGHER : suite;
        connection.TrustStore = fileTrust.isSelected() ? trustStore.getText().trim() : "";
        connection.TrustStorePassword = fileTrust.isSelected() ? new String(trustStorePassword.getPassword()) : "";
        connection.KeyStore = keyStore.getText().trim();
        connection.KeyStorePassword = new String(keyStorePassword.getPassword());
        connection.TlsPeerName = peerName.getText().trim();
    }
}
