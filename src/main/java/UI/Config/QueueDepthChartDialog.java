package UI.Config;

import MQApi.Logs.LogWriter;
import MQApi.Overview.MQFormat;
import MQApi.PCF.MQPCF;
import UI.Helpers.IconManager;
import UI.Overview.Widgets;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.PCFMessage;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Depth of a queue over time, read every few seconds while the window is open, with the open
 * handles and how fast the depth changes. Up to one hour of readings is kept.
 */
public class QueueDepthChartDialog extends JDialog {
    private static final int[] INTERVALS = {2, 5, 10, 30};
    private static final long KEEP_MILLIS = 60 * 60 * 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One reading of the queue. */
    static class Sample {
        final long Time;
        final int Depth;
        final int MaxDepth;
        final int OpenInput;
        final int OpenOutput;

        Sample(long time, int depth, int maxDepth, int openInput, int openOutput) {
            this.Time = time;
            this.Depth = depth;
            this.MaxDepth = maxDepth;
            this.OpenInput = openInput;
            this.OpenOutput = openOutput;
        }
    }

    private final MQQueueManager queueManager;
    private final String queueName;
    private final List<Sample> samples = new ArrayList<>();
    private final Chart chart = new Chart();
    private final JLabel current = Widgets.Styled("–", "font: bold +8");
    private final JLabel details = Widgets.Muted(" ");
    private final JLabel status = Widgets.Muted(" ");
    private final JComboBox<String> interval = new JComboBox<>();
    private final JButton pause = new JButton("Pause");
    private final Timer timer;
    private boolean reading;

    public QueueDepthChartDialog(Frame owner, MQQueueManager queueManager, String queueManagerName, String queueName) {
        super(owner, "Depth of " + queueName + " – " + queueManagerName, false);
        this.queueManager = queueManager;
        this.queueName = queueName;
        setIconImage(new IconManager().MonitorIcon().getImage());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        JPanel figures = new JPanel();
        figures.setLayout(new javax.swing.BoxLayout(figures, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = Widgets.Styled("Messages on " + queueName, "font: bold +2");
        figures.add(title);
        figures.add(Widgets.Spacer(4));
        figures.add(current);
        figures.add(details);
        for (java.awt.Component component : figures.getComponents()) {
            ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        top.add(figures, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.add(Widgets.Muted("Read every"));
        for (int seconds : INTERVALS) {
            interval.addItem(seconds + " s");
        }
        interval.setSelectedIndex(1);
        interval.addActionListener(e -> restart());
        controls.add(interval);
        pause.addActionListener(e -> {
            if (timerRunning()) {
                stopTimer();
                pause.setText("Resume");
            } else {
                pause.setText("Pause");
                restart();
            }
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            samples.clear();
            updateFigures();
            chart.repaint();
        });
        controls.add(Box.createHorizontalStrut(4));
        controls.add(pause);
        controls.add(clear);
        top.add(controls, BorderLayout.EAST);
        content.add(top, BorderLayout.NORTH);

        chart.setPreferredSize(new Dimension(760, 340));
        content.add(chart, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton export = new JButton("Save CSV…");
        export.setToolTipText("Save the readings (time, depth, open handles) as CSV");
        export.addActionListener(e -> saveCsv());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(export);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        timer = new Timer(5000, e -> read());
        timer.setInitialDelay(0);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
            }
        });
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
        timer.start();
    }

    private boolean timerRunning() {
        return timer.isRunning();
    }

    private void stopTimer() {
        timer.stop();
    }

    private void restart() {
        timer.stop();
        timer.setDelay(INTERVALS[interval.getSelectedIndex()] * 1000);
        if (pause.getText().equals("Pause")) {
            timer.start();
        }
    }

    private void read() {
        if (reading) {
            return;
        }
        reading = true;
        new SwingWorker<Sample, Void>() {
            @Override
            protected Sample doInBackground() throws Exception {
                PCFMessage inquire = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
                inquire.addParameter(MQConstants.MQCA_Q_NAME, queueName);
                inquire.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_LOCAL);
                inquire.addParameter(MQConstants.MQIACF_Q_ATTRS, new int[]{MQConstants.MQIA_CURRENT_Q_DEPTH,
                    MQConstants.MQIA_MAX_Q_DEPTH, MQConstants.MQIA_OPEN_INPUT_COUNT, MQConstants.MQIA_OPEN_OUTPUT_COUNT});
                for (PCFMessage response : MQPCF.Send(queueManager, inquire)) {
                    if (response.getCompCode() != MQConstants.MQCC_OK) {
                        throw new IOException(MQConstants.lookupReasonCode(response.getReason()));
                    }
                    return new Sample(System.currentTimeMillis(),
                            response.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH),
                            response.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH),
                            response.getIntParameterValue(MQConstants.MQIA_OPEN_INPUT_COUNT),
                            response.getIntParameterValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT));
                }
                throw new IOException("No answer from the command server");
            }

            @Override
            protected void done() {
                reading = false;
                try {
                    samples.add(get());
                    long oldest = System.currentTimeMillis() - KEEP_MILLIS;
                    samples.removeIf(sample -> sample.Time < oldest);
                    status.setText("Last read " + TIME.format(Instant.now()) + " · " + samples.size() + " readings");
                    updateFigures();
                    chart.repaint();
                } catch (Exception ex) {
                    LogWriter.WriteToLog("QueueDepthChartDialog", "read", ex);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("The queue could not be read: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void updateFigures() {
        if (samples.isEmpty()) {
            current.setText("–");
            details.setText(" ");
            return;
        }
        Sample last = samples.get(samples.size() - 1);
        current.setText(MQFormat.Number(last.Depth));
        StringBuilder text = new StringBuilder();
        text.append("of ").append(MQFormat.Number(last.MaxDepth)).append(" max");
        // Change over the last minute of readings, in messages per minute
        Sample from = null;
        for (Sample sample : samples) {
            if (sample.Time >= last.Time - 60000 && sample != last) {
                from = sample;
                break;
            }
        }
        if (from != null && last.Time > from.Time) {
            double perMinute = (last.Depth - from.Depth) * 60000.0 / (last.Time - from.Time);
            text.append(" · ").append(perMinute >= 0 ? "+" : "−").append(MQFormat.Number((int) Math.round(Math.abs(perMinute)))).append(" per minute");
        }
        text.append(" · open: ").append(last.OpenInput).append(" input, ").append(last.OpenOutput).append(" output");
        details.setText(text.toString());
    }

    private void saveCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV file (*.csv)", "csv"));
        chooser.setSelectedFile(new File(queueName + "-depth.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getPath() + ".csv");
        }
        StringBuilder csv = new StringBuilder("Time,Depth,Max depth,Open input,Open output\n");
        DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        for (Sample sample : samples) {
            csv.append(iso.format(Instant.ofEpochMilli(sample.Time))).append(',').append(sample.Depth).append(',')
                    .append(sample.MaxDepth).append(',').append(sample.OpenInput).append(',').append(sample.OpenOutput).append('\n');
        }
        try {
            Files.writeString(file.toPath(), csv.toString(), StandardCharsets.UTF_8);
            status.setText("Saved to " + file.getPath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "The file could not be saved: " + ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Line of the depth over time; hovering shows the reading under the mouse. */
    private class Chart extends JComponent {
        private static final int LEFT = 56;
        private static final int RIGHT = 16;
        private static final int TOP = 12;
        private static final int BOTTOM = 28;
        private int mouseX = -1;

        Chart() {
            MouseAdapter hover = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    mouseX = e.getX();
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mouseX = -1;
                    repaint();
                }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color text = UIManager.getColor("Label.foreground");
            Color muted = UIManager.getColor("Label.disabledForeground");
            Color grid = Widgets.Tint(muted, 50);
            Color line = UIManager.getColor("Component.accentColor");
            Font font = getFont().deriveFont(getFont().getSize2D() - 1f);
            g2.setFont(font);
            FontMetrics metrics = g2.getFontMetrics();
            int width = getWidth() - LEFT - RIGHT;
            int height = getHeight() - TOP - BOTTOM;
            if (samples.isEmpty()) {
                g2.setColor(muted);
                String wait = "Reading the queue…";
                g2.drawString(wait, (getWidth() - metrics.stringWidth(wait)) / 2, getHeight() / 2);
                g2.dispose();
                return;
            }
            long start = samples.get(0).Time;
            long end = Math.max(samples.get(samples.size() - 1).Time, start + 60000);
            int top = 0;
            for (Sample sample : samples) {
                top = Math.max(top, sample.Depth);
            }
            long step = niceStep(Math.max(top, 4) / 4.0);
            long yMax = Math.max(step * 4, ((top + step - 1) / step) * step);

            // Recessive grid and y labels
            for (long value = 0; value <= yMax; value += step) {
                int y = TOP + height - (int) Math.round(value * height / (double) yMax);
                g2.setColor(grid);
                g2.drawLine(LEFT, y, LEFT + width, y);
                g2.setColor(muted);
                String label = MQFormat.Number((int) value);
                g2.drawString(label, LEFT - 8 - metrics.stringWidth(label), y + metrics.getAscent() / 2 - 1);
            }
            // Time labels: start, middle, end
            for (int i = 0; i <= 2; i++) {
                long time = start + (end - start) * i / 2;
                String label = TIME.format(Instant.ofEpochMilli(time));
                int x = LEFT + width * i / 2 - (i == 0 ? 0 : i == 2 ? metrics.stringWidth(label) : metrics.stringWidth(label) / 2);
                g2.drawString(label, x, TOP + height + 18);
            }
            // The maximum depth as a reference line when it is in view
            int maxDepth = samples.get(samples.size() - 1).MaxDepth;
            if (maxDepth <= yMax) {
                int y = TOP + height - (int) Math.round(maxDepth * height / (double) yMax);
                g2.setColor(muted);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                g2.drawLine(LEFT, y, LEFT + width, y);
                g2.drawString("Max depth", LEFT + width - metrics.stringWidth("Max depth"), y - 4);
            }

            Path2D path = new Path2D.Double();
            for (int i = 0; i < samples.size(); i++) {
                Sample sample = samples.get(i);
                double x = LEFT + (sample.Time - start) * width / (double) (end - start);
                double y = TOP + height - sample.Depth * height / (double) yMax;
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            g2.setColor(line);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);
            if (samples.size() == 1) {
                Sample only = samples.get(0);
                int y = TOP + height - (int) Math.round(only.Depth * height / (double) yMax);
                g2.fillOval(LEFT - 4, y - 4, 8, 8);
            }

            // Crosshair and the reading nearest to the mouse
            if (mouseX >= LEFT && mouseX <= LEFT + width) {
                long time = start + (long) ((mouseX - LEFT) * (end - start) / (double) width);
                Sample nearest = samples.get(0);
                for (Sample sample : samples) {
                    if (Math.abs(sample.Time - time) < Math.abs(nearest.Time - time)) {
                        nearest = sample;
                    }
                }
                int x = LEFT + (int) Math.round((nearest.Time - start) * width / (double) (end - start));
                int y = TOP + height - (int) Math.round(nearest.Depth * height / (double) yMax);
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(muted);
                g2.drawLine(x, TOP, x, TOP + height);
                g2.setColor(getBackground() != null ? UIManager.getColor("Panel.background") : Color.WHITE);
                g2.fillOval(x - 6, y - 6, 12, 12);
                g2.setColor(line);
                g2.fillOval(x - 4, y - 4, 8, 8);
                String first = MQFormat.Number(nearest.Depth) + " messages";
                String second = TIME.format(Instant.ofEpochMilli(nearest.Time)) + " · open in " + nearest.OpenInput + ", out " + nearest.OpenOutput;
                int boxWidth = Math.max(metrics.stringWidth(first), metrics.stringWidth(second)) + 16;
                int boxHeight = metrics.getHeight() * 2 + 10;
                int boxX = x + 12 + boxWidth > LEFT + width ? x - 12 - boxWidth : x + 12;
                int boxY = Math.max(TOP, Math.min(y - boxHeight / 2, TOP + height - boxHeight));
                g2.setColor(UIManager.getColor("ToolTip.background") != null ? UIManager.getColor("ToolTip.background") : Color.WHITE);
                g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
                g2.setColor(grid);
                g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
                g2.setColor(text);
                g2.setFont(font.deriveFont(Font.BOLD));
                g2.drawString(first, boxX + 8, boxY + 5 + metrics.getAscent());
                g2.setFont(font);
                g2.setColor(muted);
                g2.drawString(second, boxX + 8, boxY + 5 + metrics.getHeight() + metrics.getAscent());
            }
            g2.dispose();
        }

        // 1, 2, 5 × 10^n: grid lines at round numbers
        private long niceStep(double rough) {
            double power = Math.pow(10, Math.floor(Math.log10(Math.max(rough, 1))));
            for (double factor : new double[]{1, 2, 5, 10}) {
                if (factor * power >= rough) {
                    return Math.max(1, Math.round(factor * power));
                }
            }
            return Math.max(1, Math.round(10 * power));
        }
    }
}
