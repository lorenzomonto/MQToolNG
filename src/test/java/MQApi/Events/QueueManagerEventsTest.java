package MQApi.Events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import MQApi.Events.QueueManagerEvents.Event;
import MQApi.Events.QueueManagerEvents.Kind;
import MQApi.Events.QueueManagerEvents.Level;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.PCFMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Event messages as IBM MQ 10.0 writes them (parameters taken from SYSTEM.ADMIN.*.EVENT of a test queue manager). */
class QueueManagerEventsTest {

    private static PCFMessage event(int command, int reason, int control, int sequence) {
        PCFMessage message = new PCFMessage(MQConstants.MQCFT_EVENT, command, sequence, control == MQConstants.MQCFC_LAST);
        message.getHeader().setReason(reason);
        return message;
    }

    @Test
    void channelStoppedForRetryIsAWarning() {
        PCFMessage message = event(MQConstants.MQCMD_CHANNEL_EVENT, MQConstants.MQRC_CHANNEL_STOPPED, MQConstants.MQCFC_LAST, 1);
        message.addParameter(MQConstants.MQCA_Q_MGR_NAME, "QM1");
        message.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "QM1.TO.QM2");
        message.addParameter(MQConstants.MQIACF_REASON_QUALIFIER, MQConstants.MQRQ_CHANNEL_STOPPED_RETRY);
        Event event = QueueManagerEvents.FromPcf(Kind.CHANNEL, message, Instant.EPOCH);
        assertEquals("Channel stopped, retrying", event.Title);
        assertEquals(Level.WARNING, event.Level);
        assertEquals("QM1.TO.QM2", event.Object);
    }

    @Test
    void wrongPasswordNamesTheUserAndWhereFrom() {
        PCFMessage message = event(MQConstants.MQCMD_Q_MGR_EVENT, MQConstants.MQRC_NOT_AUTHORIZED, MQConstants.MQCFC_LAST, 1);
        message.addParameter(MQConstants.MQCA_Q_MGR_NAME, "QM1                                             ");
        message.addParameter(MQConstants.MQIACF_REASON_QUALIFIER, MQConstants.MQRQ_CSP_NOT_AUTHORIZED);
        message.addParameter(MQConstants.MQCACF_USER_IDENTIFIER, "ubuntu      ");
        message.addParameter(MQConstants.MQCACF_CSP_USER_IDENTIFIER, "app");
        message.addParameter(MQConstants.MQIA_APPL_TYPE, MQConstants.MQAT_JAVA);
        message.addParameter(MQConstants.MQCACF_APPL_NAME, "Shop                        ");
        message.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "DEV.APP.SVRCONN     ");
        message.addParameter(MQConstants.MQCACH_CONNECTION_NAME, "172.17.0.1");
        Event event = QueueManagerEvents.FromPcf(Kind.QUEUE_MANAGER, message, Instant.EPOCH);
        assertEquals("Wrong user id or password", event.Title);
        assertEquals(Level.ERROR, event.Level);
        assertEquals("DEV.APP.SVRCONN", event.Object);
        assertEquals("User: ubuntu · User id given: app · Application: Shop · Connection: 172.17.0.1", event.Summary);
        assertTrue(event.Fields.stream().anyMatch(field -> field.Name.equals("Application type") && field.Value.equals("Java")));
    }

    @Test
    void configurationChangeShowsTheAttributesChanged() {
        PCFMessage before = event(MQConstants.MQCMD_CONFIG_EVENT, MQConstants.MQRC_CONFIG_CHANGE_OBJECT, MQConstants.MQCFC_NOT_LAST, 1);
        PCFMessage after = event(MQConstants.MQCMD_CONFIG_EVENT, MQConstants.MQRC_CONFIG_CHANGE_OBJECT, MQConstants.MQCFC_LAST, 2);
        String[][] values = {{"", "10.11.24"}, {"Orders", "12.11.40"}};
        PCFMessage[] messages = {before, after};
        for (int i = 0; i < 2; i++) {
            messages[i].addParameter(MQConstants.MQCACF_EVENT_USER_ID, "mqm");
            messages[i].addParameter(MQConstants.MQIACF_EVENT_ORIGIN, MQConstants.MQEVO_CONSOLE);
            messages[i].addParameter(MQConstants.MQIACF_OBJECT_TYPE, MQConstants.MQOT_Q);
            messages[i].addParameter(MQConstants.MQCA_Q_NAME, "APP.IN");
            messages[i].addParameter(MQConstants.MQCA_Q_DESC, values[i][0]);
            messages[i].addParameter(MQConstants.MQCA_ALTERATION_TIME, values[i][1]);
            messages[i].addParameter(MQConstants.MQIA_INHIBIT_GET, i == 0 ? MQConstants.MQQA_GET_ALLOWED : MQConstants.MQQA_GET_INHIBITED);
        }
        Event event = QueueManagerEvents.Merge(QueueManagerEvents.FromPcf(Kind.CONFIGURATION, before, Instant.EPOCH),
                QueueManagerEvents.FromPcf(Kind.CONFIGURATION, after, Instant.EPOCH));
        assertEquals("Object changed", event.Title);
        assertEquals("APP.IN", event.Object);
        assertEquals(2, event.Changes.size());
        assertEquals("Queue description", event.Changes.get(0).Name);
        assertEquals("Orders", event.Changes.get(0).After);
        assertEquals("Get allowed", event.Changes.get(1).Before);
        assertEquals("Get inhibited", event.Changes.get(1).After);
        assertTrue(event.Summary.startsWith("Changed: Queue description, Inhibit get · Type: Queue · User: mqm"));
        assertFalse(event.BySet);
    }

    @Test
    void changesByApplicationsAreMarked() {
        PCFMessage message = event(MQConstants.MQCMD_CONFIG_EVENT, MQConstants.MQRC_CONFIG_CHANGE_OBJECT, MQConstants.MQCFC_LAST, 2);
        message.addParameter(MQConstants.MQIACF_EVENT_ORIGIN, MQConstants.MQEVO_MQSET);
        message.addParameter(MQConstants.MQCA_Q_NAME, "QM2");
        assertTrue(QueueManagerEvents.FromPcf(Kind.CONFIGURATION, message, Instant.EPOCH).BySet);
    }

    @Test
    void commandEventIsTitledByTheCommand() {
        PCFMessage message = event(MQConstants.MQCMD_COMMAND_EVENT, MQConstants.MQRC_COMMAND_MQSC, MQConstants.MQCFC_LAST, 1);
        com.ibm.mq.headers.pcf.MQCFGR context = new com.ibm.mq.headers.pcf.MQCFGR();
        context.setParameter(MQConstants.MQGACF_COMMAND_CONTEXT);
        context.addParameter(new com.ibm.mq.headers.pcf.MQCFST(MQConstants.MQCACF_EVENT_USER_ID, "mqm"));
        context.addParameter(new com.ibm.mq.headers.pcf.MQCFIN(MQConstants.MQIACF_COMMAND, MQConstants.MQCMD_CHANGE_Q_MGR));
        message.addParameter(context);
        Event event = QueueManagerEvents.FromPcf(Kind.COMMAND, message, Instant.EPOCH);
        assertEquals("Change queue manager", event.Title);
        assertEquals(Level.INFO, event.Level);
        assertEquals("Command context", event.Fields.get(0).Name);
        assertEquals(1, event.Fields.get(1).Depth);
    }

    @Test
    void namesAndValuesAreReadable() {
        assertEquals("Queue manager name", QueueManagerEvents.ParameterName("MQCA_Q_MGR_NAME"));
        assertEquals("Connection name", QueueManagerEvents.ParameterName("MQCACH_CONNECTION_NAME"));
        assertEquals("Get allowed", QueueManagerEvents.Constants("MQIA_INHIBIT_GET",
                "MQQA_GET_ALLOWED/MQQA_PUT_ALLOWED/MQQA_NOT_SHAREABLE/MQQA_BACKOUT_NOT_HARDENED"));
        assertEquals("Shareable", QueueManagerEvents.Constants("MQIA_SHAREABILITY",
                "MQQA_GET_INHIBITED/MQQA_PUT_INHIBITED/MQQA_SHAREABLE/MQQA_BACKOUT_HARDENED"));
        assertEquals("Enabled", QueueManagerEvents.Constants("MQIA_CHANNEL_EVENT", "MQEVR_ENABLED"));
        assertEquals("Change queue manager", QueueManagerEvents.ConstantName(MQConstants.MQCMD_CHANGE_Q_MGR, "MQCMD_"));
    }
}
