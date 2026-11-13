package UI;

/**
 * Application name and version (the version comes from the jar manifest, i.e. the Maven project version).
 */
public class AppInfo {
    public static final String NAME = "MQToolNG";
    public static final String REPOSITORY_URL = "https://github.com/lorenzomonto/MQToolNG";
    public static final String DONATE_URL = "https://www.paypal.me/LorenzoMontoro";

    public static String Version() {
        String version = AppInfo.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
