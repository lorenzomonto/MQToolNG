package MQApi.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MqscObjectTest {

    private static final String QUEUE = "AMQ8409I: Display Queue details.\n"
            + "   QUEUE(APP.IN)                           TYPE(QLOCAL)\n"
            + "   ALTDATE(2026-11-19)                     ALTTIME(11.11.40)\n"
            + "   CURDEPTH(8)                             DEFPSIST(NO)\n"
            + "   DEFTYPE(PREDEFINED)                     DESCR(Orders from the shop's site)\n"
            + "   HARDENBO                                MAXDEPTH(5000)\n"
            + "   NOTRIGGER                               INITQ( )\n"
            + "   USAGE(NORMAL)                        ";

    @Test
    void displayBecomesDefine() {
        MqscObject queue = MqscObject.FromDisplay("QLOCAL", QUEUE);
        assertEquals("APP.IN", queue.Name);
        assertFalse(queue.Attributes.containsKey("CURDEPTH"));
        assertFalse(queue.Attributes.containsKey("ALTDATE"));
        assertFalse(queue.Attributes.containsKey("TYPE"));
        assertFalse(queue.Attributes.containsKey("DEFTYPE"));
        assertEquals("DEFINE QLOCAL(APP.IN) DEFPSIST(NO) DESCR('Orders from the shop''s site') HARDENBO MAXDEPTH(5000)"
                + " NOTRIGGER INITQ(' ') USAGE(NORMAL) REPLACE", queue.Command());
    }

    @Test
    void valuesWithParenthesesListsAndLowerCase() {
        MqscObject channel = MqscObject.FromDisplay("CHANNEL", "AMQ8414I: Display Channel details.\n"
                + "   CHANNEL(TO.PARTNER)                     CHLTYPE(SDR)\n"
                + "   CONNAME(host.example.com(1414),backup(1414))\n"
                + "   MCAUSER(app)                            MSGEXIT(EXIT1\n"
                + "        ,exit2)                            RESETSEQ(NO)\n"
                + "   SSLCIPH(ANY_TLS12_OR_HIGHER)            XMITQ(PARTNER)");
        assertEquals("DEFINE CHANNEL(TO.PARTNER) CHLTYPE(SDR) CONNAME('host.example.com(1414),backup(1414)') MCAUSER('app')"
                + " MSGEXIT(EXIT1,'exit2') SSLCIPH(ANY_TLS12_OR_HIGHER) XMITQ(PARTNER) REPLACE", channel.Command());
    }

    @Test
    void rulesAndAuthorityRecords() {
        MqscObject rule = MqscObject.FromDisplay("CHLAUTH", "AMQ8878I: Display channel authentication record details.\n"
                + "   CHLAUTH(DEV.ADMIN.SVRCONN)              TYPE(USERMAP)\n"
                + "   DESCR(Allow admin)                      CUSTOM( )\n"
                + "   ADDRESS( )                              CLNTUSER(admin)\n"
                + "   MCAUSER(mqm)                            USERSRC(MAP)\n"
                + "   ALTDATE(2026-11-19)                     ALTTIME(11.05.12)");
        assertEquals("DEV.ADMIN.SVRCONN USERMAP CLNTUSER(admin)", rule.Name);
        assertEquals("SET CHLAUTH(DEV.ADMIN.SVRCONN) TYPE(USERMAP) DESCR('Allow admin') CLNTUSER('admin') MCAUSER('mqm')"
                + " USERSRC(MAP) ACTION(REPLACE)", rule.Command());

        MqscObject record = MqscObject.FromDisplay("AUTHREC", "AMQ8864I: Display authority record details.\n"
                + "   PROFILE(APP.**)                         ENTITY(apps)\n"
                + "   ENTTYPE(GROUP)                          OBJTYPE(QUEUE)\n"
                + "   AUTHLIST(BROWSE,GET,INQ,PUT)");
        assertEquals("SET AUTHREC PROFILE('APP.**') OBJTYPE(QUEUE) GROUP('apps') AUTHADD(BROWSE,GET,INQ,PUT)", record.Command());
        MqscObject self = MqscObject.FromDisplay("AUTHREC", "AMQ8864I: Display authority record details.\n"
                + "   PROFILE(self)                           ENTITY(mqm)\n"
                + "   ENTTYPE(PRINCIPAL)                      OBJTYPE(QMGR)\n"
                + "   AUTHLIST(CONNECT,INQ)");
        assertEquals("SET AUTHREC OBJTYPE(QMGR) PRINCIPAL('mqm') AUTHADD(CONNECT,INQ)", self.Command());
    }

    @Test
    void stateAndTemporaryObjectsAreLeftOut() {
        assertNull(MqscObject.FromDisplay("QLOCAL", "AMQ8409I: Display Queue details.\n"
                + "   QUEUE(AMQ.6AFED96B24C60103)             TYPE(QLOCAL)\n   DEFTYPE(TEMPDYN)"));
        assertNull(MqscObject.FromDisplay("SUB", "AMQ8096I: IBM MQ subscription inquired.\n"
                + "   SUBID(414D51)\n   SUB(APP.SUB)                            SUBTYPE(API)"));
        MqscObject qmgr = MqscObject.FromDisplay("QMGR", "AMQ8408I: Display Queue Manager details.\n"
                + "   QMNAME(QM1)                             CCSID(1208)\n"
                + "   PLATFORM(UNIX)                          SYNCPT\n"
                + "   KEYRPWD(********)                       DEADQ(DLQ)");
        assertEquals("ALTER QMGR CCSID(1208) DEADQ(DLQ)", qmgr.Command());
    }

    @Test
    void scriptHasOneAttributePerLine() {
        assertEquals("DEFINE QLOCAL('a b') +\n   DESCR('x (y) z') +\n   REPLACE",
                QueueManagerConfig.Wrap("DEFINE QLOCAL('a b') DESCR('x (y) z') REPLACE"));
    }

    @Test
    void comparison() {
        MqscObject left = MqscObject.FromDisplay("QLOCAL", QUEUE);
        MqscObject right = MqscObject.FromDisplay("QLOCAL", QUEUE.replace("MAXDEPTH(5000)", "MAXDEPTH(9000)")
                .replace("CURDEPTH(8)", "CURDEPTH(0)").replace("HARDENBO", "NOHARDENBO"));
        MqscObject other = MqscObject.FromDisplay("QLOCAL", QUEUE.replace("APP.IN", "APP.OUT"));
        List<ConfigComparison.Difference> differences = ConfigComparison.Compare(List.of(left), List.of(right, other));
        assertEquals(2, differences.size());
        ConfigComparison.Difference changed = differences.get(0);
        assertEquals("HARDENBO, MAXDEPTH, NOHARDENBO", changed.Summary("QM1", "QM2"));
        assertEquals("Only in QM2", differences.get(1).Summary("QM1", "QM2"));
        assertTrue(ConfigComparison.Compare(List.of(left), List.of(MqscObject.FromDisplay("QLOCAL", QUEUE))).isEmpty());
    }
}
