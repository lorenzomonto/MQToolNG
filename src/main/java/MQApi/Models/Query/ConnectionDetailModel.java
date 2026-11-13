/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Models.Query;

/**
 *
 * @author jzhou
 */
public class ConnectionDetailModel {
    public String Host;
    public String QueueManager;
    /**
     * Set for a queue manager administered through another one: the queue manager the client connects
     * to (Host, Port, Channel, user and TLS are its own). The commands reach QueueManager through
     * its channels, the replies come back the same way. Empty for a direct connection.
     */
    public String ViaQueueManager;
    public String Channel; 
    public String Port;
    
    public String User;
    public String Password;
    /** "true" when the user is sent without a password (the queue manager does not check it): never asked. */
    public String NoPassword;

    // TLS (saved in ConnectionSettings.xml; the passwords encrypted, see XMLHelper)
    /** "true" to encrypt the connection; otherwise the connection is not encrypted. */
    public String UseTls;
    /** Java cipher suite or "*TLS12ORHIGHER" / "*TLS13ORHIGHER"; empty = TLS 1.2 or higher. */
    public String TlsCipherSuite;
    /** Trust store file (.p12, .jks); empty = the Windows certificate store (Java's default elsewhere). */
    public String TrustStore;
    public String TrustStorePassword;
    /** Key store with the client certificate, for channels with SSLCAUTH(REQUIRED); optional. */
    public String KeyStore;
    public String KeyStorePassword;
    /** Expected distinguished name of the queue manager certificate (SSLPEER), e.g. "CN=QM1"; optional. */
    public String TlsPeerName;

    public boolean IsTlsEnabled() {
        return "true".equalsIgnoreCase(UseTls);
    }

    public boolean IsNoPassword() {
        return "true".equalsIgnoreCase(NoPassword);
    }

    public boolean IsRemote() {
        return ViaQueueManager != null && !ViaQueueManager.trim().isEmpty();
    }

    /**
     * Administers this queue manager through the direct connection given: its host, channel, user,
     * passwords and TLS settings are copied, so the connection works even if that one is deleted.
     */
    public void ConnectThrough(ConnectionDetailModel via) {
        for (java.lang.reflect.Field field : ConnectionDetailModel.class.getFields()) {
            if (field.getName().equals("QueueManager") || field.getName().equals("ViaQueueManager")) {
                continue;
            }
            try {
                field.set(this, field.get(via));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
        ViaQueueManager = via.QueueManager;
    }

    /** True when this remote queue manager goes through the given direct connection. */
    public boolean IsThrough(ConnectionDetailModel direct) {
        return IsRemote() && direct != null && !direct.IsRemote()
                && ViaQueueManager.trim().equals(String.valueOf(direct.QueueManager).trim())
                && java.util.Objects.equals(Host, direct.Host) && java.util.Objects.equals(Port, direct.Port);
    }

    /** The intermediate connection, rebuilt from the settings copied in this remote one. */
    public ConnectionDetailModel ViaConnection() {
        ConnectionDetailModel via = new ConnectionDetailModel();
        via.ConnectThrough(this);
        via.QueueManager = ViaQueueManager;
        via.ViaQueueManager = null;
        return via;
    }

    /** Where the queue manager is reached, for display: host:port, or the intermediate queue manager. */
    public String Address() {
        return IsRemote() ? "via " + ViaQueueManager.trim() : Host + ":" + Port;
    }

    /** The queue manager the client connects to: the intermediate one for a remote queue manager. */
    public String ConnectedQueueManager() {
        return IsRemote() ? ViaQueueManager.trim() : QueueManager;
    }

    public String toString() {
        return "<html>\n" + (IsRemote() ? "through queue manager: " + ViaQueueManager + "<br/>\n" : "") +
                "host: " + Host +
                "<br/>\nqueue manager: " + QueueManager +
                "<br/>\nchannel: " + Channel + 
                "<br/>\nport: " + Port + 
                "<br/>\nuser: " + User + (IsNoPassword() && User != null && !User.isEmpty() ? " (no password)" : "") +
                "<br/>\nTLS: " + (IsTlsEnabled() ? "on" : "off (not encrypted)") +
                "</html>";
    }
}
