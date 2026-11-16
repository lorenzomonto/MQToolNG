/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Misc;

import UI.Helpers.IconManager;
import UI.Helpers.TreeHelper;
import UI.Models.TreeNodeObject;
import UI.Models.TreeNodeObject.TreeNodeObjectType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;

/**
 * Rows of the queue manager list: section headers, one row per queue manager (status dot, name,
 * address or last connection error) and the object folders. The version and the quick actions on
 * the right side are painted by {@link QueueManagerTreeLayer}.
 *
 * @author jzhou
 */
public class CustomTreeRender extends DefaultTreeCellRenderer {
    public static final Color GREEN = new Color(0x22A06B);
    public static final Color RED = new Color(0xE5484D);
    static final int ROW_HEIGHT = 26;
    private static final int SECTION_HEIGHT = 30;

    IconManager iconManager = new IconManager();
    private final JLabel sectionLabel = new JLabel();
    private final QueueManagerRow queueManagerRow = new QueueManagerRow();

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row,boolean hasFocus) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        if(nodeInfo.Type == TreeNodeObjectType.QueueManagerFolder){
            return section(tree, (DefaultMutableTreeNode) value, nodeInfo);
        }
        if(nodeInfo.Type == TreeNodeObjectType.QueueManager){
            queueManagerRow.set(tree, nodeInfo, sel && tree.isFocusOwner() && strongSelection(tree));
            return queueManagerRow;
        }
        super.getTreeCellRendererComponent(tree, value, sel,expanded, leaf, row,hasFocus);
        if(isRoot(value)){
            setIcon(iconManager.Root());
        }
        else if(isQueueFolder(value)){
            setIcon(iconManager.Queue());
        }
        else if(isChannelFolder(value)){
            setIcon(iconManager.Channel());
        }
        else if(isChannelAuthFolder(value)){
            setIcon(iconManager.AuthIcon());
        }
        else if(isPubFolder(value)){
            setIcon(iconManager.PubIcon());
        }
        else if(isSubFolder(value)){
            setIcon(iconManager.SubIcon());
        }
        if(sel && tree.isFocusOwner() && strongSelection(tree)){
            // Colored icons would disappear on the accent colored selection: use the selection text color
            setIcon(IconManager.InColor(getIcon(), getForeground()));
        }
        return this;
    }

    @Override
    public Dimension getPreferredSize(){
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, Math.max(size.height, ROW_HEIGHT));
    }

    // "CONNECTED · 4": small muted header, a little space above it
    private Component section(JTree tree, DefaultMutableTreeNode node, TreeNodeObject nodeInfo){
        Font font = tree.getFont();
        sectionLabel.setFont(font.deriveFont(Font.BOLD, font.getSize2D() - 2f));
        sectionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        sectionLabel.setText(nodeInfo.Name.toUpperCase(Locale.ROOT) + "  ·  " + TreeHelper.CountInSection(node));
        sectionLabel.setVerticalAlignment(JLabel.BOTTOM);
        sectionLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 5, 0));
        sectionLabel.setPreferredSize(null);
        sectionLabel.setPreferredSize(new Dimension(sectionLabel.getPreferredSize().width, SECTION_HEIGHT));
        return sectionLabel;
    }

    private static boolean strongSelection(JTree tree){
        return UI.Helpers.SelectionStyle.IsStrong(UIManager.getColor("Tree.selectionForeground"), tree.getForeground());
    }

    /** Connection error of an MQ reason, short enough for a row: "Host not available (2538)". */
    public static String ShortReason(int reason){
        String name = com.ibm.mq.constants.MQConstants.lookupReasonCode(reason);
        if(name == null || !name.startsWith("MQRC_")){
            return "Error " + reason;
        }
        String words = name.substring(5).replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + " (" + reason + ")";
    }

    private boolean isRoot(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.Root;
    }

    private boolean isQueueFolder(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.Queues;
    }

    private boolean isChannelFolder(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.Channel;
    }

    private boolean isChannelAuthFolder(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.ChannelAuth;
    }

    private boolean isPubFolder(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.Pub;
    }

    private boolean isSubFolder(Object value) {
        TreeNodeObject nodeInfo = getTreeNodeObject(value);
        return nodeInfo.Type == TreeNodeObjectType.Sub;
    }

    private TreeNodeObject getTreeNodeObject(Object value){
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
        TreeNodeObject nodeInfo = (TreeNodeObject)(node.getUserObject());
        return nodeInfo;
    }

    /** Status dot, name and a secondary text (address, "Connecting…" or the last error). */
    private static class QueueManagerRow extends JComponent {
        private static final int DOT = 8;
        private static final int GAP = 8;
        private TreeNodeObject node;
        private boolean onStrongSelection;
        private Font nameFont;
        private String detail;
        private Color detailColor;

        void set(JTree tree, TreeNodeObject node, boolean onStrongSelection){
            this.node = node;
            this.onStrongSelection = onStrongSelection;
            Font font = tree.getFont();
            setFont(font);
            nameFont = node.IsConnected() ? font.deriveFont(Font.BOLD) : font;
            Color muted = UIManager.getColor("Label.disabledForeground");
            if(node.Connecting){
                detail = "Connecting…";
                detailColor = UIManager.getColor("Component.accentColor");
            }
            else if(!node.IsConnected() && node.LastError != null){
                detail = node.LastError;
                detailColor = RED;
            }
            else{
                detail = node.ConnectionDetail == null ? "" : node.ConnectionDetail.Address()
                        + (node.ConnectionDetail.IsTlsEnabled() ? " · TLS" : "");
                detailColor = muted;
            }
            Color foreground = onStrongSelection ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground();
            setForeground(foreground);
            if(onStrongSelection){
                detailColor = foreground;
            }
        }

        @Override
        public Dimension getPreferredSize(){
            FontMetrics nameMetrics = getFontMetrics(nameFont);
            FontMetrics metrics = getFontMetrics(getFont());
            int width = 2 + DOT + GAP + nameMetrics.stringWidth(String.valueOf(node.Name)) + GAP + metrics.stringWidth(detail) + 4;
            return new Dimension(width, ROW_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g){
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int dotY = (getHeight() - DOT) / 2;
            paintStatus(g2, 2, dotY);
            int x = 2 + DOT + GAP;
            g2.setFont(nameFont);
            FontMetrics nameMetrics = g2.getFontMetrics();
            int baseline = (getHeight() - nameMetrics.getHeight()) / 2 + nameMetrics.getAscent();
            g2.setColor(getForeground());
            g2.drawString(String.valueOf(node.Name), x, baseline);
            x += nameMetrics.stringWidth(String.valueOf(node.Name)) + GAP;
            g2.setFont(getFont());
            g2.setColor(detailColor);
            g2.drawString(detail, x, baseline);
            g2.dispose();
        }

        private void paintStatus(Graphics2D g2, int x, int y){
            if(node.Connecting){
                // Spinning arc: QueueManagerTreeLayer repaints the list while a connection is opening
                double angle = (System.currentTimeMillis() / 4) % 360;
                g2.setColor(onStrongSelection ? getForeground() : UIManager.getColor("Component.accentColor"));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Arc2D.Double(x - 1, y - 1, DOT + 2, DOT + 2, -angle, 270, Arc2D.OPEN));
                return;
            }
            if(node.IsConnected()){
                g2.setColor(onStrongSelection ? getForeground() : GREEN);
                g2.fill(new Ellipse2D.Double(x, y, DOT, DOT));
            }
            else if(node.LastError != null){
                g2.setColor(onStrongSelection ? getForeground() : RED);
                g2.fill(new Ellipse2D.Double(x, y, DOT, DOT));
            }
            else{
                g2.setColor(onStrongSelection ? getForeground() : UIManager.getColor("Label.disabledForeground"));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new Ellipse2D.Double(x + 0.75, y + 0.75, DOT - 1.5, DOT - 1.5));
            }
        }
    }
}
