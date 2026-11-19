package MQApi.Config;

import MQApi.PCF.MQPCF;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs an MQSC command on the command server of a queue manager (PCF Escape, as runmqsc -c does),
 * also on one administered through another. Not available on z/OS.
 */
public final class Mqsc {

    /** The answer of a command: one text per response, like runmqsc shows them. */
    public static class Result {
        public boolean Ok = true;
        public final List<String> Texts = new ArrayList<>();

        /** The first error message, e.g. "AMQ8871E: Entity, principal or group not known or is invalid." */
        public String Error() {
            for (String text : Texts) {
                for (String line : text.split("\n")) {
                    if (line.matches("AMQ\\d+E:.*")) {
                        return line.trim();
                    }
                }
            }
            return Texts.isEmpty() ? "The command failed" : Texts.get(0).split("\n")[0].trim();
        }
    }

    private Mqsc() {
    }

    public static Result Run(MQQueueManager queueManager, String command) throws MQDataException, IOException {
        PCFMessage escape = new PCFMessage(MQConstants.MQCMD_ESCAPE);
        escape.addParameter(MQConstants.MQIACF_ESCAPE_TYPE, MQConstants.MQET_MQSC);
        escape.addParameter(MQConstants.MQCACF_ESCAPE_TEXT, command);
        Result result = new Result();
        for (PCFMessage response : MQPCF.Send(queueManager, escape)) {
            if (response.getCompCode() != MQConstants.MQCC_OK) {
                result.Ok = false;
            }
            String text;
            try {
                text = response.getStringParameterValue(MQConstants.MQCACF_ESCAPE_TEXT);
            } catch (MQDataException ex) {
                text = response.getCompCode() != MQConstants.MQCC_OK ? MQConstants.lookupReasonCode(response.getReason()) : null;
            }
            if (text != null) {
                result.Texts.add(text);
            }
        }
        return result;
    }
}
