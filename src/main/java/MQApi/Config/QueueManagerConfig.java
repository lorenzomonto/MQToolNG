package MQApi.Config;

import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * The definitions of a queue manager, read with MQSC DISPLAY commands sent to its command server
 * (PCF Escape, as runmqsc -c does), and the MQSC script that defines them again, like dmpmqcfg.
 * Works for queue managers administered through another one too; not on z/OS (no Escape there).
 */
public class QueueManagerConfig {
    /** What is read, in the order of the script: what an object refers to comes before it. */
    static final String[][] DISPLAYS = {
        {"QMGR", "DISPLAY QMGR ALL"},
        {"AUTHINFO", "DISPLAY AUTHINFO(*) ALL"},
        {"PROCESS", "DISPLAY PROCESS(*) ALL"},
        {"NAMELIST", "DISPLAY NAMELIST(*) ALL"},
        {"QLOCAL", "DISPLAY QLOCAL(*) ALL"},
        {"QMODEL", "DISPLAY QMODEL(*) ALL"},
        {"QALIAS", "DISPLAY QALIAS(*) ALL"},
        {"QREMOTE", "DISPLAY QREMOTE(*) ALL"},
        {"TOPIC", "DISPLAY TOPIC(*) ALL"},
        {"COMMINFO", "DISPLAY COMMINFO(*) ALL"},
        {"CHANNEL", "DISPLAY CHANNEL(*) ALL"},
        {"LISTENER", "DISPLAY LISTENER(*) ALL"},
        {"SERVICE", "DISPLAY SERVICE(*) ALL"},
        {"SUB", "DISPLAY SUB(*) ALL"},
        {"CHLAUTH", "DISPLAY CHLAUTH(*) ALL"},
        {"AUTHREC", "DISPLAY AUTHREC"},
    };

    public final String QueueManagerName;
    public final List<MqscObject> Objects = new ArrayList<>();
    /** Commands that could not be read, with the reason. */
    public final List<String> Problems = new ArrayList<>();

    public QueueManagerConfig(String queueManagerName) {
        this.QueueManagerName = queueManagerName;
    }

    /** Reads all the definitions; progress receives the kind being read. */
    public static QueueManagerConfig Read(MQQueueManager queueManager, String queueManagerName, Consumer<String> progress) throws MQDataException, IOException {
        QueueManagerConfig config = new QueueManagerConfig(queueManagerName);
        for (String[] display : DISPLAYS) {
            if (progress != null) {
                progress.accept(display[0]);
            }
            Mqsc.Result result = Mqsc.Run(queueManager, display[1]);
            if (!result.Ok) {
                // "AMQ8147E: IBM MQ object * not found.": none of this kind
                if (result.Texts.stream().noneMatch(text -> text.contains("not found"))) {
                    config.Problems.add(display[1] + ": " + result.Error());
                }
                continue;
            }
            for (String text : result.Texts) {
                MqscObject object = MqscObject.FromDisplay(display[0], text);
                if (object != null) {
                    config.Objects.add(object);
                }
            }
        }
        return config;
    }

    /** True for objects of the queue manager itself (SYSTEM.*), which are left out unless asked for. */
    public static boolean IsSystem(MqscObject object) {
        switch (object.Kind) {
            case "QMGR":
                return false;
            case "AUTHREC": {
                String profile = object.Name.split(" ", 3)[1];
                return profile.startsWith("SYSTEM.");
            }
            default:
                return object.Name.startsWith("SYSTEM.");
        }
    }

    /** The objects to export or compare. */
    public List<MqscObject> Selected(boolean includeSystem, boolean includeAuthorities) {
        List<MqscObject> selected = new ArrayList<>();
        for (MqscObject object : Objects) {
            if ((includeSystem || !IsSystem(object)) && (includeAuthorities || !object.Kind.equals("AUTHREC"))) {
                selected.add(object);
            }
        }
        return selected;
    }

    /** MQSC script that defines the selected objects again (for runmqsc), one attribute per line. */
    public String Script(boolean includeSystem, boolean includeAuthorities, String program) {
        StringBuilder script = new StringBuilder();
        script.append("* Definitions of queue manager ").append(QueueManagerName).append('\n');
        script.append("* Exported by ").append(program).append(" on ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        script.append("* Run with: runmqsc <queue manager> < file").append('\n');
        script.append("* Passwords (PASSWORD, KEYRPWD, LDAPPWD) are not exported").append('\n');
        if (!includeSystem) {
            script.append("* SYSTEM.* objects are not included").append('\n');
        }
        for (String problem : Problems) {
            script.append("* Not read: ").append(problem).append('\n');
        }
        String kind = null;
        for (MqscObject object : Selected(includeSystem, includeAuthorities)) {
            if (!object.Kind.equals(kind)) {
                kind = object.Kind;
                script.append('\n').append("* ").append(object.KindText()).append('\n');
            }
            script.append(Wrap(object.Command())).append('\n');
        }
        return script.toString();
    }

    /**
     * One attribute per line with the MQSC continuation character: "DEFINE QLOCAL('A') +\n   DESCR('x') +\n   REPLACE".
     * Lines are split only between attributes, never inside a quoted value.
     */
    public static String Wrap(String command) {
        StringBuilder wrapped = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        int wordStart = 0;
        List<String> words = new ArrayList<>();
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ' ') {
                words.add(command.substring(wordStart, i));
                wordStart = i + 1;
            }
        }
        words.add(command.substring(wordStart));
        // The verb and the name stay together: "DEFINE QLOCAL('A')", "SET CHLAUTH('X')"
        wrapped.append(words.get(0)).append(' ').append(words.size() > 1 ? words.get(1) : "");
        for (int i = 2; i < words.size(); i++) {
            wrapped.append(" +\n   ").append(words.get(i));
        }
        return wrapped.toString().trim();
    }
}
