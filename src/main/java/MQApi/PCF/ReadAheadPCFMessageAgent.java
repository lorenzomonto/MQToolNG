package MQApi.PCF;

import MQApi.Logs.LogWriter;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String REMOTE_COMMAND_QUEUE = "SYSTEM.ADMIN.COMMAND.QUEUE";

    // Queue manager the commands are sent to through the connected one; null for the connected one
    private final String remoteQueueManager;

    public ReadAheadPCFMessageAgent() {
        this(null);
    }

    public ReadAheadPCFMessageAgent(String remoteQueueManager) {
        this.remoteQueueManager = remoteQueueManager;
    }

    @Override
    protected synchronized void open(MQQueueManager queueManager, String modelQueue, String replyPrefix, boolean b) throws MQDataException {
        super.open(queueManager, modelQueue, replyPrefix, b);
        if (remoteQueueManager != null) {
            openRemoteCommandQueue(queueManager);
        }
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

    /**
     * Sends all the commands, then reads all their replies: the requests are put without waiting for
     * the network (asynchronous put) and the replies arrive together through read ahead, so a group of
     * commands costs about one network round trip instead of two per command. The responses of each
     * command are returned in the order of the commands; error responses are returned, not thrown.
     */
    public synchronized PCFMessage[][] SendAll(PCFMessage[] commands) throws MQDataException, IOException {
        Map<String, Integer> commandOfRequest = new HashMap<>();
        List<List<PCFMessage>> responses = new ArrayList<>();
        MQPutMessageOptions put = new MQPutMessageOptions();
        put.options = CMQC.MQPMO_NO_SYNCPOINT | CMQC.MQPMO_NEW_MSG_ID | CMQC.MQPMO_FAIL_IF_QUIESCING | CMQC.MQPMO_ASYNC_RESPONSE;
        try {
            for (int i = 0; i < commands.length; i++) {
                MQMessage request = setRequestMQMD(new MQMessage());
                // Each request gets its own correlation id, which the command server passes to its replies
                byte[] correlation = new byte[CMQC.MQ_CORREL_ID_LENGTH];
                RANDOM.nextBytes(correlation);
                request.correlationId = correlation;
                request.report |= CMQC.MQRO_PASS_CORREL_ID;
                commands[i].write(request);
                adminQueue.put(request, put);
                commandOfRequest.put(key(correlation), i);
                responses.add(new ArrayList<>());
            }
            int waiting = commands.length;
            while (waiting > 0) {
                MQMessage reply = new MQMessage();
                MQGetMessageOptions get = new MQGetMessageOptions();
                get.options = CMQC.MQGMO_WAIT | CMQC.MQGMO_CONVERT | CMQC.MQGMO_NO_SYNCPOINT | CMQC.MQGMO_FAIL_IF_QUIESCING;
                get.matchOptions = CMQC.MQMO_NONE;
                get.waitInterval = gmo.waitInterval;
                replyQueue.get(reply, get);
                Integer command = commandOfRequest.get(key(reply.correlationId));
                if (command == null) {
                    continue; // a late reply to an earlier command
                }
                PCFMessage response = new PCFMessage(reply);
                responses.get(command).add(response);
                if (response.getControl() == MQConstants.MQCFC_LAST) {
                    waiting--;
                }
            }
        } catch (MQException ex) {
            throw MQDataException.getMQDataException(ex);
        }
        PCFMessage[][] result = new PCFMessage[commands.length][];
        for (int i = 0; i < commands.length; i++) {
            result[i] = responses.get(i).toArray(new PCFMessage[0]);
        }
        return result;
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String key(byte[] id) {
        StringBuilder text = new StringBuilder(id.length * 2);
        for (byte b : id) {
            text.append(String.format("%02X", b));
        }
        return text.toString();
    }

    // The commands go to the command queue of the remote queue manager, through the channels of the
    // connected one; the replies come back to the reply queue opened here
    private void openRemoteCommandQueue(MQQueueManager queueManager) throws MQDataException {
        try {
            MQQueue remoteQueue = queueManager.accessQueue(REMOTE_COMMAND_QUEUE,
                    CMQC.MQOO_OUTPUT | CMQC.MQOO_FAIL_IF_QUIESCING, remoteQueueManager, null, null);
            MQQueue localQueue = adminQueue;
            adminQueue = remoteQueue;
            localQueue.close();
        } catch (MQException ex) {
            throw MQDataException.getMQDataException(ex);
        }
    }
}
