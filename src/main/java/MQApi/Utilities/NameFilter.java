package MQApi.Utilities;

import java.util.Locale;

/**
 * Name filter of the object lists: matches the names that contain the text, ignoring case
 * ("tmp" finds "APP.TMP.QUEUE"). Leading and trailing '*' are ignored, so the old
 * "TEST.*" style keeps working. An empty text matches every name.
 */
public class NameFilter {

    private final String text;

    private NameFilter(String text) {
        this.text = text;
    }

    public static NameFilter Compile(String searchString) {
        String search = searchString == null ? "" : searchString.trim();
        int start = 0;
        int end = search.length();
        while (start < end && search.charAt(start) == '*') {
            start++;
        }
        while (end > start && search.charAt(end - 1) == '*') {
            end--;
        }
        search = search.substring(start, end).trim();
        return new NameFilter(search.isEmpty() ? null : search.toUpperCase(Locale.ROOT));
    }

    /** True when the filter is empty, i.e. every name matches. */
    public boolean IsEmpty() {
        return text == null;
    }

    public boolean Matches(String name) {
        // Objects without a name were never filtered out by name
        return text == null || name == null || name.toUpperCase(Locale.ROOT).contains(text);
    }
}
