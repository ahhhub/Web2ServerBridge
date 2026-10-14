package top.nekolove.web2serverbridge.util;

/**
 * 占位符核心工具（<b>不依赖 Bukkit</b>）。
 *
 * <p>只负责两件事：内置 {@code {key}} 占位符替换、{@code &} 颜色代码转换。
 * PlaceholderAPI（{@code %xxx%}）相关的、需要 Bukkit 玩家对象的逻辑放在
 * {@code PapiPlaceholders} 中，避免在无服务端环境（自检/单测）加载到 Bukkit 类。</p>
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() {
    }

    /** 内置 {key} 占位符替换（未匹配的保持原样，便于排查配置） */
    public static String format(String template, Object... kv) {
        if (template == null || template.isEmpty() || kv == null || kv.length < 2) {
            return template == null ? "" : template;
        }
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = "{" + kv[i] + "}";
            String value = kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]);
            if (out.contains(key)) {
                out = out.replace(key, value);
            }
        }
        return out;
    }

    /** & 颜色代码 -> §（仅转换合法的颜色/格式代码） */
    public static String colorize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i + 1 < chars.length; i++) {
            if (chars[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(chars[i + 1]) >= 0) {
                chars[i] = '\u00A7';
            }
        }
        return new String(chars);
    }

    /**
     * 通用渲染：内置占位符 -> 外部上下文（如 PAPI）-> 可选颜色转换。
     */
    public static String render(String template, PlaceholderContext context, boolean colorize, Object... kv) {
        String out = format(template, kv);
        if (context != null && context != PlaceholderContext.NONE) {
            out = context.resolve(out);
        }
        return colorize ? colorize(out) : out;
    }
}
