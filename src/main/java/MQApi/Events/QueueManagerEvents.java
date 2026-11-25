package MQApi.Events;

import MQApi.Logs.LogWriter;
import MQApi.PCF.MQPCF;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.MQCFGR;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFParameter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The event messages of a queue manager (authority failures, channels started and stopped, full queues,
 * configuration changes, commands…), read from its SYSTEM.ADMIN.*.EVENT queues without removing them,
 * and the queue manager attributes that turn each kind of event on and off.
 */
public class QueueManagerEvents {

    public enum Level {
        ERROR("Error"), WARNING("Warning"), INFO("Info");

        public final String Text;

        Level(String text) {
            this.Text = text;
        }
    }

    /** One event queue. */
    public enum Kind {
        QUEUE_MANAGER("Queue manager", "SYSTEM.ADMIN.QMGR.EVENT"),
        CHANNEL("Channel", "SYSTEM.ADMIN.CHANNEL.EVENT"),
        PERFORMANCE("Performance", "SYSTEM.ADMIN.PERFM.EVENT"),
        CONFIGURATION("Configuration", "SYSTEM.ADMIN.CONFIG.EVENT"),
        COMMAND("Command", "SYSTEM.ADMIN.COMMAND.EVENT"),
        LOGGER("Logger", "SYSTEM.ADMIN.LOGGER.EVENT");

        public final String Text;
        public final String Queue;

        Kind(String text, String queue) {
            this.Text = text;
            this.Queue = queue;
        }
    }

    /** A value of an event: Depth 1 for the values inside a group (e.g. the data of a command). */
    public static class Field {
        public final String Name;
        public final String Value;
        public final int Depth;
        /** The MQ name of the parameter, e.g. MQCACH_CHANNEL_NAME. */
        public final String MQName;

        Field(String name, String value, int depth, String mqName) {
            this.Name = name;
            this.Value = value;
            this.Depth = depth;
            this.MQName = mqName;
        }
    }

    /** An attribute changed by a configuration change: its value before and after. */
    public static class Change {
        public final String Name;
        public final String Before;
        public final String After;

        Change(String name, String before, String after) {
            this.Name = name;
            this.Before = before;
            this.After = after;
        }
    }

    public static class Event {
        public Instant Time;
        public Kind Kind;
        public Level Level;
        public int Reason;
        public String Title;
        public String Object = "";
        public String Summary = "";
        public List<Field> Fields = new ArrayList<>();
        /** Configuration changes only (the "before" and "after" messages read as one event). */
        public List<Change> Changes = new ArrayList<>();
        /** Message id, in hexadecimal: tells the events apart when they are read again. */
        public String Id = "";
        /**
         * A configuration change made by an application with MQSET, not by an administrator: e.g. the channel
         * initiator turning triggering on and off on transmission queues, every few seconds.
         */
        public boolean BySet;
    }

    /** The events read, with what could not be read and how many events each queue holds. */
    public static class Result {
        public final List<Event> Events = new ArrayList<>();
        public final Map<Kind, String> Errors = new EnumMap<>(Kind.class);
        public final Map<Kind, Integer> Depth = new EnumMap<>(Kind.class);
        public final Map<Kind, Integer> MaxDepth = new EnumMap<>(Kind.class);
        /** Queues with more events than were read: only the newest ones are kept. */
        public final Map<Kind, Integer> Skipped = new EnumMap<>(Kind.class);
    }

    /** Events kept for each queue: the newest ones. */
    public static final int MAX_PER_QUEUE = 10000;

    /** Reads the events of every event queue, newest first. */
    public static Result Read(MQQueueManager queueManager) {
        Result result = new Result();
        for (Kind kind : Kind.values()) {
            try {
                readQueue(queueManager, kind, result);
            } catch (MQException ex) {
                if (ex.getReason() == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {
                    continue; // e.g. no logger events queue on z/OS
                }
                LogWriter.WriteToLog("QueueManagerEvents", "Read " + kind.Queue, ex);
                result.Errors.put(kind, MQConstants.lookupReasonCode(ex.getReason()) + " (" + ex.getReason() + ")");
            }
        }
        result.Events.sort(Comparator.comparing((Event event) -> event.Time).reversed());
        return result;
    }

    private static void readQueue(MQQueueManager queueManager, Kind kind, Result result) throws MQException {
        MQQueue queue = open(queueManager, kind.Queue);
        try {
            result.Depth.put(kind, queue.getCurrentDepth());
            result.MaxDepth.put(kind, queue.getMaximumDepth());
            Deque<Event> newest = new ArrayDeque<>();
            int skipped = 0;
            Event before = null;
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = CMQC.MQGMO_BROWSE_NEXT | CMQC.MQGMO_NO_WAIT;
            options.matchOptions = CMQC.MQMO_NONE;
            while (true) {
                MQMessage message = new MQMessage();
                try {
                    queue.get(message, options);
                } catch (MQException ex) {
                    if (ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                        break;
                    }
                    throw ex;
                }
                Event event;
                PCFMessage pcf;
                try {
                    pcf = new PCFMessage(message);
                    event = FromPcf(kind, pcf, message.putDateTime != null ? message.putDateTime.toInstant() : Instant.now());
                } catch (MQDataException | IOException | RuntimeException ex) {
                    LogWriter.WriteToLog("QueueManagerEvents", "Parse " + kind.Queue, ex);
                    continue; // not an event message
                }
                event.Id = hex(message.messageId);
                // A configuration change comes as two messages: the object before and after it
                if (event.Reason == MQConstants.MQRC_CONFIG_CHANGE_OBJECT && pcf.getControl() == MQConstants.MQCFC_NOT_LAST) {
                    before = event;
                    continue;
                }
                if (event.Reason == MQConstants.MQRC_CONFIG_CHANGE_OBJECT && before != null) {
                    event = Merge(before, event);
                }
                before = null;
                newest.addLast(event);
                if (newest.size() > MAX_PER_QUEUE) {
                    newest.removeFirst();
                    skipped++;
                }
            }
            if (before != null) {
                newest.addLast(before);
            }
            if (skipped > 0) {
                result.Skipped.put(kind, skipped);
            }
            result.Events.addAll(newest);
        } finally {
            try {
                queue.close();
            } catch (MQException ex) {
                LogWriter.WriteToLog("QueueManagerEvents", "close", ex);
            }
        }
    }

    // Browse with read ahead (one network round trip for many events), the usual open where it is refused
    private static MQQueue open(MQQueueManager queueManager, String name) throws MQException {
        try {
            return queueManager.accessQueue(name, CMQC.MQOO_BROWSE | CMQC.MQOO_INQUIRE | CMQC.MQOO_READ_AHEAD | CMQC.MQOO_FAIL_IF_QUIESCING);
        } catch (MQException ex) {
            if (ex.getReason() != MQConstants.MQRC_OPTIONS_ERROR && ex.getReason() != MQConstants.MQRC_OPTION_NOT_VALID_FOR_TYPE) {
                throw ex;
            }
            return queueManager.accessQueue(name, CMQC.MQOO_BROWSE | CMQC.MQOO_INQUIRE | CMQC.MQOO_FAIL_IF_QUIESCING);
        }
    }

    // ---- Reading one event ----

    /** The event in a PCF event message. */
    public static Event FromPcf(Kind kind, PCFMessage pcf, Instant time) {
        Event event = new Event();
        event.Time = time;
        event.Kind = kind;
        event.Reason = pcf.getReason();
        addFields(pcf.getParameters(), 0, event.Fields);
        Map<Integer, PCFParameter> parameters = parameters(pcf);
        Integer qualifier = intValue(parameters.get(MQConstants.MQIACF_REASON_QUALIFIER));
        event.Title = title(event.Reason, qualifier, parameters);
        event.Level = level(kind, event.Reason, qualifier);
        event.Object = objectName(parameters);
        event.Summary = summary(event, parameters);
        event.BySet = kind == Kind.CONFIGURATION
                && Objects.equals(intValue(parameters.get(MQConstants.MQIACF_EVENT_ORIGIN)), MQConstants.MQEVO_MQSET);
        return event;
    }

    /** The "before" and "after" messages of a configuration change as one event, with the attributes changed. */
    public static Event Merge(Event before, Event after) {
        Map<String, Field> old = new LinkedHashMap<>();
        for (Field field : before.Fields) {
            old.put(field.MQName, field);
        }
        for (Field field : after.Fields) {
            Field was = old.get(field.MQName);
            if (IGNORED_CHANGES.contains(field.MQName) || (was != null && Objects.equals(was.Value, field.Value))) {
                continue;
            }
            after.Changes.add(new Change(field.Name, was == null ? "" : was.Value, field.Value));
        }
        if (!after.Changes.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Change change : after.Changes) {
                names.add(change.Name);
            }
            String changed = "Changed: " + String.join(", ", names);
            after.Summary = after.Summary.isEmpty() ? changed : changed + " · " + after.Summary;
        }
        return after;
    }

    // Alteration date and time change with every change
    private static final Set<String> IGNORED_CHANGES = Set.of("MQCA_ALTERATION_DATE", "MQCA_ALTERATION_TIME");

    private static Map<Integer, PCFParameter> parameters(PCFMessage pcf) {
        Map<Integer, PCFParameter> parameters = new HashMap<>();
        collect(pcf.getParameters(), parameters);
        return parameters;
    }

    private static void collect(Enumeration<?> list, Map<Integer, PCFParameter> parameters) {
        while (list.hasMoreElements()) {
            PCFParameter parameter = (PCFParameter) list.nextElement();
            if (parameter instanceof MQCFGR) {
                collect(((MQCFGR) parameter).getParameters(), parameters);
            } else {
                parameters.putIfAbsent(parameter.getParameter(), parameter);
            }
        }
    }

    private static void addFields(Enumeration<?> list, int depth, List<Field> fields) {
        while (list.hasMoreElements()) {
            PCFParameter parameter = (PCFParameter) list.nextElement();
            String mqName = firstAlias(parameter.getParameterName());
            if (parameter instanceof MQCFGR) {
                fields.add(new Field(ParameterName(mqName), null, depth, mqName));
                addFields(((MQCFGR) parameter).getParameters(), depth + 1, fields);
            } else {
                fields.add(new Field(ParameterName(mqName), Value(parameter), depth, mqName));
            }
        }
    }

    // ---- Titles and levels ----

    private static final Map<Integer, String> TITLES = new HashMap<>();
    private static final Map<Integer, Level> LEVELS = new HashMap<>();

    private static void known(int reason, String title, Level level) {
        TITLES.put(reason, title);
        LEVELS.put(reason, level);
    }

    static {
        // Queue manager events
        known(MQConstants.MQRC_NOT_AUTHORIZED, "Not authorized", Level.ERROR);
        known(MQConstants.MQRC_GET_INHIBITED, "Get inhibited", Level.WARNING);
        known(MQConstants.MQRC_PUT_INHIBITED, "Put inhibited", Level.WARNING);
        known(MQConstants.MQRC_UNKNOWN_OBJECT_NAME, "Unknown object", Level.WARNING);
        known(MQConstants.MQRC_UNKNOWN_ALIAS_BASE_Q, "Unknown alias base queue", Level.WARNING);
        known(MQConstants.MQRC_ALIAS_BASE_Q_TYPE_ERROR, "Alias base queue of the wrong type", Level.WARNING);
        known(MQConstants.MQRC_Q_TYPE_ERROR, "Queue of the wrong type", Level.WARNING);
        known(MQConstants.MQRC_UNKNOWN_REMOTE_Q_MGR, "Unknown remote queue manager", Level.WARNING);
        known(MQConstants.MQRC_UNKNOWN_XMIT_Q, "Unknown transmission queue", Level.WARNING);
        known(MQConstants.MQRC_UNKNOWN_DEF_XMIT_Q, "Unknown default transmission queue", Level.WARNING);
        known(MQConstants.MQRC_XMIT_Q_TYPE_ERROR, "Transmission queue of the wrong type", Level.WARNING);
        known(MQConstants.MQRC_XMIT_Q_USAGE_ERROR, "Transmission queue with the wrong usage", Level.WARNING);
        known(MQConstants.MQRC_DEF_XMIT_Q_TYPE_ERROR, "Default transmission queue of the wrong type", Level.WARNING);
        known(MQConstants.MQRC_DEF_XMIT_Q_USAGE_ERROR, "Default transmission queue with the wrong usage", Level.WARNING);
        known(MQConstants.MQRC_PRIORITY_EXCEEDS_MAXIMUM, "Priority above the maximum", Level.INFO);
        known(MQConstants.MQRC_Q_MGR_ACTIVE, "Queue manager started", Level.INFO);
        known(MQConstants.MQRC_Q_MGR_NOT_ACTIVE, "Queue manager stopping", Level.WARNING);
        // Performance events
        known(MQConstants.MQRC_Q_FULL, "Queue full", Level.ERROR);
        known(MQConstants.MQRC_Q_DEPTH_HIGH, "Queue depth high", Level.WARNING);
        known(MQConstants.MQRC_Q_DEPTH_LOW, "Queue depth low", Level.INFO);
        known(MQConstants.MQRC_Q_SERVICE_INTERVAL_HIGH, "Queue service interval high", Level.WARNING);
        known(MQConstants.MQRC_Q_SERVICE_INTERVAL_OK, "Queue service interval OK", Level.INFO);
        // Channel events
        known(MQConstants.MQRC_CHANNEL_STARTED, "Channel started", Level.INFO);
        known(MQConstants.MQRC_CHANNEL_STOPPED, "Channel stopped", Level.INFO);
        known(MQConstants.MQRC_CHANNEL_STOPPED_BY_USER, "Channel stopped by a user", Level.INFO);
        known(MQConstants.MQRC_CHANNEL_CONV_ERROR, "Channel data conversion error", Level.ERROR);
        known(MQConstants.MQRC_CHANNEL_ACTIVATED, "Channel activated", Level.INFO);
        known(MQConstants.MQRC_CHANNEL_NOT_ACTIVATED, "Channel not activated", Level.WARNING);
        known(MQConstants.MQRC_CHANNEL_AUTO_DEF_OK, "Channel defined automatically", Level.INFO);
        known(MQConstants.MQRC_CHANNEL_AUTO_DEF_ERROR, "Automatic channel definition failed", Level.ERROR);
        known(MQConstants.MQRC_CHANNEL_SSL_ERROR, "Channel TLS error", Level.ERROR);
        known(MQConstants.MQRC_CHANNEL_BLOCKED, "Channel blocked", Level.ERROR);
        known(MQConstants.MQRC_CHANNEL_BLOCKED_WARNING, "Channel would be blocked", Level.WARNING);
        // Configuration, command and logger events
        known(MQConstants.MQRC_CONFIG_CREATE_OBJECT, "Object created", Level.INFO);
        known(MQConstants.MQRC_CONFIG_CHANGE_OBJECT, "Object changed", Level.INFO);
        known(MQConstants.MQRC_CONFIG_DELETE_OBJECT, "Object deleted", Level.INFO);
        known(MQConstants.MQRC_CONFIG_REFRESH_OBJECT, "Object refreshed", Level.INFO);
        known(MQConstants.MQRC_COMMAND_MQSC, "MQSC command", Level.INFO);
        known(MQConstants.MQRC_COMMAND_PCF, "PCF command", Level.INFO);
        known(MQConstants.MQRC_LOGGER_STATUS, "Logger status", Level.INFO);
    }

    private static String title(int reason, Integer qualifier, Map<Integer, PCFParameter> parameters) {
        if (reason == MQConstants.MQRC_NOT_AUTHORIZED && qualifier != null) {
            switch (qualifier) {
                case MQConstants.MQRQ_CONN_NOT_AUTHORIZED:
                    return "Connection not authorized";
                case MQConstants.MQRQ_OPEN_NOT_AUTHORIZED:
                    return "Open not authorized";
                case MQConstants.MQRQ_CLOSE_NOT_AUTHORIZED:
                    return "Close not authorized";
                case MQConstants.MQRQ_CMD_NOT_AUTHORIZED:
                    return "Command not authorized";
                case MQConstants.MQRQ_CSP_NOT_AUTHORIZED:
                    return "Wrong user id or password";
                case MQConstants.MQRQ_SUB_NOT_AUTHORIZED:
                    return "Subscription not authorized";
                case MQConstants.MQRQ_SUB_DEST_NOT_AUTHORIZED:
                    return "Subscription destination not authorized";
                case MQConstants.MQRQ_SYS_CONN_NOT_AUTHORIZED:
                    return "System connection not authorized";
                default:
                    break;
            }
        }
        if (reason == MQConstants.MQRC_CHANNEL_STOPPED && qualifier != null) {
            switch (qualifier) {
                case MQConstants.MQRQ_CHANNEL_STOPPED_ERROR:
                    return "Channel stopped with an error";
                case MQConstants.MQRQ_CHANNEL_STOPPED_RETRY:
                    return "Channel stopped, retrying";
                case MQConstants.MQRQ_CHANNEL_STOPPED_DISABLED:
                    return "Channel stopped and disabled";
                default:
                    break;
            }
        }
        if (reason == MQConstants.MQRC_COMMAND_MQSC || reason == MQConstants.MQRC_COMMAND_PCF) {
            Integer command = intValue(parameters.get(MQConstants.MQIACF_COMMAND));
            if (command != null) {
                return ConstantName(command, "MQCMD_");
            }
        }
        String title = TITLES.get(reason);
        if (title != null) {
            return title;
        }
        // An event not known here: the name of its reason, "MQRC_Q_MGR_QUIESCING" → "Q mgr quiescing"
        return ConstantName(reason, "MQRC_");
    }

    private static Level level(Kind kind, int reason, Integer qualifier) {
        if (reason == MQConstants.MQRC_CHANNEL_STOPPED && qualifier != null) {
            if (qualifier == MQConstants.MQRQ_CHANNEL_STOPPED_ERROR) {
                return Level.ERROR;
            }
            if (qualifier == MQConstants.MQRQ_CHANNEL_STOPPED_RETRY || qualifier == MQConstants.MQRQ_CHANNEL_STOPPED_DISABLED) {
                return Level.WARNING;
            }
        }
        Level level = LEVELS.get(reason);
        if (level != null) {
            return level;
        }
        return kind == Kind.CONFIGURATION || kind == Kind.COMMAND || kind == Kind.LOGGER ? Level.INFO : Level.WARNING;
    }

    // ---- Object and summary ----

    // Parameters that name the object of an event, the most specific first
    private static final int[] OBJECT_NAMES = {
        MQConstants.MQCACH_CHANNEL_NAME, MQConstants.MQCA_Q_NAME, MQConstants.MQCA_BASE_OBJECT_NAME,
        MQConstants.MQCA_XMIT_Q_NAME, MQConstants.MQCA_PROCESS_NAME, MQConstants.MQCA_NAMELIST_NAME,
        MQConstants.MQCA_TOPIC_NAME, MQConstants.MQCA_AUTH_INFO_NAME, MQConstants.MQCACH_LISTENER_NAME,
        MQConstants.MQCA_SERVICE_NAME, MQConstants.MQCA_COMM_INFO_NAME, MQConstants.MQCA_CF_STRUC_NAME,
        MQConstants.MQCACF_AUTH_PROFILE_NAME, MQConstants.MQCACF_SUB_NAME, MQConstants.MQCA_CLUSTER_NAME,
        MQConstants.MQCA_Q_MGR_NAME,
    };

    private static String objectName(Map<Integer, PCFParameter> parameters) {
        for (int name : OBJECT_NAMES) {
            String value = stringValue(parameters.get(name));
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    // Values shown in the list next to the title, in this order
    private static final int[] SUMMARY = {
        MQConstants.MQIACF_OBJECT_TYPE, MQConstants.MQCACF_USER_IDENTIFIER, MQConstants.MQCACF_EVENT_USER_ID,
        MQConstants.MQCACF_CSP_USER_IDENTIFIER, MQConstants.MQCACF_APPL_NAME, MQConstants.MQCACF_EVENT_APPL_NAME,
        MQConstants.MQCACH_CHANNEL_NAME, MQConstants.MQCACH_CONNECTION_NAME, MQConstants.MQIACF_EVENT_ORIGIN,
        MQConstants.MQCA_XMIT_Q_NAME, MQConstants.MQIA_HIGH_Q_DEPTH, MQConstants.MQIACF_ERROR_IDENTIFIER,
        MQConstants.MQCACF_AUX_ERROR_DATA_STR_1,
    };

    private static String summary(Event event, Map<Integer, PCFParameter> parameters) {
        List<String> parts = new ArrayList<>();
        for (int id : SUMMARY) {
            PCFParameter parameter = parameters.get(id);
            if (parameter == null) {
                continue;
            }
            String value = Value(parameter);
            if (value.isEmpty() || value.equals(event.Object)) {
                continue;
            }
            parts.add(SUMMARY_LABELS.getOrDefault(id, ParameterName(firstAlias(parameter.getParameterName()))) + ": " + value);
        }
        return String.join(" · ", parts);
    }

    private static final Map<Integer, String> SUMMARY_LABELS = Map.of(
            MQConstants.MQIACF_OBJECT_TYPE, "Type",
            MQConstants.MQCACF_USER_IDENTIFIER, "User",
            MQConstants.MQCACF_EVENT_USER_ID, "User",
            MQConstants.MQCACF_CSP_USER_IDENTIFIER, "User id given",
            MQConstants.MQCACF_APPL_NAME, "Application",
            MQConstants.MQCACF_EVENT_APPL_NAME, "Application",
            MQConstants.MQCACH_CONNECTION_NAME, "Connection",
            MQConstants.MQIACF_EVENT_ORIGIN, "Origin",
            MQConstants.MQIA_HIGH_Q_DEPTH, "Highest depth");

    // ---- Names and values ----

    // Prefixes of the parameter names: MQCACH_CONNECTION_NAME → "Connection name"
    private static final String[] PARAMETER_PREFIXES = {"MQCACF_", "MQCACH_", "MQCAMO_", "MQCA_", "MQIACF_", "MQIACH_",
        "MQIAMO_", "MQIA_", "MQBACF_", "MQGACF_", "MQIAMO64_"};

    /** "MQCACH_CONNECTION_NAME" → "Connection name". */
    public static String ParameterName(String mqName) {
        if (mqName == null || mqName.isEmpty()) {
            return "";
        }
        String name = mqName;
        for (String prefix : PARAMETER_PREFIXES) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        return sentence(name);
    }

    // Integer parameters whose values are constants of another prefix than the one decodeValue finds first
    private static final Map<Integer, String> VALUE_PREFIXES = Map.of(
            MQConstants.MQIA_APPL_TYPE, "MQAT_",
            MQConstants.MQIACF_REASON_QUALIFIER, "MQRQ_",
            MQConstants.MQIACF_OBJECT_TYPE, "MQOT_",
            MQConstants.MQIACF_EVENT_ORIGIN, "MQEVO_",
            MQConstants.MQIACF_COMMAND, "MQCMD_",
            MQConstants.MQIACF_EVENT_APPL_TYPE, "MQAT_");

    /** The value of a parameter as text: constants by name ("Channel stopped disabled"), bytes in hexadecimal. */
    public static String Value(PCFParameter parameter) {
        Object value = parameter.getValue();
        if (value instanceof Integer) {
            return intText(parameter, (Integer) value);
        }
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (value instanceof String[]) {
            List<String> items = new ArrayList<>();
            for (String item : (String[]) value) {
                if (item != null && !item.trim().isEmpty()) {
                    items.add(item.trim());
                }
            }
            return String.join(", ", items);
        }
        if (value instanceof int[]) {
            List<String> items = new ArrayList<>();
            for (int item : (int[]) value) {
                items.add(Integer.toString(item));
            }
            return String.join(", ", items);
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            boolean empty = true;
            for (byte b : bytes) {
                empty &= b == 0;
            }
            return empty ? "" : hex(bytes);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String intText(PCFParameter parameter, int value) {
        String prefix = VALUE_PREFIXES.get(parameter.getParameter());
        if (prefix != null) {
            return ConstantName(value, prefix);
        }
        String decoded;
        try {
            decoded = parameter.decodeValue();
        } catch (RuntimeException ex) {
            decoded = null;
        }
        // decodeValue gives the constant names ("MQEVR_ENABLED") or nothing for counts and sizes
        if (decoded == null || !decoded.startsWith("MQ") || decoded.indexOf('_') < 0) {
            return Integer.toString(value);
        }
        return Constants(firstAlias(parameter.getParameterName()), decoded);
    }

    /**
     * The constants decodeValue found for a value, as text. A flag shares its value with others of the same
     * prefix (MQIA_INHIBIT_GET 0: MQQA_GET_ALLOWED/MQQA_PUT_ALLOWED/MQQA_NOT_SHAREABLE…): the ones whose
     * words are in the name of the parameter are kept ("Get allowed").
     */
    static String Constants(String parameterName, String decoded) {
        String[] aliases = decoded.split("/");
        List<String> best = new ArrayList<>();
        int bestScore = 0;
        for (String alias : aliases) {
            int score = sharedWords(parameterName, alias);
            if (score > bestScore) {
                best.clear();
                bestScore = score;
            }
            if (score == bestScore) {
                best.add(alias);
            }
        }
        List<String> names = new ArrayList<>();
        for (String alias : best) {
            String shown = sentence(alias.indexOf('_') > 0 ? alias.substring(alias.indexOf('_') + 1) : alias);
            if (!names.contains(shown)) {
                names.add(shown);
            }
        }
        return String.join(" / ", names);
    }

    // Words of the constant (after its prefix) that start like a word of the parameter: SHAREABLE ~ SHAREABILITY
    private static int sharedWords(String parameterName, String constant) {
        String[] parameterWords = parameterName.split("_");
        String[] words = constant.split("_");
        int shared = 0;
        for (int i = 1; i < words.length; i++) {
            for (int j = 1; j < parameterWords.length; j++) {
                if (sameStem(words[i], parameterWords[j])) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static boolean sameStem(String a, String b) {
        int length = Math.min(a.length(), b.length());
        if (length < 3) {
            return a.equals(b);
        }
        int common = 0;
        while (common < length && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= Math.min(length, 5);
    }

    /**
     * Name of an MQ constant without its prefix, in sentence case; constants with the same value are
     * all named ("MQAT_AIX/MQAT_UNIX" → "Aix / Unix"). The number when there is no such constant.
     */
    public static String ConstantName(int value, String prefix) {
        String name;
        try {
            name = MQConstants.lookup(value, prefix.replace(".", "\\.") + ".*");
        } catch (RuntimeException ex) {
            name = null;
        }
        if (name == null || name.isEmpty()) {
            return Integer.toString(value);
        }
        List<String> names = new ArrayList<>();
        for (String alias : name.split("/")) {
            // MQAT_DEFAULT is the type of the platform, not a type of its own: "Java", not "Java / Default"
            if (alias.endsWith("_DEFAULT") && name.contains("/")) {
                continue;
            }
            String shown = sentence(alias.startsWith(prefix) ? alias.substring(prefix.length()) : alias);
            if (!names.contains(shown)) {
                names.add(shown);
            }
        }
        return String.join(" / ", names);
    }

    private static String firstAlias(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }

    // Words kept upper case in names
    private static final Set<String> ACRONYMS = Set.of("MQSC", "PCF", "TLS", "SSL", "CF", "IP", "ID", "MCA", "MQI",
            "CSP", "CRL", "DLQ", "MQ", "AMQP", "MQTT", "JMS", "TCP", "LU62", "CCSID", "MRU", "CLWL", "AIX", "UNIX", "MQSET",
            "MQPCF");

    // MQ abbreviations spelled out: "Q mgr desc" → "Queue manager description"
    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("q", "queue"), Map.entry("qs", "queues"), Map.entry("mgr", "manager"), Map.entry("qmgr", "queue manager"),
            Map.entry("desc", "description"), Map.entry("appl", "application"), Map.entry("xmit", "transmission"),
            Map.entry("def", "default"), Map.entry("conn", "connection"), Map.entry("msg", "message"),
            Map.entry("msgs", "messages"), Map.entry("attrs", "attributes"), Map.entry("auth", "authority"),
            Map.entry("chl", "channel"), Map.entry("cmd", "command"), Map.entry("dest", "destination"),
            Map.entry("num", "number"), Map.entry("hb", "heartbeat"), Map.entry("seq", "sequence"),
            Map.entry("enq", "enqueue"), Map.entry("deq", "dequeue"), Map.entry("persist", "persistence"),
            Map.entry("prty", "priority"), Map.entry("psist", "persistence"), Map.entry("dist", "distribution"),
            Map.entry("ctl", "control"), Map.entry("svc", "service"), Map.entry("addr", "address"));

    private static String sentence(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String upper = word.toUpperCase(Locale.ROOT);
            String shown = ACRONYMS.contains(upper) ? upper : WORDS.getOrDefault(word, word);
            if (text.length() > 0) {
                text.append(' ');
            } else if (!ACRONYMS.contains(upper)) {
                shown = Character.toUpperCase(shown.charAt(0)) + shown.substring(1);
            }
            text.append(shown);
        }
        return text.toString();
    }

    private static Integer intValue(PCFParameter parameter) {
        return parameter != null && parameter.getValue() instanceof Integer ? (Integer) parameter.getValue() : null;
    }

    private static String stringValue(PCFParameter parameter) {
        return parameter != null && parameter.getValue() instanceof String ? ((String) parameter.getValue()).trim() : null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(String.format("%02X", b));
        }
        return text.toString();
    }

    // ---- Which events are recorded ----

    /** A queue manager attribute that turns events on and off. */
    public enum Switch {
        AUTHORITY(MQConstants.MQIA_AUTHORITY_EVENT, "AUTHOREV", "Authority", "Failed connections and authorizations, wrong passwords", false),
        INHIBIT(MQConstants.MQIA_INHIBIT_EVENT, "INHIBTEV", "Inhibit", "Put or get on a queue where it is inhibited", false),
        LOCAL(MQConstants.MQIA_LOCAL_EVENT, "LOCALEV", "Local", "Unknown objects and queues of the wrong type", false),
        REMOTE(MQConstants.MQIA_REMOTE_EVENT, "REMOTEEV", "Remote", "Errors in remote queues and transmission queues", false),
        START_STOP(MQConstants.MQIA_START_STOP_EVENT, "STRSTPEV", "Start and stop", "Queue manager started and stopping", false),
        PERFORMANCE(MQConstants.MQIA_PERFORMANCE_EVENT, "PERFMEV", "Performance",
                "Queue full, depth high or low, service interval", false),
        CHANNEL(MQConstants.MQIA_CHANNEL_EVENT, "CHLEV", "Channel", "Channels started, stopped, blocked", true),
        SSL(MQConstants.MQIA_SSL_EVENT, "SSLEV", "TLS", "TLS errors on channels", false),
        CHANNEL_AUTO_DEF(MQConstants.MQIA_CHANNEL_AUTO_DEF_EVENT, "CHADEV", "Channel auto-definition", "Channels defined automatically", false),
        CONFIGURATION(MQConstants.MQIA_CONFIGURATION_EVENT, "CONFIGEV", "Configuration", "Objects created, changed and deleted", false),
        COMMAND(MQConstants.MQIA_COMMAND_EVENT, "CMDEV", "Command", "MQSC and PCF commands run", true),
        LOGGER(MQConstants.MQIA_LOGGER_EVENT, "LOGGEREV", "Logger", "Recovery log status (linear logging only)", false);

        public final int Attribute;
        public final String Mqsc;
        public final String Text;
        public final String Description;
        /** CHLEV can record the exceptions only, CMDEV everything but the DISPLAY commands. */
        public final boolean HasThirdChoice;

        Switch(int attribute, String mqsc, String text, String description, boolean hasThirdChoice) {
            this.Attribute = attribute;
            this.Mqsc = mqsc;
            this.Text = text;
            this.Description = description;
            this.HasThirdChoice = hasThirdChoice;
        }

        /** The values of the attribute, in the order of Choices. */
        public int[] Values() {
            if (this == CHANNEL) {
                return new int[]{MQConstants.MQEVR_DISABLED, MQConstants.MQEVR_ENABLED, MQConstants.MQEVR_EXCEPTION};
            }
            if (this == COMMAND) {
                return new int[]{MQConstants.MQEVR_DISABLED, MQConstants.MQEVR_ENABLED, MQConstants.MQEVR_NO_DISPLAY};
            }
            return new int[]{MQConstants.MQEVR_DISABLED, MQConstants.MQEVR_ENABLED};
        }

        public String[] Choices() {
            if (this == CHANNEL) {
                return new String[]{"Off", "On", "Exceptions only"};
            }
            if (this == COMMAND) {
                return new String[]{"Off", "On", "On, except DISPLAY"};
            }
            return new String[]{"Off", "On"};
        }
    }

    /** The event switches of the queue manager and their values; the ones it does not have are left out. */
    public static Map<Switch, Integer> ReadSwitches(MQQueueManager queueManager) throws MQDataException, IOException {
        PCFMessage inquire = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR);
        inquire.addParameter(MQConstants.MQIACF_Q_MGR_ATTRS, new int[]{MQConstants.MQIACF_ALL});
        Map<Switch, Integer> switches = new EnumMap<>(Switch.class);
        for (PCFMessage response : MQPCF.Send(queueManager, inquire)) {
            if (response.getCompCode() != MQConstants.MQCC_OK) {
                throw new IOException(MQConstants.lookupReasonCode(response.getReason()));
            }
            Map<Integer, PCFParameter> parameters = parameters(response);
            for (Switch eventSwitch : Switch.values()) {
                Integer value = intValue(parameters.get(eventSwitch.Attribute));
                if (value != null) {
                    switches.put(eventSwitch, value);
                }
            }
        }
        return switches;
    }

    /** Sets the event switches (ALTER QMGR); null when done, else why not. */
    public static String WriteSwitches(MQQueueManager queueManager, Map<Switch, Integer> values) {
        PCFMessage change = new PCFMessage(MQConstants.MQCMD_CHANGE_Q_MGR);
        for (Map.Entry<Switch, Integer> entry : values.entrySet()) {
            change.addParameter(entry.getKey().Attribute, entry.getValue());
        }
        try {
            for (PCFMessage response : MQPCF.Send(queueManager, change)) {
                if (response.getCompCode() != MQConstants.MQCC_OK) {
                    return MQConstants.lookupReasonCode(response.getReason());
                }
            }
            return null;
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("QueueManagerEvents", "WriteSwitches", ex);
            return reason(ex);
        } catch (IOException ex) {
            LogWriter.WriteToLog("QueueManagerEvents", "WriteSwitches", ex);
            return ex.getMessage();
        }
    }

    // The PCF error of a refused command (e.g. LOGGEREV on a queue manager with circular logging)
    private static String reason(MQDataException ex) {
        if (ex instanceof com.ibm.mq.headers.pcf.PCFException) {
            com.ibm.mq.headers.pcf.PCFException pcf = (com.ibm.mq.headers.pcf.PCFException) ex;
            if (pcf.exceptionSource instanceof PCFMessage[]) {
                for (PCFMessage response : (PCFMessage[]) pcf.exceptionSource) {
                    if (response.getCompCode() != MQConstants.MQCC_OK) {
                        return MQConstants.lookupReasonCode(response.getReason());
                    }
                }
            }
        }
        return MQConstants.lookupReasonCode(ex.getReason()) + " (" + ex.getReason() + ")";
    }

    /** The switches that are off, by name. */
    public static List<String> Off(Map<Switch, Integer> switches) {
        List<String> off = new ArrayList<>();
        for (Map.Entry<Switch, Integer> entry : switches.entrySet()) {
            if (entry.getValue() == MQConstants.MQEVR_DISABLED) {
                off.add(entry.getKey().Text);
            }
        }
        return Collections.unmodifiableList(off);
    }
}
