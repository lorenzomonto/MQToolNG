package MQApi.Overview;

import com.ibm.mq.constants.MQConstants;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Readable text for queue manager attribute values.
 */
public class MQFormat {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    public static String Number(Integer value) {
        return value == null ? "" : NumberFormat.getIntegerInstance().format(value);
    }

    /** "10000100" → "10.0.1.0" (VVRRMMFF). */
    public static String Version(String version) {
        if (version == null || version.length() != 8) {
            return version;
        }
        return Integer.parseInt(version.substring(0, 2)) + "." + Integer.parseInt(version.substring(2, 4)) + "."
                + Integer.parseInt(version.substring(4, 6)) + "." + Integer.parseInt(version.substring(6, 8));
    }

    public static String Platform(Integer platform) {
        if (platform == null) {
            return "";
        }
        switch (platform) {
            case MQConstants.MQPL_UNIX:
                return "Unix";
            case MQConstants.MQPL_WINDOWS_NT:
                return "Windows";
            case MQConstants.MQPL_ZOS:
                return "z/OS";
            case MQConstants.MQPL_OS400:
                return "IBM i";
            case MQConstants.MQPL_APPLIANCE:
                return "Appliance";
            default:
                return Constant(platform, "MQPL_");
        }
    }

    /**
     * Name of an MQ constant without its prefix, in sentence case:
     * (1, "MQEVR_") → "Enabled", (0, "MQLOGTYPE_") → "Circular". Aliases ("A/B") → the first.
     */
    public static String Constant(Integer value, String prefix) {
        if (value == null) {
            return "";
        }
        String name;
        try {
            name = MQConstants.lookup(value, prefix + ".*");
        } catch (RuntimeException ex) {
            name = null;
        }
        if (name == null || name.isEmpty()) {
            return String.valueOf(value);
        }
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        }
        name = name.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** "2026-11-10" + "16.50.59" (or "16:50:59") → localized date and time; null when not parsable. */
    public static String DateTime(String date, String time) {
        LocalDateTime dateTime = ParseDateTime(date, time);
        return dateTime != null ? dateTime.format(DATE_TIME) : (date != null ? (date + " " + (time != null ? time : "")).trim() : "");
    }

    public static LocalDateTime ParseDateTime(String date, String time) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(date.trim());
            LocalTime hour = time == null || time.trim().isEmpty()
                    ? LocalTime.MIDNIGHT
                    : LocalTime.parse(time.trim().replace('.', ':'));
            return LocalDateTime.of(day, hour);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String Bytes(Integer bytes) {
        if (bytes == null) {
            return "";
        }
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return Number(bytes) + " bytes (" + (bytes / (1024 * 1024)) + " MB)";
        }
        return Number(bytes) + " bytes";
    }

    /** Empty strings shown as "none". */
    public static String Text(String value) {
        return value == null || value.trim().isEmpty() ? "none" : value.trim();
    }
}
