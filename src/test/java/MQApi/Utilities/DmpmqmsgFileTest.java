package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.mq.MQMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files written by IBM dmpmqmsg 10.0 from the same 4 messages, with each data format. */
class DmpmqmsgFileTest {

    @TempDir
    Path folder;

    private Path resource(String name) throws IOException {
        Path file = folder.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/dmpmqmsg/" + name)) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static List<MQMessage> read(MessageFile.Input input) throws IOException {
        List<MQMessage> messages = new ArrayList<>();
        MQMessage message;
        while ((message = input.Next()) != null) {
            messages.add(message);
        }
        return messages;
    }

    private static byte[] data(MQMessage message) throws IOException {
        message.seek(0);
        byte[] bytes = new byte[message.getMessageLength()];
        message.readFully(bytes);
        return bytes;
    }

    @Test
    void allDataFormatsGiveTheSameMessages() throws Exception {
        List<MQMessage> hex;
        try (MessageFile.Input input = MessageFile.OpenAny(resource("hex.txt").toString())) {
            assertEquals("ENC.SRC", input.QueueName() == null ? "ENC.SRC" : input.QueueName());
            hex = read(input);
        }
        assertEquals(4, hex.size());
        MQMessage first = hex.get(0);
        assertEquals("MQHRF2  ", first.format);
        assertEquals(1208, first.characterSet);
        assertEquals(7, first.priority);
        assertEquals("REPLY.Q", first.replyToQueueName);
        assertEquals("Put", first.putApplicationName);
        assertTrue(new String(data(first), "UTF-8").endsWith("Città è più – €\nsecond line"));
        MQMessage ebcdic = hex.get(3);
        assertEquals(500, ebcdic.characterSet);
        assertEquals(785, ebcdic.encoding);
        assertArrayEquals("EBCDIC text".getBytes(Charset.forName("IBM500")), data(ebcdic));
        for (String name : new String[]{"ascii.txt", "text.txt", "printable.txt"}) {
            try (MessageFile.Input input = MessageFile.OpenAny(resource(name).toString())) {
                List<MQMessage> messages = read(input);
                assertEquals(4, messages.size(), name);
                for (int i = 0; i < messages.size(); i++) {
                    assertArrayEquals(data(hex.get(i)), data(messages.get(i)), name + " message " + (i + 1));
                    assertEquals(hex.get(i).characterSet, messages.get(i).characterSet);
                    assertArrayEquals(hex.get(i).messageId, messages.get(i).messageId);
                }
            }
        }
        assertEquals(4, MessageFile.CountAny(resource("text.txt").toString()));
    }

    @Test
    void writtenFilesAreReadBack() throws Exception {
        MQMessage message = new MQMessage();
        message.format = "MQSTR   ";
        message.characterSet = 1208;
        message.priority = 3;
        message.correlationId = new byte[24];
        message.correlationId[0] = 0x43;
        message.applicationIdData = "ident";
        message.putApplicationName = "App";
        GregorianCalendar time = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        time.setTimeInMillis(1763456789120L);
        message.putDateTime = time;
        byte[] text = "Città è più".getBytes("UTF-8");
        message.write(text);
        Path file = folder.resolve("out.txt");
        try (DmpmqmsgFile.Writer writer = new DmpmqmsgFile.Writer(file.toString(), "Q1", "QM1", "MQToolNG")) {
            writer.Write(message);
            writer.Write(message);
            writer.Finish();
        }
        assertTrue(DmpmqmsgFile.IsDmpmqmsgFile(file.toString()));
        try (MessageFile.Input input = MessageFile.OpenAny(file.toString())) {
            List<MQMessage> messages = read(input);
            assertEquals(2, messages.size());
            MQMessage read = messages.get(0);
            assertArrayEquals(text, data(read));
            assertEquals(1208, read.characterSet);
            assertEquals(3, read.priority);
            assertArrayEquals(message.correlationId, read.correlationId);
            assertEquals("ident", read.applicationIdData);
            assertEquals("App", read.putApplicationName);
            assertEquals(1763456789120L, read.putDateTime.getTimeInMillis());
        }
    }
}
