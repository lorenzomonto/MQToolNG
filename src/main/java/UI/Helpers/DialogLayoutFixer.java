package UI.Helpers;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * Makes the dialogs generated with the NetBeans GUI builder fit the current look and feel.
 *
 * The generated code gives many components a fixed preferred size in pixels (labels 150x25, fields
 * 300x25, buttons 100x25, whole dialogs 500x400) computed for the old look and feel: with the current
 * fonts and paddings texts get truncated, buttons cut off and dialogs have empty areas. Before a dialog
 * is shown this keeps the intended widths but never below what the content needs, uses the natural
 * height of the theme, and sizes the dialog to its content.
 */
public class DialogLayoutFixer {

    private static final String FIXED = "DialogLayoutFixer.fixed";

    public static void Apply(Window window) {
        if (window instanceof JDialog && ((JDialog) window).getRootPane().getClientProperty(FIXED) != null) {
            return;
        }
        // Read before the components change: the preferred size of the window as the old layout made it
        Dimension oldLayoutSize = window.getPreferredSize();
        boolean setOnPurpose = !window.isPreferredSizeSet() && !window.getSize().equals(oldLayoutSize);
        fixComponents(window);
        invalidateTree(window);
        fixScrollPanes(window);
        // setPreferredSize does not invalidate: the layouts would keep the old sizes in their cache
        invalidateTree(window);
        if (window instanceof JDialog) {
            JDialog dialog = (JDialog) window;
            dialog.getRootPane().putClientProperty(FIXED, Boolean.TRUE);
            closeOnEscape(dialog);
        }
        // A size set on purpose with setSize (different from what the old layout computed) is kept as
        // the starting size; a fixed preferred size, or the size of the old layout, is what leaves
        // empty areas and truncated texts
        Dimension before = setOnPurpose ? window.getSize() : new Dimension(0, 0);
        window.setPreferredSize(null);
        window.setMinimumSize(null);
        window.pack();
        Dimension packed = window.getSize();
        // Keep a larger size that the dialog set on purpose (e.g. the message browser), within the screen
        Rectangle screen = usableScreen(window);
        int width = Math.min(Math.max(packed.width, before.width), screen.width * 9 / 10);
        int height = Math.min(Math.max(packed.height, before.height), screen.height * 9 / 10);
        window.setMinimumSize(new Dimension(Math.min(packed.width, width), Math.min(packed.height, height)));
        window.setSize(width, height);
        if (window instanceof Dialog) {
            ((Dialog) window).setResizable(true);
        }
        window.setLocationRelativeTo(window.getOwner());
    }

    private static void fixScrollPanes(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof javax.swing.JScrollPane) {
                fixScrollPane((javax.swing.JScrollPane) component);
            }
            if (component instanceof Container) {
                fixScrollPanes((Container) component);
            }
        }
    }

    private static void invalidateTree(Container container) {
        container.invalidate();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                invalidateTree((Container) component);
            } else {
                component.invalidate();
            }
        }
    }

    private static void fixComponents(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JComponent) {
                fix((JComponent) component);
            }
            if (component instanceof Container) {
                fixComponents((Container) component);
            }
        }
    }

    // Scrolling areas ask for at most this height: the dialogs are resizable, the content scrolls
    private static final int MAX_SCROLL_HEIGHT = 400;

    private static void fixScrollPane(javax.swing.JScrollPane scroll) {
        if (scroll.isPreferredSizeSet()) {
            return;
        }
        Dimension preferred = scroll.getPreferredSize();
        if (preferred.height > MAX_SCROLL_HEIGHT) {
            int scrollBar = scroll.getVerticalScrollBar().getPreferredSize().width;
            scroll.setPreferredSize(new Dimension(preferred.width + scrollBar, MAX_SCROLL_HEIGHT));
        }
    }

    private static void fix(JComponent component) {
        if (component instanceof javax.swing.JScrollPane) {
            return; // handled by fixScrollPanes, once the content has its final size
        }
        boolean text = component instanceof JLabel || component instanceof AbstractButton;
        boolean field = component instanceof JTextComponent && !(component instanceof javax.swing.JEditorPane) && !(component instanceof javax.swing.JTextArea)
                || component instanceof JComboBox || component instanceof JSpinner || component instanceof JProgressBar;
        if (!text && !field) {
            return;
        }
        if (component.isPreferredSizeSet()) {
            Dimension intended = component.getPreferredSize();
            component.setPreferredSize(null);
            Dimension natural = component.getPreferredSize();
            // Labels and buttons: never narrower than their text. Fields: the intended width.
            int width = text ? Math.max(intended.width, natural.width) : intended.width;
            component.setPreferredSize(new Dimension(width, natural.height));
        }
        if (component.isMinimumSizeSet()) {
            Dimension minimum = component.getMinimumSize();
            component.setMinimumSize(null);
            Dimension natural = component.getMinimumSize();
            component.setMinimumSize(new Dimension(text ? Math.max(minimum.width, natural.width) : minimum.width, natural.height));
        }
        if (component.isMaximumSizeSet()) {
            Dimension maximum = component.getMaximumSize();
            int height = component.getPreferredSize().height;
            component.setMaximumSize(new Dimension(Math.max(maximum.width, component.getPreferredSize().width), Math.max(maximum.height, height)));
        }
    }

    private static void closeOnEscape(JDialog dialog) {
        JRootPane root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        root.getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // Same as the close button of the title bar, so the dialog's own closing logic runs
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
            }
        });
    }

    private static Rectangle usableScreen(Window window) {
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        if (configuration == null) {
            Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(size);
        }
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
    }
}
