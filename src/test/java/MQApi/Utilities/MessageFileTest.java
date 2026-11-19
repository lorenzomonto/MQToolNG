package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageFileTest {

    @TempDir
    Path folder;

    private static byte[] data(MQMessage message) throws IOException {
        message.seek(0);
        byte[] bytes = new byte[message.getMessageLength()];
        message.readFully(bytes);
        return bytes;
    }

    private static MQMessage ebcdicMessage() throws Exception {
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_STRING;
        message.characterSet = 500;
        message.encoding = 785;
        message.priority = 7;
        message.persistence = MQConstants.MQPER_PERSISTENT;
        message.messageId = new byte[24];
        message.messageId[0] = 0x41;
        message.correlationId = new byte[24];
        message.correlationId[23] = 0x7f;
        message.userId = "app1        ";
        message.putApplicationName = "Test program";
        GregorianCalendar time = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        time.setTimeInMillis(1763456789000L);
        message.putDateTime = time;
        message.setStringProperty("color", "blue");
        message.setIntProperty("count", 42);
        message.setBooleanProperty("urgent", true);
        message.setBytesProperty("raw", new byte[]{1, 2, 3});
        message.write("Città è più".getBytes(Charset.forName("IBM500")));
        return message;
    }

    private Path write(boolean compress, MQMessage... messages) throws Exception {
        Path file = folder.resolve(compress ? "compressed.msg" : "plain.msg");
        try (MessageFile.Writer writer = new MessageFile.Writer(file.toString(), compress, "APP.QUEUE", "QM1")) {
            for (MQMessage message : messages) {
                writer.Write(message);
            }
            writer.Finish();
        }
        return file;
    }

    @Test
    void messagesComeBackWithBytesDescriptorAndProperties() throws Exception {
        for (boolean compress : new boolean[]{true, false}) {
            MQMessage original = ebcdicMessage();
            MQMessage binary = new MQMessage();
            binary.format = MQConstants.MQFMT_NONE;
            byte[] all = new byte[256];
            for (int i = 0; i < all.length; i++) {
                all[i] = (byte) i;
            }
            binary.write(all);
            Path file = write(compress, original, binary);

            try (MessageFile.Reader reader = MessageFile.Open(file.toString())) {
                assertEquals("APP.QUEUE", reader.QueueName);
                assertEquals("QM1", reader.QueueManagerName);
                assertFalse(reader.Legacy);
                MQMessage read = reader.Next();
                assertArrayEquals(data(original), data(read));
                assertEquals(500, read.characterSet);
                assertEquals(785, read.encoding);
                assertEquals(MQConstants.MQFMT_STRING, read.format);
                assertEquals(7, read.priority);
                assertEquals(MQConstants.MQPER_PERSISTENT, read.persistence);
                assertArrayEquals(original.messageId, read.messageId);
                assertArrayEquals(original.correlationId, read.correlationId);
                assertEquals("app1        ", read.userId);
                assertEquals("Test program", read.putApplicationName);
                assertEquals(1763456789000L, read.putDateTime.getTimeInMillis());
                assertEquals("blue", read.getStringProperty("color"));
                assertEquals(42, read.getIntProperty("count"));
                assertTrue(read.getBooleanProperty("urgent"));
                assertArrayEquals(new byte[]{1, 2, 3}, read.getBytesProperty("raw"));
                assertArrayEquals(all, data(reader.Next()));
                assertNull(reader.Next());
                assertTrue(reader.Complete());
                assertEquals(2, reader.ExpectedCount);
            }
            assertEquals(2, MessageFile.Count(file.toString()));
        }
    }

    @Test
    void fileThatEndsEarlyIsReadUpToItsLastWholeMessage() throws Exception {
        for (boolean compress : new boolean[]{true, false}) {
            Path whole = folder.resolve("whole.msg");
            long afterFirst;
            try (MessageFile.Writer writer = new MessageFile.Writer(whole.toString(), compress, "Q", "QM1")) {
                writer.Write(ebcdicMessage());
                writer.Sync();
                afterFirst = Files.size(whole);
                // Random bytes: also compressed, the second message takes some space in the file
                MQMessage random = new MQMessage();
                byte[] noise = new byte[4000];
                new java.util.Random(1).nextBytes(noise);
                random.write(noise);
                writer.Write(random);
                writer.Sync();
                // No Finish: like a program stopped while writing
            }
            byte[] bytes = Files.readAllBytes(whole);
            Path cut = folder.resolve("cut.msg");
            // Also stopped in the middle of the second message
            Files.write(cut, java.util.Arrays.copyOf(bytes, (int) (afterFirst + (bytes.length - afterFirst) / 2)));

            try (MessageFile.Reader reader = MessageFile.Open(whole.toString())) {
                assertNotNull(reader.Next());
                assertNotNull(reader.Next());
                assertNull(reader.Next());
                assertFalse(reader.Complete());
            }
            try (MessageFile.Reader reader = MessageFile.Open(cut.toString())) {
                assertNotNull(reader.Next());
                assertNull(reader.Next());
                assertFalse(reader.Complete());
            }
        }
    }

    @Test
    void backupsOfMQAdminToolAreRead() throws Exception {
        Path file = folder.resolve("old.bak");
        try (InputStream in = getClass().getResourceAsStream("/backups/mqadmintool-original.bak")) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try (MessageFile.Reader reader = MessageFile.Open(file.toString())) {
            assertTrue(reader.Legacy);
            assertTrue(reader.ExpectedCount > 0);
            int count = 0;
            MQMessage message;
            while ((message = reader.Next()) != null) {
                assertNotNull(message.format);
                count++;
            }
            assertEquals(reader.ExpectedCount, count);
            assertTrue(reader.Complete());
        }
    }

    @Test
    void otherFilesAreRefused() throws Exception {
        Path text = folder.resolve("notes.msg");
        Files.writeString(text, "hello");
        assertThrows(IOException.class, () -> MessageFile.Open(text.toString()));
        assertFalse(MessageFile.IsMessageFile(text.toString()));
    }
}
