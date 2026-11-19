package UI.Misc;

import UI.Helpers.IconManager;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

/** Chooses how often the page shown is read again by itself: off, every 5 s ... 1 min. */
public class AutoRefreshButton extends JButton {
    static final int[] CHOICES = {0, 5, 10, 30, 60};

    /** The interval shared by all the buttons, in seconds (0: off). */
    public interface Setting {
        int Seconds();

        void SetSeconds(int seconds);
    }

    private final Setting setting;
    private final boolean compact;

    /** compact: only the interval ("10 s") next to the icon, for a tool bar. */
    public AutoRefreshButton(Setting setting, boolean compact) {
        this.setting = setting;
        this.compact = compact;
        setIcon(new IconManager().RefreshIcon());
        setFocusable(false);
        addActionListener(e -> showChoices());
        Update();
    }

    /** Shows the current interval. */
    public final void Update() {
        int seconds = setting.Seconds();
        String interval = Text(seconds);
        if (compact) {
            setText(seconds == 0 ? "Auto" : interval);
        } else {
            setText(seconds == 0 ? "Auto refresh" : "Every " + interval);
        }
        setToolTipText(seconds == 0 ? "Read the page again by itself: off" : "The page is read again every " + interval);
    }

    static String Text(int seconds) {
        return seconds == 0 ? "Off" : seconds < 60 ? seconds + " s" : (seconds / 60) + " min";
    }

    private void showChoices() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (int seconds : CHOICES) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(seconds == 0 ? "Off" : "Every " + Text(seconds));
            item.setSelected(seconds == setting.Seconds());
            item.addActionListener(e -> setting.SetSeconds(seconds));
            group.add(item);
            menu.add(item);
        }
        menu.show(this, 0, getHeight());
    }
}
