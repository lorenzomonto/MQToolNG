package MQApi.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One object of a queue manager definition (queue, channel, rule, authority record...) as DISPLAY shows
 * it, and the MQSC command that defines it again, like dmpmqcfg. The attributes that only describe the
 * current state (dates, depth, open handles...) or cannot be set are left out, also from comparisons.
 */
public class MqscObject {
    /** QMGR, QLOCAL, QMODEL, QALIAS, QREMOTE, CHANNEL, LISTENER, SERVICE, PROCESS, NAMELIST, AUTHINFO, COMMINFO, TOPIC, SUB, CHLAUTH, AUTHREC. */
    public final String Kind;
    /** Name of the object; for a rule or an authority record what identifies it. */
    public final String Name;
    /** Attributes that define the object; a flag without value (e.g. NOTRIGGER) has a null value. */
    public final Map<String, String> Attributes;

    private static final Set<String> STATE = new HashSet<>(Arrays.asList(
            "ALTDATE", "ALTTIME", "CRDATE", "CRTIME", "CURDEPTH", "IPPROCS", "OPPROCS", "QMID", "CLUSDATE",
            "CLUSTIME", "CLUSQMGR", "CLUSQT", "NAMCOUNT", "RESETSEQ"));
    // Shown by DISPLAY QMGR but not set with ALTER QMGR
    private static final Set<String> QMGR_FIXED = new HashSet<>(Arrays.asList(
            "QMNAME", "CMDLEVEL", "COMMANDQ", "DISTL", "MAXPRTY", "PLATFORM", "SYNCPT", "VERSION", "CPILEVEL",
            "ADVCAP", "SPLCAP", "AMQPCAP", "XRCAP", "QSGNAME", "QSGCERTL", "INITKEY"));
    private static final Set<String> SUB_FIXED = new HashSet<>(Arrays.asList(
            "SUBID", "DISTYPE", "DURABLE", "SUBTYPE", "SUBUSER", "SELTYPE"));
    // Values the queue manager never shows (masked): they are not exported
    private static final Set<String> PASSWORDS = new HashSet<>(Arrays.asList(
            "PASSWORD", "KEYRPWD", "LDAPPWD"));
    // Values that are lists of names
    private static final Set<String> LISTS = new HashSet<>(Arrays.asList(
            "NAMES", "MSGEXIT", "MSGDATA", "SENDEXIT", "SENDDATA", "RCVEXIT", "RCVDATA", "USERLIST", "ADDRLIST",
            "CLUSNL"));

    // Values that are lists of keywords: never quoted
    private static final Set<String> KEYWORD_LISTS = new HashSet<>(Arrays.asList("PROTOCOL"));
    // Blank values that cannot be set: a system topic has no topic string, a selector cannot change
    private static final Set<String> NOT_BLANK = new HashSet<>(Arrays.asList("TOPICSTR", "SELECTOR"));

    public MqscObject(String kind, String name, Map<String, String> attributes) {
        this.Kind = kind;
        this.Name = name;
        this.Attributes = attributes;
    }

    /** Key that is the same for the same object on two queue managers. */
    public String Key() {
        return Kind + " " + Name;
    }

    /** Readable kind: "Local queue", "Channel", ... */
    public String KindText() {
        switch (Kind) {
            case "QMGR": return "Queue manager";
            case "QLOCAL": return "Local queue";
            case "QMODEL": return "Model queue";
            case "QALIAS": return "Alias queue";
            case "QREMOTE": return "Remote queue";
            case "CHANNEL": return "Channel";
            case "LISTENER": return "Listener";
            case "SERVICE": return "Service";
            case "PROCESS": return "Process";
            case "NAMELIST": return "Namelist";
            case "AUTHINFO": return "Authentication information";
            case "COMMINFO": return "Communication information";
            case "TOPIC": return "Topic";
            case "SUB": return "Subscription";
            case "CHLAUTH": return "Channel authentication rule";
            case "AUTHREC": return "Authority record";
            default: return Kind;
        }
    }

    /**
     * Builds the object from the text of one DISPLAY response (without the AMQ message line).
     * Returns null for what is not exported (cluster topics, subscriptions made by applications,
     * authority records without authorities).
     */
    public static MqscObject FromDisplay(String kind, String text) {
        LinkedHashMap<String, String> all = Parse(text);
        if (all.isEmpty()) {
            return null;
        }
        String name;
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        switch (kind) {
            case "QMGR":
                name = "";
                break;
            case "SUB":
                if (!"ADMIN".equals(all.get("SUBTYPE"))) {
                    return null;
                }
                name = all.get("SUB");
                break;
            case "QLOCAL":
                // Temporary dynamic queues end with the application that opened them
                if ("TEMPDYN".equals(all.get("DEFTYPE"))) {
                    return null;
                }
                name = all.get("QUEUE");
                break;
            case "TOPIC":
                if ("CLUSTER".equals(all.get("TYPE"))) {
                    return null;
                }
                name = all.get("TOPIC");
                break;
            case "CHLAUTH":
                name = all.get("CHLAUTH") + " " + all.get("TYPE") + chlauthQualifier(all);
                break;
            case "AUTHREC":
                if (all.get("AUTHLIST") == null || all.get("AUTHLIST").isEmpty() || "NONE".equals(all.get("AUTHLIST"))) {
                    return null;
                }
                if (all.get("PROFILE") != null && all.get("PROFILE").matches("AMQ\\.[0-9A-F]{16}")) {
                    // Profile of a temporary dynamic queue
                    return null;
                }
                name = all.get("OBJTYPE") + " " + all.get("PROFILE") + " " + all.get("ENTTYPE") + " " + all.get("ENTITY");
                break;
            default:
                name = all.get(NameKey(kind));
                break;
        }
        if (name == null) {
            return null;
        }
        if (kind.equals("AUTHREC")) {
            // Object, profile and entity are in the name
            attributes.put("AUTHLIST", all.get("AUTHLIST"));
            return new MqscObject(kind, name, attributes);
        }
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.equals(NameKey(kind)) || STATE.contains(key) || PASSWORDS.contains(key)) {
                continue;
            }
            if (kind.startsWith("Q") && !kind.equals("QMGR") && (key.equals("TYPE") || (key.equals("DEFTYPE") && !kind.equals("QMODEL")))) {
                continue;
            }
            if ((kind.equals("QMGR") && QMGR_FIXED.contains(key)) || (kind.equals("SUB") && SUB_FIXED.contains(key))) {
                continue;
            }
            if (NOT_BLANK.contains(key) && entry.getValue() != null && entry.getValue().isBlank()) {
                continue;
            }
            attributes.put(key, entry.getValue());
        }
        return new MqscObject(kind, name, attributes);
    }

    /** Attribute of the DISPLAY response that has the name of the object. */
    static String NameKey(String kind) {
        return kind.startsWith("Q") && !kind.equals("QMGR") ? "QUEUE" : kind;
    }

    private static String chlauthQualifier(Map<String, String> all) {
        for (String key : new String[]{"ADDRESS", "CLNTUSER", "SSLPEER", "QMNAME", "USERLIST", "ADDRLIST", "SSLCERTI"}) {
            String value = all.get(key);
            if (value != null && !value.isBlank()) {
                return " " + key + "(" + value + ")";
            }
        }
        return "";
    }

    /** The MQSC command that defines the object as it is. */
    public String Command() {
        StringBuilder command = new StringBuilder();
        Map<String, String> attributes = new LinkedHashMap<>(Attributes);
        switch (Kind) {
            case "QMGR":
                command.append("ALTER QMGR");
                break;
            case "CHLAUTH": {
                String profile = Name.substring(0, Name.indexOf(' '));
                command.append("SET CHLAUTH(").append(Quote(profile)).append(")");
                // Blank values are the defaults: ACTION(REPLACE) replaces the whole rule
                attributes.values().removeIf(value -> value != null && value.isBlank());
                break;
            }
            case "AUTHREC": {
                String[] parts = Name.split(" ", 4);
                command.append("SET AUTHREC");
                // The queue manager itself has no profile ("self"); its @class record has one
                if (!(parts[0].equals("QMGR") && parts[1].equals("self"))) {
                    command.append(" PROFILE(").append(Quote(parts[1])).append(")");
                }
                command.append(" OBJTYPE(").append(parts[0]).append(")");
                command.append(parts[2].equals("GROUP") ? " GROUP(" : " PRINCIPAL(").append(Quote(parts[3])).append(")");
                command.append(" AUTHADD(").append(attributes.get("AUTHLIST")).append(")");
                return command.toString();
            }
            default:
                if (Kind.equals("TOPIC") && !attributes.containsKey("TOPICSTR")) {
                    // A system topic without topic string (SYSTEM.BASE.TOPIC): it always exists, only changed
                    attributes.remove("TYPE");
                    command.append("ALTER TOPIC(").append(Quote(Name)).append(")");
                    break;
                }
                command.append("DEFINE ").append(Kind).append("(").append(Quote(Name)).append(")");
                // CHLTYPE, AUTHTYPE and the TRPTYPE of a listener must follow the name
                for (String key : Kind.equals("LISTENER") ? new String[]{"TRPTYPE"} : new String[]{"CHLTYPE", "AUTHTYPE"}) {
                    if (attributes.containsKey(key)) {
                        command.append(" ").append(key).append("(").append(attributes.remove(key)).append(")");
                    }
                }
                break;
        }
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            command.append(" ").append(entry.getKey());
            if (entry.getValue() != null) {
                command.append("(").append(Value(entry.getKey(), entry.getValue())).append(")");
            }
        }
        if (Kind.equals("CHLAUTH")) {
            command.append(" ACTION(REPLACE)");
        } else if (!Kind.equals("QMGR") && !command.toString().startsWith("ALTER")) {
            command.append(" REPLACE");
        }
        return command.toString();
    }

    /** The value as MQSC wants it: lists with each name quoted, text quoted when needed. */
    static String Value(String key, String value) {
        if (KEYWORD_LISTS.contains(key)) {
            return value;
        }
        if (LISTS.contains(key) && !value.isBlank()) {
            List<String> items = new ArrayList<>();
            for (String item : value.split(",")) {
                items.add(Quote(item.trim()));
            }
            return String.join(",", items);
        }
        return Quote(value);
    }

    /**
     * Quoted unless MQSC reads it the same way without quotes: keywords, numbers and upper case names.
     * Without quotes MQSC folds the text to upper case.
     */
    public static String Quote(String value) {
        if (value.isBlank()) {
            return "' '";
        }
        if (value.matches("[A-Z0-9_.%/]+")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Attributes of a DISPLAY response: KEY(VALUE) and flags without value. A value can contain
     * parentheses (e.g. CONNAME(host(1414))) and, for lists, span several lines.
     */
    public static LinkedHashMap<String, String> Parse(String text) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        // The first line is the AMQ message ("AMQ8409I: Display Queue details.")
        if (text.startsWith("AMQ")) {
            int end = text.indexOf('\n');
            i = end < 0 ? n : end + 1;
        }
        while (i < n) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            String key = text.substring(start, i).toUpperCase(Locale.ROOT);
            if (i < n && text.charAt(i) == '(') {
                int depth = 0;
                int valueStart = i + 1;
                int j = i;
                // The value ends at the parenthesis that closes it and is followed by a space or the end
                for (; j < n; j++) {
                    char d = text.charAt(j);
                    if (d == '(') {
                        depth++;
                    } else if (d == ')') {
                        depth--;
                        boolean atEnd = j + 1 >= n || Character.isWhitespace(text.charAt(j + 1));
                        if (depth <= 0 && atEnd) {
                            break;
                        }
                    }
                }
                String value = text.substring(valueStart, Math.min(j, n));
                attributes.put(key, normalize(value));
                i = j + 1;
            } else {
                attributes.put(key, null);
            }
        }
        return attributes;
    }

    // Lists shown on several lines ("Q1   \n   ,Q2") become "Q1,Q2"; the padding of values is removed
    private static String normalize(String value) {
        if (value.indexOf('\n') >= 0) {
            List<String> items = new ArrayList<>();
            for (String item : value.split("\\s*\\n\\s*,|,\\s*\\n\\s*")) {
                items.add(item.trim());
            }
            return String.join(",", items).trim();
        }
        return value.isBlank() ? "" : value.stripTrailing();
    }
}
