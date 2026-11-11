package UI.Helpers;

import MQApi.Connection.TlsSettings;
import MQApi.Models.Query.ConnectionDetailModel;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads the queue manager connections of a file exported by IBM MQ Explorer
 * (File > Export > MQ Explorer settings): one QueueManagerHandle element per remote queue manager.
 * Passwords are not imported: MQ Explorer stores them encrypted with its own key.
 */
public class MQExplorerImport {

    /** A queue manager found in the file: the connection, or why it cannot be imported. */
    public static class Entry {
        public final String Name;
        public final ConnectionDetailModel Connection;
        /** Null when the connection can be imported. */
        public final String Skipped;
        /** What was not imported (e.g. other addresses, security exit), shown to the user; can be empty. */
        public final List<String> Notes = new ArrayList<>();

        Entry(String name, ConnectionDetailModel connection, String skipped) {
            this.Name = name;
            this.Connection = connection;
            this.Skipped = skipped;
        }
    }

    // host(port): the port is optional, 1414 by default
    private static final Pattern ADDRESS = Pattern.compile("^\\s*([^()\\s]+)\\s*(?:\\(\\s*(\\d+)\\s*\\))?\\s*$");
    private static final String CLIENT_CONNECTION = "2";

    public static List<Entry> Read(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            return Read(stream);
        }
    }

    public static List<Entry> Read(InputStream stream) throws IOException {
        Document document;
        try {
            document = XMLHelper.SecureDocumentBuilderFactory().newDocumentBuilder().parse(stream);
        } catch (Exception ex) {
            throw new IOException("The file is not a valid XML file: " + ex.getMessage(), ex);
        }
        if (!"MQExplorer".equals(document.getDocumentElement().getTagName())) {
            throw new IOException("The file is not an export of MQ Explorer settings (no MQExplorer element).");
        }
        List<Entry> entries = new ArrayList<>();
        NodeList handles = document.getElementsByTagName("QueueManagerHandle");
        for (int i = 0; i < handles.getLength(); i++) {
            entries.add(read((Element) handles.item(i)));
        }
        return entries;
    }

    private static Entry read(Element handle) {
        String name = attribute(handle, "name");
        // MQ Explorer writes "connnectionType" (three n)
        String type = attribute(handle, "connnectionType");
        if (type.isEmpty()) {
            type = attribute(handle, "connectionType");
        }
        String connName = attribute(handle, "connName");
        if (name.isEmpty()) {
            return new Entry("(no name)", null, "No queue manager name");
        }
        if (!type.isEmpty() && !CLIENT_CONNECTION.equals(type)) {
            return new Entry(name, null, "Not a client connection (local queue manager or CCDT)");
        }
        if (connName.isEmpty()) {
            return new Entry(name, null, "No connection name (host and port)");
        }
        // Several addresses (multi-instance queue managers): the first one is used
        String[] addresses = connName.split(",");
        Matcher address = ADDRESS.matcher(addresses[0]);
        if (!address.matches()) {
            return new Entry(name, null, "Connection name not understood: " + connName);
        }
        ConnectionDetailModel connection = new ConnectionDetailModel();
        connection.QueueManager = name;
        connection.Host = address.group(1);
        connection.Port = address.group(2) != null ? address.group(2) : "1414";
        connection.Channel = attribute(handle, "channel").isEmpty() ? "SYSTEM.DEF.SVRCONN" : attribute(handle, "channel");
        // userIdState="1": the user id is sent when connecting
        connection.User = "1".equals(attribute(handle, "userIdState")) ? attribute(handle, "userid") : "";
        connection.Password = "";
        String cipher = attribute(handle, "sslCipherSuite");
        connection.UseTls = cipher.isEmpty() ? "false" : "true";
        connection.TlsCipherSuite = cipher.isEmpty() ? TlsSettings.TLS12_OR_HIGHER : cipherSuite(cipher);
        connection.TrustStore = attribute(handle, "sslTrustedStore");
        connection.TrustStorePassword = "";
        connection.KeyStore = attribute(handle, "sslPersonalStore");
        connection.KeyStorePassword = "";
        connection.TlsPeerName = attribute(handle, "sslPeerName");
        Entry entry = new Entry(name, connection, null);
        if (addresses.length > 1) {
            entry.Notes.add("only the first address is used");
        }
        if (!attribute(handle, "securityExitName").isEmpty()) {
            entry.Notes.add("security exit not imported");
        }
        return entry;
    }

    /**
     * MQ Explorer stores a Java cipher suite, with the IBM names (SSL_ prefix) when it runs on the IBM
     * Java: MQToolNG uses the standard names. The ANY_TLS CipherSpecs become the TLS 1.2/1.3 or higher choice.
     */
    static String cipherSuite(String cipher) {
        String value = cipher.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("TLS13_OR_HIGHER") || upper.equals("*TLS13ORHIGHER")) {
            return TlsSettings.TLS13_OR_HIGHER;
        }
        if (upper.startsWith("ANY") || upper.startsWith("*")) {
            return TlsSettings.TLS12_OR_HIGHER;
        }
        if (upper.startsWith("SSL_")) {
            return "TLS_" + value.substring(4);
        }
        return value;
    }

    /** True when the list already has a connection to the same queue manager, host, port and channel. */
    public static boolean IsAlreadyPresent(ConnectionDetailModel connection, List<ConnectionDetailModel> existing) {
        for (ConnectionDetailModel other : existing) {
            if (equalsIgnoreCase(other.QueueManager, connection.QueueManager)
                    && equalsIgnoreCase(other.Host, connection.Host)
                    && same(other.Port, connection.Port)
                    && same(other.Channel, connection.Channel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }

    private static String attribute(Element element, String name) {
        return element.getAttribute(name).trim();
    }
}
