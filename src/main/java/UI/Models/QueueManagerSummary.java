package UI.Models;

import MQApi.Overview.HealthCheck;
import MQApi.Overview.HealthCheck.Severity;
import MQApi.Overview.MQFormat;
import MQApi.Overview.QueueManagerHealth;
import MQApi.Overview.QueueManagerOverview;
import com.ibm.mq.constants.MQConstants;
import java.util.List;

/**
 * What the queue manager list shows of the last overview of a queue manager: version, number of
 * checks that need attention and the object counts of its folders.
 */
public class QueueManagerSummary {
    /** Major and minor version, like "9.4"; null when not known. */
    public String Version;
    public int Problems;
    public int Warnings;
    /** -1 when not known. */
    public int LocalQueues = -1;
    public int Channels = -1;
    public int ChannelAuthRules = -1;
    public int StoppedChannels;
    public int RetryingChannels;

    public static QueueManagerSummary From(QueueManagerOverview overview) {
        QueueManagerSummary summary = new QueueManagerSummary();
        summary.Version = ShortVersion(MQFormat.Version(overview.ConfigText(MQConstants.MQCA_VERSION)));
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(overview);
        summary.Problems = (int) QueueManagerHealth.Count(checks, Severity.PROBLEM);
        summary.Warnings = (int) QueueManagerHealth.Count(checks, Severity.WARNING);
        summary.LocalQueues = overview.LocalQueues;
        summary.Channels = overview.DefinedChannels;
        summary.ChannelAuthRules = overview.ChannelAuthRules;
        for (int status : overview.ChannelStatus.values()) {
            if (status == MQConstants.MQCHS_STOPPED) {
                summary.StoppedChannels++;
            } else if (status == MQConstants.MQCHS_RETRYING) {
                summary.RetryingChannels++;
            }
        }
        return summary;
    }

    public int Attention() {
        return Problems + Warnings;
    }

    /** "9.4.0.0" becomes "9.4". */
    static String ShortVersion(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }
}
