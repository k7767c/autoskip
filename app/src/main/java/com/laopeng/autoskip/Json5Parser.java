package com.laopeng.autoskip;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small JSON5 reader for remote GKD subscription manifests. */
final class Json5Parser {
    private final String source;
    private int pos;

    private Json5Parser(String source) {
        this.source = source == null ? "" : source;
    }

    static Object parse(String source) throws Exception {
        Json5Parser p = new Json5Parser(source);
        Object value = p.value();
        p.space();
        if (p.pos != p.source.length()) throw p.error("Unexpected trailing content");
        return value;
    }

    private Object value() throws Exception {
        space();
        if (pos >= source.length()) throw error("Expected a value");
        char c = source.charAt(pos);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '\'' || c == '"') return string();
        String token = bareValue();
        if ("true".equals(token)) return Boolean.TRUE;
        if ("false".equals(token)) return Boolean.FALSE;
        if ("null".equals(token)) return JSONObject.NULL;
        try {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0)
                return Double.valueOf(token);
            return Long.valueOf(token);
        } catch (NumberFormatException ignored) {
            throw error("Invalid value " + token);
        }
    }

    private JSONObject object() throws Exception {
        JSONObject out = new JSONObject();
        pos++;
        space();
        if (take('}')) return out;
        while (pos < source.length()) {
            space();
            String key;
            char c = source.charAt(pos);
            if (c == '\'' || c == '"') key = string();
            else key = identifier();
            space();
            if (!take(':')) throw error("Expected ':' after key");
            out.put(key, value());
            space();
            if (take('}')) return out;
            if (!take(',')) throw error("Expected ',' or '}'");
            space();
            if (take('}')) return out;
        }
        throw error("Unclosed object");
    }

    private JSONArray array() throws Exception {
        JSONArray out = new JSONArray();
        pos++;
        space();
        if (take(']')) return out;
        while (pos < source.length()) {
            out.put(value());
            space();
            if (take(']')) return out;
            if (!take(',')) throw error("Expected ',' or ']'");
            space();
            if (take(']')) return out;
        }
        throw error("Unclosed array");
    }

    private String string() throws Exception {
        char quote = source.charAt(pos++);
        StringBuilder out = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == quote) return out.toString();
            if (c == '\n' || c == '\r') throw error("Unescaped newline in string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= source.length()) throw error("Incomplete escape");
            char e = source.charAt(pos++);
            switch (e) {
                case '\n': break;
                case '\r': if (pos < source.length() && source.charAt(pos) == '\n') pos++; break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'v': out.append('\u000b'); break;
                case '0': out.append('\0'); break;
                case 'x': out.append((char) hex(2)); break;
                case 'u': out.append((char) hex(4)); break;
                default: out.append(e); break;
            }
        }
        throw error("Unclosed string");
    }

    private int hex(int count) throws Exception {
        if (pos + count > source.length()) throw error("Incomplete hex escape");
        int n = 0;
        for (int i = 0; i < count; i++) {
            int digit = Character.digit(source.charAt(pos++), 16);
            if (digit < 0) throw error("Invalid hex escape");
            n = (n << 4) | digit;
        }
        return n;
    }

    private String identifier() throws Exception {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') pos++;
            else break;
        }
        if (start == pos) throw error("Expected an object key");
        return source.substring(start, pos);
    }

    private String bareValue() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ',' || c == ']' || c == '}' || Character.isWhitespace(c)) break;
            pos++;
        }
        return source.substring(start, pos);
    }

    private void space() throws Exception {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isWhitespace(c) || c == '\ufeff') {
                pos++;
            } else if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < source.length() && source.charAt(pos) != '\n') pos++;
            } else if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
                int end = source.indexOf("*/", pos + 2);
                if (end < 0) throw error("Unclosed comment");
                pos = end + 2;
            } else {
                return;
            }
        }
    }

    private boolean take(char c) {
        if (pos < source.length() && source.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private Exception error(String message) {
        return new Exception(message + " at " + pos);
    }
}
