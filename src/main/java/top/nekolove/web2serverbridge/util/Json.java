package top.nekolove.web2serverbridge.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：零第三方依赖，避免与服务端内置 Gson/Jackson 版本冲突。
 *
 * 支持两种用法：
 * <ul>
 *   <li>构造输出：{@link JsonWriter} 链式写入 → {@link JsonWriter#toString()}</li>
 *   <li>解析输入：{@link #parse(String)} 得到 Map/List/String/Number/Boolean/null</li>
 * </ul>
 * 解析器对非法输入抛出 {@link JsonException}，调用方需捕获并返回 400。
 */
public final class Json {

    private Json() {
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    /* ==================== 序列化 ==================== */

    /** 流式 JSON 构造器（保证对象键顺序与写入顺序一致） */
    public static final class Writer {
        private final StringBuilder sb = new StringBuilder(256);
        private final Deque stack = new Deque();
        private boolean needComma = false;

        private static final class Frame {
            final char kind;      // 'o' = object, 'a' = array
            boolean first = true;

            Frame(char kind) {
                this.kind = kind;
            }
        }

        private static final class Deque {
            final List<Frame> items = new ArrayList<>(8);

            void push(Frame f) {
                items.add(f);
            }

            Frame peek() {
                return items.isEmpty() ? null : items.get(items.size() - 1);
            }

            void pop() {
                if (!items.isEmpty()) {
                    items.remove(items.size() - 1);
                }
            }
        }

        public Writer beginObject() {
            beforeValue();
            sb.append('{');
            stack.push(new Frame('o'));
            return this;
        }

        public Writer endObject() {
            Frame f = stack.peek();
            if (f == null || f.kind != 'o') {
                throw new JsonException("endObject 与 beginObject 不匹配");
            }
            stack.pop();
            sb.append('}');
            return this;
        }

        public Writer beginArray() {
            beforeValue();
            sb.append('[');
            stack.push(new Frame('a'));
            return this;
        }

        public Writer endArray() {
            Frame f = stack.peek();
            if (f == null || f.kind != 'a') {
                throw new JsonException("endArray 与 beginArray 不匹配");
            }
            stack.pop();
            sb.append(']');
            return this;
        }

        public Writer name(String name) {
            Frame f = stack.peek();
            if (f == null || f.kind != 'o') {
                throw new JsonException("name() 只能在对象内调用");
            }
            if (!f.first) {
                sb.append(',');
            }
            f.first = false;
            writeEscaped(name);
            sb.append(':');
            needComma = false;
            return this;
        }

        public Writer value(String value) {
            beforeValue();
            if (value == null) {
                sb.append("null");
            } else {
                writeEscaped(value);
            }
            return this;
        }

        public Writer value(Number value) {
            beforeValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Double || value instanceof Float) {
                double d = value.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("-1");
                } else {
                    sb.append(round(d));
                }
            } else {
                sb.append(value.toString());
            }
            return this;
        }

        public Writer value(boolean value) {
            beforeValue();
            sb.append(value);
            return this;
        }

        public Writer nullValue() {
            beforeValue();
            sb.append("null");
            return this;
        }

        /** 写入任意对象：Map / Collection / 数组 / 基本类型 / 其它按 toString 处理 */
        @SuppressWarnings("unchecked")
        public Writer valueObject(Object value) {
            if (value == null) {
                return nullValue();
            }
            if (value instanceof Map) {
                beginObject();
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
                    name(String.valueOf(e.getKey()));
                    valueObject(e.getValue());
                }
                endObject();
                return this;
            }
            if (value instanceof Collection) {
                beginArray();
                for (Object item : (Collection<Object>) value) {
                    valueObject(item);
                }
                endArray();
                return this;
            }
            if (value instanceof Object[]) {
                beginArray();
                for (Object item : (Object[]) value) {
                    valueObject(item);
                }
                endArray();
                return this;
            }
            if (value instanceof Boolean) {
                return value((Boolean) value);
            }
            if (value instanceof Number) {
                return value((Number) value);
            }
            return value(String.valueOf(value));
        }

        /** 便捷方法：写入一对键值 */
        public Writer put(String name, Object value) {
            name(name);
            return valueObject(value);
        }

        private void beforeValue() {
            Frame f = stack.peek();
            if (f == null) {
                return; // 顶层值
            }
            if (f.kind == 'a') {
                if (!f.first) {
                    sb.append(',');
                }
                f.first = false;
            } else {
                // 对象内的值紧跟 name():，无需逗号
            }
            needComma = false;
        }

        /** 保留 2 位小数，避免输出 19.980000000000004 这类噪声 */
        private static String round(double d) {
            double r = Math.round(d * 100.0) / 100.0;
            if (r == Math.floor(r) && !Double.isInfinite(r)) {
                return String.valueOf((long) r);
            }
            return String.valueOf(r);
        }

        private void writeEscaped(String text) {
            sb.append('"');
            int len = text.length();
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
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

        @Override
        public String toString() {
            if (!stack.items.isEmpty()) {
                throw new JsonException("JSON 结构未闭合");
            }
            return sb.toString();
        }
    }

    public static Writer writer() {
        return new Writer();
    }

    /* ==================== 反序列化 ==================== */

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("内容为空");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.eof()) {
            throw new JsonException("JSON 结尾存在多余字符，位置 " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("期望 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    /** 安全取字符串字段 */
    public static String string(Map<String, Object> map, String key, String def) {
        if (map == null) {
            return def;
        }
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int integer(Map<String, Object> map, String key, int def) {
        if (map == null) {
            return def;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        if (map == null) {
            return def;
        }
        Object v = map.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean(((String) v).trim());
        }
        return def;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean eof() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            if (eof()) {
                throw new JsonException("内容意外结束");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonException("位置 " + pos + " 期望 " + literal);
            }
            pos += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!eof() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (eof() || src.charAt(pos) != '"') {
                    throw new JsonException("位置 " + pos + " 期望对象键");
                }
                String key = readString();
                skipWhitespace();
                if (eof() || src.charAt(pos) != ':') {
                    throw new JsonException("位置 " + pos + " 期望 ':'");
                }
                pos++;
                map.put(key, readValue());
                skipWhitespace();
                if (eof()) {
                    throw new JsonException("对象未闭合");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new JsonException("位置 " + pos + " 期望 ',' 或 '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!eof() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (eof()) {
                    throw new JsonException("数组未闭合");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new JsonException("位置 " + pos + " 期望 ',' 或 ']'");
            }
        }

        private String readString() {
            pos++; // 起始引号
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("转义符未结束");
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("\\u 转义不完整");
                        }
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("非法 \\u 转义: " + hex);
                        }
                    }
                    default -> throw new JsonException("未知转义: \\" + e);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (!eof() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                pos++;
            }
            boolean floating = false;
            while (!eof()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            if (num.isEmpty() || num.equals("-") || num.equals("+")) {
                throw new JsonException("位置 " + start + " 非法数字");
            }
            try {
                if (floating) {
                    return Double.parseDouble(num);
                }
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException ex) {
                throw new JsonException("非法数字: " + num);
            }
        }
    }
}
