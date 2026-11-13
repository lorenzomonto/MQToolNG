package MQApi.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import MQApi.Models.Query.ConnectionDetailModel;
import UI.Helpers.XMLHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteConnectionTest {

    @TempDir
    Path dir;

    private static ConnectionDetailModel gateway() {
        ConnectionDetailModel c = new ConnectionDetailModel();
        c.Host = "gw.example.com";
        c.Port = "1414";
        c.QueueManager = "GW";
        c.Channel = "ADMIN.SVRCONN";
        c.User = "admin";
        c.NoPassword = "false";
        c.UseTls = "true";
        c.TlsCipherSuite = "*TLS12ORHIGHER";
        return c;
    }

    private static ConnectionDetailModel through(ConnectionDetailModel via, String name) {
        ConnectionDetailModel c = new ConnectionDetailModel();
        c.QueueManager = name;
        c.ConnectThrough(via);
        return c;
    }

    @Test
    void remoteUsesTheSettingsOfTheIntermediateQueueManager() {
        ConnectionDetailModel remote = through(gateway(), "QMB");
        assertTrue(remote.IsRemote());
        assertEquals("QMB", remote.QueueManager);
        assertEquals("GW", remote.ConnectedQueueManager());
        assertEquals("gw.example.com", remote.Host);
        assertEquals("ADMIN.SVRCONN", remote.Channel);
        assertTrue(remote.IsTlsEnabled());
        assertEquals("via GW", remote.Address());
    }

    @Test
    void directConnectionIsNotRemote() {
        ConnectionDetailModel direct = gateway();
        assertFalse(direct.IsRemote());
        assertEquals("GW", direct.ConnectedQueueManager());
        assertEquals("gw.example.com:1414", direct.Address());
    }

    @Test
    void recognizesItsIntermediateConnection() {
        ConnectionDetailModel gateway = gateway();
        ConnectionDetailModel remote = through(gateway, "QMB");
        assertTrue(remote.IsThrough(gateway));
        ConnectionDetailModel otherHost = gateway();
        otherHost.Host = "other.example.com";
        assertFalse(remote.IsThrough(otherHost));
        assertFalse(gateway.IsThrough(gateway));
    }

    @Test
    void intermediateConnectionCanBeRebuiltWhenDeleted() {
        ConnectionDetailModel via = through(gateway(), "QMB").ViaConnection();
        assertFalse(via.IsRemote());
        assertEquals("GW", via.QueueManager);
        assertEquals("gw.example.com", via.Host);
        assertEquals("ADMIN.SVRCONN", via.Channel);
    }

    @Test
    void savedAndReadBack() {
        String file = dir.resolve("ConnectionSettings.xml").toString();
        ArrayList<ConnectionDetailModel> models = new ArrayList<>();
        models.add(gateway());
        models.add(through(gateway(), "QMB"));
        XMLHelper.WriteConnectionModelToXml(models, file);

        ArrayList<ConnectionDetailModel> read = XMLHelper.ReadConnectionModelFromXml(file);
        assertNull(read.get(0).ViaQueueManager);
        assertEquals("GW", read.get(1).ViaQueueManager);
        assertEquals("QMB", read.get(1).QueueManager);
        assertTrue(read.get(1).IsThrough(read.get(0)));
    }
}
