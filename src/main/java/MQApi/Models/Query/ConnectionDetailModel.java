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
    public String Channel; 
    public String Port;
    
    public String User;
    public String Password;

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
 
    public String toString() {
        return "<html>\nhost: " + Host + 
                "<br/>\nqueue manager: " + QueueManager + 
                "<br/>\nchannel: " + Channel + 
                "<br/>\nport: " + Port + 
                "<br/>\nuser: " + User + 
                "<br/>\nTLS: " + (IsTlsEnabled() ? "on" : "off (not encrypted)") +
                "</html>";
    }
}
