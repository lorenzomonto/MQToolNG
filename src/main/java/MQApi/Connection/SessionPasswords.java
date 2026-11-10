package MQApi.Connection;

import MQApi.Models.Query.ConnectionDetailModel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passwords typed when connecting to a queue manager whose connection has a user but no saved password.
 * They are kept in memory only, while the queue manager is connected, unless the user chooses to save
 * them: then they become the saved password of the connection, written encrypted (SecurePasswordStore).
 */
public class SessionPasswords {

    /** Asks the user for a password; returns null when the user cancels. */
    public interface PasswordPrompt {
        PromptResult Ask(ConnectionDetailModel connectionDetail);
    }

    public static class PromptResult {
        public final String Password;
        /** True when the user chose to save the password (encrypted) with the connection. */
        public final boolean Save;

        public PromptResult(String password, boolean save) {
            this.Password = password;
            this.Save = save;
        }
    }

    private static final Map<String, String> passwords = new ConcurrentHashMap<>();
    private static volatile PasswordPrompt prompt;
    private static volatile Runnable saveConnections;

    public static void SetPrompt(PasswordPrompt passwordPrompt) {
        prompt = passwordPrompt;
    }

    /** Writes the connections to disk; called when the user chooses to save a typed password. */
    public static void SetSaver(Runnable saver) {
        saveConnections = saver;
    }

    /** True when the connection authenticates with a user and has no saved password. */
    public static boolean NeedsPassword(ConnectionDetailModel connectionDetail) {
        return isSet(connectionDetail.User) && !isSet(connectionDetail.Password);
    }

    /** Asks for the password and keeps it for this session. Returns false when the user cancels. */
    public static boolean AskAndRemember(ConnectionDetailModel connectionDetail) {
        PasswordPrompt currentPrompt = prompt;
        PromptResult result = currentPrompt != null ? currentPrompt.Ask(connectionDetail) : null;
        if (result == null) {
            return false;
        }
        if (result.Save && SecurePasswordStore.IsAvailable()) {
            // From now on it is a saved password (written encrypted)
            connectionDetail.Password = result.Password;
            Runnable saver = saveConnections;
            if (saver != null) {
                saver.run();
            }
        } else {
            passwords.put(key(connectionDetail), result.Password);
        }
        return true;
    }

    /**
     * Password to use for a connection: the saved one, otherwise the one typed in this session,
     * otherwise the user is asked. Returns null when there is no password (or the user cancels).
     */
    public static String Resolve(ConnectionDetailModel connectionDetail) {
        if (!NeedsPassword(connectionDetail)) {
            return connectionDetail.Password;
        }
        String password = passwords.get(key(connectionDetail));
        if (password == null && AskAndRemember(connectionDetail)) {
            password = NeedsPassword(connectionDetail) ? passwords.get(key(connectionDetail)) : connectionDetail.Password;
        }
        return password;
    }

    public static void Forget(ConnectionDetailModel connectionDetail) {
        if (connectionDetail != null) {
            passwords.remove(key(connectionDetail));
        }
    }

    private static String key(ConnectionDetailModel c) {
        return c.Host + "|" + c.Port + "|" + c.QueueManager + "|" + c.Channel + "|" + c.User;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }
}
