package MQApi.Utilities;

import com.ibm.mq.MQMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Message files of IBM dmpmqmsg (and of the qload SupportPac it comes from): text, one "A" line per
 * MQMD field and the data in "X" lines (hexadecimal). Files written here are loaded by
 * "dmpmqmsg -o QUEUE -f file"; files written by dmpmqmsg with any data format (-dA, -dt, -dp) are read.
 * The message properties are kept in an MQRFH2 header of the data, as dmpmqmsg does.
 */
public final class DmpmqmsgFile {
    private static final int BYTES_PER_LINE = 25;

    private DmpmqmsgFile() {
    }

    /** Writes a dmpmqmsg file. */
    public static class Writer implements MessageFile.Output {
        private final FileOutputStream file;
        private final BufferedWriter out;
        private int count;
        private boolean finished;

        public Writer(String path, String queueName, String queueManagerName, String program) throws IOException {
            file = new FileOutputStream(path);
            out = new BufferedWriter(new OutputStreamWriter(file, StandardCharsets.ISO_8859_1));
            out.write("* DMPMQMSG compatible file written by " + program + " Created:"
                    + new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).format(new Date()) + "\n");
            out.write("* Qmgr  = " + (queueManagerName == null ? "" : queueManagerName) + "\n");
            out.write("* Queue = " + queueName + "\n");
            out.write("\n");
        }

        @Override
        public void Write(MQMessage m) throws IOException {
            line("VER", "2");
            line("RPT", m.report);
            line("MST", m.messageType);
            line("EXP", m.expiry);
            line("FDB", m.feedback);
            line("ENC", m.encoding);
            line("CCS", m.characterSet);
            line("FMT", pad(m.format, 8));
            line("PRI", m.priority);
            line("PER", m.persistence);
            line("MSI", MQUtility.BytesToHex(m.messageId));
            line("COI", MQUtility.BytesToHex(m.correlationId));
            line("BOC", m.backoutCount);
            line("RTQ", pad(m.replyToQueueName, 48));
            line("RTM", pad(m.replyToQueueManagerName, 48));
            line("USR", pad(m.userId, 12));
            line("ACC", MQUtility.BytesToHex(m.accountingToken));
            line("AIX", MQUtility.BytesToHex(pad(m.applicationIdData, 32).getBytes(StandardCharsets.ISO_8859_1)));
            line("PAT", m.putApplicationType);
            line("PAN", pad(m.putApplicationName, 28));
            if (m.putDateTime != null) {
                SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd");
                SimpleDateFormat time = new SimpleDateFormat("HHmmss");
                date.setTimeZone(TimeZone.getTimeZone("GMT"));
                time.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date put = m.putDateTime.getTime();
                line("PTD", date.format(put));
                line("PTT", time.format(put) + String.format("%02d", m.putDateTime.get(GregorianCalendar.MILLISECOND) / 10));
            }
            line("AOX", MQUtility.BytesToHex(pad(m.applicationOriginData, 4).getBytes(StandardCharsets.ISO_8859_1)));
            line("GRP", MQUtility.BytesToHex(m.groupId));
            line("MSQ", m.messageSequenceNumber);
            line("OFF", m.offset);
            line("MSF", m.messageFlags);
            line("ORL", m.originalLength);
            m.seek(0);
            byte[] data = new byte[m.getMessageLength()];
            m.readFully(data);
            m.seek(0);
            for (int i = 0; i < data.length; i += BYTES_PER_LINE) {
                byte[] part = java.util.Arrays.copyOfRange(data, i, Math.min(data.length, i + BYTES_PER_LINE));
                out.write("X " + MQUtility.BytesToHex(part) + "\n");
            }
            out.write("\n");
            count++;
        }

        private void line(String field, Object value) throws IOException {
            out.write("A " + field + " " + value + "\n");
        }

        @Override
        public void Finish() throws IOException {
            out.flush();
            file.getFD().sync();
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
                out.close();
            }
        }
    }

    private static String pad(String text, int length) {
        String value = text == null ? "" : text;
        StringBuilder padded = new StringBuilder(value.length() > length ? value.substring(0, length) : value);
        while (padded.length() < length) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /** True when the file starts like a dmpmqmsg file: comment lines and MQMD fields ("A VER 2"). */
    public static boolean IsDmpmqmsgFile(String path) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.ISO_8859_1))) {
            String line;
            int read = 0;
            while ((line = in.readLine()) != null && read++ < 50) {
                if (line.isBlank() || line.startsWith("*")) {
                    continue;
                }
                return line.startsWith("A ") || line.startsWith("N");
            }
        } catch (IOException ex) {
            return false;
        }
        return false;
    }

    /** Reads the messages of a dmpmqmsg file one at a time. */
    public static class Reader implements MessageFile.Input {
        private final BufferedReader in;
        private String pending;
        private int lineNumber;
        private boolean ended;
        private String queueName;

        public Reader(String path) throws IOException {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.ISO_8859_1));
        }

        @Override
        public MQMessage Next() throws IOException {
            if (ended) {
                return null;
            }
            MQMessage message = null;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            boolean inData = false;
            String line;
            while ((line = pending != null ? pending : in.readLine()) != null) {
                pending = null;
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                char type = line.charAt(0);
                if (type == '*') {
                    if (queueName == null && line.startsWith("* Queue = ")) {
                        queueName = line.substring(10).trim();
                    }
                    continue;
                }
                if (type == 'A') {
                    if (inData) {
                        // The fields of the next message
                        pending = line;
                        lineNumber--;
                        break;
                    }
                    if (message == null) {
                        message = new MQMessage();
                    }
                    field(message, line);
                    continue;
                }
                if (type == 'N') {
                    // Message index line (-di)
                    continue;
                }
                if (message == null) {
                    message = new MQMessage();
                }
                inData = true;
                data(data, line);
            }
            if (message == null) {
                ended = true;
                return null;
            }
            message.write(data.toByteArray());
            message.seek(0);
            return message;
        }

        private void field(MQMessage m, String line) throws IOException {
            if (line.length() < 5) {
                return;
            }
            String name = line.substring(2, Math.min(5, line.length()));
            // The value starts after "A XXX "; text values keep their padding
            String value = line.length() > 6 ? line.substring(6) : "";
            String number = value.trim();
            try {
                switch (name) {
                    case "RPT": m.report = Integer.parseInt(number); break;
                    case "MST": m.messageType = Integer.parseInt(number); break;
                    case "EXP": m.expiry = Integer.parseInt(number); break;
                    case "FDB": m.feedback = Integer.parseInt(number); break;
                    case "ENC": m.encoding = Integer.parseInt(number); break;
                    case "CCS": m.characterSet = Integer.parseInt(number); break;
                    case "FMT": m.format = pad(value, 8); break;
                    case "PRI": m.priority = Integer.parseInt(number); break;
                    case "PER": m.persistence = Integer.parseInt(number); break;
                    case "MSI": m.messageId = hex(number); break;
                    case "COI": m.correlationId = hex(number); break;
                    case "BOC": m.backoutCount = Integer.parseInt(number); break;
                    case "RTQ": m.replyToQueueName = value.trim(); break;
                    case "RTM": m.replyToQueueManagerName = value.trim(); break;
                    case "USR": m.userId = value.trim(); break;
                    case "ACC": m.accountingToken = hex(number); break;
                    case "AIX": m.applicationIdData = new String(hex(number), StandardCharsets.ISO_8859_1).trim(); break;
                    case "AID": m.applicationIdData = value.trim(); break;
                    case "PAT": m.putApplicationType = Integer.parseInt(number); break;
                    case "PAN": m.putApplicationName = value.trim(); break;
                    case "PTD": putDate(m, number, null); break;
                    case "PTT": putDate(m, null, number); break;
                    case "AOX": m.applicationOriginData = new String(hex(number), StandardCharsets.ISO_8859_1).trim(); break;
                    case "AOD": m.applicationOriginData = value.trim(); break;
                    case "GRP": m.groupId = hex(number); break;
                    case "MSQ": m.messageSequenceNumber = Integer.parseInt(number); break;
                    case "OFF": m.offset = Integer.parseInt(number); break;
                    case "MSF": m.messageFlags = Integer.parseInt(number); break;
                    case "ORL": m.originalLength = Integer.parseInt(number); break;
                    default: break;
                }
            } catch (NumberFormatException ex) {
                throw new IOException("Line " + lineNumber + " of the dmpmqmsg file is not valid: " + line);
            }
        }

        // Put date (yyyyMMdd) and time (HHmmsshh), in GMT, as they come
        private void putDate(MQMessage m, String date, String time) {
            GregorianCalendar put = m.putDateTime != null ? m.putDateTime : new GregorianCalendar(TimeZone.getTimeZone("GMT"));
            put.setTimeZone(TimeZone.getTimeZone("GMT"));
            if (date != null && date.length() == 8) {
                put.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(4, 6)) - 1, Integer.parseInt(date.substring(6, 8)));
            }
            if (time != null && time.length() >= 6) {
                put.set(GregorianCalendar.HOUR_OF_DAY, Integer.parseInt(time.substring(0, 2)));
                put.set(GregorianCalendar.MINUTE, Integer.parseInt(time.substring(2, 4)));
                put.set(GregorianCalendar.SECOND, Integer.parseInt(time.substring(4, 6)));
                put.set(GregorianCalendar.MILLISECOND, time.length() >= 8 ? Integer.parseInt(time.substring(6, 8)) * 10 : 0);
            }
            m.putDateTime = put;
        }

        // X: hexadecimal; S: "text" (~ escapes the next character); T: text up to the end of the line
        private void data(ByteArrayOutputStream data, String line) throws IOException {
            char type = line.charAt(0);
            String value = line.length() > 2 ? line.substring(2) : "";
            switch (type) {
                case 'X':
                    data.write(hex(value.trim()));
                    break;
                case 'S': {
                    String text = value.trim();
                    if (text.isEmpty()) {
                        // dmpmqmsg writes an empty "S" line before data that starts with bytes that are not text
                        break;
                    }
                    if (text.length() < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
                        throw new IOException("Line " + lineNumber + " of the dmpmqmsg file is not valid: " + line);
                    }
                    text = text.substring(1, text.length() - 1);
                    StringBuilder unescaped = new StringBuilder();
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c == '~' && i + 1 < text.length()) {
                            c = text.charAt(++i);
                        }
                        unescaped.append(c);
                    }
                    data.write(unescaped.toString().getBytes(StandardCharsets.ISO_8859_1));
                    break;
                }
                case 'T':
                    data.write(value.getBytes(StandardCharsets.ISO_8859_1));
                    break;
                default:
                    throw new IOException("Line " + lineNumber + " of the dmpmqmsg file is not valid: " + line);
            }
        }

        private byte[] hex(String text) throws IOException {
            if (text.length() % 2 != 0 || !text.matches("[0-9A-Fa-f]*")) {
                throw new IOException("Line " + lineNumber + " of the dmpmqmsg file has invalid hexadecimal data");
            }
            byte[] bytes = new byte[text.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
            }
            return bytes;
        }

        @Override
        public boolean Complete() {
            return true;
        }

        @Override
        public int ExpectedCount() {
            return -1;
        }

        @Override
        public String QueueName() {
            return queueName;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
