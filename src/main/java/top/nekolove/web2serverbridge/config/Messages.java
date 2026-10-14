package top.nekolove.web2serverbridge.config;

import top.nekolove.web2serverbridge.util.PlaceholderContext;
import top.nekolove.web2serverbridge.util.PlaceholderResolver;
import top.nekolove.web2serverbridge.util.SimpleYaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本配置（messages.yml）读取与格式化。
 *
 * <p><b>插件所有外显字符串都来自本文件，代码中不硬编码提示文本。</b>
 * 运营方可直接改文案 / 做本地化，无需重新编译插件。</p>
 *
 * <h3>占位符支持</h3>
 * <ol>
 *   <li><b>内置占位符</b>：{@code {player}} {@code {current}} {@code {max}} {@code {version}} …
 *       —— 由调用方传入键值对。</li>
 *   <li><b>PlaceholderAPI 占位符</b>：{@code %player_name%} {@code %server_online%} …
 *       —— 服务端装了 PlaceholderAPI 时自动解析，未安装则原样保留（不报错）。</li>
 * </ol>
 *
 * <p>用法：</p>
 * <pre>
 *   messages.mc("game.web-message-format", player, "player", name, "text", text)
 *   messages.raw("command.push-ok", "messages", 12, "delivered", 3)
 * </pre>
 */
public final class Messages {

    private final Map<String, String> flat = new LinkedHashMap<>();
    private final Map<String, String> missing = new LinkedHashMap<>();

    private Messages() {
    }

    public static Messages defaults() {
        return new Messages();
    }

    /** 从 YAML 文本加载 */
    public static Messages fromYaml(String yaml) {
        Messages messages = new Messages();
        if (yaml == null || yaml.isBlank()) {
            return messages;
        }
        SimpleYaml.Result result = SimpleYaml.parse(yaml);
        messages.flatten("", result.root);
        return messages;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value);
            } else if (value != null) {
                flat.put(key, String.valueOf(value));
            }
        }
    }

    public boolean has(String key) {
        return flat.containsKey(key);
    }

    /* ---------------- 基础取值 ---------------- */

    /** 原始模板（未替换占位符），用于需要自行控制的场景 */
    public String template(String key, String fallback) {
        String t = flat.get(key);
        if (t == null) {
            if (fallback == null) {
                rememberMissing(key);
            }
            return fallback == null ? "[" + key + "]" : fallback;
        }
        return t;
    }

    /**
     * 原始文本（不做颜色转换），适用于日志与 HTTP JSON。
     * 已解析内置 {key} 占位符与外部占位符（无玩家上下文）。
     */
    public String raw(String key, Object... kv) {
        return render(template(key, null), PlaceholderContext.NONE, false, kv);
    }

    /**
     * 原始文本 + 占位符上下文（例如 PAPI 玩家上下文）。
     */
    public String raw(String key, PlaceholderContext context, Object... kv) {
        return render(template(key, null), context, false, kv);
    }

    /** 游戏内文本：渲染占位符并把 & 转 § */
    public String mc(String key, Object... kv) {
        return render(template(key, null), PlaceholderContext.NONE, true, kv);
    }

    /** 游戏内文本 + 占位符上下文 */
    public String mc(String key, PlaceholderContext context, Object... kv) {
        return render(template(key, null), context, true, kv);
    }

    /** 带默认值（配置缺失时不显示 [key] 占位） */
    public String rawOr(String key, String fallback, Object... kv) {
        String t = flat.get(key);
        if (t == null || t.isEmpty()) {
            return render(fallback == null ? "" : fallback, PlaceholderContext.NONE, false, kv);
        }
        return render(t, PlaceholderContext.NONE, false, kv);
    }

    /** 带默认值的游戏内文本 */
    public String mcOr(String key, String fallback, Object... kv) {
        String t = flat.get(key);
        String source = (t == null || t.isEmpty()) ? (fallback == null ? "" : fallback) : t;
        return render(source, PlaceholderContext.NONE, true, kv);
    }

    /** 通用渲染：内置 {key} -> 外部上下文 -> 可选颜色转换 */
    private static String render(String template, PlaceholderContext context, boolean colorize, Object... kv) {
        String out = PlaceholderResolver.format(template, kv);
        if (context != null && context != PlaceholderContext.NONE) {
            out = context.resolve(out);
        }
        return colorize ? PlaceholderResolver.colorize(out) : out;
    }

    /* ---------------- 静态工具（保留给内部工具类使用） ---------------- */

    /** & 代码 -> § 代码 */
    public static String colorize(String text) {
        return PlaceholderResolver.colorize(text);
    }

    /** 仅替换内置 {key} 占位符 */
    public static String format(String template, Object... kv) {
        return PlaceholderResolver.format(template, kv);
    }

    /** 渲染任意模板（供非 key 场景使用，例如控制台命令日志格式） */
    public static String renderTemplate(String template, PlaceholderContext context, boolean colorize, Object... kv) {
        return render(template, context, colorize, kv);
    }

    private void rememberMissing(String key) {
        missing.putIfAbsent(key, "");
    }

    /** 缺失的 key（启动时可用于告警） */
    public Map<String, String> missing() {
        return new LinkedHashMap<>(missing);
    }

    public int size() {
        return flat.size();
    }

    public Map<String, String> all() {
        return new LinkedHashMap<>(flat);
    }
}
