package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.mq.MQMessage;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDLH;
import com.ibm.mq.headers.pcf.PCFMessage;
import org.junit.jupiter.api.Test;

class PcfMessageTest {

    /** A command server response dead-lettered because its reply queue was gone, like amqpcsea writes it. */
    private static MQMessage deadLetteredResponse() throws Exception {
        PCFMessage pcf = new PCFMessage(MQConstants.MQCFT_RESPONSE, MQConstants.MQCMD_INQUIRE_Q, 1, true);
        pcf.addParameter(MQConstants.MQCA_Q_NAME, "APP.QUEUE");
        pcf.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_LOCAL);
        MQDLH dlh = new MQDLH();
        dlh.setReason(MQConstants.MQRC_Q_DELETED);
        dlh.setDestQName("AMQ.69AFE20F24973209");
        dlh.setEncoding(273);
        dlh.setCodedCharSetId(819);
        dlh.setFormat(MQConstants.MQFMT_ADMIN);
        dlh.setPutApplName("amqpcsea");
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_DEAD_LETTER_HEADER;
        message.encoding = 273;
        message.characterSet = 819;
        dlh.write(message, 273, 819);
        pcf.write(message);
        return message;
    }

    @Test
    void pcfAfterTheDeadLetterHeaderIsDataNotHeaders() throws Exception {
        MQMessage message = deadLetteredResponse();
        MQUtility.MessageHeaders headers = MQUtility.ParseHeaders(message);
        assertEquals(1, headers.Headers.size());
        assertNotNull(MQUtility.FindHeader(headers.Headers, MQDLH.class));
        assertEquals(MQConstants.MQFMT_ADMIN, headers.DataFormat);
        assertEquals(message.getMessageLength() - 172, MQUtility.GetMessageContentLength(message));
    }

    @Test
    void decodesThePcfParameters() throws Exception {
        String text = MQUtility.DecodePcf(deadLetteredResponse());
        assertNotNull(text);
        assertTrue(text.startsWith("MQCFT_RESPONSE  MQCMD_INQUIRE_Q\n"), text);
        assertTrue(text.contains("APP.QUEUE\n"), text);
        assertTrue(text.contains("MQQT_LOCAL"), text);
    }

    @Test
    void textIsNotPcf() throws Exception {
        MQMessage message = new MQMessage();
        message.format = MQConstants.MQFMT_STRING;
        message.writeString("hello");
        assertNull(MQUtility.DecodePcf(message));
    }
}
