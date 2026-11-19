package MQApi.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Differences between the definitions of two queue managers. */
public class ConfigComparison {

    /** An object that is only on one of the two queue managers, or that has different attributes. */
    public static class Difference {
        public final MqscObject Left;
        public final MqscObject Right;
        /** Attributes with different values: name, left value, right value (null when missing). */
        public final List<String[]> Attributes = new ArrayList<>();

        Difference(MqscObject left, MqscObject right) {
            this.Left = left;
            this.Right = right;
        }

        public MqscObject Any() {
            return Left != null ? Left : Right;
        }

        /** "Only in QM1", "Only in QM2" or the changed attributes: "MAXDEPTH, DESCR". */
        public String Summary(String leftName, String rightName) {
            if (Right == null) {
                return "Only in " + leftName;
            }
            if (Left == null) {
                return "Only in " + rightName;
            }
            List<String> names = new ArrayList<>();
            for (String[] attribute : Attributes) {
                names.add(attribute[0]);
            }
            return String.join(", ", names);
        }
    }

    /** The differences, in the order of the definitions of the left queue manager, then the ones only on the right. */
    public static List<Difference> Compare(List<MqscObject> left, List<MqscObject> right) {
        Map<String, MqscObject> rightByKey = new LinkedHashMap<>();
        for (MqscObject object : right) {
            rightByKey.put(object.Key(), object);
        }
        List<Difference> differences = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MqscObject object : left) {
            seen.add(object.Key());
            MqscObject other = rightByKey.get(object.Key());
            if (other == null) {
                differences.add(new Difference(object, null));
                continue;
            }
            Difference difference = new Difference(object, other);
            Set<String> names = new LinkedHashSet<>(object.Attributes.keySet());
            names.addAll(other.Attributes.keySet());
            for (String name : names) {
                boolean inLeft = object.Attributes.containsKey(name);
                boolean inRight = other.Attributes.containsKey(name);
                String a = object.Attributes.get(name);
                String b = other.Attributes.get(name);
                if (inLeft != inRight || !Objects.equals(a, b)) {
                    difference.Attributes.add(new String[]{name, inLeft ? display(a) : null, inRight ? display(b) : null});
                }
            }
            if (!difference.Attributes.isEmpty()) {
                differences.add(difference);
            }
        }
        for (MqscObject object : right) {
            if (!seen.contains(object.Key())) {
                differences.add(new Difference(null, object));
            }
        }
        return differences;
    }

    // A flag (NOTRIGGER) has no value; a blank value is shown as such
    private static String display(String value) {
        return value == null ? "(set)" : value;
    }
}
