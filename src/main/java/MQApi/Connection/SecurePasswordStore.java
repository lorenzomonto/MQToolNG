package MQApi.Connection;

import MQApi.Logs.LogWriter;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encrypts saved connection passwords with the Windows Data Protection API (DPAPI).
 * The encrypted value can only be decrypted by the same Windows user on the same computer,
 * so a copy of ConnectionSettings.xml does not reveal the passwords.
 * On other systems there is no secure store: passwords are not saved there.
 */
public class SecurePasswordStore {

    private static final String PREFIX = "dpapi:";
    // Additional secret mixed into the encryption, so that other applications of the same user
    // cannot decrypt the values by calling DPAPI without it
    private static final byte[] ENTROPY = "MQToolNG connection password".getBytes(StandardCharsets.UTF_8);

    public static boolean IsAvailable() {
        return Platform.isWindows();
    }

    /** Encrypted form of the password, or null when it cannot be saved securely. */
    public static String Protect(String password) {
        if (password == null || password.isEmpty() || !IsAvailable()) {
            return null;
        }
        try {
            byte[] encrypted = Crypt32Util.cryptProtectData(password.getBytes(StandardCharsets.UTF_8), ENTROPY,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "MQToolNG", null);
            return PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (RuntimeException | Error ex) {
            LogWriter.WriteToLog(ex);
            return null;
        }
    }

    /**
     * Decrypted password, or null when it cannot be decrypted
     * (another Windows user or computer, or not on Windows).
     */
    public static String Unprotect(String protectedValue) {
        if (protectedValue == null || !protectedValue.startsWith(PREFIX) || !IsAvailable()) {
            return null;
        }
        try {
            byte[] encrypted = Base64.getDecoder().decode(protectedValue.substring(PREFIX.length()));
            byte[] password = Crypt32Util.cryptUnprotectData(encrypted, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
            return new String(password, StandardCharsets.UTF_8);
        } catch (RuntimeException | Error ex) {
            LogWriter.WriteToLog(ex);
            return null;
        }
    }
}
