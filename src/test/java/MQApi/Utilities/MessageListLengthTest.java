package MQApi.Utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Values measured on IBM MQ 10.0 browsing 1 MB messages with 72 bytes of message properties. */
class MessageListLengthTest {

    @Test
    void truncatedMessageKeepsItsDataLength() {
        // Total 1048648 (data and properties), 16384 bytes asked, 16312 bytes of data received
        assertEquals(1048576, MQUtility.DataLength(1048648, 16312, MQUtility.LIST_BUFFER));
    }

    @Test
    void wholeMessageIsTheLengthRead() {
        assertEquals(1200, MQUtility.DataLength(1272, 1200, 0));
        assertEquals(0, MQUtility.DataLength(0, 0, 0));
    }
}
