package UI.Misc;

import UI.Helpers.UpdateCheck;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.net.URI;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.UIManager;

/** Bar at the top of the main window that says a new version is available. Hidden until Show is called. */
public class UpdateBanner extends JPanel {

    private final JLabel message = new JLabel();
    private final JButton open = link("What's new and download");
    private final JButton skip = link("Skip this version");
    private final JButton close = new JButton("✕");
    private UpdateCheck.Release release;

    /** onSkip receives the version the user does not want to be told about again. */
    public UpdateBanner(Consumer<String> onSkip) {
        super(new BorderLayout(12, 0));
        setVisible(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 14, 0));
        actions.setOpaque(false);
        actions.add(open);
        actions.add(skip);
        close.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        close.setToolTipText("Hide until the next start");
        actions.add(close);
        add(message, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        open.addActionListener(e -> openPage());
        skip.addActionListener(e -> {
            setVisible(false);
            onSkip.accept(release.Version);
        });
        close.addActionListener(e -> setVisible(false));
        updateColors();
    }

    public void Show(UpdateCheck.Release release, String currentVersion) {
        this.release = release;
        message.setText("<html><b>MQToolNG " + release.Version + "</b> is available. You are using version " + currentVersion + ".</html>");
        setVisible(true);
        revalidate();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateColors();
    }

    // Light tint of the accent color, so the bar is noticed without looking like an error
    private void updateColors() {
        Color accent = UIManager.getColor("Component.accentColor");
        Color background = UIManager.getColor("Panel.background");
        if (accent == null || background == null) {
            return;
        }
        setBackground(mix(accent, background, 0.14f));
        Color line = UIManager.getColor("Component.borderColor");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, line != null ? line : accent),
                BorderFactory.createEmptyBorder(6, 12, 6, 6)));
    }

    private static Color mix(Color a, Color b, float amount) {
        return new Color(
                Math.round(a.getRed() * amount + b.getRed() * (1 - amount)),
                Math.round(a.getGreen() * amount + b.getGreen() * (1 - amount)),
                Math.round(a.getBlue() * amount + b.getBlue() * (1 - amount)));
    }

    private void openPage() {
        try {
            Desktop.getDesktop().browse(URI.create(release.PageUrl));
        } catch (Exception ex) {
            // No browser available: show the address to copy
            JOptionPane.showMessageDialog(this, "Download the new version from:\n" + release.PageUrl,
                    "MQToolNG " + release.Version, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static JButton link(String text) {
        JButton button = new JButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
}
