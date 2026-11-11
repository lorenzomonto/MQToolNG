package UI.Misc;

import MQApi.Enums.ChannelStatusType;
import MQApi.Enums.ChannelType;
import MQApi.Enums.QueueType;
import MQApi.QueryModel.MQChannelListResult.ChannelDetailModel;
import MQApi.QueryModel.MQQueueListResult.QueueDetailModel;
import UI.Helpers.IconManager;
import UI.Helpers.SelectionStyle;
import UI.Models.TableListObject;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Name column of the queue and channel lists, with an icon left of the name:
 * queues: an icon colored by queue type (local, alias, remote, model, transmission, temporary);
 * channels: a neutral icon for the channel type with a dot for its status
 * (green running, amber starting or retrying, red stopped, gray inactive).
 * Colors have one meaning per list: the type for queues, the status for channels.
 */
public class ObjectNameRender extends DefaultTableCellRenderer {

    private static final Color BLUE = new Color(0x2F6FED);
    private static final Color GREEN = new Color(0x22A06B);
    private static final Color AMBER = new Color(0xE8833A);
    private static final Color RED = new Color(0xE5484D);
    private static final Color PURPLE = new Color(0x8E6CEF);
    private static final Color TEAL = new Color(0x1A9FB0);
    private static final Color GRAY = new Color(0x8A919C);

    /** What an object is: icon, color of its type, description for the tooltip. */
    static class Kind {
        final String Icon;
        final Color Color;
        final String Description;

        Kind(String icon, Color color, String description) {
            this.Icon = icon;
            this.Color = color;
            this.Description = description;
        }
    }

    static Kind queueKind(QueueDetailModel queue) {
        if ("Dynamically defined temporary queue".equals(queue.DefinitionType) && queue.Type != QueueType.Model) {
            return new Kind("hourglass", GRAY, "Temporary dynamic queue");
        }
        if (queue.Type == null) {
            return new Kind("inbox", BLUE, "Queue");
        }
        switch (queue.Type) {
            case Remote:
                return new Kind("send", PURPLE, "Remote queue");
            case Alias:
                return new Kind("link", TEAL, "Alias queue");
            case Model:
                return new Kind("copy", GRAY, "Model queue");
            case Cluster:
                return new Kind("network", GREEN, "Cluster queue");
            default:
                return "Transmission".equals(queue.Usage)
                        ? new Kind("truck", AMBER, "Transmission queue")
                        : new Kind("inbox", BLUE, "Local queue");
        }
    }

    static Kind channelKind(ChannelType type) {
        if (type == null) {
            return new Kind("cable", GRAY, "Channel");
        }
        switch (type) {
            case Sender:
                return new Kind("arrow-up-right", GRAY, "Sender");
            case Server:
                return new Kind("server", GRAY, "Server");
            case Receiver:
                return new Kind("arrow-down-left", GRAY, "Receiver");
            case Requester:
                return new Kind("arrow-left-right", GRAY, "Requester");
            case Server_Connection:
                return new Kind("laptop", GRAY, "Server-connection");
            case Client_connection:
                return new Kind("plug", GRAY, "Client-connection");
            case Cluster_Sender:
                return new Kind("share-2", GRAY, "Cluster-sender");
            case Cluster_Receiver:
                return new Kind("git-merge", GRAY, "Cluster-receiver");
            case Telemetry:
                return new Kind("radio", GRAY, "Telemetry (MQTT)");
            default:
                return new Kind("cable", GRAY, "Channel");
        }
    }

    /** Running green, starting or retrying amber, stopped red, no status gray. */
    static Color statusColor(ChannelStatusType status) {
        if (status == null || status == ChannelStatusType.Inactive) {
            return GRAY;
        }
        switch (status) {
            case Running:
                return GREEN;
            case Stopped:
                return RED;
            default:
                return AMBER;
        }
    }

    static String statusText(ChannelStatusType status) {
        return status == null ? "Inactive" : status.toString();
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        setIcon(null);
        setToolTipText(null);
        if (!(value instanceof TableListObject)) {
            return this;
        }
        TableListObject object = (TableListObject) value;
        setText(object.ObjectName);
        Kind kind;
        Color status = null;
        String tooltip;
        if (object.Detail instanceof QueueDetailModel) {
            kind = queueKind((QueueDetailModel) object.Detail);
            tooltip = kind.Description;
        } else if (object.Detail instanceof ChannelDetailModel) {
            ChannelDetailModel channel = (ChannelDetailModel) object.Detail;
            kind = channelKind(channel.Type);
            status = statusColor(channel.Status);
            tooltip = kind.Description + " channel · " + statusText(channel.Status);
        } else {
            return this;
        }
        setToolTipText(tooltip);
        setIconTextGap(8);
        // On a selected row the icon takes the selection text color, so it stays visible;
        // channel icons are drawn in the text color of the table, the dot shows the status
        Color selectionText = isSelected && SelectionStyle.IsStrong(table.getSelectionForeground(), table.getForeground())
                ? table.getSelectionForeground() : null;
        if (status == null) {
            setIcon(IconManager.Named(kind.Icon, selectionText != null ? selectionText : kind.Color));
        } else {
            Icon base = IconManager.Named(kind.Icon, selectionText != null ? selectionText : table.getForeground());
            setIcon(new WithDot(base, status, isSelected ? table.getSelectionBackground() : table.getBackground()));
        }
        return this;
    }

    /** Icon with a status dot at its bottom right corner. */
    static class WithDot implements Icon {
        private final Icon base;
        private final Color dot;
        private final Color ring;

        WithDot(Icon base, Color dot, Color ring) {
            this.base = base;
            this.dot = dot;
            this.ring = ring;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            base.paintIcon(c, g, x, y);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = 8;
            int dx = x + base.getIconWidth() - d + 2;
            int dy = y + base.getIconHeight() - d + 2;
            g2.setColor(ring);
            g2.fillOval(dx - 2, dy - 2, d + 4, d + 4);
            g2.setColor(dot);
            g2.fillOval(dx, dy, d, d);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return base.getIconWidth() + 2;
        }

        @Override
        public int getIconHeight() {
            return base.getIconHeight() + 2;
        }
    }
}
