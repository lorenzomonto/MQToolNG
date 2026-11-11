package UI.Helpers;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import javax.swing.UIManager;

/**
 * Selection colors of tables, trees and lists: a light tint of the accent color with the normal text
 * color, instead of the full accent color; a light gray when the component does not have the focus.
 */
public class SelectionStyle {

    /** Sets the colors for the current look and feel: call after every theme change, before updateUI. */
    public static void Apply() {
        boolean dark = FlatLaf.isLafDark();
        Color accent = UIManager.getColor("Component.accentColor");
        Color background = UIManager.getColor("Table.background");
        Color text = UIManager.getColor("Table.foreground");
        if (accent == null || background == null || text == null) {
            return;
        }
        Color selected = mix(accent, background, dark ? 0.34f : 0.16f);
        Color inactive = mix(text, background, dark ? 0.12f : 0.07f);
        for (String component : new String[]{"Table", "Tree", "List"}) {
            Color foreground = UIManager.getColor(component + ".foreground");
            UIManager.put(component + ".selectionBackground", selected);
            UIManager.put(component + ".selectionInactiveBackground", inactive);
            UIManager.put(component + ".selectionForeground", foreground);
            UIManager.put(component + ".selectionInactiveForeground", foreground);
        }
    }

    /** True when the selection uses a strong color: icons and secondary texts must then take the selection text color. */
    public static boolean IsStrong(Color selectionForeground, Color foreground) {
        return selectionForeground != null && !selectionForeground.equals(foreground);
    }

    private static Color mix(Color a, Color b, float amount) {
        return new Color(
                Math.round(a.getRed() * amount + b.getRed() * (1 - amount)),
                Math.round(a.getGreen() * amount + b.getGreen() * (1 - amount)),
                Math.round(a.getBlue() * amount + b.getBlue() * (1 - amount)));
    }
}
