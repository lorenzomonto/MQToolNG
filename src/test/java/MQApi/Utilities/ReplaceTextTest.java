package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDLH;
import com.ibm.mq.headers.MQRFH2;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class ReplaceTextTest {

    private static byte[] data(MQMessage message) throws Exception {
        MQUtility.ReadHeaders(message);
        byte[] bytes = new byte[message.getDataLength()];
        message.readFully(bytes);
        return bytes;
    }

    @Test
    void deadLetterHeaderIsKeptAndTextUsesItsCharacterSet() throws Exception {
        MQDLH dlh = new MQDLH();
        dlh.setReason(MQConstants.MQRC_UNKNOWN_OBJECT_NAME);
        dlh.setDestQName("APP.TARGET");
        dlh.setCodedCharSetId(500);
        dlh.setFormat(MQConstants.MQFMT_STRING);
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_DEAD_LETTER_HEADER;
        message.characterSet = 819;
        dlh.write(message, message.encoding, 819);
        message.write("old".getBytes(Charset.forName("IBM500")));

        MQUtility.ReplaceText(message, "new text", () -> message.priority = 7);

        assertEquals(MQConstants.MQFMT_DEAD_LETTER_HEADER, message.format);
        assertEquals(7, message.priority);
        MQDLH kept = MQUtility.FindHeader(MQUtility.ReadHeaders(message), MQDLH.class);
        assertNotNull(kept);
        assertEquals("APP.TARGET", kept.getDestQName().trim());
        assertEquals(MQConstants.MQRC_UNKNOWN_OBJECT_NAME, kept.getReason());
        assertArrayEquals("new text".getBytes(Charset.forName("IBM500")), data(message));
        assertEquals("new text", MQUtility.GetMessageStringContent(message, null));
    }

    @Test
    void rfh2IsKept() throws Exception {
        MQRFH2 rfh2 = new MQRFH2();
        rfh2.setFieldValue("usr", "color", "blue");
        rfh2.setFormat(MQConstants.MQFMT_STRING);
        rfh2.setNameValueCCSID(1208);
        rfh2.setCodedCharSetId(1208);
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_RF_HEADER_2;
        message.characterSet = 819;
        rfh2.write(message, message.encoding, 819);
        message.writeString("old");

        MQUtility.ReplaceText(message, "città", () -> {});

        assertEquals(MQConstants.MQFMT_RF_HEADER_2, message.format);
        MQRFH2 kept = MQUtility.FindHeader(MQUtility.ReadHeaders(message), MQRFH2.class);
        assertNotNull(kept);
        assertEquals("blue", kept.getFieldValue("usr", "color"));
        assertEquals("città", MQUtility.GetMessageStringContent(message, null));
    }

    @Test
    void messageWithoutHeadersBecomesString() throws Exception {
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_NONE;
        message.write(new byte[]{1, 2, 3});

        MQUtility.ReplaceText(message, "plain", () -> {});

        assertEquals(MQConstants.MQFMT_STRING, message.format);
        assertEquals("plain", MQUtility.GetMessageStringContent(message, null));
    }
}
