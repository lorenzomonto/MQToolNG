package MQApi.Utilities;

import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Message backup file (.msg). Each message is saved with its MQMD fields and its message properties,
 * both by name, and its bytes as they are on the queue (headers included, never converted).
 * The file can be gzip compressed. Layout, after the optional gzip:
 * <pre>
 * "MQToolNG message file\n" version(int) fields(queue, queue manager, created)
 * 'M' fields(MQMD) fields(properties) length(int) bytes     one per message
 * 'E' count(int)                                            end of the file
 * fields: count(int), then name(UTF) type(byte) value for each
 * </pre>
 * A file that ends early (disk full, program stopped) is read up to its last whole message.
 * Files of older versions and of MQAdminTool (Java serialization of the MQMessage fields, without the
 * message properties) are still read.
 */
public final class MessageFile {
    private static final byte[] MAGIC = "MQToolNG message file\n".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final byte MESSAGE = 'M';
    private static final byte END = 'E';
    private static final String LEGACY_ID = "MQAdminToolQueueDataFile";
    // A file comes from outside: lengths are checked before allocating (MQ messages are at most 100 MB)
    private static final int MAX_DATA = 104857600 + 65536;
    private static final int MAX_FIELDS = 100000;
    private static final int MAX_STRING = 16 * 1024 * 1024;

    private MessageFile() {
    }

    /** A file messages are saved to: this format or the one of dmpmqmsg. */
    public interface Output extends Closeable {
        void Write(MQMessage message) throws IOException;

        /** Completes the file and puts it on the disk. */
        void Finish() throws IOException;

        int Count();
    }

    /** A file messages are restored from. */
    public interface Input extends Closeable {
        /** The next message, null after the last one. */
        MQMessage Next() throws IOException;

        /** False when the file ended before its end mark. */
        boolean Complete();

        /** Number of messages the file says it has, -1 when it does not say. */
        int ExpectedCount();

        /** Queue the messages were saved from, null when not known. */
        String QueueName();
    }

    /** Opens a message file of MQToolNG, MQAdminTool or dmpmqmsg. */
    public static Input OpenAny(String path) throws IOException {
        try {
            return Open(path);
        } catch (IOException ex) {
            if (!(ex instanceof java.io.FileNotFoundException) && DmpmqmsgFile.IsDmpmqmsgFile(path)) {
                return new DmpmqmsgFile.Reader(path);
            }
            if (ex.getMessage() != null && ex.getMessage().equals("Not a message file")) {
                throw new IOException("Not a message file of MQToolNG, MQAdminTool or dmpmqmsg");
            }
            throw ex;
        }
    }

    /** Number of messages in a file of any format (reads all of it when the file does not say). */
    public static int CountAny(String path) throws IOException {
        try (Input input = OpenAny(path)) {
            if (input.ExpectedCount() >= 0 && !(input instanceof Reader && !((Reader) input).Legacy)) {
                return input.ExpectedCount();
            }
            int count = 0;
            while (input.Next() != null) {
                count++;
            }
            return count;
        }
    }

    /** Writes a message file. close() without Finish() leaves a file that is read up to its last message. */
    public static class Writer implements Output {
        private final FileOutputStream file;
        private final DataOutputStream out;
        private int count;
        private boolean finished;

        public Writer(String path, boolean compress, String queueName, String queueManagerName) throws IOException {
            file = new FileOutputStream(path);
            try {
                OutputStream stream = new BufferedOutputStream(file, 65536);
                if (compress) {
                    // syncFlush: Sync() puts on the disk everything written so far, also when compressed
                    stream = new BufferedOutputStream(new GZIPOutputStream(stream, 65536, true), 65536);
                }
                out = new DataOutputStream(stream);
                out.write(MAGIC);
                out.writeInt(VERSION);
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("Queue", queueName);
                header.put("QueueManager", queueManagerName);
                header.put("Created", System.currentTimeMillis());
                writeFields(out, header);
            } catch (IOException | RuntimeException ex) {
                file.close();
                throw ex;
            }
        }

        @Override
        public void Write(MQMessage message) throws IOException {
            out.writeByte(MESSAGE);
            writeFields(out, Descriptor(message));
            writeFields(out, Properties(message));
            message.seek(0);
            byte[] data = new byte[message.getMessageLength()];
            message.readFully(data);
            message.seek(0);
            out.writeInt(data.length);
            out.write(data);
            count++;
        }

        /** Everything written so far is on the disk. */
        public void Sync() throws IOException {
            out.flush();
            file.getFD().sync();
        }

        /** Writes the end of the file and closes it. */
        @Override
        public void Finish() throws IOException {
            out.writeByte(END);
            out.writeInt(count);
            Sync();
            finished = true;
            out.close();
        }

        @Override
        public int Count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                try {
                    out.close();
                } catch (IOException ex) {
                    file.close();
                }
            }
        }
    }

    /** Reads a message file of this version or an older one. */
    public static class Reader implements Input {
        private final InputStream stream;
        private final DataInputStream in;
        private final ObjectInputStream legacy;
        private int legacyLeft;
        private boolean complete;
        private boolean ended;
        /** Name of the queue the messages were saved from; null when not known. */
        public String QueueName;
        public String QueueManagerName;
        /** Number of messages the file says it contains, -1 when it says it only at its end. */
        public int ExpectedCount = -1;
        /** A file of an older version: without message properties. */
        public final boolean Legacy;

        private Reader(InputStream stream, boolean legacyFormat) throws IOException {
            this.stream = stream;
            this.Legacy = legacyFormat;
            if (legacyFormat) {
                in = null;
                legacy = new ObjectInputStream(stream);
                legacy.setObjectInputFilter(MQUtility.BACKUP_FILE_FILTER);
                try {
                    Object id = legacy.readObject();
                    int version = legacy.readInt();
                    if (!LEGACY_ID.equals(id) || version != 0x10001) {
                        throw new IOException("Not a message file");
                    }
                    QueueName = (String) legacy.readObject();
                    ExpectedCount = legacy.readInt();
                    legacyLeft = ExpectedCount;
                } catch (ClassNotFoundException | ClassCastException ex) {
                    throw new IOException("Not a message file", ex);
                }
            } else {
                legacy = null;
                in = new DataInputStream(stream);
                in.readFully(new byte[MAGIC.length]);
                int version = in.readInt();
                if (version > VERSION) {
                    throw new IOException("The file was made by a newer version of MQToolNG");
                }
                Map<String, Object> header = readFields(in);
                QueueName = header.get("Queue") instanceof String ? (String) header.get("Queue") : null;
                QueueManagerName = header.get("QueueManager") instanceof String ? (String) header.get("QueueManager") : null;
            }
        }

        @Override
        public MQMessage Next() throws IOException {
            if (ended) {
                return null;
            }
            try {
                return Legacy ? nextLegacy() : nextMessage();
            } catch (EOFException ex) {
                // The file ends early: the messages read so far are all it contains
                ended = true;
                return null;
            }
        }

        private MQMessage nextMessage() throws IOException {
            byte tag = in.readByte();
            if (tag == END) {
                ExpectedCount = in.readInt();
                ended = true;
                complete = true;
                return null;
            }
            if (tag != MESSAGE) {
                throw new IOException("The message file is damaged");
            }
            MQMessage message = new MQMessage();
            SetDescriptor(message, readFields(in));
            SetProperties(message, readFields(in));
            int length = in.readInt();
            if (length < 0 || length > MAX_DATA) {
                throw new IOException("The message file is damaged");
            }
            byte[] data = new byte[length];
            in.readFully(data);
            message.write(data);
            message.seek(0);
            return message;
        }

        private MQMessage nextLegacy() throws IOException {
            if (legacyLeft <= 0) {
                ended = true;
                complete = true;
                return null;
            }
            MQMessage message = new MQMessage();
            for (Field field : legacyFields()) {
                try {
                    field.set(message, legacy.readObject());
                } catch (ClassNotFoundException | IllegalArgumentException | IllegalAccessException ex) {
                    // A value of another type: the field keeps its default
                }
            }
            int length = legacy.readInt();
            if (length < 0 || length > MAX_DATA) {
                throw new IOException("The message file is damaged");
            }
            byte[] data = new byte[length];
            legacy.readFully(data);
            message.write(data);
            message.seek(0);
            legacyLeft--;
            return message;
        }

        /** True when the whole file was read, false when it ended early. */
        @Override
        public boolean Complete() {
            return complete;
        }

        @Override
        public int ExpectedCount() {
            return ExpectedCount;
        }

        @Override
        public String QueueName() {
            return QueueName;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /** Opens a message file of any version, compressed or not. */
    public static Reader Open(String path) throws IOException {
        InputStream stream = new BufferedInputStream(new FileInputStream(path), 65536);
        try {
            stream.mark(2);
            int first = stream.read();
            int second = stream.read();
            stream.reset();
            if (first == 0x1f && second == 0x8b) {
                stream = new BufferedInputStream(new GZIPInputStream(stream, 65536), 65536);
            }
            stream.mark(MAGIC.length);
            byte[] start = stream.readNBytes(MAGIC.length);
            stream.reset();
            if (Arrays.equals(start, MAGIC)) {
                return new Reader(stream, false);
            }
            if (start.length >= 2 && (start[0] & 0xff) == 0xac && (start[1] & 0xff) == 0xed) {
                return new Reader(stream, true);
            }
            throw new IOException("Not a message file");
        } catch (IOException | RuntimeException ex) {
            stream.close();
            throw ex;
        }
    }

    /** True when the file starts like a message file (compressed or not, of any version). */
    public static boolean IsMessageFile(String path) {
        try (Reader reader = Open(path)) {
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Number of messages in the file (reads all of it). */
    public static int Count(String path) throws IOException {
        try (Reader reader = Open(path)) {
            if (reader.Legacy) {
                return reader.ExpectedCount;
            }
            int count = 0;
            while (reader.Next() != null) {
                count++;
            }
            return count;
        }
    }

    // MQMD fields by their MQMD names
    static Map<String, Object> Descriptor(MQMessage m) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("Report", m.report);
        md.put("MsgType", m.messageType);
        md.put("Expiry", m.expiry);
        md.put("Feedback", m.feedback);
        md.put("Encoding", m.encoding);
        md.put("CodedCharSetId", m.characterSet);
        md.put("Format", m.format);
        md.put("Priority", m.priority);
        md.put("Persistence", m.persistence);
        md.put("MsgId", m.messageId);
        md.put("CorrelId", m.correlationId);
        md.put("BackoutCount", m.backoutCount);
        md.put("ReplyToQ", m.replyToQueueName);
        md.put("ReplyToQMgr", m.replyToQueueManagerName);
        md.put("UserIdentifier", m.userId);
        md.put("AccountingToken", m.accountingToken);
        md.put("ApplIdentityData", m.applicationIdData);
        md.put("PutApplType", m.putApplicationType);
        md.put("PutApplName", m.putApplicationName);
        md.put("PutDateTime", m.putDateTime == null ? null : m.putDateTime.getTimeInMillis());
        md.put("ApplOriginData", m.applicationOriginData);
        md.put("GroupId", m.groupId);
        md.put("MsgSeqNumber", m.messageSequenceNumber);
        md.put("Offset", m.offset);
        md.put("MsgFlags", m.messageFlags);
        md.put("OriginalLength", m.originalLength);
        return md;
    }

    // Values of an unexpected type, and names this version does not know, are left out
    static void SetDescriptor(MQMessage m, Map<String, Object> md) {
        for (Map.Entry<String, Object> entry : md.entrySet()) {
            Object v = entry.getValue();
            switch (entry.getKey()) {
                case "Report": if (v instanceof Integer) m.report = (Integer) v; break;
                case "MsgType": if (v instanceof Integer) m.messageType = (Integer) v; break;
                case "Expiry": if (v instanceof Integer) m.expiry = (Integer) v; break;
                case "Feedback": if (v instanceof Integer) m.feedback = (Integer) v; break;
                case "Encoding": if (v instanceof Integer) m.encoding = (Integer) v; break;
                case "CodedCharSetId": if (v instanceof Integer) m.characterSet = (Integer) v; break;
                case "Format": if (v instanceof String) m.format = (String) v; break;
                case "Priority": if (v instanceof Integer) m.priority = (Integer) v; break;
                case "Persistence": if (v instanceof Integer) m.persistence = (Integer) v; break;
                case "MsgId": if (v instanceof byte[]) m.messageId = (byte[]) v; break;
                case "CorrelId": if (v instanceof byte[]) m.correlationId = (byte[]) v; break;
                case "BackoutCount": if (v instanceof Integer) m.backoutCount = (Integer) v; break;
                case "ReplyToQ": if (v instanceof String) m.replyToQueueName = (String) v; break;
                case "ReplyToQMgr": if (v instanceof String) m.replyToQueueManagerName = (String) v; break;
                case "UserIdentifier": if (v instanceof String) m.userId = (String) v; break;
                case "AccountingToken": if (v instanceof byte[]) m.accountingToken = (byte[]) v; break;
                case "ApplIdentityData": if (v instanceof String) m.applicationIdData = (String) v; break;
                case "PutApplType": if (v instanceof Integer) m.putApplicationType = (Integer) v; break;
                case "PutApplName": if (v instanceof String) m.putApplicationName = (String) v; break;
                case "PutDateTime":
                    if (v instanceof Long) {
                        GregorianCalendar time = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
                        time.setTimeInMillis((Long) v);
                        m.putDateTime = time;
                    }
                    break;
                case "ApplOriginData": if (v instanceof String) m.applicationOriginData = (String) v; break;
                case "GroupId": if (v instanceof byte[]) m.groupId = (byte[]) v; break;
                case "MsgSeqNumber": if (v instanceof Integer) m.messageSequenceNumber = (Integer) v; break;
                case "Offset": if (v instanceof Integer) m.offset = (Integer) v; break;
                case "MsgFlags": if (v instanceof Integer) m.messageFlags = (Integer) v; break;
                case "OriginalLength": if (v instanceof Integer) m.originalLength = (Integer) v; break;
                default: break;
            }
        }
    }

    /** The message properties (usr, jms...) with their values; empty when it has none. */
    public static Map<String, Object> Properties(MQMessage message) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            Enumeration<String> names = message.getPropertyNames("%");
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                properties.put(name, message.getObjectProperty(name));
            }
        } catch (MQException ex) {
            MQApi.Logs.LogWriter.WriteToLog("MessageFile", "Properties", ex);
        }
        return properties;
    }

    /** Sets the message properties; one that the client does not accept is left out and logged. */
    public static void SetProperties(MQMessage message, Map<String, Object> properties) {
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            try {
                message.setObjectProperty(property.getKey(), property.getValue());
            } catch (MQException | RuntimeException ex) {
                MQApi.Logs.LogWriter.WriteToLog("MessageFile", "SetProperties " + property.getKey(), ex);
            }
        }
    }

    /** Copies the message properties of one message to another. */
    public static void CopyProperties(MQMessage from, MQMessage to) {
        SetProperties(to, Properties(from));
    }

    private static void writeFields(DataOutputStream out, Map<String, Object> fields) throws IOException {
        out.writeInt(fields.size());
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            out.writeUTF(field.getKey());
            Object v = field.getValue();
            if (v == null) {
                out.writeByte('N');
            } else if (v instanceof String) {
                byte[] text = ((String) v).getBytes(StandardCharsets.UTF_8);
                out.writeByte('S');
                out.writeInt(text.length);
                out.write(text);
            } else if (v instanceof Integer) {
                out.writeByte('I');
                out.writeInt((Integer) v);
            } else if (v instanceof Long) {
                out.writeByte('J');
                out.writeLong((Long) v);
            } else if (v instanceof Short) {
                out.writeByte('H');
                out.writeShort((Short) v);
            } else if (v instanceof Byte) {
                out.writeByte('Y');
                out.writeByte((Byte) v);
            } else if (v instanceof Boolean) {
                out.writeByte('Z');
                out.writeBoolean((Boolean) v);
            } else if (v instanceof Float) {
                out.writeByte('F');
                out.writeFloat((Float) v);
            } else if (v instanceof Double) {
                out.writeByte('D');
                out.writeDouble((Double) v);
            } else if (v instanceof byte[]) {
                out.writeByte('B');
                out.writeInt(((byte[]) v).length);
                out.write((byte[]) v);
            } else {
                // Not a type of MQ message properties: kept as text
                byte[] text = v.toString().getBytes(StandardCharsets.UTF_8);
                out.writeByte('S');
                out.writeInt(text.length);
                out.write(text);
            }
        }
    }

    private static Map<String, Object> readFields(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_FIELDS) {
            throw new IOException("The message file is damaged");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            byte type = in.readByte();
            Object value;
            switch (type) {
                case 'N': value = null; break;
                case 'S': value = new String(readBytes(in, MAX_STRING), StandardCharsets.UTF_8); break;
                case 'I': value = in.readInt(); break;
                case 'J': value = in.readLong(); break;
                case 'H': value = in.readShort(); break;
                case 'Y': value = in.readByte(); break;
                case 'Z': value = in.readBoolean(); break;
                case 'F': value = in.readFloat(); break;
                case 'D': value = in.readDouble(); break;
                case 'B': value = readBytes(in, MAX_DATA); break;
                default: throw new IOException("The message file is damaged");
            }
            fields.put(name, value);
        }
        return fields;
    }

    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) {
            throw new IOException("The message file is damaged");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    // Fields of the files of MQAdminTool: the public fields of MQMessage, in the order of the class
    private static List<Field> legacyFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : MQMessage.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getName().equals("unmappableAction")) {
                fields.add(field);
            }
        }
        return fields;
    }
}
