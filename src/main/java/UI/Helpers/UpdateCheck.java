package UI.Helpers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub which is the latest release of MQToolNG. Only reads the public release list:
 * nothing is downloaded or installed, and nothing about the user is sent besides the request itself.
 */
public class UpdateCheck {

    public static final String RELEASES_PAGE = "https://github.com/lorenzomonto/MQToolNG/releases";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/lorenzomonto/MQToolNG/releases/latest";
    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+){0,3})$");

    /** A published release. */
    public static class Release {
        public final String Version;
        public final String PageUrl;

        Release(String version) {
            this.Version = version;
            this.PageUrl = RELEASES_PAGE + "/tag/v" + version;
        }
    }

    /** Version of the running application (from the jar manifest); null when not run from the jar. */
    public static String CurrentVersion() {
        String version = UpdateCheck.class.getPackage().getImplementationVersion();
        return version == null ? null : version.replaceFirst("-SNAPSHOT$", "");
    }

    /** The latest release on GitHub. Uses the proxy configured in the system. */
    public static Release FetchLatest() throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MQToolNG/" + (CurrentVersion() == null ? "dev" : CurrentVersion()))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", ex);
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        String version = ParseVersion(response.body());
        if (version == null) {
            throw new IOException("No version in the answer of GitHub");
        }
        return new Release(version);
    }

    /** Version number of the release tag in the JSON of GitHub ("v1.3.0" -> "1.3.0"); null when missing or not a version. */
    static String ParseVersion(String json) {
        Matcher tag = TAG_NAME.matcher(json);
        if (!tag.find()) {
            return null;
        }
        Matcher version = VERSION.matcher(tag.group(1).trim());
        return version.matches() ? version.group(1) : null;
    }

    /** Compares version numbers part by part (1.10.0 > 1.9.2); missing parts count as 0. */
    public static int Compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? parse(left[i]) : 0;
            int r = i < right.length ? parse(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
