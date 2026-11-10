package UI.Helpers;

import MQApi.Logs.LogWriter;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

/**
 * Swing components may only be touched from the event dispatch thread (EDT).
 * The background tasks use this to apply their results to the UI.
 */
public class SwingThread {

    /** Runs the action on the EDT and waits for it, so callers keep their sequential flow. */
    public static void RunOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            LogWriter.WriteToLog(cause);
        }
    }
}
