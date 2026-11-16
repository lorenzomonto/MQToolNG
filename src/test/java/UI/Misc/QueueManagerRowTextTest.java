package UI.Misc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.Test;

class QueueManagerRowTextTest {

    @Test
    void shortReasonIsReadable() {
        assertEquals("Host not available (2538)", CustomTreeRender.ShortReason(MQConstants.MQRC_HOST_NOT_AVAILABLE));
        assertEquals("Not authorized (2035)", CustomTreeRender.ShortReason(MQConstants.MQRC_NOT_AUTHORIZED));
    }

    @Test
    void unknownReasonKeepsTheCode() {
        assertEquals("Error 999999", CustomTreeRender.ShortReason(999999));
    }
}
