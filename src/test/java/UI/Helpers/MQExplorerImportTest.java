package UI.Helpers;

import static org.junit.jupiter.api.Assertions.*;

import MQApi.Connection.TlsSettings;
import MQApi.Models.Query.ConnectionDetailModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MQExplorerImportTest {

    private static List<MQExplorerImport.Entry> sample() throws IOException {
        try (InputStream stream = MQExplorerImportTest.class.getResourceAsStream("/mqexplorer/export.xml")) {
            return MQExplorerImport.Read(stream);
        }
    }

    @Test
    void readsPlainConnectionWithUser() throws IOException {
        ConnectionDetailModel c = sample().get(0).Connection;
        assertEquals("QMA", c.QueueManager);
        assertEquals("mq1.example.com", c.Host);
        assertEquals("1415", c.Port);
        assertEquals("APP.SVRCONN", c.Channel);
        assertEquals("appuser", c.User);
        assertEquals("", c.Password);
        assertFalse(c.IsTlsEnabled());
    }

    @Test
    void userIdOnlyWhenEnabled() throws IOException {
        assertEquals("", sample().get(1).Connection.User);
    }

    @Test
    void readsTlsSettingsWithoutPasswords() throws IOException {
        MQExplorerImport.Entry entry = sample().get(2);
        ConnectionDetailModel c = entry.Connection;
        assertTrue(c.IsTlsEnabled());
        assertEquals("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", c.TlsCipherSuite);
        assertEquals("C:\\certs\\trust.jks", c.TrustStore);
        assertEquals("C:\\certs\\client.jks", c.KeyStore);
        assertEquals("", c.TrustStorePassword);
        assertEquals("", c.KeyStorePassword);
        assertEquals("CN=QMC", c.TlsPeerName);
        // Two addresses: the first one is used, and the user is told
        assertEquals("mq2.example.com", c.Host);
        assertEquals("1420", c.Port);
        assertEquals(1, entry.Notes.size());
    }

    @Test
    void defaultPortAndSecurityExitNote() throws IOException {
        MQExplorerImport.Entry entry = sample().get(3);
        assertEquals("1414", entry.Connection.Port);
        assertTrue(entry.Notes.contains("security exit not imported"));
    }

    @Test
    void skipsLocalQueueManagers() throws IOException {
        MQExplorerImport.Entry entry = sample().get(4);
        assertNull(entry.Connection);
        assertNotNull(entry.Skipped);
    }

    @Test
    void cipherSuiteNames() {
        assertEquals(TlsSettings.TLS12_OR_HIGHER, MQExplorerImport.cipherSuite("ANY_TLS12_OR_HIGHER"));
        assertEquals(TlsSettings.TLS13_OR_HIGHER, MQExplorerImport.cipherSuite("ANY_TLS13_OR_HIGHER"));
        assertEquals("TLS_RSA_WITH_AES_128_CBC_SHA256", MQExplorerImport.cipherSuite("TLS_RSA_WITH_AES_128_CBC_SHA256"));
    }

    @Test
    void detectsDuplicates() throws IOException {
        ConnectionDetailModel imported = sample().get(0).Connection;
        ConnectionDetailModel existing = new ConnectionDetailModel();
        existing.QueueManager = "qma";
        existing.Host = "MQ1.example.com";
        existing.Port = "1415";
        existing.Channel = "APP.SVRCONN";
        assertTrue(MQExplorerImport.IsAlreadyPresent(imported, List.of(existing)));
        existing.Port = "1414";
        assertFalse(MQExplorerImport.IsAlreadyPresent(imported, List.of(existing)));
    }

    @Test
    void rejectsOtherFiles() {
        InputStream other = new ByteArrayInputStream("<Settings/>".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> MQExplorerImport.Read(other));
    }
}
