package MQApi.Overview;

import MQApi.Overview.HealthCheck.ActionKind;
import MQApi.Overview.HealthCheck.Severity;
import MQApi.Overview.QueueManagerOverview.QueueDepth;
import com.ibm.mq.constants.MQConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checks on a queue manager overview: what needs attention first, then what is fine.
 */
public class QueueManagerHealth {

    public static final int QUEUE_WARNING_PERCENT = 60;
    public static final int QUEUE_PROBLEM_PERCENT = 90;
    public static final int DISK_WARNING_PERCENT = 80;
    public static final int DISK_PROBLEM_PERCENT = 90;
    public static final int LOG_WARNING_PERCENT = 80;

    public static List<HealthCheck> Evaluate(QueueManagerOverview o) {
        List<HealthCheck> checks = new ArrayList<>();
        checkServices(o, checks);
        checkListeners(o, checks);
        checkQueues(o, checks);
        checkDeadLetterQueue(o, checks);
        checkChannels(o, checks);
        checkDisks(o, checks);
        checkLog(o, checks);
        checkEvents(o, checks);
        checkSecurity(o, checks);
        checkConnection(o, checks);
        checks.sort(Comparator.comparing(c -> c.Level));
        return checks;
    }

    public static long Count(List<HealthCheck> checks, Severity level) {
        return checks.stream().filter(c -> c.Level == level).count();
    }

    private static void checkServices(QueueManagerOverview o, List<HealthCheck> checks) {
        Integer commandServer = o.StatusInt(MQConstants.MQIACF_CMD_SERVER_STATUS);
        Integer channelInitiator = o.StatusInt(MQConstants.MQIACF_CHINIT_STATUS);
        if (commandServer == null && channelInitiator == null) {
            return;
        }
        List<String> notRunning = new ArrayList<>();
        if (commandServer != null && commandServer != MQConstants.MQSVC_STATUS_RUNNING) {
            notRunning.add("command server (" + MQFormat.Constant(commandServer, "MQSVC_STATUS_").toLowerCase() + ")");
        }
        if (channelInitiator != null && channelInitiator != MQConstants.MQSVC_STATUS_RUNNING) {
            notRunning.add("channel initiator (" + MQFormat.Constant(channelInitiator, "MQSVC_STATUS_").toLowerCase() + ")");
        }
        if (notRunning.isEmpty()) {
            checks.add(new HealthCheck(Severity.OK, "Command server and channel initiator running", null));
        } else {
            checks.add(new HealthCheck(Severity.PROBLEM, "Not running: " + String.join(", ", notRunning),
                    "Without the channel initiator channels do not start; without the command server this tool cannot work."));
        }
    }

    private static void checkListeners(QueueManagerOverview o, List<HealthCheck> checks) {
        if (o.Listeners.isEmpty()) {
            return;
        }
        List<String> stopped = o.Listeners.stream().filter(l -> !l.Running)
                .map(l -> l.Name + " (" + MQFormat.Constant(l.Status, "MQSVC_STATUS_").toLowerCase() + ")")
                .collect(Collectors.toList());
        if (stopped.isEmpty()) {
            String ports = o.Listeners.stream().filter(l -> l.Port != null).map(l -> String.valueOf(l.Port)).collect(Collectors.joining(", "));
            checks.add(new HealthCheck(Severity.OK, o.Listeners.size() == 1 ? "Listener running" : o.Listeners.size() + " listeners running",
                    ports.isEmpty() ? null : "Port " + ports));
        } else {
            checks.add(new HealthCheck(Severity.PROBLEM, "Listener not running", String.join(", ", stopped)));
        }
    }

    private static void checkQueues(QueueManagerOverview o, List<HealthCheck> checks) {
        if (o.LocalQueues < 0) {
            return;
        }
        List<QueueDepth> filling = o.ApplicationQueues.stream()
                .filter(q -> q.Max > 0 && q.Percent() > QUEUE_WARNING_PERCENT)
                .sorted(Comparator.comparingInt(QueueDepth::Percent).reversed())
                .collect(Collectors.toList());
        for (QueueDepth q : filling) {
            String depth = MQFormat.Number(q.Current) + " of " + MQFormat.Number(q.Max) + " messages (" + q.Percent() + "%)";
            if (q.Current >= q.Max) {
                checks.add(new HealthCheck(Severity.PROBLEM, q.Name + " is full",
                        depth + ": new messages go to the dead-letter queue or are rejected.", ActionKind.BROWSE_QUEUE, q.Name));
            } else if (q.Percent() > QUEUE_PROBLEM_PERCENT) {
                checks.add(new HealthCheck(Severity.PROBLEM, q.Name + " is almost full", depth, ActionKind.SHOW_QUEUE, q.Name));
            } else {
                checks.add(new HealthCheck(Severity.WARNING, q.Name + " is filling up", depth, ActionKind.SHOW_QUEUE, q.Name));
            }
        }
        if (filling.isEmpty()) {
            checks.add(new HealthCheck(Severity.OK, "No queue above " + QUEUE_WARNING_PERCENT + "%", null));
        }
    }

    private static void checkDeadLetterQueue(QueueManagerOverview o, List<HealthCheck> checks) {
        if (o.Config.isEmpty()) {
            return;
        }
        String name = o.ConfigText(MQConstants.MQCA_DEAD_LETTER_Q_NAME);
        if (name == null || name.isEmpty()) {
            checks.add(new HealthCheck(Severity.WARNING, "No dead-letter queue",
                    "Messages that cannot be delivered are rejected or stop the channels."));
        } else if (o.DeadLetterQueue != null && o.DeadLetterQueue.Current > 0) {
            checks.add(new HealthCheck(Severity.WARNING, MQFormat.Number(o.DeadLetterQueue.Current) + " messages in the dead-letter queue",
                    name, ActionKind.BROWSE_QUEUE, name));
        } else if (o.DeadLetterQueue != null) {
            checks.add(new HealthCheck(Severity.OK, "Dead-letter queue empty", name));
        }
    }

    private static void checkChannels(QueueManagerOverview o, List<HealthCheck> checks) {
        if (o.Errors.containsKey("Channel status")) {
            return;
        }
        List<String> stopped = names(o.ChannelStatus, MQConstants.MQCHS_STOPPED);
        List<String> retrying = names(o.ChannelStatus, MQConstants.MQCHS_RETRYING);
        if (!stopped.isEmpty()) {
            checks.add(new HealthCheck(Severity.PROBLEM, stopped.size() == 1 ? "Channel stopped" : stopped.size() + " channels stopped", String.join(", ", stopped)));
        }
        if (!retrying.isEmpty()) {
            checks.add(new HealthCheck(Severity.PROBLEM, retrying.size() == 1 ? "Channel retrying" : retrying.size() + " channels retrying", String.join(", ", retrying)));
        }
        if (stopped.isEmpty() && retrying.isEmpty()) {
            checks.add(new HealthCheck(Severity.OK, "No channels stopped or retrying", null));
        }
    }

    private static List<String> names(Map<String, Integer> channels, int status) {
        return channels.entrySet().stream().filter(e -> e.getValue() == status).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private static void checkDisks(QueueManagerOverview o, List<HealthCheck> checks) {
        checkDisk(checks, "Queue manager file system", o.StatusInt(MQConstants.MQIACF_Q_MGR_FS_IN_USE), o.StatusText(MQConstants.MQCACF_Q_MGR_DATA_PATH));
        checkDisk(checks, "Log file system", o.StatusInt(MQConstants.MQIACF_LOG_FS_IN_USE), o.StatusText(MQConstants.MQCACF_LOG_PATH));
    }

    private static void checkDisk(List<HealthCheck> checks, String what, Integer percent, String path) {
        if (percent == null || percent < 0) {
            return; // -1: shared with another file system or not reported
        }
        Severity level = percent > DISK_PROBLEM_PERCENT ? Severity.PROBLEM : percent > DISK_WARNING_PERCENT ? Severity.WARNING : Severity.OK;
        checks.add(new HealthCheck(level, what + " " + percent + "% used", path));
    }

    private static void checkLog(QueueManagerOverview o, List<HealthCheck> checks) {
        Integer inUse = o.StatusInt(MQConstants.MQIACF_LOG_IN_USE);
        if (inUse == null || inUse < 0) {
            return;
        }
        String type = MQFormat.Constant(o.StatusInt(MQConstants.MQIACF_LOG_TYPE), "MQLOGTYPE_").toLowerCase();
        Severity level = inUse > LOG_WARNING_PERCENT ? Severity.WARNING : Severity.OK;
        checks.add(new HealthCheck(level, "Recovery log " + inUse + "% in use",
                level == Severity.OK ? type + " log" : type + " log: long-running transactions may fill it"));
    }

    private static void checkEvents(QueueManagerOverview o, List<HealthCheck> checks) {
        for (QueueDepth events : o.EventQueues) {
            checks.add(new HealthCheck(Severity.NOTE, events.Current == 1 ? "1 event to read" : MQFormat.Number(events.Current) + " events to read",
                    events.Name, ActionKind.BROWSE_QUEUE, events.Name));
        }
    }

    private static void checkConnection(QueueManagerOverview o, List<HealthCheck> checks) {
        if (Boolean.FALSE.equals(o.Encrypted)) {
            checks.add(new HealthCheck(Severity.NOTE, "This connection is not encrypted",
                    "Messages and the password travel in clear. TLS can be enabled in the connection settings (Edit)."));
        } else if (Boolean.TRUE.equals(o.Encrypted)) {
            checks.add(new HealthCheck(Severity.OK, "Connection encrypted with TLS", null));
        }
    }

    private static void checkSecurity(QueueManagerOverview o, List<HealthCheck> checks) {
        if (o.Config.isEmpty()) {
            return;
        }
        Integer channelAuth = o.ConfigInt(MQConstants.MQIA_CHLAUTH_RECORDS);
        if (channelAuth != null && channelAuth == MQConstants.MQCHLA_DISABLED) {
            checks.add(new HealthCheck(Severity.WARNING, "Channel authentication records disabled", "CHLAUTH rules are not applied (CHLAUTH(DISABLED))."));
        }
        Integer authorityEvents = o.ConfigInt(MQConstants.MQIA_AUTHORITY_EVENT);
        if (authorityEvents != null && authorityEvents == MQConstants.MQEVR_DISABLED) {
            checks.add(new HealthCheck(Severity.NOTE, "Authority events are off", "Refused access attempts are not recorded (AUTHOREV disabled)."));
        }
    }
}
