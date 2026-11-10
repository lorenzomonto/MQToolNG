package MQApi.PCF;

import MQApi.Logs.LogWriter;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

/**
 * PCFMessageAgent whose reply queue is opened with MQOO_READ_AHEAD.
 *
 * A command like INQUIRE_Q returns one reply message per object, and the
 * standard agent gets them with one network round trip each (2000 queues on a
 * link with 10 ms latency take more than 20 seconds). With read ahead the client
 * receives the replies in batches. Sending and checking the responses is left
 * to PCFMessageAgent.
 */
public class ReadAheadPCFMessageAgent extends PCFMessageAgent {

    private static final String DEFAULT_MODEL_QUEUE = "SYSTEM.DEFAULT.MODEL.QUEUE";

    @Override
    protected synchronized void open(MQQueueManager queueManager, String modelQueue, String replyPrefix, boolean b) throws MQDataException {
        super.open(queueManager, modelQueue, replyPrefix, b);
        MQQueue readAheadQueue;
        String readAheadQueueName;
        try {
            String model = modelQueue != null && !modelQueue.isEmpty() ? modelQueue : DEFAULT_MODEL_QUEUE;
            String prefix = replyPrefix != null && !replyPrefix.isEmpty() ? replyPrefix : "AMQ.";
            readAheadQueue = queueManager.accessQueue(model,
                    CMQC.MQOO_INPUT_EXCLUSIVE | CMQC.MQOO_FAIL_IF_QUIESCING | CMQC.MQOO_READ_AHEAD,
                    "", prefix + "*", "mqm");
            readAheadQueueName = readAheadQueue.getName();
        } catch (MQException ex) {
            // Keep the reply queue opened by PCFMessageAgent (e.g. queue managers older than MQ 7)
            LogWriter.WriteToLog("ReadAheadPCFMessageAgent", "open", ex);
            return;
        }
        MQQueue standardQueue = replyQueue;
        readAheadQueue.closeOptions = standardQueue.closeOptions;
        replyQueue = readAheadQueue;
        replyQueueName = readAheadQueueName;
        try {
            standardQueue.close();
        } catch (MQException ex) {
            LogWriter.WriteToLog("ReadAheadPCFMessageAgent", "open", ex);
        }
    }
}
