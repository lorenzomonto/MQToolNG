package MQApi.PCF;

import MQApi.Logs.LogWriter;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps connected PCFMessageAgents per queue manager so that each PCF command
 * does not have to open a new agent (and a new dynamic reply queue).
 * An agent is used by one thread at a time: it is taken with Acquire and
 * handed back with Release.
 */
public class PCFAgentPool {

    public interface AgentFactory {
        PCFMessageAgent Create(MQQueueManager queueManager) throws MQDataException;
    }

    private static final Map<MQQueueManager, Deque<PCFMessageAgent>> idleAgents = new IdentityHashMap<>();
    private static final Map<PCFMessageAgent, MQQueueManager> busyAgents = new IdentityHashMap<>();

    public static PCFMessageAgent Acquire(MQQueueManager queueManager, AgentFactory factory) throws MQDataException {
        PCFMessageAgent agent = null;
        synchronized (PCFAgentPool.class) {
            Deque<PCFMessageAgent> idle = idleAgents.get(queueManager);
            if (idle != null && queueManager.isConnected()) {
                agent = idle.poll();
            }
        }
        if (agent == null) {
            agent = factory.Create(queueManager);
        }
        // Callers may change this per command, so every agent starts from the default
        agent.setCheckResponses(true);
        synchronized (PCFAgentPool.class) {
            busyAgents.put(agent, queueManager);
        }
        return agent;
    }

    public static void Release(PCFMessageAgent agent) {
        if (agent == null) {
            return;
        }
        MQQueueManager queueManager;
        synchronized (PCFAgentPool.class) {
            queueManager = busyAgents.remove(agent);
            if (queueManager == null) {
                return; // already released
            }
            if (queueManager.isConnected()) {
                idleAgents.computeIfAbsent(queueManager, k -> new ArrayDeque<>()).push(agent);
                return;
            }
        }
        disconnect(agent);
    }

    /**
     * Drops an agent that is in a doubtful state (e.g. after a connection error)
     * instead of putting it back in the pool.
     */
    public static void Discard(PCFMessageAgent agent) {
        if (agent == null) {
            return;
        }
        synchronized (PCFAgentPool.class) {
            busyAgents.remove(agent);
        }
        disconnect(agent);
    }

    /** Disconnects all the idle agents of a queue manager; call it before disconnecting the queue manager. */
    public static void ReleaseAll(MQQueueManager queueManager) {
        Deque<PCFMessageAgent> idle;
        synchronized (PCFAgentPool.class) {
            idle = idleAgents.remove(queueManager);
        }
        if (idle != null) {
            for (PCFMessageAgent agent : idle) {
                disconnect(agent);
            }
        }
    }

    private static void disconnect(PCFMessageAgent agent) {
        try {
            agent.disconnect();
        } catch (MQDataException ex) {
            LogWriter.WriteToLog("PCFAgentPool", "disconnect", ex);
        }
    }
}
