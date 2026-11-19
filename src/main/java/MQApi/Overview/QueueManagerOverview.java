package MQApi.Overview;

import MQApi.Logs.LogWriter;
import MQApi.PCF.MQPCF;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFParameter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything shown on the queue manager page, read with a few PCF commands.
 * Each part is loaded on its own: when a command is not supported (platform, version, authority)
 * only that part is missing and its error is kept in Errors.
 */
public class QueueManagerOverview {

    public static class QueueDepth {
        public final String Name;
        public final int Current;
        public final int Max;

        public QueueDepth(String name, int current, int max) {
            this.Name = name;
            this.Current = current;
            this.Max = max;
        }

        public int Percent() {
            return Max > 0 ? (int) Math.round(Current * 100.0 / Max) : 0;
        }

        /** How full the queue is, from 0 to 1. */
        public double Fill() {
            return Max > 0 ? Math.min(1.0, Current / (double) Max) : 0;
        }
    }

    public static class Listener {
        public String Name;
        public Integer Port;
        public int Status;
        public boolean Running;
    }

    public static class ChannelState {
        public final String Name;
        public final int Status;

        public ChannelState(String name, int status) {
            this.Name = name;
            this.Status = status;
        }
    }

    /** INQUIRE QMGR, by parameter id, in the order returned. */
    public final Map<Integer, Object> Config = new LinkedHashMap<>();
    /** INQUIRE QMGR STATUS, by parameter id. */
    public final Map<Integer, Object> Status = new LinkedHashMap<>();
    /** Every attribute of both, by IBM constant name, for the "All attributes" view. */
    public final Map<String, String> AllAttributes = new TreeMap<>();

    public final List<Listener> Listeners = new ArrayList<>();
    /** One entry per channel name, with its "worst" status (several instances of a channel may run). */
    public final Map<String, Integer> ChannelStatus = new TreeMap<>();
    public int DefinedChannels = -1;

    public int LocalQueues = -1;
    public int QueuesWithMessages;
    /** Messages and queues with messages, application queues only (not SYSTEM.*). */
    public int ApplicationMessages;
    public int ApplicationQueuesWithMessages;
    public final List<QueueDepth> TopQueues = new ArrayList<>();
    public final List<QueueDepth> ApplicationQueues = new ArrayList<>();
    /** SYSTEM.ADMIN.*.EVENT queues that hold events. */
    public final List<QueueDepth> EventQueues = new ArrayList<>();
    public QueueDepth DeadLetterQueue;

    public int ChannelAuthRules = -1;

    /** Whether this tool's connection uses TLS; null when not known. */
    public Boolean Encrypted;

    /** Part name → error message, for the parts that could not be read. */
    public final Map<String, String> Errors = new LinkedHashMap<>();
    public LocalDateTime LoadedAt;

    private static final int TOP_QUEUES = 6;

    // The parts of the page: one command each, sent together
    private static final String[] PARTS = {"Configuration", "Status", "Listeners", "Channels", "Channel status", "Channel authentication", "Queues"};

    /**
     * Reads the page. All the commands are sent at once and their replies read together: on a slow
     * link the page costs about one network round trip instead of two per command.
     */
    public static QueueManagerOverview Load(MQQueueManager queueManager) {
        QueueManagerOverview overview = new QueueManagerOverview();
        // The queues last: while the queue manager reads them, no request of this page waits on its
        // command queue (it would count as a queue with messages)
        PCFMessage[] commands = {configCommand(), statusCommand(), listenersCommand(), channelNamesCommand(),
            channelStatusCommand(), channelAuthCommand(), queuesCommand()};
        PCFMessage[][] replies = new PCFMessage[commands.length][];
        try {
            replies = MQPCF.SendAll(queueManager, commands);
        } catch (Exception ex) {
            LogWriter.WriteToLog("QueueManagerOverview", "Load", ex);
            for (String part : PARTS) {
                overview.Errors.put(part, String.valueOf(ex.getMessage()));
            }
        }
        // In the order of the page: the queues need the dead-letter queue name of the configuration
        overview.loadConfig(overview.checked(replies[0], PARTS[0]));
        overview.loadStatus(overview.checked(replies[1], PARTS[1]));
        overview.loadListeners(overview.checked(replies[2], PARTS[2]));
        overview.loadChannels(overview.checked(replies[3], PARTS[3]), overview.checked(replies[4], PARTS[4]));
        overview.loadQueues(overview.checked(replies[6], PARTS[6]));
        overview.loadChannelAuth(overview.checked(replies[5], PARTS[5]));
        overview.LoadedAt = LocalDateTime.now();
        return overview;
    }

    // --- Typed access ---

    public String ConfigText(int id) {
        Object value = Config.get(id);
        return value instanceof String ? ((String) value).trim() : null;
    }

    public Integer ConfigInt(int id) {
        Object value = Config.get(id);
        return value instanceof Integer ? (Integer) value : null;
    }

    public String StatusText(int id) {
        Object value = Status.get(id);
        return value instanceof String ? ((String) value).trim() : null;
    }

    public Integer StatusInt(int id) {
        Object value = Status.get(id);
        return value instanceof Integer ? (Integer) value : null;
    }

    public int RunningChannels() {
        return (int) ChannelStatus.values().stream().filter(s -> s == MQConstants.MQCHS_RUNNING).count();
    }

    // --- Loading ---

    // The responses of a part, null (and the reason in Errors) when it failed; "none found" is not an error
    private PCFMessage[] checked(PCFMessage[] responses, String part) {
        if (responses == null) {
            return null;
        }
        if (responses.length > 0 && responses[0].getCompCode() != MQConstants.MQCC_OK
                && responses[0].getReason() != MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND
                && responses[0].getReason() != MQConstants.MQRCCF_NONE_FOUND) {
            Errors.put(part, MQConstants.lookupReasonCode(responses[0].getReason()));
            return null;
        }
        return responses;
    }

    private static boolean isOk(PCFMessage response) {
        return response.getCompCode() == MQConstants.MQCC_OK;
    }

    private void readParameters(PCFMessage response, Map<Integer, Object> target) {
        Enumeration<?> parameters = response.getParameters();
        while (parameters.hasMoreElements()) {
            PCFParameter parameter = (PCFParameter) parameters.nextElement();
            target.put(parameter.getParameter(), parameter.getValue());
            String name = parameter.getParameterName();
            if (name == null) {
                name = String.valueOf(parameter.getParameter());
            }
            // Some ids have several names ("A/B"): the first is the usual one
            int slash = name.indexOf('/');
            AllAttributes.put(slash > 0 ? name.substring(0, slash) : name, parameter.getStringValue().trim());
        }
    }

    private static PCFMessage configCommand() {
        return new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
    }

    private void loadConfig(PCFMessage[] responses) {
        if (responses != null && responses.length > 0 && isOk(responses[0])) {
            readParameters(responses[0], Config);
        }
    }

    private static PCFMessage statusCommand() {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
        command.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, new int[]{MQConstants.MQIACF_ALL});
        return command;
    }

    private void loadStatus(PCFMessage[] responses) {
        if (responses != null && responses.length > 0 && isOk(responses[0])) {
            readParameters(responses[0], Status);
        }
    }

    private static PCFMessage listenersCommand() {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
        command.addParameter(MQConstants.MQCACH_LISTENER_NAME, "*");
        return command;
    }

    private void loadListeners(PCFMessage[] responses) {
        if (responses == null) {
            return;
        }
        for (PCFMessage response : responses) {
            if (!isOk(response)) {
                continue;
            }
            try {
                Listener listener = new Listener();
                listener.Name = response.getStringParameterValue(MQConstants.MQCACH_LISTENER_NAME).trim();
                listener.Status = response.getIntParameterValue(MQConstants.MQIACH_LISTENER_STATUS);
                listener.Running = listener.Status == MQConstants.MQSVC_STATUS_RUNNING;
                Object port = response.getParameterValue(MQConstants.MQIACH_PORT);
                listener.Port = port instanceof Integer ? (Integer) port : null;
                Listeners.add(listener);
            } catch (Exception ex) {
                LogWriter.WriteToLog("QueueManagerOverview", "Listeners", ex);
            }
        }
    }

    private static PCFMessage channelNamesCommand() {
        PCFMessage names = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_NAMES);
        names.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        return names;
    }

    private static PCFMessage channelStatusCommand() {
        PCFMessage status = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
        status.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        status.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, new int[]{MQConstants.MQCACH_CHANNEL_NAME, MQConstants.MQIACH_CHANNEL_STATUS});
        return status;
    }

    private void loadChannels(PCFMessage[] nameResponses, PCFMessage[] responses) {
        if (nameResponses != null && nameResponses.length > 0 && isOk(nameResponses[0])) {
            Object value = nameResponses[0].getParameterValue(MQConstants.MQCACH_CHANNEL_NAMES);
            if (value instanceof String[]) {
                int count = 0;
                for (String name : (String[]) value) {
                    if (!name.trim().startsWith("SYSTEM.")) {
                        count++;
                    }
                }
                DefinedChannels = count;
            }
        }
        if (responses == null) {
            return;
        }
        for (PCFMessage response : responses) {
            if (!isOk(response)) {
                continue;
            }
            try {
                String name = response.getStringParameterValue(MQConstants.MQCACH_CHANNEL_NAME).trim();
                int state = response.getIntParameterValue(MQConstants.MQIACH_CHANNEL_STATUS);
                // Keep the state that needs most attention among the instances of the channel
                ChannelStatus.merge(name, state, (a, b) -> severity(a) >= severity(b) ? a : b);
            } catch (Exception ex) {
                LogWriter.WriteToLog("QueueManagerOverview", "Channel status", ex);
            }
        }
    }

    private static int severity(int channelStatus) {
        switch (channelStatus) {
            case MQConstants.MQCHS_STOPPED:
                return 3;
            case MQConstants.MQCHS_RETRYING:
                return 2;
            case MQConstants.MQCHS_RUNNING:
                return 0;
            default:
                return 1;
        }
    }

    // Only the local queues and the 4 attributes the page uses (the queue list reads them all)
    private static PCFMessage queuesCommand() {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q);
        command.addParameter(MQConstants.MQCA_Q_NAME, "*");
        command.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_LOCAL);
        command.addParameter(MQConstants.MQIACF_Q_ATTRS, new int[]{MQConstants.MQCA_Q_NAME, MQConstants.MQIA_CURRENT_Q_DEPTH,
            MQConstants.MQIA_MAX_Q_DEPTH, MQConstants.MQIA_DEFINITION_TYPE});
        return command;
    }

    private void loadQueues(PCFMessage[] responses) {
        if (responses == null) {
            return;
        }
        String deadLetterName = ConfigText(MQConstants.MQCA_DEAD_LETTER_Q_NAME);
        int locals = 0;
        for (PCFMessage response : responses) {
            if (!isOk(response)) {
                continue;
            }
            String name;
            int current;
            int max;
            try {
                // Reply queues of running requests, this tool's PCF replies included
                if (response.getIntParameterValue(MQConstants.MQIA_DEFINITION_TYPE) == MQConstants.MQQDT_TEMPORARY_DYNAMIC) {
                    continue;
                }
                name = response.getStringParameterValue(MQConstants.MQCA_Q_NAME).trim();
                current = response.getIntParameterValue(MQConstants.MQIA_CURRENT_Q_DEPTH);
                max = response.getIntParameterValue(MQConstants.MQIA_MAX_Q_DEPTH);
            } catch (Exception ex) {
                LogWriter.WriteToLog("QueueManagerOverview", "Queues", ex);
                continue;
            }
            locals++;
            QueueDepth depth = new QueueDepth(name, current, max);
            if (depth.Current > 0) {
                QueuesWithMessages++;
            }
            if (name.equals(deadLetterName)) {
                DeadLetterQueue = depth;
            } else if (name.startsWith("SYSTEM.ADMIN.") && name.endsWith(".EVENT")) {
                if (depth.Current > 0) {
                    EventQueues.add(depth);
                }
            } else if (!name.startsWith("SYSTEM.")) {
                ApplicationQueues.add(depth);
                if (depth.Current > 0) {
                    ApplicationMessages += depth.Current;
                    ApplicationQueuesWithMessages++;
                }
            }
        }
        LocalQueues = locals;
        // Fullest first: 10 messages in a queue that holds 10 matter more than 30 in one that holds 5000
        ApplicationQueues.stream()
                .filter(q -> q.Current > 0)
                .sorted(Comparator.comparingDouble(QueueDepth::Fill).reversed()
                        .thenComparing(Comparator.comparingInt((QueueDepth q) -> q.Current).reversed()))
                .limit(TOP_QUEUES)
                .forEach(TopQueues::add);
    }

    private static PCFMessage channelAuthCommand() {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHLAUTH_RECS);
        command.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        return command;
    }

    private void loadChannelAuth(PCFMessage[] responses) {
        if (responses == null) {
            return;
        }
        int count = 0;
        for (PCFMessage response : responses) {
            if (isOk(response)) {
                count++;
            }
        }
        ChannelAuthRules = count;
    }
}
