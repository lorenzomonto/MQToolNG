package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDLH;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class RemoveDeadLetterHeaderTest {

    private static byte[] bytes(MQMessage message) throws Exception {
        message.seek(0);
        byte[] bytes = new byte[message.getMessageLength()];
        message.readFully(bytes);
        return bytes;
    }

    @Test
    void dataAfterTheHeaderKeepsItsBytesAndCharacterSet() throws Exception {
        byte[] ebcdic = "Città è più".getBytes(Charset.forName("IBM500"));
        MQDLH dlh = new MQDLH();
        dlh.setReason(MQConstants.MQRC_UNKNOWN_OBJECT_NAME);
        dlh.setFormat(MQConstants.MQFMT_STRING);
        dlh.setCodedCharSetId(500);
        dlh.setEncoding(785);
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_DEAD_LETTER_HEADER;
        message.characterSet = 1208;
        message.encoding = 273;
        message.priority = 6;
        dlh.write(message, 273, 1208);
        message.write(ebcdic);

        MQMessage removed = MQUtility.RemoveMQDLH(message);

        assertArrayEquals(ebcdic, bytes(removed));
        assertEquals(MQConstants.MQFMT_STRING, removed.format);
        assertEquals(500, removed.characterSet);
        assertEquals(785, removed.encoding);
        assertEquals(6, removed.priority);
        assertEquals("Città è più", MQUtility.GetMessageStringContent(removed, null));
    }

    @Test
    void messageWithoutHeaderIsUnchanged() throws Exception {
        byte[] utf16 = "Città".getBytes("UTF-16BE");
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_STRING;
        message.characterSet = 1200;
        message.write(utf16);

        MQMessage result = MQUtility.RemoveMQDLH(message);

        assertSame(message, result);
        assertArrayEquals(utf16, bytes(result));
        assertEquals(1200, result.characterSet);
    }
}
