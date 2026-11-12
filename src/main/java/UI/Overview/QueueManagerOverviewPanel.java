package UI.Overview;

import MQApi.Overview.HealthCheck;
import MQApi.Overview.HealthCheck.Severity;
import MQApi.Overview.MQFormat;
import MQApi.Overview.QueueManagerHealth;
import MQApi.Overview.QueueManagerOverview;
import MQApi.Overview.QueueManagerOverview.QueueDepth;
import UI.Helpers.IconManager;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.ibm.mq.constants.MQConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * Page shown when a queue manager is selected in the tree: key figures, what needs attention,
 * busiest queues, services, recovery log and key settings.
 */
public class QueueManagerOverviewPanel extends JPanel implements Scrollable {

    /** What the page asks the main window to do. */
    public interface Actions {
        void Refresh();
        void ShowProperties();
        void Connect();
        void ShowQueue(String queueName);
        void BrowseQueue(String queueName);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    // Height of the "Needs attention" and "Fullest queues" row until the user asks to see more
    private static final int MIDDLE_HEIGHT = 440;

    private final Actions actions;
    private final IconManager iconManager = new IconManager();
    // "Needs attention" opened with "Show N more" / showing the passed checks too; kept on refresh
    private boolean expanded;
    private boolean showPassed;
    private String expandedFor;
    private JPanel attentionFooter;
    private QueueManagerOverview overview;
    private List<HealthCheck> checks;
    private String queueManagerName;

    public QueueManagerOverviewPanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setBorder(BorderFactory.createEmptyBorder(20, 24, 24, 24));
    }

    // --- States ---

    public void ShowLoading(String name) {
        queueManagerName = name;
        removeAll();
        JPanel center = centered();
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(200, 4));
        center.add(Widgets.Styled("Loading " + name + "…", "font: +2"), gbc(0));
        center.add(progress, gbc(1));
        add(center, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void ShowNotConnected(String name, String address) {
        queueManagerName = name;
        removeAll();
        JPanel center = centered();
        JLabel icon = new JLabel(IconManager.InColor(iconManager.QueueMgrDisconnectedIcon(), Widgets.Muted("").getForeground()));
        center.add(Widgets.Styled(name, "font: bold +6"), gbc(0));
        center.add(Widgets.Muted(address + " · not connected"), gbc(1));
        JButton connect = new JButton("Connect", iconManager.ConnectIcon());
        connect.addActionListener(e -> actions.Connect());
        GridBagConstraints c = gbc(2);
        c.insets = new Insets(16, 0, 0, 0);
        center.add(connect, c);
        add(center, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void ShowError(String name, String message) {
        queueManagerName = name;
        removeAll();
        JPanel center = centered();
        center.add(Widgets.Styled("The information of " + name + " could not be read", "font: bold +2"), gbc(0));
        center.add(Widgets.Muted(message), gbc(1));
        JButton retry = new JButton("Try again", iconManager.RefreshIcon());
        retry.addActionListener(e -> actions.Refresh());
        GridBagConstraints c = gbc(2);
        c.insets = new Insets(16, 0, 0, 0);
        center.add(retry, c);
        add(center, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void ShowOverview(String name, QueueManagerOverview overview) {
        this.queueManagerName = name;
        this.overview = overview;
        this.checks = QueueManagerHealth.Evaluate(overview);
        if (!name.equals(expandedFor)) {
            expanded = false;
            showPassed = false;
            expandedFor = name;
        }
        rebuild();
    }

    private void rebuild() {
        removeAll();
        JPanel page = new JPanel();
        page.setOpaque(false);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.add(header());
        page.add(Widgets.Spacer(18));
        page.add(keyFigures());
        page.add(Widgets.Spacer(12));
        page.add(middleRow(attentionCard(), topQueuesCard()));
        page.add(Widgets.Spacer(12));
        page.add(weightedRow(new double[]{1, 1.2, 1.5}, servicesCard(), logCard(), settingsCard()));
        page.add(Widgets.Spacer(12));
        page.add(footer());
        for (java.awt.Component component : page.getComponents()) {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        add(page, BorderLayout.NORTH);
        revalidate();
        repaint();
    }

    // --- Sections ---

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JPanel nameRow = new JPanel();
        nameRow.setOpaque(false);
        nameRow.setLayout(new BoxLayout(nameRow, BoxLayout.X_AXIS));
        nameRow.add(Widgets.Styled(queueManagerName, "font: bold +10"));
        Integer status = overview.StatusInt(MQConstants.MQIACF_Q_MGR_STATUS);
        if (status != null) {
            boolean running = status == MQConstants.MQQMSTA_RUNNING;
            nameRow.add(Box.createHorizontalStrut(12));
            nameRow.add(new Widgets.Pill(MQFormat.Constant(status, "MQQMSTA_"), running ? Widgets.GREEN : Widgets.ORANGE));
        }
        long problems = QueueManagerHealth.Count(checks, Severity.PROBLEM);
        if (problems > 0) {
            nameRow.add(Box.createHorizontalStrut(8));
            nameRow.add(new Widgets.Pill(problems + (problems == 1 ? " problem" : " problems"), Widgets.RED, false));
        }
        titles.add(nameRow);
        titles.add(Widgets.Spacer(4));
        titles.add(Widgets.Muted(subtitle()));
        for (java.awt.Component component : titles.getComponents()) {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        header.add(titles, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton refresh = new JButton("Refresh", iconManager.RefreshIcon());
        refresh.addActionListener(e -> actions.Refresh());
        JButton properties = new JButton("Properties", iconManager.PropertiesIcon());
        properties.addActionListener(e -> actions.ShowProperties());
        buttons.add(refresh);
        buttons.add(properties);
        header.add(buttons, BorderLayout.EAST);
        return header;
    }

    private String subtitle() {
        StringBuilder text = new StringBuilder();
        String version = MQFormat.Version(overview.ConfigText(MQConstants.MQCA_VERSION));
        if (version != null) {
            text.append("IBM MQ ").append(version);
        }
        Integer platform = overview.ConfigInt(MQConstants.MQIA_PLATFORM);
        if (platform != null) {
            text.append(" · ").append(MQFormat.Platform(platform));
        }
        String host = overview.StatusText(MQConstants.MQCACF_HOST_NAME);
        if (host != null && !host.isEmpty()) {
            text.append(" · host ").append(host);
        }
        String started = MQFormat.DateTime(overview.StatusText(MQConstants.MQCACF_Q_MGR_START_DATE), overview.StatusText(MQConstants.MQCACF_Q_MGR_START_TIME));
        if (!started.isEmpty()) {
            text.append(" · running since ").append(started);
        }
        if (overview.Encrypted != null) {
            text.append(overview.Encrypted ? " · TLS" : " · not encrypted");
        }
        return text.toString();
    }

    private JComponent keyFigures() {
        JPanel figures = new JPanel(new GridLayout(1, 5, 12, 0));
        figures.setOpaque(false);
        figures.add(figure("inbox", Widgets.BLUE, "Local queues", count(overview.LocalQueues), null,
                overview.LocalQueues < 0 ? "not available" : MQFormat.Number(overview.QueuesWithMessages) + " with messages"));
        figures.add(figure("mail", Widgets.PURPLE, "Messages queued", count(overview.LocalQueues < 0 ? -1 : overview.ApplicationMessages), null,
                "in " + MQFormat.Number(overview.ApplicationQueuesWithMessages) + " application " + (overview.ApplicationQueuesWithMessages == 1 ? "queue" : "queues")));
        Integer connections = overview.StatusInt(MQConstants.MQIACF_CONNECTION_COUNT);
        Integer maxHandles = overview.ConfigInt(MQConstants.MQIA_MAX_HANDLES);
        figures.add(figure("plug", Widgets.TEAL, "Connections", connections == null ? "–" : MQFormat.Number(connections), null,
                maxHandles == null ? "" : "max handles " + MQFormat.Number(maxHandles)));
        String channels = overview.DefinedChannels < 0 ? MQFormat.Number(overview.RunningChannels())
                : MQFormat.Number(overview.RunningChannels()) + " / " + MQFormat.Number(overview.DefinedChannels);
        long notRunning = overview.ChannelStatus.values().stream()
                .filter(s -> s == MQConstants.MQCHS_RETRYING || s == MQConstants.MQCHS_STOPPED).count();
        figures.add(figure("radio", notRunning > 0 ? Widgets.RED : Widgets.GREEN, "Channels running",
                overview.Errors.containsKey("Channel status") ? "–" : channels, notRunning > 0 ? Widgets.RED : null,
                notRunning == 0 ? "none retrying or stopped" : notRunning + " retrying or stopped"));
        QueueDepth dlq = overview.DeadLetterQueue;
        String dlqName = overview.ConfigText(MQConstants.MQCA_DEAD_LETTER_Q_NAME);
        boolean deadLetters = dlq != null && dlq.Current > 0;
        figures.add(figure("archive-x", deadLetters ? Widgets.ORANGE : Widgets.GREEN, "Dead-letter queue",
                dlq == null ? "–" : MQFormat.Number(dlq.Current), deadLetters ? Widgets.ORANGE : null,
                dlqName == null || dlqName.isEmpty() ? "not defined" : dlqName));
        return figures;
    }

    private static String count(int value) {
        return value < 0 ? "–" : MQFormat.Number(value);
    }

    /** Key figure card: icon and title, the value (in valueColor when it signals a problem), a detail line. */
    private JComponent figure(String icon, Color iconColor, String title, String value, Color valueColor, String detail) {
        Widgets.Card card = new Widgets.Card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.add(Widgets.Muted(title));
        // The icon goes beside the value, so the title keeps the whole width of the card
        JPanel valueRow = new JPanel(new BorderLayout(8, 0));
        valueRow.setOpaque(false);
        JLabel valueLabel = Widgets.Styled(value, "font: bold +10");
        if (valueColor != null) {
            valueLabel.setForeground(Widgets.Ink(valueColor));
        }
        valueRow.add(valueLabel, BorderLayout.CENTER);
        valueRow.add(new JLabel(new Widgets.IconTile(icon, iconColor)), BorderLayout.EAST);
        card.add(valueRow);
        JLabel detailLabel = Widgets.Muted(detail);
        detailLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        card.add(detailLabel);
        for (java.awt.Component component : card.getComponents()) {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        card.setToolTipText(title + ": " + value + (detail.isEmpty() ? "" : " · " + detail));
        return card;
    }

    private JComponent attentionCard() {
        Widgets.Card card = new Widgets.Card();
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        heading.add(Widgets.Styled("Needs attention", "font: bold"), BorderLayout.WEST);
        JPanel counts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        counts.setOpaque(false);
        addCount(counts, Severity.PROBLEM, "critical", "critical");
        addCount(counts, Severity.WARNING, "warning", "warnings");
        addCount(counts, Severity.NOTE, "info", "info");
        heading.add(counts, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);

        boolean open = expanded || showPassed;
        AttentionList list = new AttentionList(hidden -> updateAttentionFooter(hidden));
        list.SetCollapsed(!open);
        List<HealthCheck> visible = checks.stream()
                .filter(c -> showPassed || c.Level != Severity.OK)
                .collect(Collectors.toList());
        if (visible.isEmpty()) {
            list.AddRow(Severity.OK, "Everything looks fine", null, null);
        }
        for (HealthCheck check : visible) {
            list.AddRow(check.Level, check.Title, check.Detail, actionFor(check));
        }
        card.add(list, BorderLayout.CENTER);

        // Always as tall as a button, so the rows that fit do not change when the button appears
        attentionFooter = new JPanel(new BorderLayout(8, 0));
        attentionFooter.setOpaque(false);
        attentionFooter.setPreferredSize(new Dimension(1, new JButton("X").getPreferredSize().height));
        if (open) {
            updateAttentionFooter(0);
        }
        card.add(attentionFooter, BorderLayout.SOUTH);
        return card;
    }

    private void addCount(JPanel counts, Severity severity, String one, String many) {
        long count = QueueManagerHealth.Count(checks, severity);
        if (count > 0) {
            counts.add(new Widgets.Pill(count + " " + (count == 1 ? one : many), Widgets.SeverityColor(severity), false));
        }
    }

    private JButton actionFor(HealthCheck check) {
        switch (check.Action) {
            case SHOW_QUEUE:
                return Widgets.Link("Show queue", () -> actions.ShowQueue(check.Target));
            case BROWSE_QUEUE:
                return Widgets.Link("Browse messages", () -> actions.BrowseQueue(check.Target));
            default:
                return null;
        }
    }

    /**
     * One button under the checks: "Show N more" while some problems do not fit; once they are
     * all visible, the passed checks can be shown; "Show less" goes back to the starting height.
     */
    private void updateAttentionFooter(int hidden) {
        attentionFooter.removeAll();
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        long passed = QueueManagerHealth.Count(checks, Severity.OK);
        if (!expanded && !showPassed && hidden > 0) {
            JButton more = new JButton("Show " + hidden + " more");
            more.addActionListener(e -> {
                expanded = true;
                rebuild();
            });
            left.add(more);
        } else if (passed > 0) {
            left.add(Widgets.Link(showPassed ? "Hide passed checks" : "Show passed checks (" + passed + ")", () -> {
                showPassed = !showPassed;
                rebuild();
            }));
        }
        attentionFooter.add(left, BorderLayout.CENTER);
        if (expanded) {
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(Widgets.Link("Show less", () -> {
                expanded = false;
                showPassed = false;
                rebuild();
            }));
            attentionFooter.add(right, BorderLayout.EAST);
        }
        attentionFooter.revalidate();
        attentionFooter.repaint();
    }

    private JComponent topQueuesCard() {
        Widgets.Card card = Widgets.Card("Fullest queues");
        JPanel rows = verticalList();
        if (overview.TopQueues.isEmpty()) {
            rows.add(Widgets.Muted(overview.LocalQueues < 0 ? "Not available" : "No messages in application queues"));
        }
        for (QueueDepth queue : overview.TopQueues) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            JButton name = Widgets.Link(queue.Name, () -> actions.ShowQueue(queue.Name));
            name.setHorizontalAlignment(SwingConstants.LEADING);
            row.add(name, BorderLayout.WEST);
            String percent = queue.Max > 0 ? " · " + queue.Percent() + "%" : "";
            JLabel depth = new JLabel("<html><b>" + MQFormat.Number(queue.Current) + "</b> / " + MQFormat.Number(queue.Max) + percent + "</html>");
            row.add(depth, BorderLayout.EAST);
            rows.add(row);
            JPanel bar = new JPanel(new BorderLayout());
            bar.setOpaque(false);
            bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 10, 0));
            bar.add(new Widgets.FillBar(queue.Fill(), fillColor(queue), 6));
            rows.add(bar);
        }
        for (java.awt.Component component : rows.getComponents()) {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        card.add(top(rows), BorderLayout.CENTER);
        if (!overview.TopQueues.isEmpty()) {
            JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
            legend.setOpaque(false);
            legend.add(legendItem(Widgets.BLUE, "up to " + QueueManagerHealth.QUEUE_WARNING_PERCENT + "%"));
            legend.add(legendItem(Widgets.ORANGE, "up to " + QueueManagerHealth.QUEUE_PROBLEM_PERCENT + "%"));
            legend.add(legendItem(Widgets.RED, "over " + QueueManagerHealth.QUEUE_PROBLEM_PERCENT + "%"));
            card.add(legend, BorderLayout.SOUTH);
        }
        return card;
    }

    /** Same thresholds as the "filling up" and "almost full" checks. */
    private static Color fillColor(QueueDepth queue) {
        int percent = queue.Percent();
        return percent > QueueManagerHealth.QUEUE_PROBLEM_PERCENT ? Widgets.RED
                : percent > QueueManagerHealth.QUEUE_WARNING_PERCENT ? Widgets.ORANGE : Widgets.BLUE;
    }

    private static JLabel legendItem(Color color, String text) {
        JLabel label = Widgets.Muted(text);
        label.setIcon(new Widgets.Dot(color));
        label.setIconTextGap(5);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        return label;
    }

    private JComponent servicesCard() {
        Widgets.Card card = Widgets.Card("Services");
        JPanel rows = verticalList();
        addService(rows, "Command server", overview.StatusInt(MQConstants.MQIACF_CMD_SERVER_STATUS));
        addService(rows, "Channel initiator", overview.StatusInt(MQConstants.MQIACF_CHINIT_STATUS));
        for (QueueManagerOverview.Listener listener : overview.Listeners) {
            String name = "Listener " + (listener.Port != null ? "port " + listener.Port : listener.Name);
            addService(rows, name, listener.Status);
        }
        if (rows.getComponentCount() == 0) {
            rows.add(Widgets.Muted("Not available"));
        }
        card.add(top(rows), BorderLayout.CENTER);
        return card;
    }

    private void addService(JPanel rows, String name, Integer status) {
        if (status == null) {
            return;
        }
        boolean running = status == MQConstants.MQSVC_STATUS_RUNNING;
        JLabel value = new JLabel(MQFormat.Constant(status, "MQSVC_STATUS_"));
        value.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        value.setForeground(Widgets.Ink(running ? Widgets.GREEN : Widgets.RED));
        rows.add(listRow(new Widgets.Dot(running ? Widgets.GREEN : Widgets.RED), name, null, value));
    }

    private JComponent logCard() {
        Widgets.Card card = Widgets.Card("Recovery log");
        Integer inUse = overview.StatusInt(MQConstants.MQIACF_LOG_IN_USE);
        JPanel content = verticalList();
        if (inUse == null || inUse < 0) {
            content.add(Widgets.Muted("Not available"));
        } else {
            String type = MQFormat.Constant(overview.StatusInt(MQConstants.MQIACF_LOG_TYPE), "MQLOGTYPE_").toLowerCase();
            JPanel value = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            value.setOpaque(false);
            value.add(Widgets.Styled(inUse + "%", "font: bold +6"));
            value.add(Widgets.Muted("in use · " + type));
            content.add(value);
            content.add(Widgets.Spacer(4));
            content.add(new Widgets.FillBar(inUse / 100.0, inUse > QueueManagerHealth.LOG_WARNING_PERCENT ? Widgets.ORANGE : Widgets.GREEN, 6));
            content.add(Widgets.Spacer(8));
            Widgets.KeyValueGrid grid = new Widgets.KeyValueGrid();
            Integer primary = overview.StatusInt(MQConstants.MQIACF_LOG_PRIMARIES);
            Integer secondary = overview.StatusInt(MQConstants.MQIACF_LOG_SECONDARIES);
            if (primary != null && secondary != null) {
                grid.Add("Primary / secondary", primary + " / " + secondary + " extents");
            }
            Integer extent = overview.StatusInt(MQConstants.MQIACF_LOG_EXTENT_SIZE);
            if (extent != null) {
                grid.Add("Extent size", MQFormat.Number(extent) + " pages");
            }
            content.add(grid);
        }
        card.add(top(content), BorderLayout.CENTER);
        return card;
    }

    private JComponent settingsCard() {
        Widgets.Card card = Widgets.Card("Key settings");
        Widgets.KeyValueGrid grid = new Widgets.KeyValueGrid();
        grid.Add("Dead-letter queue", MQFormat.Text(overview.ConfigText(MQConstants.MQCA_DEAD_LETTER_Q_NAME)));
        Integer ccsid = overview.ConfigInt(MQConstants.MQIA_CODED_CHAR_SET_ID);
        if (ccsid != null) {
            grid.Add("CCSID", ccsid == 1208 ? "1208 (UTF-8)" : String.valueOf(ccsid));
        }
        Integer maxLength = overview.ConfigInt(MQConstants.MQIA_MAX_MSG_LENGTH);
        if (maxLength != null) {
            grid.Add("Max message length", maxLength % (1024 * 1024) == 0 ? (maxLength / (1024 * 1024)) + " MB" : MQFormat.Number(maxLength) + " bytes");
        }
        Integer channelAuth = overview.ConfigInt(MQConstants.MQIA_CHLAUTH_RECORDS);
        if (channelAuth != null) {
            String rules = overview.ChannelAuthRules >= 0 ? " · " + overview.ChannelAuthRules + " rules" : "";
            grid.Add("Channel auth", MQFormat.Constant(channelAuth, "MQCHLA_") + rules);
        }
        grid.Add("Connection auth", MQFormat.Text(overview.ConfigText(MQConstants.MQCA_CONN_AUTH)));
        String repository = overview.ConfigText(MQConstants.MQCA_REPOSITORY_NAME);
        grid.Add("Cluster", repository == null || repository.isEmpty() ? "Not a repository" : "Repository for " + repository);
        card.add(top(grid), BorderLayout.CENTER);
        return card;
    }

    private JComponent footer() {
        String text = "Updated " + overview.LoadedAt.format(TIME);
        if (!overview.Errors.isEmpty()) {
            text += " · not available: " + overview.Errors.entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
        }
        JLabel footer = Widgets.Muted(text);
        footer.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        return footer;
    }

    // --- Layout helpers ---

    /** Two equal columns that start at a fixed height, and grow only when "Needs attention" is opened. */
    private JPanel middleRow(JComponent left, JComponent right) {
        JPanel row = new JPanel(new GridLayout(1, 2, 12, 0)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                int fixed = UIScale.scale(MIDDLE_HEIGHT);
                return new Dimension(size.width, expanded || showPassed ? Math.max(fixed, size.height) : fixed);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.add(left);
        row.add(right);
        return row;
    }

    /** Columns sharing the width by weight, all as tall as the tallest. */
    private static JPanel weightedRow(double[] weights, JComponent... cards) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        for (int i = 0; i < cards.length; i++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = i;
            c.weightx = weights[i];
            c.fill = GridBagConstraints.BOTH;
            c.insets = new Insets(0, i == 0 ? 0 : 12, 0, 0);
            // Equal minimum widths: the weights then decide how the space is shared
            cards[i].setPreferredSize(new Dimension(1, cards[i].getPreferredSize().height));
            row.add(cards[i], c);
        }
        return row;
    }

    /** Keeps a list at its natural height at the top of a taller card. */
    private static JComponent top(JComponent content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);
        return wrapper;
    }

    private static JPanel verticalList() {
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        return list;
    }

    /** Row: status dot, bold title with an optional muted detail, optional component on the right. */
    private static JComponent listRow(Widgets.Dot dot, String title, String detail, JComponent right) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        JLabel dotLabel = new JLabel(dot);
        dotLabel.setVerticalAlignment(SwingConstants.TOP);
        dotLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        row.add(dotLabel, BorderLayout.WEST);
        JPanel texts = verticalList();
        texts.add(Widgets.Styled(title, "font: bold"));
        if (detail != null && !detail.isEmpty()) {
            JLabel detailLabel = Widgets.Muted("<html>" + escape(detail) + "</html>");
            texts.add(detailLabel);
        }
        row.add(texts, BorderLayout.CENTER);
        if (right != null) {
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            east.setOpaque(false);
            east.add(right);
            row.add(east, BorderLayout.EAST);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));
        return row;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JPanel centered() {
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(120, 0, 0, 0));
        return center;
    }

    private static GridBagConstraints gbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 0, 4, 0);
        return c;
    }

    // --- Scrollable: follow the viewport width, scroll vertically ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return visibleRect.height - 32;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
