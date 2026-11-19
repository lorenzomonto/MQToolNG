package MQApi.Utilities;

import MQApi.Logs.LogWriter;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Work under syncpoint. A unit of work belongs to the connection, and the connection of a queue manager
 * is shared by the whole program: the work under syncpoint runs one at a time per connection, so a
 * commit or a backout never ends the work of another task. If the program stops, the queue manager
 * backs out the work that was not committed.
 */
final class UnitOfWork {
    /** Messages per unit of work when a task commits in steps (the default MAXUMSGS is 10000). */
    static final int BATCH = 1000;

    private static final Map<MQQueueManager, Object> LOCKS = Collections.synchronizedMap(new WeakHashMap<>());

    private UnitOfWork() {
    }

    static Object Lock(MQQueueManager queueManager) {
        return LOCKS.computeIfAbsent(queueManager, key -> new Object());
    }

    static void Backout(MQQueueManager queueManager) {
        try {
            queueManager.backout();
        } catch (MQException ex) {
            LogWriter.WriteToLog("UnitOfWork", "Backout", ex);
        }
    }
}
