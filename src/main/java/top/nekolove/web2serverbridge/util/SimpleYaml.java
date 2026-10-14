package top.nekolove.web2serverbridge.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 读取器：只实现本插件 config.yml 需要的子集，零第三方依赖。
 *
 * 支持：
 * <ul>
 *   <li>`key: value` 标量（自动去引号、识别 true/false/数字）</li>
 *   <li>按缩进嵌套的映射</li>
 *   <li>`- item` 标量列表（可为空的 `[]` / `{}` 内联形式）</li>
 *   <li>`#` 注释与空行</li>
 * </ul>
 * 不支持锚点、多行块、复杂类型——遇到无法解析的行会收集到 warnings 中而不抛异常，
 * 保证配置写错时插件仍能带默认值启动。
 */
public final class SimpleYaml {

    private SimpleYaml() {
    }

    public static final class Result {
        public final Map<String, Object> root;
        public final List<String> warnings;

        Result(Map<String, Object> root, List<String> warnings) {
            this.root = root;
            this.warnings = warnings;
        }
    }

    public static Result parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (text == null) {
            return new Result(root, warnings);
        }

        // 行 -> (缩进, 内容)
        List<int[]> indentOf = new ArrayList<>();
        List<String> contentOf = new ArrayList<>();
        String[] rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < rawLines.length; i++) {
            String raw = rawLines[i];
            String stripped = stripComment(raw);
            if (stripped.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < stripped.length() && stripped.charAt(indent) == ' ') {
                indent++;
            }
            indentOf.add(new int[]{indent, i + 1});
            contentOf.add(stripped.trim());
        }

        parseBlock(indentOf, contentOf, 0, indentOf.size(), 0, root, warnings, 0);
        return new Result(root, warnings);
    }

    /** 解析 [from, to) 范围内、缩进 >= minIndent 的块 */
    private static void parseBlock(List<int[]> meta, List<String> lines, int from, int to,
                                   int minIndent, Map<String, Object> target,
                                   List<String> warnings, int depth) {
        int i = from;
        while (i < to) {
            int indent = meta.get(i)[0];
            int lineNo = meta.get(i)[1];
            String line = lines.get(i);
            if (indent < minIndent) {
                return;
            }

            if (line.startsWith("- ")) {
                warnings.add("第 " + lineNo + " 行：此处不应出现列表项，已忽略");
                i++;
                continue;
            }

            int colon = indexOfColon(line);
            if (colon < 0) {
                warnings.add("第 " + lineNo + " 行：缺少 ':'，已忽略 -> " + line);
                i++;
                continue;
            }

            String key = unquote(line.substring(0, colon).trim());
            String value = line.substring(colon + 1).trim();

            if (!value.isEmpty()) {
                // 多行块标量：| （保留换行）或 > （折叠为空格）
                if (value.equals("|") || value.equals("|-") || value.equals(">") || value.equals(">-")) {
                    boolean folded = value.startsWith(">");
                    boolean keepTrailing = !value.endsWith("-");
                    int childStart = i + 1;
                    int childEnd = childStart;
                    int blockIndent = -1;
                    while (childEnd < to) {
                        if (meta.get(childEnd)[0] <= indent) {
                            break;
                        }
                        if (blockIndent < 0) {
                            blockIndent = meta.get(childEnd)[0];
                        }
                        childEnd++;
                    }
                    if (childIndentValid(lines, childStart, childEnd)) {
                        List<String> parts = new ArrayList<>();
                        for (int k = childStart; k < childEnd; k++) {
                            parts.add(lines.get(k));
                        }
                        String joined = folded ? String.join(" ", parts) : String.join("\n", parts);
                        if (keepTrailing && !folded) {
                            joined = joined + "\n";
                        }
                        target.put(key, joined);
                        i = childEnd;
                        continue;
                    }
                }
                target.put(key, scalar(value));
                i++;
                continue;
            }

            // 值为空：可能是嵌套映射，也可能是列表
            int childStart = i + 1;
            if (childStart >= to || meta.get(childStart)[0] <= indent) {
                target.put(key, new LinkedHashMap<String, Object>());
                i++;
                continue;
            }

            int childIndent = meta.get(childStart)[0];
            int childEnd = childStart;
            while (childEnd < to && meta.get(childEnd)[0] >= childIndent) {
                childEnd++;
            }

            if (lines.get(childStart).startsWith("- ") || lines.get(childStart).equals("-")) {
                target.put(key, parseList(meta, lines, childStart, childEnd, childIndent));
            } else {
                Map<String, Object> child = new LinkedHashMap<>();
                parseBlock(meta, lines, childStart, childEnd, childIndent, child, warnings, depth + 1);
                target.put(key, child);
            }
            i = childEnd;
        }
    }

    private static boolean childIndentValid(List<String> lines, int from, int to) {
        return from < to;
    }

    private static List<Object> parseList(List<int[]> meta, List<String> lines, int from, int to, int indent) {
        List<Object> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (meta.get(i)[0] < indent) {
                break;
            }
            String line = lines.get(i);
            if (!line.startsWith("-")) {
                continue;
            }
            String rest = line.substring(1).trim();
            if (rest.isEmpty()) {
                list.add("");
            } else {
                list.add(scalar(rest));
            }
        }
        return list;
    }

    /** 找到不在引号内的第一个冒号 */
    private static int indexOfColon(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                // 形如 key: 或 key: value；排除 value 中的 "http://"
                if (i + 1 >= line.length() || line.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                if (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t') {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static Object scalar(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.equals("[]")) {
            return new ArrayList<>();
        }
        if (v.equals("{}")) {
            return new LinkedHashMap<String, Object>();
        }
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) {
            return unquote(v);
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off")) {
            return Boolean.FALSE;
        }
        if (v.equalsIgnoreCase("null") || v.equals("~")) {
            return null;
        }
        try {
            if (v.indexOf('.') < 0 && v.indexOf('e') < 0 && v.indexOf('E') < 0) {
                long l = Long.parseLong(v);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            return v;
        }
    }

    private static String unquote(String s) {
        String v = s.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            String inner = v.substring(1, v.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    /* ==================== 取值助手 ==================== */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<String, Object> root, String key) {
        Object v = root == null ? null : root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    public static String string(Map<String, Object> map, String key, String def) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? def : s;
    }

    public static int integer(Map<String, Object> map, String key, int def) {
        Object v = map == null ? null : map.get(key);
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
        Object v = map == null ? null : map.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean(((String) v).trim());
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object item : (List<Object>) v) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (v instanceof String && !((String) v).isBlank()) {
            for (String part : ((String) v).split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }
}
