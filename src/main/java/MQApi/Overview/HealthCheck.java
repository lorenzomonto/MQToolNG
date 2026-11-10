package MQApi.Overview;

/**
 * Result of one queue manager check shown on the queue manager page.
 */
public class HealthCheck {

    public enum Severity {
        PROBLEM, WARNING, NOTE, OK
    }

    public enum ActionKind {
        NONE,
        /** Open the queue list filtered on Target. */
        SHOW_QUEUE,
        /** Browse the messages of the queue Target. */
        BROWSE_QUEUE
    }

    public final Severity Level;
    public final String Title;
    public final String Detail;
    public final ActionKind Action;
    public final String Target;

    public HealthCheck(Severity level, String title, String detail) {
        this(level, title, detail, ActionKind.NONE, null);
    }

    public HealthCheck(Severity level, String title, String detail, ActionKind action, String target) {
        this.Level = level;
        this.Title = title;
        this.Detail = detail;
        this.Action = action;
        this.Target = target;
    }
}
