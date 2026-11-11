package UI.Misc;

/**
 * Small XML lexer for the message payload: what each character is (tag, attribute, value, comment...)
 * for the syntax colors, and an indenter that only changes the whitespace between tags.
 * It never fails: text that is not well-formed XML is still colored as far as it can be.
 */
public final class XmlSyntax {

    public static final byte TEXT = 0;
    public static final byte PUNCTUATION = 1;  // < > </ /> = and the markup of declarations
    public static final byte ELEMENT = 2;
    public static final byte ATTRIBUTE = 3;
    public static final byte VALUE = 4;
    public static final byte COMMENT = 5;
    public static final byte CDATA = 6;
    public static final byte ENTITY = 7;

    private XmlSyntax() {
    }

    /** The kind of every character of the text. */
    public static byte[] Kinds(CharSequence text) {
        int length = text.length();
        byte[] kinds = new byte[length];
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '<') {
                i = markup(text, i, kinds);
            } else if (c == '&') {
                int end = indexOf(text, ";", i, Math.min(length, i + 12));
                int stop = end < 0 ? i + 1 : end + 1;
                fill(kinds, i, stop, end < 0 ? TEXT : ENTITY);
                i = stop;
            } else {
                kinds[i++] = TEXT;
            }
        }
        return kinds;
    }

    /** Colors the markup starting at '<' and returns the index after it. */
    private static int markup(CharSequence text, int start, byte[] kinds) {
        int length = text.length();
        if (startsWith(text, start, "<!--")) {
            return block(text, start, "-->", COMMENT, kinds);
        }
        if (startsWith(text, start, "<![CDATA[")) {
            int end = block(text, start, "]]>", CDATA, kinds);
            fill(kinds, start, Math.min(end, start + 9), PUNCTUATION);
            if (end <= length && end - 3 >= start + 9 && startsWith(text, end - 3, "]]>")) {
                fill(kinds, end - 3, end, PUNCTUATION);
            }
            return end;
        }
        if (startsWith(text, start, "<?") || startsWith(text, start, "<!")) {
            int end = tag(text, start, start + 2, kinds);
            fill(kinds, start, start + 2, PUNCTUATION);
            return end;
        }
        int nameStart = start + 1;
        if (nameStart < length && text.charAt(nameStart) == '/') {
            nameStart++;
        }
        fill(kinds, start, nameStart, PUNCTUATION);
        return tag(text, start, nameStart, kinds);
    }

    /** Element name, then attributes until '>'. */
    private static int tag(CharSequence text, int start, int i, byte[] kinds) {
        int length = text.length();
        int name = i;
        while (i < length && isNameChar(text.charAt(i))) {
            i++;
        }
        fill(kinds, name, i, ELEMENT);
        while (i < length) {
            char c = text.charAt(i);
            if (c == '>') {
                kinds[i] = PUNCTUATION;
                return i + 1;
            }
            if (c == '<') {
                return i; // unterminated tag: the next markup starts here
            }
            if (c == '"' || c == '\'') {
                int end = indexOf(text, String.valueOf(c), i + 1, length);
                int stop = end < 0 ? length : end + 1;
                fill(kinds, i, stop, VALUE);
                i = stop;
            } else if (isNameChar(c)) {
                int attribute = i;
                while (i < length && isNameChar(text.charAt(i))) {
                    i++;
                }
                fill(kinds, attribute, i, ATTRIBUTE);
            } else {
                kinds[i++] = Character.isWhitespace(c) ? TEXT : PUNCTUATION;
            }
        }
        return length;
    }

    private static int block(CharSequence text, int start, String terminator, byte kind, byte[] kinds) {
        int end = indexOf(text, terminator, start, text.length());
        int stop = end < 0 ? text.length() : end + terminator.length();
        fill(kinds, start, stop, kind);
        return stop;
    }

    /**
     * Puts each tag on its own line, indented by its depth. Only whitespace between two tags is
     * changed: attributes, their order, namespaces, comments, CDATA, the declaration and the text
     * of the elements stay exactly as they are.
     */
    public static String Indent(String xml, String unit) {
        StringBuilder out = new StringBuilder(xml.length() + xml.length() / 4);
        int depth = 0;
        int i = 0;
        int length = xml.length();
        Token previous = null;
        while (i < length) {
            int next = xml.indexOf('<', i);
            if (next < 0) {
                next = length;
            }
            String gap = xml.substring(i, next);
            if (next == length) {
                // Text after the last tag: kept unless it is only whitespace
                if (!gap.isBlank()) {
                    out.append(gap);
                }
                break;
            }
            Token token = token(xml, next);
            if (token.kind == Token.CLOSE) {
                depth = Math.max(0, depth - 1);
            }
            // A gap with text is kept as it is, so <name>text</name> stays on one line;
            // an empty element stays <a></a>
            boolean emptyElement = previous != null && previous.kind == Token.OPEN && token.kind == Token.CLOSE;
            // CDATA is text of its element: whitespace next to it is part of the value
            boolean nextToText = previous != null && (previous.kind == Token.TEXT || token.kind == Token.TEXT);
            if (previous == null) {
                out.append(gap.isBlank() ? "" : gap);
            } else if (gap.isBlank() && !emptyElement && !nextToText) {
                out.append('\n').append(unit.repeat(depth));
            } else {
                out.append(gap);
            }
            out.append(xml, next, token.end);
            if (token.kind == Token.OPEN) {
                depth++;
            }
            previous = token;
            i = token.end;
        }
        return out.toString();
    }

    private static final class Token {
        static final int OPEN = 0;
        static final int CLOSE = 1;
        static final int OTHER = 2; // self-closing tag, comment, declaration
        static final int TEXT = 3;  // CDATA section
        final int kind;
        final int end;

        Token(int kind, int end) {
            this.kind = kind;
            this.end = end;
        }
    }

    private static Token token(String xml, int start) {
        int length = xml.length();
        if (xml.startsWith("<!--", start)) {
            return new Token(Token.OTHER, end(xml, start, "-->"));
        }
        if (xml.startsWith("<![CDATA[", start)) {
            return new Token(Token.TEXT, end(xml, start, "]]>"));
        }
        if (xml.startsWith("<?", start)) {
            return new Token(Token.OTHER, end(xml, start, "?>"));
        }
        if (xml.startsWith("<!", start)) {
            return new Token(Token.OTHER, end(xml, start, ">"));
        }
        // End of the tag: the first '>' outside quotes
        int i = start + 1;
        char quote = 0;
        while (i < length) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                break;
            }
            i++;
        }
        int end = Math.min(length, i + 1);
        if (start + 1 < length && xml.charAt(start + 1) == '/') {
            return new Token(Token.CLOSE, end);
        }
        return new Token(i < length && xml.charAt(i - 1) == '/' ? Token.OTHER : Token.OPEN, end);
    }

    private static int end(String xml, int start, String terminator) {
        int end = xml.indexOf(terminator, start);
        return end < 0 ? xml.length() : end + terminator.length();
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '-' || c == '.';
    }

    private static boolean startsWith(CharSequence text, int start, String prefix) {
        if (start + prefix.length() > text.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            if (text.charAt(start + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(CharSequence text, String what, int from, int to) {
        for (int i = from; i + what.length() <= to; i++) {
            if (startsWith(text, i, what)) {
                return i;
            }
        }
        return -1;
    }

    private static void fill(byte[] kinds, int from, int to, byte kind) {
        for (int k = Math.max(0, from); k < Math.min(kinds.length, to); k++) {
            kinds[k] = kind;
        }
    }
}
