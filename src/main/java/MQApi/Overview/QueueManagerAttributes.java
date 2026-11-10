package MQApi.Overview;

import static com.ibm.mq.constants.MQConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Readable queue manager attributes, grouped as in the properties dialog.
 * Each attribute has a label, its MQSC keyword and how to show its value.
 */
public class QueueManagerAttributes {

    public static class Attribute {
        public final String Label;
        public final String Mqsc;
        private final Function<QueueManagerOverview, String> value;

        Attribute(String label, String mqsc, Function<QueueManagerOverview, String> value) {
            this.Label = label;
            this.Mqsc = mqsc;
            this.value = value;
        }

        /** Null when the queue manager did not return the attribute. */
        public String Value(QueueManagerOverview overview) {
            try {
                return value.apply(overview);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    public static class Section {
        public final String Title;
        public final List<Attribute> Attributes = new ArrayList<>();

        Section(String title) {
            this.Title = title;
        }

        Section text(String label, String mqsc, int id) {
            Attributes.add(new Attribute(label, mqsc, o -> o.Config.containsKey(id) ? MQFormat.Text(o.ConfigText(id)) : null));
            return this;
        }

        Section number(String label, String mqsc, int id, String unit) {
            Attributes.add(new Attribute(label, mqsc, o -> o.ConfigInt(id) == null ? null : MQFormat.Number(o.ConfigInt(id)) + unitText(unit)));
            return this;
        }

        Section constant(String label, String mqsc, int id, String prefix) {
            Attributes.add(new Attribute(label, mqsc, o -> o.ConfigInt(id) == null ? null : MQFormat.Constant(o.ConfigInt(id), prefix)));
            return this;
        }

        Section statusText(String label, String mqsc, int id) {
            Attributes.add(new Attribute(label, mqsc, o -> o.Status.containsKey(id) ? MQFormat.Text(o.StatusText(id)) : null));
            return this;
        }

        Section statusNumber(String label, String mqsc, int id, String unit) {
            Attributes.add(new Attribute(label, mqsc, o -> {
                Integer v = o.StatusInt(id);
                return v == null || v < 0 ? null : MQFormat.Number(v) + unitText(unit);
            }));
            return this;
        }

        Section statusConstant(String label, String mqsc, int id, String prefix) {
            Attributes.add(new Attribute(label, mqsc, o -> o.StatusInt(id) == null ? null : MQFormat.Constant(o.StatusInt(id), prefix)));
            return this;
        }

        private static String unitText(String unit) {
            return unit == null ? "" : "%".equals(unit) ? unit : " " + unit;
        }

        Section custom(String label, String mqsc, Function<QueueManagerOverview, String> value) {
            Attributes.add(new Attribute(label, mqsc, value));
            return this;
        }
    }

    public static List<Section> Configuration() {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("General")
                .text("Name", "QMNAME", MQCA_Q_MGR_NAME)
                .text("Description", "DESCR", MQCA_Q_MGR_DESC)
                .custom("Platform", "PLATFORM", o -> o.ConfigInt(MQIA_PLATFORM) == null ? null : MQFormat.Platform(o.ConfigInt(MQIA_PLATFORM)))
                .custom("Version", "VERSION", o -> o.ConfigText(MQCA_VERSION) == null ? null : "IBM MQ " + MQFormat.Version(o.ConfigText(MQCA_VERSION)))
                .custom("Command level", "CMDLEVEL", o -> o.ConfigInt(MQIA_COMMAND_LEVEL) == null ? null : String.valueOf(o.ConfigInt(MQIA_COMMAND_LEVEL)))
                .custom("Coded character set", "CCSID", o -> o.ConfigInt(MQIA_CODED_CHAR_SET_ID) == null ? null
                        : o.ConfigInt(MQIA_CODED_CHAR_SET_ID) + (o.ConfigInt(MQIA_CODED_CHAR_SET_ID) == 1208 ? " (UTF-8)" : ""))
                .text("Queue manager ID", "QMID", MQCA_Q_MGR_IDENTIFIER)
                .custom("Created", "CRDATE, CRTIME", o -> o.ConfigText(MQCA_CREATION_DATE) == null ? null
                        : MQFormat.DateTime(o.ConfigText(MQCA_CREATION_DATE), o.ConfigText(MQCA_CREATION_TIME)))
                .custom("Last altered", "ALTDATE, ALTTIME", o -> o.ConfigText(MQCA_ALTERATION_DATE) == null ? null
                        : MQFormat.DateTime(o.ConfigText(MQCA_ALTERATION_DATE), o.ConfigText(MQCA_ALTERATION_TIME))));
        sections.add(new Section("Messaging")
                .text("Dead-letter queue", "DEADQ", MQCA_DEAD_LETTER_Q_NAME)
                .text("Default transmission queue", "DEFXMITQ", MQCA_DEF_XMIT_Q_NAME)
                .custom("Max message length", "MAXMSGL", o -> o.ConfigInt(MQIA_MAX_MSG_LENGTH) == null ? null : MQFormat.Bytes(o.ConfigInt(MQIA_MAX_MSG_LENGTH)))
                .number("Max priority", "MAXPRTY", MQIA_MAX_PRIORITY, null)
                .number("Max uncommitted messages", "MAXUMSGS", MQIA_MAX_UNCOMMITTED_MSGS, null)
                .number("Max handles per connection", "MAXHANDS", MQIA_MAX_HANDLES, null)
                .text("Command queue", "COMMANDQ", MQCA_COMMAND_INPUT_Q_NAME)
                .number("Trigger interval", "TRIGINT", MQIA_TRIGGER_INTERVAL, "ms")
                .constant("Distribution lists", "DISTL", MQIA_DIST_LISTS, "MQDL_")
                .number("Browse mark interval", "MARKINT", MQIA_MSG_MARK_BROWSE_INTERVAL, "ms"));
        sections.add(new Section("Publish/subscribe and cluster")
                .constant("Publish/subscribe engine", "PSMODE", MQIA_PUBSUB_MODE, "MQPSM_")
                .constant("Undeliverable non-persistent", "PSNPMSG", MQIA_PUBSUB_NP_MSG, "MQUNDELIVERED_")
                .number("Retry count", "PSRTYCNT", MQIA_PUBSUB_MAXMSG_RETRY_COUNT, null)
                .number("Topic tree lifetime", "TREELIFE", MQIA_TREE_LIFE_TIME, "s")
                .text("Cluster repository", "REPOS", MQCA_REPOSITORY_NAME)
                .text("Cluster repository namelist", "REPOSNL", MQCA_REPOSITORY_NAMELIST)
                .custom("Default cluster transmission queue", "DEFCLXQ", o -> o.ConfigInt(MQIA_DEF_CLUSTER_XMIT_Q_TYPE) == null ? null
                        : o.ConfigInt(MQIA_DEF_CLUSTER_XMIT_Q_TYPE) == MQCLXQ_SCTQ ? "Shared (SYSTEM.CLUSTER.TRANSMIT.QUEUE)" : "One per channel")
                .constant("Use local queue for cluster workload", "CLWLUSEQ", MQIA_CLWL_USEQ, "MQCLWL_USEQ_"));
        sections.add(new Section("Monitoring and statistics")
                .constant("Queue monitoring", "MONQ", MQIA_MONITORING_Q, "MQMON_")
                .constant("Channel monitoring", "MONCHL", MQIA_MONITORING_CHANNEL, "MQMON_")
                .constant("MQI statistics", "STATMQI", MQIA_STATISTICS_MQI, "MQMON_")
                .constant("Queue statistics", "STATQ", MQIA_STATISTICS_Q, "MQMON_")
                .constant("Channel statistics", "STATCHL", MQIA_STATISTICS_CHANNEL, "MQMON_")
                .number("Statistics interval", "STATINT", MQIA_STATISTICS_INTERVAL, "s")
                .constant("MQI accounting", "ACCTMQI", MQIA_ACCOUNTING_MQI, "MQMON_")
                .constant("Queue accounting", "ACCTQ", MQIA_ACCOUNTING_Q, "MQMON_")
                .number("Accounting interval", "ACCTINT", MQIA_ACCOUNTING_INTERVAL, "s")
                .constant("Activity recording", "ACTIVREC", MQIA_ACTIVITY_RECORDING, "MQRECORDING_")
                .constant("Trace-route recording", "ROUTEREC", MQIA_TRACE_ROUTE_RECORDING, "MQRECORDING_"));
        sections.add(new Section("Security")
                .custom("Channel authentication", "CHLAUTH", o -> o.ConfigInt(MQIA_CHLAUTH_RECORDS) == null ? null
                        : MQFormat.Constant(o.ConfigInt(MQIA_CHLAUTH_RECORDS), "MQCHLA_") + (o.ChannelAuthRules >= 0 ? " · " + o.ChannelAuthRules + " rules" : ""))
                .text("Connection authentication", "CONNAUTH", MQCA_CONN_AUTH)
                .text("TLS key repository", "SSLKEYR", MQCA_SSL_KEY_REPOSITORY)
                .text("Certificate label", "CERTLABL", MQCA_CERT_LABEL)
                .constant("FIPS required", "SSLFIPS", MQIA_SSL_FIPS_REQUIRED, "MQSSL_FIPS_")
                .constant("Reverse DNS lookup", "REVDNS", MQIA_REVERSE_DNS_LOOKUP, "MQRDNS_")
                .constant("Channel auto-definition", "CHAD", MQIA_CHANNEL_AUTO_DEF, "MQCHAD_"));
        return sections;
    }

    public static List<Section> Events() {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Events written to the SYSTEM.ADMIN.*.EVENT queues")
                .constant("Authority", "AUTHOREV", MQIA_AUTHORITY_EVENT, "MQEVR_")
                .constant("Inhibit", "INHIBTEV", MQIA_INHIBIT_EVENT, "MQEVR_")
                .constant("Local", "LOCALEV", MQIA_LOCAL_EVENT, "MQEVR_")
                .constant("Remote", "REMOTEEV", MQIA_REMOTE_EVENT, "MQEVR_")
                .constant("Start and stop", "STRSTPEV", MQIA_START_STOP_EVENT, "MQEVR_")
                .constant("Performance", "PERFMEV", MQIA_PERFORMANCE_EVENT, "MQEVR_")
                .constant("Channel", "CHLEV", MQIA_CHANNEL_EVENT, "MQEVR_")
                .constant("TLS", "SSLEV", MQIA_SSL_EVENT, "MQEVR_")
                .constant("Channel auto-definition", "CHADEV", MQIA_CHANNEL_AUTO_DEF_EVENT, "MQEVR_")
                .constant("Configuration", "CONFIGEV", MQIA_CONFIGURATION_EVENT, "MQEVR_")
                .constant("Command", "CMDEV", MQIA_COMMAND_EVENT, "MQEVR_")
                .constant("Logger", "LOGGEREV", MQIA_LOGGER_EVENT, "MQEVR_"));
        return sections;
    }

    public static List<Section> Status() {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Queue manager")
                .statusConstant("Status", "STATUS", MQIACF_Q_MGR_STATUS, "MQQMSTA_")
                .custom("Started", "STARTDA, STARTTI", o -> o.StatusText(MQCACF_Q_MGR_START_DATE) == null ? null
                        : MQFormat.DateTime(o.StatusText(MQCACF_Q_MGR_START_DATE), o.StatusText(MQCACF_Q_MGR_START_TIME)))
                .statusNumber("Connections", "CONNS", MQIACF_CONNECTION_COUNT, null)
                .statusConstant("Command server", "CMDSERV", MQIACF_CMD_SERVER_STATUS, "MQSVC_STATUS_")
                .statusConstant("Channel initiator", "CHINIT", MQIACF_CHINIT_STATUS, "MQSVC_STATUS_")
                .statusText("Host", "HOSTNAME", MQCACF_HOST_NAME)
                .statusConstant("Standby instance", "STANDBY", MQIACF_PERMIT_STANDBY, "MQSTDBY_"));
        sections.add(new Section("Installation")
                .statusText("Name", "INSTNAME", MQCA_INSTALLATION_NAME)
                .statusText("Path", "INSTPATH", MQCA_INSTALLATION_PATH)
                .statusText("Description", "INSTDESC", MQCA_INSTALLATION_DESC));
        sections.add(new Section("Recovery log")
                .statusConstant("Type", "LOGTYPE", MQIACF_LOG_TYPE, "MQLOGTYPE_")
                .statusNumber("In use", "LOGINUSE", MQIACF_LOG_IN_USE, "%")
                .statusNumber("Utilization", "LOGUTIL", MQIACF_LOG_UTILIZATION, "%")
                .statusNumber("Primary extents", "LOGPRIM", MQIACF_LOG_PRIMARIES, null)
                .statusNumber("Secondary extents", "LOGSEC", MQIACF_LOG_SECONDARIES, null)
                .statusNumber("Extent size", "LOGEXTSZ", MQIACF_LOG_EXTENT_SIZE, "pages")
                .statusText("Path", "LOGPATH", MQCACF_LOG_PATH)
                .custom("Log started", "LOGSTRDA, LOGSTRTI", o -> o.StatusText(MQCACF_LOG_START_DATE) == null ? null
                        : MQFormat.DateTime(o.StatusText(MQCACF_LOG_START_DATE), o.StatusText(MQCACF_LOG_START_TIME))));
        sections.add(new Section("File systems")
                .statusText("Queue manager data", "DATPATH", MQCACF_Q_MGR_DATA_PATH)
                .statusNumber("Queue manager file system in use", "QMFSUSE", MQIACF_Q_MGR_FS_IN_USE, "%")
                .statusNumber("Queue manager file system size", "QMFSSZ", MQIACF_Q_MGR_FS_SIZE, "MB")
                .custom("Log file system in use", "LOGFSUSE", o -> o.StatusInt(MQIACF_LOG_FS_IN_USE) == null ? null
                        : o.StatusInt(MQIACF_LOG_FS_IN_USE) < 0 ? "Shared with the queue manager data" : o.StatusInt(MQIACF_LOG_FS_IN_USE) + "%"));
        return sections;
    }
}
