package io.github.thelinuxseal.seal.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON the language server reads and writes (docs/LSP.md): objects are maps, in order;
 * arrays are lists; numbers are longs, or doubles when they have a fraction or exponent; and
 * strings, booleans and null are Java's own.
 */
final class Json {

    private Json() {
    }

    /** Thrown for text that isn't JSON. */
    static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    // ================================================================ reading

    static Object parse(String text) {
        Reader r = new Reader(text);
        r.space();
        Object value = r.value();
        r.space();
        if (r.at < text.length()) {
            throw new Malformed("text after the value at " + r.at);
        }
        return value;
    }

    private static final class Reader {
        final String s;
        int at;

        Reader(String s) {
            this.s = s;
        }

        void space() {
            while (at < s.length() && " \t\r\n".indexOf(s.charAt(at)) >= 0) {
                at++;
            }
        }

        char next() {
            if (at >= s.length()) {
                throw new Malformed("the text ends inside a value");
            }
            return s.charAt(at++);
        }

        void expect(char c) {
            if (next() != c) {
                throw new Malformed("expected '" + c + "' at " + (at - 1));
            }
        }

        Object value() {
            if (at >= s.length()) {
                throw new Malformed("expected a value at " + at);
            }
            char c = s.charAt(at);
            switch (c) {
                case '{' -> {
                    at++;
                    Map<String, Object> obj = new LinkedHashMap<>();
                    space();
                    if (s.startsWith("}", at)) {
                        at++;
                        return obj;
                    }
                    while (true) {
                        space();
                        expect('"');
                        String key = string();
                        space();
                        expect(':');
                        space();
                        obj.put(key, value());
                        space();
                        char d = next();
                        if (d == '}') {
                            return obj;
                        }
                        if (d != ',') {
                            throw new Malformed("expected ',' or '}' at " + (at - 1));
                        }
                    }
                }
                case '[' -> {
                    at++;
                    List<Object> list = new ArrayList<>();
                    space();
                    if (s.startsWith("]", at)) {
                        at++;
                        return list;
                    }
                    while (true) {
                        space();
                        list.add(value());
                        space();
                        char d = next();
                        if (d == ']') {
                            return list;
                        }
                        if (d != ',') {
                            throw new Malformed("expected ',' or ']' at " + (at - 1));
                        }
                    }
                }
                case '"' -> {
                    at++;
                    return string();
                }
                default -> {
                    if (s.startsWith("true", at)) {
                        at += 4;
                        return true;
                    }
                    if (s.startsWith("false", at)) {
                        at += 5;
                        return false;
                    }
                    if (s.startsWith("null", at)) {
                        at += 4;
                        return null;
                    }
                    return number();
                }
            }
        }

        /** A string's text, after its opening quote. */
        String string() {
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (at + 4 > s.length()) {
                            throw new Malformed("a \\u escape is cut off at " + at);
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        } catch (NumberFormatException x) {
                            throw new Malformed("bad \\u escape at " + at);
                        }
                        at += 4;
                    }
                    default -> throw new Malformed("bad escape \\" + e + " at " + (at - 1));
                }
            }
        }

        Object number() {
            int start = at;
            if (at < s.length() && s.charAt(at) == '-') {
                at++;
            }
            boolean fraction = false;
            while (at < s.length()) {
                char c = s.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || ((c == '+' || c == '-') && fraction)) {
                    fraction = true;
                    at++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, at);
            if (text.isEmpty() || text.equals("-")) {
                throw new Malformed("expected a value at " + start);
            }
            try {
                return fraction ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
            } catch (NumberFormatException x) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException y) {
                    throw new Malformed("bad number " + text + " at " + start);
                }
            }
        }
    }

    // ================================================================ writing

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String str -> quote(str, sb);
            case Boolean b -> sb.append(b);
            case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
            case Float f when f.isNaN() || f.isInfinite() -> sb.append("null");
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(l.get(i), sb);
                }
                sb.append(']');
            }
            default -> quote(value.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ================================================================ building and reading objects

    /** An object of these keys and values, in order: obj("line", 3, "character", 0). Null values are left out. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                m.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return m;
    }

    /** The value at a path of keys in nested objects, or null where one is missing: get(params, "textDocument", "uri"). */
    static Object get(Object value, String... keys) {
        for (String k : keys) {
            if (!(value instanceof Map<?, ?> m)) {
                return null;
            }
            value = m.get(k);
        }
        return value;
    }

    static String string(Object value, String... keys) {
        return get(value, keys) instanceof String s ? s : null;
    }

    static int integer(Object value, String... keys) {
        return get(value, keys) instanceof Number n ? n.intValue() : 0;
    }
}
