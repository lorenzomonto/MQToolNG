package MQApi.Connection;

import MQApi.Models.Query.ConnectionDetailModel;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.sun.jna.Platform;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS for the connection to a queue manager. The channel must have a matching SSLCIPH;
 * the queue manager certificate must be trusted by the chosen store.
 */
public class TlsSettings {

    /** Any TLS 1.2 or 1.3 cipher the queue manager accepts (the channel's SSLCIPH: ANY_TLS12_OR_HIGHER). */
    public static final String TLS12_OR_HIGHER = "*TLS12ORHIGHER";
    /** TLS 1.3 only (the channel's SSLCIPH: ANY_TLS13_OR_HIGHER). */
    public static final String TLS13_OR_HIGHER = "*TLS13ORHIGHER";

    static {
        // Cipher suites are given with their Java (Oracle/OpenJDK) names, not the IBM JRE ones
        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings", "false");
    }

    public static String CipherSuite(ConnectionDetailModel connection) {
        return isSet(connection.TlsCipherSuite) ? connection.TlsCipherSuite.trim() : TLS12_OR_HIGHER;
    }

    /** Adds the TLS properties to the connection properties. */
    public static void Apply(ConnectionDetailModel connection, Map<Object, Object> properties) throws MQException {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trustStore(connection));
            KeyManager[] keys = null;
            if (isSet(connection.KeyStore)) {
                char[] password = chars(connection.KeyStorePassword);
                KeyStore keyStore = KeyStore.getInstance(new File(connection.KeyStore.trim()), password);
                KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyFactory.init(keyStore, password);
                keys = keyFactory.getKeyManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys, trust.getTrustManagers(), null);
            properties.put(MQConstants.SSL_SOCKET_FACTORY_PROPERTY, context.getSocketFactory());
            properties.put(MQConstants.SSL_CIPHER_SUITE_PROPERTY, CipherSuite(connection));
            if (isSet(connection.TlsPeerName)) {
                properties.put(MQConstants.SSL_PEER_NAME_PROPERTY, connection.TlsPeerName.trim());
            }
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ex) {
            throw new MQException(MQConstants.MQCC_FAILED, MQConstants.MQRC_SSL_INITIALIZATION_ERROR,
                    "TLS settings: " + ex.getMessage());
        }
    }

    /** Explanation added to a connection error, or null. */
    public static String Hint(int reason, boolean tlsEnabled) {
        switch (reason) {
            case MQConstants.MQRC_SSL_PEER_NAME_MISMATCH:
                return "The queue manager certificate does not match the expected server certificate name (SSLPEER).";
            case MQConstants.MQRC_SSL_INITIALIZATION_ERROR:
            case MQConstants.MQRC_JSSE_ERROR:
            case MQConstants.MQRC_SSL_NOT_ALLOWED:
            case MQConstants.MQRC_SSL_CERTIFICATE_REVOKED:
                return tlsEnabled
                        ? "The TLS connection could not be established. Check that the channel has SSLCIPH set, "
                        + "that the encryption setting matches it and that the queue manager certificate is trusted "
                        + "(certificate store or trust store file)."
                        : "The channel may require TLS: enable it in the connection settings (Edit).";
            case MQConstants.MQRC_CONNECTION_BROKEN:
            case MQConstants.MQRC_Q_MGR_NOT_AVAILABLE:
                return tlsEnabled ? null : "If the channel requires TLS, enable it in the connection settings (Edit).";
            default:
                return null;
        }
    }

    /** Null = Java's default trust store (cacerts). */
    private static KeyStore trustStore(ConnectionDetailModel connection) throws GeneralSecurityException, IOException {
        if (isSet(connection.TrustStore)) {
            return KeyStore.getInstance(new File(connection.TrustStore.trim()), chars(connection.TrustStorePassword));
        }
        if (Platform.isWindows()) {
            // Certificates trusted by Windows (company root CAs are usually installed there)
            KeyStore windows = KeyStore.getInstance("Windows-ROOT");
            windows.load(null, null);
            return windows;
        }
        return null;
    }

    private static char[] chars(String password) {
        return isSet(password) ? password.toCharArray() : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
