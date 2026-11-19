package MQApi.Config;

import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authority records of the object authority manager (setmqaut / SET AUTHREC): who can do what on
 * which objects. Read and changed with MQSC commands on the command server.
 */
public final class AuthorityRecords {

    /** One record: a profile (object name or generic profile like APP.**), a group or principal, its authorities. */
    public static class Record {
        public String Profile;
        public String ObjectType;
        public String Entity;
        /** GROUP or PRINCIPAL. */
        public String EntityType;
        public List<String> Authorities = new ArrayList<>();

        /** The part of SET/DELETE AUTHREC that identifies the record. */
        String Target() {
            StringBuilder target = new StringBuilder();
            if (!(ObjectType.equals("QMGR") && (Profile == null || Profile.isEmpty() || Profile.equals("self")))) {
                target.append(" PROFILE(").append(MqscObject.Quote(Profile)).append(")");
            }
            target.append(" OBJTYPE(").append(ObjectType).append(")");
            target.append(EntityType.equals("GROUP") ? " GROUP(" : " PRINCIPAL(").append(MqscObject.Quote(Entity)).append(")");
            return target.toString();
        }
    }

    /** The authorities that apply to each object type, as MQSC names them. */
    public static final Map<String, List<String>> AUTHORITIES = new LinkedHashMap<>();

    static {
        AUTHORITIES.put("QUEUE", Arrays.asList("BROWSE", "GET", "PUT", "INQ", "SET", "DSP", "CHG", "CLR", "DLT",
                "PASSALL", "PASSID", "SETALL", "SETID"));
        AUTHORITIES.put("QMGR", Arrays.asList("CONNECT", "INQ", "DSP", "SET", "CHG", "DLT", "CTRL", "SYSTEM",
                "ALTUSR", "SETALL", "SETID", "CRT"));
        AUTHORITIES.put("TOPIC", Arrays.asList("PUB", "SUB", "RESUME", "DSP", "CHG", "CLR", "CTRL", "DLT",
                "PASSALL", "PASSID", "SETALL", "SETID"));
        AUTHORITIES.put("CHANNEL", Arrays.asList("DSP", "CHG", "CTRL", "CTRLX", "DLT"));
        AUTHORITIES.put("CLNTCONN", Arrays.asList("DSP", "CHG", "DLT"));
        AUTHORITIES.put("LISTENER", Arrays.asList("DSP", "CHG", "CTRL", "DLT"));
        AUTHORITIES.put("SERVICE", Arrays.asList("DSP", "CHG", "CTRL", "DLT"));
        AUTHORITIES.put("PROCESS", Arrays.asList("INQ", "DSP", "CHG", "DLT"));
        AUTHORITIES.put("NAMELIST", Arrays.asList("INQ", "DSP", "CHG", "DLT"));
        AUTHORITIES.put("AUTHINFO", Arrays.asList("INQ", "DSP", "CHG", "DLT"));
        AUTHORITIES.put("COMMINFO", Arrays.asList("DSP", "CHG", "DLT"));
        AUTHORITIES.put("RQMNAME", Arrays.asList("PUT"));
    }

    /** What an authority allows, for the tool tips. */
    public static String Description(String authority) {
        switch (authority) {
            case "BROWSE": return "Browse messages";
            case "GET": return "Get messages";
            case "PUT": return "Put messages";
            case "INQ": return "Inquire attributes (MQINQ)";
            case "SET": return "Set attributes (MQSET)";
            case "DSP": return "Display the object (administration)";
            case "CHG": return "Change the object (administration)";
            case "CLR": return "Clear the queue or topic";
            case "DLT": return "Delete the object";
            case "CTRL": return "Start and stop (channels, listeners, services) or control the queue manager";
            case "CTRLX": return "Reset and resolve the channel";
            case "CONNECT": return "Connect to the queue manager";
            case "SYSTEM": return "Privileged operations of the queue manager";
            case "ALTUSR": return "Use another user id (alternate user)";
            case "PASSALL": return "Pass all context";
            case "PASSID": return "Pass identity context";
            case "SETALL": return "Set all context";
            case "SETID": return "Set identity context";
            case "CRT": return "Create objects of this type (@class profile)";
            case "PUB": return "Publish on the topic";
            case "SUB": return "Subscribe to the topic";
            case "RESUME": return "Resume a durable subscription";
            default: return authority;
        }
    }

    private AuthorityRecords() {
    }

    /**
     * The records; with a profile and an object type, the ones that apply to that object (also through
     * generic profiles), otherwise all of them.
     */
    public static List<Record> Read(MQQueueManager queueManager, String profile, String objectType) throws MQDataException, IOException {
        String command = "DISPLAY AUTHREC";
        if (objectType != null) {
            if (profile != null && !(objectType.equals("QMGR"))) {
                command += " PROFILE(" + MqscObject.Quote(profile) + ")";
            }
            command += " OBJTYPE(" + objectType + ")";
        }
        Mqsc.Result result = Mqsc.Run(queueManager, command);
        List<Record> records = new ArrayList<>();
        if (!result.Ok) {
            if (result.Texts.stream().anyMatch(text -> text.contains("not found"))) {
                return records;
            }
            throw new IOException(result.Error());
        }
        for (String text : result.Texts) {
            Map<String, String> attributes = MqscObject.Parse(text);
            if (attributes.get("PROFILE") == null) {
                continue;
            }
            Record record = new Record();
            record.Profile = attributes.get("PROFILE");
            record.ObjectType = attributes.get("OBJTYPE");
            record.Entity = attributes.get("ENTITY");
            record.EntityType = attributes.get("ENTTYPE");
            String list = attributes.get("AUTHLIST");
            if (list != null && !list.isBlank() && !list.equals("NONE")) {
                record.Authorities = new ArrayList<>(Arrays.asList(list.split(",")));
            }
            records.add(record);
        }
        return records;
    }

    /** The SET AUTHREC command that gives the record exactly these authorities. */
    public static String SetCommand(Record record, List<String> before) {
        List<String> add = new ArrayList<>(record.Authorities);
        List<String> remove = new ArrayList<>();
        for (String authority : before) {
            if (!record.Authorities.contains(authority)) {
                remove.add(authority);
            }
        }
        StringBuilder command = new StringBuilder("SET AUTHREC").append(record.Target());
        if (!add.isEmpty()) {
            command.append(" AUTHADD(").append(String.join(",", add)).append(")");
        }
        if (!remove.isEmpty()) {
            command.append(" AUTHRMV(").append(String.join(",", remove)).append(")");
        }
        return command.toString();
    }

    public static String DeleteCommand(Record record) {
        return "DELETE AUTHREC" + record.Target();
    }

    /** Runs the command; returns null when it worked, otherwise the error of the queue manager. */
    public static String Apply(MQQueueManager queueManager, String command) throws MQDataException, IOException {
        Mqsc.Result result = Mqsc.Run(queueManager, command);
        return result.Ok ? null : result.Error();
    }
}
