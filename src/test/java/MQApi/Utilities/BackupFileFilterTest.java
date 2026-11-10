package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class BackupFileFilterTest {

    /** Reads every object of a backup file with the filter used by RestoreMessageFromFile. */
    private static int readAll(InputStream raw) throws Exception {
        InputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            in = new GZIPInputStream(in);
        }
        ObjectInputStream objects = new ObjectInputStream(in);
        objects.setObjectInputFilter(MQUtility.BACKUP_FILE_FILTER);
        assertEquals("MQAdminToolQueueDataFile", objects.readObject());
        int count = 0;
        try {
            while (true) {
                try {
                    objects.readObject();
                    count++;
                } catch (OptionalDataException e) {
                    if (e.eof) {
                        break;
                    }
                    objects.skipBytes(e.length);
                }
            }
        } catch (EOFException end) {
            // end of file
        }
        return count;
    }

    @Test
    void backupsOfTheOriginalMQAdminToolAreAccepted() throws Exception {
        int objects = readAll(getClass().getResourceAsStream("/backups/mqadmintool-original.bak"));
        assertTrue(objects > 100, "objects read: " + objects);
    }

    @Test
    void backupsOfMQToolNGAreAccepted() throws Exception {
        int objects = readAll(getClass().getResourceAsStream("/backups/mqtoolng-uncompressed.bak"));
        assertTrue(objects > 100, "objects read: " + objects);
    }

    @Test
    void otherClassesAreRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject("MQAdminToolQueueDataFile");
            out.writeInt(0x10001);
            // Any class outside the list stands for a gadget chain in a crafted file
            HashMap<String, String> payload = new HashMap<>();
            payload.put("a", "b");
            out.writeObject(payload);
        }
        assertThrows(InvalidClassException.class, () -> readAll(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
