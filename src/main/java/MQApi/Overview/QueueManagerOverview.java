package MQApi.Overview;

import MQApi.Logs.LogWriter;
import MQApi.PCF.MQPCF;
import MQApi.QueryModel.MQQueueListResult;
import MQApi.QueryModel.MQQueueListResult.QueueDetailModel;
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

    // DefinitionType text of temporary dynamic queues (see QueueDetailModel)
    private static final String TEMPORARY_DYNAMIC = "Dynamically defined temporary queue";
    private static final int TOP_QUEUES = 6;

    public static QueueManagerOverview Load(MQQueueManager queueManager) {
        QueueManagerOverview overview = new QueueManagerOverview();
        overview.loadConfig(queueManager);
        overview.loadStatus(queueManager);
        overview.loadListeners(queueManager);
        overview.loadChannels(queueManager);
        overview.loadQueues(queueManager);
        overview.loadChannelAuth(queueManager);
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

    private PCFMessage[] send(MQQueueManager queueManager, PCFMessage command, String part) {
        try {
            PCFMessage[] responses = MQPCF.Send(queueManager, command);
            if (responses.length > 0 && responses[0].getCompCode() != MQConstants.MQCC_OK
                    && responses[0].getReason() != MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND
                    && responses[0].getReason() != MQConstants.MQRCCF_NONE_FOUND) {
                Errors.put(part, MQConstants.lookupReasonCode(responses[0].getReason()));
                return null;
            }
            return responses;
        } catch (Exception ex) {
            LogWriter.WriteToLog("QueueManagerOverview", part, ex);
            Errors.put(part, String.valueOf(ex.getMessage()));
            return null;
        }
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

    private void loadConfig(MQQueueManager queueManager) {
        PCFMessage[] responses = send(queueManager, new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR), "Configuration");
        if (responses != null && responses.length > 0 && isOk(responses[0])) {
            readParameters(responses[0], Config);
        }
    }

    private void loadStatus(MQQueueManager queueManager) {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS);
        command.addParameter(MQConstants.MQIACF_Q_MGR_STATUS_ATTRS, new int[]{MQConstants.MQIACF_ALL});
        PCFMessage[] responses = send(queueManager, command, "Status");
        if (responses != null && responses.length > 0 && isOk(responses[0])) {
            readParameters(responses[0], Status);
        }
    }

    private void loadListeners(MQQueueManager queueManager) {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_LISTENER_STATUS);
        command.addParameter(MQConstants.MQCACH_LISTENER_NAME, "*");
        PCFMessage[] responses = send(queueManager, command, "Listeners");
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

    private void loadChannels(MQQueueManager queueManager) {
        PCFMessage names = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_NAMES);
        names.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        PCFMessage[] nameResponses = send(queueManager, names, "Channels");
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
        PCFMessage status = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS);
        status.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        status.addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_ATTRS, new int[]{MQConstants.MQCACH_CHANNEL_NAME, MQConstants.MQIACH_CHANNEL_STATUS});
        PCFMessage[] responses = send(queueManager, status, "Channel status");
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

    private void loadQueues(MQQueueManager queueManager) {
        MQQueueListResult result = MQPCF.GetQueueList(queueManager, "*", null);
        if (!result.QuerySuccess) {
            Errors.put("Queues", result.ErrorMessage);
            return;
        }
        String deadLetterName = ConfigText(MQConstants.MQCA_DEAD_LETTER_Q_NAME);
        int locals = 0;
        for (QueueDetailModel queue : result.DataModels) {
            if (queue.CurrentQueueDepth == null || queue.Name == null) {
                continue; // alias, remote and model queues have no depth
            }
            if (TEMPORARY_DYNAMIC.equals(queue.DefinitionType)) {
                continue; // reply queues of running requests, this tool's PCF replies included
            }
            locals++;
            QueueDepth depth = new QueueDepth(queue.Name, queue.CurrentQueueDepth, queue.MaxQueueDepth != null ? queue.MaxQueueDepth : 0);
            if (depth.Current > 0) {
                QueuesWithMessages++;
            }
            if (queue.Name.equals(deadLetterName)) {
                DeadLetterQueue = depth;
            } else if (queue.Name.startsWith("SYSTEM.ADMIN.") && queue.Name.endsWith(".EVENT")) {
                if (depth.Current > 0) {
                    EventQueues.add(depth);
                }
            } else if (!queue.Name.startsWith("SYSTEM.")) {
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

    private void loadChannelAuth(MQQueueManager queueManager) {
        PCFMessage command = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHLAUTH_RECS);
        command.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");
        PCFMessage[] responses = send(queueManager, command, "Channel authentication");
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
