package UI.Models;

import MQApi.Overview.MQFormat;
import MQApi.Overview.QueueManagerOverview;
import com.ibm.mq.constants.MQConstants;

/**
 * What the queue manager list shows of the last overview of a queue manager: its version.
 */
public class QueueManagerSummary {
    /** Major and minor version, like "9.4"; null when not known. */
    public String Version;

    public static QueueManagerSummary From(QueueManagerOverview overview) {
        QueueManagerSummary summary = new QueueManagerSummary();
        summary.Version = ShortVersion(MQFormat.Version(overview.ConfigText(MQConstants.MQCA_VERSION)));
        return summary;
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
