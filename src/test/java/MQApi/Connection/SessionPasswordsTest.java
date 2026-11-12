package MQApi.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import MQApi.Models.Query.ConnectionDetailModel;
import UI.Helpers.XMLHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionPasswordsTest {

    @TempDir
    Path dir;

    private static ConnectionDetailModel connection(String user, String password, String noPassword) {
        ConnectionDetailModel c = new ConnectionDetailModel();
        c.Host = "localhost";
        c.Port = "1414";
        c.QueueManager = "QM1";
        c.Channel = "APP.SVRCONN";
        c.User = user;
        c.Password = password;
        c.NoPassword = noPassword;
        return c;
    }

    @Test
    void userWithoutSavedPasswordIsAsked() {
        assertTrue(SessionPasswords.NeedsPassword(connection("app", "", null)));
        assertTrue(SessionPasswords.NeedsPassword(connection("app", null, "false")));
    }

    @Test
    void userMarkedWithoutPasswordIsNeverAsked() {
        assertFalse(SessionPasswords.NeedsPassword(connection("app", "", "true")));
        assertFalse(SessionPasswords.NeedsPassword(connection("", "", null)));
        assertFalse(SessionPasswords.NeedsPassword(connection("app", "secret", null)));
    }

    /** Connections saved by older versions have no NoPassword element: the password is asked as before. */
    @Test
    void noPasswordIsSavedAndOlderFilesStillAskThePassword() {
        String file = dir.resolve("ConnectionSettings.xml").toString();
        ArrayList<ConnectionDetailModel> models = new ArrayList<>();
        models.add(connection("ops", "", "true"));
        models.add(connection("app", "", null));
        XMLHelper.WriteConnectionModelToXml(models, file);
        ArrayList<ConnectionDetailModel> read = XMLHelper.ReadConnectionModelFromXml(file);
        assertEquals(2, read.size());
        assertTrue(read.get(0).IsNoPassword());
        assertFalse(SessionPasswords.NeedsPassword(read.get(0)));
        assertFalse(read.get(1).IsNoPassword());
        assertTrue(SessionPasswords.NeedsPassword(read.get(1)));
    }
}
