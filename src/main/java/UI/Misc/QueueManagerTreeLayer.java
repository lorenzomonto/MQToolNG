package UI.Misc;

import UI.Helpers.IconManager;
import UI.Helpers.TreeHelper;
import UI.Models.TreeNodeObject;
import UI.Models.TreeNodeObject.TreeNodeObjectType;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Drawn over the queue manager list, aligned on its right side: the version of each connected queue manager
 * and the quick actions of the row under the mouse. Also keeps the "Connecting…" dots spinning.
 */
public class QueueManagerTreeLayer extends LayerUI<JTree> {

    /** What the quick actions do; the node is selected first. */
    public interface Actions {
        void Connect(DefaultMutableTreeNode node);
        void Edit(DefaultMutableTreeNode node);
        void Refresh(DefaultMutableTreeNode node);
        void ShowMenu(DefaultMutableTreeNode node, JComponent invoker, int x, int y);
    }

    private enum Action {
        CONNECT("log-in", "Connect"),
        EDIT("pencil", "Edit connection"),
        REFRESH("refresh-cw", "Refresh"),
        MORE("ellipsis", "More actions");

        final String icon;
        final String tip;

        Action(String icon, String tip) {
            this.icon = icon;
            this.tip = tip;
        }
    }

    private static final int BUTTON = 22;
    private static final int RIGHT = 8;
    private static final int GAP = 6;

    private final Actions actions;
    private final Timer spinner;
    private JTree tree;
    private int hoverRow = -1;
    private Point mouse;
    private Action pressedAction;

    public QueueManagerTreeLayer(Actions actions) {
        this.actions = actions;
        spinner = new Timer(40, e -> {
            if (tree != null && TreeHelper.AnyConnecting()) {
                tree.repaint();
            } else {
                ((Timer) e.getSource()).stop();
            }
        });
    }

    /** Starts the spinner of the rows whose connection is opening; it stops by itself. */
    public void ConnectingChanged() {
        if (tree != null) {
            tree.repaint();
        }
        if (TreeHelper.AnyConnecting()) {
            spinner.start();
        }
    }

    /** Name of the quick action at a point of the list, for its tooltip; null elsewhere. */
    public String ActionTip(Point point) {
        Action action = actionAt(point);
        return action == null ? null : action.tip;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void installUI(JComponent c) {
        super.installUI(c);
        JLayer<JTree> layer = (JLayer<JTree>) c;
        tree = layer.getView();
        layer.setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void uninstallUI(JComponent c) {
        ((JLayer<JTree>) c).setLayerEventMask(0);
        spinner.stop();
        super.uninstallUI(c);
    }

    // --- Mouse ---

    @Override
    protected void processMouseMotionEvent(MouseEvent e, JLayer<? extends JTree> layer) {
        track(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tree));
    }

    @Override
    protected void processMouseEvent(MouseEvent e, JLayer<? extends JTree> layer) {
        if (e.getID() == MouseEvent.MOUSE_EXITED) {
            track(null);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        Point point = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tree);
        if (e.getID() == MouseEvent.MOUSE_RELEASED) {
            // The action runs when the button is released on it: a menu opened on the press would close at once
            Action pressed = pressedAction;
            pressedAction = null;
            if (pressed != null) {
                e.consume();
                if (pressed == actionAt(point)) {
                    int row = rowAt(point);
                    SwingUtilities.invokeLater(() -> run(pressed, row));
                }
            }
            return;
        }
        if (e.getID() != MouseEvent.MOUSE_PRESSED) {
            return;
        }
        int row = rowAt(point);
        if (row < 0) {
            return;
        }
        if (TreeHelper.IsSection(node(row))) {
            e.consume(); // headers are not selectable
            return;
        }
        pressedAction = actionAt(point);
        if (pressedAction != null) {
            e.consume();
            tree.setSelectionPath(tree.getPathForRow(row));
            tree.requestFocusInWindow();
        }
    }

    private void run(Action action, int row) {
        DefaultMutableTreeNode node = node(row);
        Rectangle button = buttonBounds(row, buttons(object(node)).indexOf(action), buttons(object(node)).size());
        switch (action) {
            case CONNECT:
                actions.Connect(node);
                break;
            case EDIT:
                actions.Edit(node);
                break;
            case REFRESH:
                actions.Refresh(node);
                break;
            case MORE:
                actions.ShowMenu(node, tree, button.x, button.y + button.height);
                break;
        }
    }

    private void track(Point point) {
        int row = point == null ? -1 : rowAt(point);
        if (row != hoverRow || (row >= 0 && !point.equals(mouse))) {
            hoverRow = row;
            mouse = point;
            tree.repaint();
        }
    }

    // Row at a point, on its whole width
    private int rowAt(Point point) {
        if (point == null || tree.getRowCount() == 0) {
            return -1;
        }
        int row = tree.getClosestRowForLocation(point.x, point.y);
        Rectangle bounds = tree.getRowBounds(row);
        return bounds != null && point.y >= bounds.y && point.y < bounds.y + bounds.height ? row : -1;
    }

    private Action actionAt(Point point) {
        int row = rowAt(point);
        if (row < 0 || row != hoverRow) {
            return null;
        }
        List<Action> buttons = buttons(object(node(row)));
        for (int i = 0; i < buttons.size(); i++) {
            if (buttonBounds(row, i, buttons.size()).contains(point)) {
                return buttons.get(i);
            }
        }
        return null;
    }

    private static List<Action> buttons(TreeNodeObject object) {
        List<Action> buttons = new ArrayList<>();
        if (object.Type != TreeNodeObjectType.QueueManager || object.Connecting) {
            return buttons;
        }
        if (object.IsConnected()) {
            buttons.add(Action.REFRESH);
        } else {
            buttons.add(Action.CONNECT);
            buttons.add(Action.EDIT);
        }
        buttons.add(Action.MORE);
        return buttons;
    }

    private Rectangle buttonBounds(int row, int index, int count) {
        Rectangle bounds = tree.getRowBounds(row);
        int x = tree.getWidth() - RIGHT - (count - index) * BUTTON - (count - index - 1) * 2;
        return new Rectangle(x, bounds.y + (bounds.height - BUTTON) / 2, BUTTON, BUTTON);
    }

    // --- Painting ---

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (tree == null || tree.getRowCount() == 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle visible = tree.getVisibleRect();
        int first = tree.getClosestRowForLocation(0, visible.y);
        int last = tree.getClosestRowForLocation(0, visible.y + visible.height);
        for (int row = first; row <= last; row++) {
            paintRow(g2, row);
        }
        g2.dispose();
    }

    private void paintRow(Graphics2D g2, int row) {
        DefaultMutableTreeNode node = node(row);
        TreeNodeObject object = object(node);
        if (object.Type == TreeNodeObjectType.QueueManagerFolder) {
            return;
        }
        Rectangle bounds = tree.getRowBounds(row);
        boolean hovered = row == hoverRow;
        boolean selected = tree.isRowSelected(row);
        Color hoverTint = tint(tree.getForeground(), 16);
        if (hovered && !selected) {
            g2.setColor(hoverTint);
            g2.fillRoundRect(4, bounds.y, tree.getWidth() - 8, bounds.height, 8, 8);
        }
        List<Action> buttons = hovered ? buttons(object) : new ArrayList<>();
        int right = tree.getWidth() - RIGHT;
        if (!buttons.isEmpty()) {
            int left = buttonBounds(row, 0, buttons.size()).x;
            cover(g2, bounds, left, right, selected, hovered);
            for (int i = 0; i < buttons.size(); i++) {
                Rectangle button = buttonBounds(row, i, buttons.size());
                if (mouse != null && button.contains(mouse)) {
                    g2.setColor(tint(tree.getForeground(), 28));
                    g2.fillRoundRect(button.x, button.y, button.width, button.height, 6, 6);
                }
                Action action = buttons.get(i);
                Color color = action == Action.CONNECT ? CustomTreeRender.GREEN : UIManager.getColor("Label.foreground");
                Icon icon = IconManager.Named(action.icon, color);
                icon.paintIcon(tree, g2, button.x + (BUTTON - icon.getIconWidth()) / 2, button.y + (BUTTON - icon.getIconHeight()) / 2);
            }
            return;
        }
        String version = version(node, object);
        if (version == null) {
            return;
        }
        Font font = tree.getFont().deriveFont(tree.getFont().getSize2D() - 1f);
        FontMetrics metrics = tree.getFontMetrics(font);
        int w = metrics.stringWidth(version) + 8;
        int h = metrics.getHeight() + 2;
        int x = right - w;
        int y = bounds.y + (bounds.height - h) / 2;
        cover(g2, bounds, x, right, selected, hovered);
        Color muted = UIManager.getColor("Label.disabledForeground");
        g2.setColor(tint(muted, 110));
        g2.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
        g2.setFont(font);
        g2.setColor(muted);
        g2.drawString(version, x + 4, y + 1 + metrics.getAscent());
    }

    // Hides the end of the row text under the version or the buttons, up to the right side of the list
    private void cover(Graphics2D g2, Rectangle bounds, int left, int right, boolean selected, boolean hovered) {
        Graphics2D clipped = (Graphics2D) g2.create();
        clipped.clipRect(left - GAP, bounds.y, tree.getWidth() - left + GAP, bounds.height);
        clipped.setColor(tree.getBackground());
        clipped.fillRect(left - GAP, bounds.y, tree.getWidth() - left + GAP, bounds.height);
        // Same rounded shape as the selection and the hover (4 px from the sides)
        int x = left - GAP - 16;
        int width = tree.getWidth() - 4 - x;
        if (selected) {
            clipped.setColor(UIManager.getColor(tree.hasFocus() ? "Tree.selectionBackground" : "Tree.selectionInactiveBackground"));
            clipped.fillRoundRect(x, bounds.y, width, bounds.height, 8, 8);
        } else if (hovered) {
            clipped.setColor(tint(tree.getForeground(), 16));
            clipped.fillRoundRect(x, bounds.y, width, bounds.height, 8, 8);
        }
        clipped.dispose();
    }

    // Version of a connected queue manager, like "9.4", from its last overview
    private static String version(DefaultMutableTreeNode node, TreeNodeObject object) {
        if (object.Type != TreeNodeObjectType.QueueManager || !object.IsConnected() || object.Summary == null) {
            return null;
        }
        return object.Summary.Version;
    }

    private DefaultMutableTreeNode node(int row) {
        return (DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent();
    }

    private static TreeNodeObject object(DefaultMutableTreeNode node) {
        return (TreeNodeObject) node.getUserObject();
    }

    private static Color tint(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
