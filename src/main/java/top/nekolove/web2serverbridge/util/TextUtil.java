package top.nekolove.web2serverbridge.util;

import java.util.List;
import java.util.Locale;

/** 文本清洗工具：颜色代码剥离、长度截断、控制字符过滤。 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 服务端颜色代码是 § + 一个字符 */
    private static final char SECTION = '\u00A7';

    /** 去掉 §x 形式与 &x 形式的颜色代码（& 形式可能来自配置文件） */
    public static String stripColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) {
                i++; // 跳过颜色代码字符
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 去掉所有控制字符（含换行、制表），并把连续空白压成一个空格 */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String noColor = stripColors(text);
        StringBuilder sb = new StringBuilder(noColor.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < noColor.length(); i++) {
            char c = noColor.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                c = ' ';
            }
            if (c == ' ') {
                if (lastWasSpace) {
                    continue;
                }
                lastWasSpace = true;
            } else {
                lastWasSpace = false;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 去掉 ANSI 颜色转义序列。
     * 面板服/容器（Docker、systemd 日志、MCSManager 等）的控制台输出常带
     * {@code \u001B[32m ... \u001B[0m} 这类转义码，不清洗会把乱码传给前端。
     */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = ANSI_PATTERN.matcher(text).replaceAll("");
        // 再兜一层：去掉残留的孤立 ESC
        return out.replace("\u001B", "");
    }

    private static final java.util.regex.Pattern ANSI_PATTERN = java.util.regex.Pattern.compile(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    /**
     * 控制台行清洗：去 ANSI -> 去颜色代码 -> 压空白。
     *
     * @param stripAnsi 是否执行 ANSI 清洗
     */
    public static String cleanConsole(String text, boolean stripAnsi) {
        String base = stripAnsi ? stripAnsi(text) : text;
        return clean(base);
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    /** 是否命中敏感词（忽略大小写） */
    public static String matchBlockedWord(String text, List<String> blockWords) {
        if (text == null || blockWords == null || blockWords.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : blockWords) {
            if (word != null && !word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return null;
    }

    /** 从日志行里判断级别（用于控制台过滤） */
    public static String detectLevel(String line) {        if (line == null) {
            return "INFO";
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("WARN")) {
            return "WARN";
        }
        if (upper.contains("ERROR") || upper.contains("SEVERE")) {
            return "ERROR";
        }
        if (upper.contains("FATAL")) {
            return "ERROR";
        }
        if (upper.contains("DEBUG") || upper.contains("TRACE")) {
            return "DEBUG";
        }
        return "INFO";
    }

    /** 生成指定长度的随机令牌（URL 安全字符集） */
    public static String randomToken(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        int len = Math.max(16, length);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
