package MQApi.Overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import MQApi.Overview.HealthCheck.ActionKind;
import MQApi.Overview.HealthCheck.Severity;
import MQApi.Overview.QueueManagerOverview.QueueDepth;
import com.ibm.mq.constants.MQConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueManagerHealthTest {

    private static QueueManagerOverview healthyQueueManager() {
        QueueManagerOverview o = new QueueManagerOverview();
        o.Config.put(MQConstants.MQCA_DEAD_LETTER_Q_NAME, "DLQ");
        o.Config.put(MQConstants.MQIA_CHLAUTH_RECORDS, MQConstants.MQCHLA_ENABLED);
        o.Config.put(MQConstants.MQIA_AUTHORITY_EVENT, MQConstants.MQEVR_ENABLED);
        o.Status.put(MQConstants.MQIACF_CMD_SERVER_STATUS, MQConstants.MQSVC_STATUS_RUNNING);
        o.Status.put(MQConstants.MQIACF_CHINIT_STATUS, MQConstants.MQSVC_STATUS_RUNNING);
        o.Status.put(MQConstants.MQIACF_Q_MGR_FS_IN_USE, 40);
        o.Status.put(MQConstants.MQIACF_LOG_IN_USE, 20);
        o.Status.put(MQConstants.MQIACF_LOG_TYPE, MQConstants.MQLOGTYPE_CIRCULAR);
        o.LocalQueues = 3;
        o.DeadLetterQueue = new QueueDepth("DLQ", 0, 5000);
        o.ApplicationQueues.add(new QueueDepth("APP.A", 10, 5000));
        return o;
    }

    @Test
    void healthyQueueManagerHasOnlyPassedChecks() {
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(healthyQueueManager());
        assertTrue(checks.stream().allMatch(c -> c.Level == Severity.OK), () -> checks.toString());
        assertTrue(checks.size() >= 5);
    }

    @Test
    void queueThresholds() {
        QueueManagerOverview o = healthyQueueManager();
        o.ApplicationQueues.add(new QueueDepth("FULL", 10, 10));
        o.ApplicationQueues.add(new QueueDepth("ALMOST", 95, 100));
        o.ApplicationQueues.add(new QueueDepth("FILLING", 65, 100));
        o.ApplicationQueues.add(new QueueDepth("SIXTY", 60, 100));
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(o);
        HealthCheck full = find(checks, "FULL is full");
        assertEquals(Severity.PROBLEM, full.Level);
        assertEquals(ActionKind.BROWSE_QUEUE, full.Action);
        assertEquals("FULL", full.Target);
        assertEquals(Severity.PROBLEM, find(checks, "ALMOST is almost full").Level);
        assertEquals(Severity.WARNING, find(checks, "FILLING is filling up").Level);
        assertTrue(checks.stream().noneMatch(c -> c.Title.startsWith("SIXTY")), "60% is not above the threshold");
        // Problems come first
        assertEquals(Severity.PROBLEM, checks.get(0).Level);
    }

    @Test
    void stoppedServicesAndChannels() {
        QueueManagerOverview o = healthyQueueManager();
        o.Status.put(MQConstants.MQIACF_CHINIT_STATUS, MQConstants.MQSVC_STATUS_STOPPED);
        o.ChannelStatus.put("TO.REMOTE", MQConstants.MQCHS_RETRYING);
        o.ChannelStatus.put("CLIENTS", MQConstants.MQCHS_RUNNING);
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(o);
        assertEquals(Severity.PROBLEM, find(checks, "Not running: channel initiator (stopped)").Level);
        HealthCheck retrying = find(checks, "Channel retrying");
        assertEquals("TO.REMOTE", retrying.Detail);
    }

    @Test
    void deadLetterQueueAndDisks() {
        QueueManagerOverview o = healthyQueueManager();
        o.DeadLetterQueue = new QueueDepth("DLQ", 3, 5000);
        o.Status.put(MQConstants.MQIACF_Q_MGR_FS_IN_USE, 95);
        o.Status.put(MQConstants.MQIACF_LOG_FS_IN_USE, -1);
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(o);
        assertEquals(Severity.WARNING, find(checks, "3 messages in the dead-letter queue").Level);
        assertEquals(Severity.PROBLEM, find(checks, "Queue manager file system 95% used").Level);
        assertTrue(checks.stream().noneMatch(c -> c.Title.startsWith("Log file system")), "-1 means not reported");
    }

    @Test
    void missingDeadLetterQueueAndSecurityNotes() {
        QueueManagerOverview o = healthyQueueManager();
        o.Config.put(MQConstants.MQCA_DEAD_LETTER_Q_NAME, "  ");
        o.DeadLetterQueue = null;
        o.Config.put(MQConstants.MQIA_CHLAUTH_RECORDS, MQConstants.MQCHLA_DISABLED);
        o.Config.put(MQConstants.MQIA_AUTHORITY_EVENT, MQConstants.MQEVR_DISABLED);
        o.EventQueues.add(new QueueDepth("SYSTEM.ADMIN.QMGR.EVENT", 1, 3000));
        List<HealthCheck> checks = QueueManagerHealth.Evaluate(o);
        assertEquals(Severity.WARNING, find(checks, "No dead-letter queue").Level);
        assertEquals(Severity.WARNING, find(checks, "Channel authentication records disabled").Level);
        assertEquals(Severity.NOTE, find(checks, "Authority events are off").Level);
        assertEquals(Severity.NOTE, find(checks, "1 event to read").Level);
    }

    @Test
    void formatting() {
        assertEquals("10.0.1.0", MQFormat.Version("10000100"));
        assertEquals("9.4.5.1", MQFormat.Version("09040501"));
        assertEquals("Unix", MQFormat.Platform(MQConstants.MQPL_UNIX));
        assertEquals("Windows", MQFormat.Platform(MQConstants.MQPL_WINDOWS_NT));
        assertEquals("Enabled", MQFormat.Constant(MQConstants.MQEVR_ENABLED, "MQEVR_"));
        assertEquals("Circular", MQFormat.Constant(MQConstants.MQLOGTYPE_CIRCULAR, "MQLOGTYPE_"));
        assertEquals("none", MQFormat.Text("  "));
        assertEquals(2026, MQFormat.ParseDateTime("2026-11-10", "16.50.59").getYear());
        assertEquals(50, MQFormat.ParseDateTime("2026-11-10", "16:50:59").getMinute());
    }

    private static HealthCheck find(List<HealthCheck> checks, String title) {
        return checks.stream().filter(c -> c.Title.equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("No check \"" + title + "\" in " + titles(checks)));
    }

    private static String titles(List<HealthCheck> checks) {
        StringBuilder text = new StringBuilder();
        checks.forEach(c -> text.append(c.Level).append(": ").append(c.Title).append("; "));
        return text.toString();
    }
}
