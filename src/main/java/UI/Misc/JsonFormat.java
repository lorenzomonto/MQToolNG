package UI.Misc;

/**
 * JSON of a message, without a library: checks that the text is JSON, indents it for reading and makes
 * it compact again. Strings are copied as they are (escapes included), only the spaces between the
 * values change.
 */
public final class JsonFormat {

    private JsonFormat() {
    }

    /** True for a JSON object or array (the usual message payloads), with nothing else around it. */
    public static boolean IsJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return false;
        }
        Parser parser = new Parser(trimmed);
        try {
            parser.value();
            parser.spaces();
            return parser.at == trimmed.length();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** True when the JSON has no line breaks (written by a program, not by a person). */
    public static boolean IsCompact(String text) {
        return text.trim().indexOf('\n') < 0;
    }

    /** The JSON indented with two spaces. */
    public static String Pretty(String text) {
        return layout(text.trim(), true);
    }

    /** The JSON without spaces between the values. */
    public static String Compact(String text) {
        return layout(text.trim(), false);
    }

    private static String layout(String text, boolean indent) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[': {
                    out.append(c);
                    // An empty object or array stays on one line: {} []
                    int next = nextNonSpace(text, i + 1);
                    if (next < text.length() && (text.charAt(next) == '}' || text.charAt(next) == ']')) {
                        out.append(text.charAt(next));
                        i = next;
                        break;
                    }
                    depth++;
                    newLine(out, depth, indent);
                    break;
                }
                case '}':
                case ']':
                    depth--;
                    newLine(out, depth, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    newLine(out, depth, indent);
                    break;
                case ':':
                    out.append(indent ? ": " : ":");
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static int nextNonSpace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void newLine(StringBuilder out, int depth, boolean indent) {
        if (indent) {
            out.append('\n');
            for (int i = 0; i < depth; i++) {
                out.append("  ");
            }
        }
    }

    // Recursive descent check of RFC 8259 JSON
    private static class Parser {
        final String text;
        int at;
        int depth;

        Parser(String text) {
            this.text = text;
        }

        void spaces() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        char peek() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("end");
            }
            return text.charAt(at);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            at++;
        }

        void value() {
            spaces();
            if (++depth > 500) {
                throw new IllegalArgumentException("too deep");
            }
            char c = peek();
            if (c == '{') {
                at++;
                spaces();
                if (peek() == '}') {
                    at++;
                } else {
                    while (true) {
                        spaces();
                        string();
                        spaces();
                        expect(':');
                        value();
                        spaces();
                        if (peek() == ',') {
                            at++;
                            continue;
                        }
                        expect('}');
                        break;
                    }
                }
            } else if (c == '[') {
                at++;
                spaces();
                if (peek() == ']') {
                    at++;
                } else {
                    while (true) {
                        value();
                        spaces();
                        if (peek() == ',') {
                            at++;
                            continue;
                        }
                        expect(']');
                        break;
                    }
                }
            } else if (c == '"') {
                string();
            } else if (text.startsWith("true", at)) {
                at += 4;
            } else if (text.startsWith("false", at)) {
                at += 5;
            } else if (text.startsWith("null", at)) {
                at += 4;
            } else {
                number();
            }
            depth--;
        }

        void string() {
            expect('"');
            while (true) {
                char c = peek();
                at++;
                if (c == '"') {
                    return;
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("control character");
                }
                if (c == '\\') {
                    char e = peek();
                    at++;
                    if (e == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (Character.digit(peek(), 16) < 0) {
                                throw new IllegalArgumentException("escape");
                            }
                            at++;
                        }
                    } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                        throw new IllegalArgumentException("escape");
                    }
                }
            }
        }

        void number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            digits();
            if (at < text.length() && text.charAt(at) == '.') {
                at++;
                digits();
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                digits();
            }
            if (at == start) {
                throw new IllegalArgumentException("value");
            }
        }

        void digits() {
            int start = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (at == start) {
                throw new IllegalArgumentException("digits");
            }
        }
    }
}
