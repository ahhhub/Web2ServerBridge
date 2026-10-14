package top.nekolove.web2serverbridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.nekolove.web2serverbridge.util.SimpleYaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 插件配置读取。
 *
 * 直接使用 Bukkit 的 {@link FileConfiguration} 读取（服务端自带 YAML 实现），
 * 因此不需要额外依赖；{@link SimpleYaml} 仅用于在无服务端环境（离线测试）下解析。
 */
public final class BridgeConfig {

    /**
     * 默认的占位令牌。它是公开值，任何外部使用者都必须替换成自己的随机串；
     * {@link #isTokenPlaceholder()} 与启动告警都以此判定"尚未配置令牌"。
     */
    public static final String PLACEHOLDER_TOKEN = "CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN";

    private boolean httpEnabled = true;
    private String httpHost = "0.0.0.0";
    private int httpPort = 28069;
    private String pathPrefix = "/api";
    private String token = PLACEHOLDER_TOKEN;
    private int maxBodyBytes = 16384;
    private String corsAllowOrigin = "*";
    private int httpThreads = 4;

    private int messageBufferSize = 200;
    private boolean includeConsole = true;
    /**
     * 采集哪些控制台级别。
     * <b>默认只采 INFO</b>：ERROR 是控制台专有输出（游戏内玩家看不到），WARN 需求明确排除。
     */
    private final List<String> consoleLevels = new ArrayList<>(List.of("INFO"));
    private boolean stripAnsi = true;
    private boolean includeSystem = true;
    /** 是否采集后台命令（默认 false：控制台专有，游戏内不可见） */
    private boolean includeConsoleCommands = false;
    private String webPrefix = "[网页] ";
    private int maxMessageLength = 256;
    private final List<String> blockWords = new ArrayList<>();
    private boolean requireOnline = false;

    private int statusRefreshSeconds = 2;
    private String latencyMode = "auto";
    private int latencyClientStaleSeconds = 30;

    private int rateLimitPerMinute = 60;
    private int sendPerMinute = 10;
    private boolean logRequests = false;

    private String serverName = "Minecraft Server";
    private boolean motdKeepColorCodes = true;

    /* ---------- 主动上报（push 模式，面板服可用） ---------- */
    private String outboundMode = "both";
    private String outboundUrl = "";
    private String outboundAckUrl = "";
    private String outboundToken = "";
    private int outboundIntervalSeconds = 1;
    private int outboundConnectTimeoutMs = 3000;
    private int outboundReadTimeoutMs = 5000;
    private int outboundQueueMax = 500;
    private boolean outboundEnabledOnStart = true;

    private BridgeConfig() {
    }

    public static BridgeConfig defaults() {
        return new BridgeConfig();
    }

    /** 从 Bukkit 配置加载（生产路径） */
    public static BridgeConfig from(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        BridgeConfig cfg = defaults();

        cfg.httpEnabled = c.getBoolean("http.enabled", cfg.httpEnabled);
        cfg.httpHost = c.getString("http.host", cfg.httpHost);
        cfg.httpPort = c.getInt("http.port", cfg.httpPort);
        cfg.pathPrefix = normalizePrefix(c.getString("http.path-prefix", cfg.pathPrefix));
        cfg.token = c.getString("http.token", cfg.token);
        cfg.maxBodyBytes = Math.max(256, c.getInt("http.max-body-bytes", cfg.maxBodyBytes));
        cfg.corsAllowOrigin = c.getString("http.cors-allow-origin", cfg.corsAllowOrigin);
        cfg.httpThreads = Math.max(1, c.getInt("http.threads", cfg.httpThreads));

        cfg.messageBufferSize = Math.max(10, c.getInt("messages.buffer-size", cfg.messageBufferSize));
        cfg.includeConsole = c.getBoolean("messages.include-console", cfg.includeConsole);
        cfg.stripAnsi = c.getBoolean("messages.strip-ansi", cfg.stripAnsi);
        List<String> levels = c.getStringList("messages.console-levels");
        if (!levels.isEmpty()) {
            cfg.consoleLevels.clear();
            for (String level : levels) {
                cfg.consoleLevels.add(level.toUpperCase(Locale.ROOT));
            }
        }
        cfg.includeSystem = c.getBoolean("messages.include-system", cfg.includeSystem);
        cfg.includeConsoleCommands = c.getBoolean("messages.include-console-commands", cfg.includeConsoleCommands);
        cfg.webPrefix = c.getString("messages.web-prefix", cfg.webPrefix);
        cfg.maxMessageLength = Math.max(16, c.getInt("messages.max-length", cfg.maxMessageLength));
        cfg.blockWords.clear();
        for (String w : c.getStringList("messages.block-words")) {
            if (w != null && !w.isBlank()) {
                cfg.blockWords.add(w.trim());
            }
        }
        cfg.requireOnline = c.getBoolean("messages.require-online", cfg.requireOnline);

        cfg.statusRefreshSeconds = Math.max(1, c.getInt("status.refresh-seconds", cfg.statusRefreshSeconds));
        cfg.latencyMode = c.getString("status.latency.mode", cfg.latencyMode).toLowerCase(Locale.ROOT);
        cfg.latencyClientStaleSeconds = Math.max(5, c.getInt("status.latency.client-stale-seconds", cfg.latencyClientStaleSeconds));

        cfg.rateLimitPerMinute = Math.max(1, c.getInt("security.rate-limit.max-per-minute", cfg.rateLimitPerMinute));
        cfg.sendPerMinute = Math.max(1, c.getInt("security.rate-limit.send-per-minute", cfg.sendPerMinute));
        cfg.logRequests = c.getBoolean("security.log-requests", cfg.logRequests);

        cfg.serverName = c.getString("server-name", cfg.serverName);
        cfg.motdKeepColorCodes = c.getBoolean("motd-keep-color-codes", cfg.motdKeepColorCodes);

        cfg.outboundMode = c.getString("outbound.mode", cfg.outboundMode).toLowerCase(Locale.ROOT);
        cfg.outboundUrl = c.getString("outbound.url", cfg.outboundUrl);
        cfg.outboundAckUrl = c.getString("outbound.ack-url", cfg.outboundAckUrl);
        cfg.outboundToken = c.getString("outbound.token", cfg.outboundToken);
        cfg.outboundIntervalSeconds = Math.max(1, c.getInt("outbound.interval-seconds", cfg.outboundIntervalSeconds));
        cfg.outboundConnectTimeoutMs = Math.max(500, c.getInt("outbound.connect-timeout-ms", cfg.outboundConnectTimeoutMs));
        cfg.outboundReadTimeoutMs = Math.max(1000, c.getInt("outbound.read-timeout-ms", cfg.outboundReadTimeoutMs));
        cfg.outboundQueueMax = Math.max(10, c.getInt("outbound.queue-max", cfg.outboundQueueMax));
        cfg.outboundEnabledOnStart = c.getBoolean("outbound.enabled-on-start", cfg.outboundEnabledOnStart);
        return cfg;
    }

    /** 从 YAML 文本加载（离线测试路径，与 Bukkit 读取逻辑保持一致） */
    public static BridgeConfig fromYaml(String yaml) {
        SimpleYaml.Result result = SimpleYaml.parse(yaml);
        Map<String, Object> http = SimpleYaml.section(result.root, "http");
        Map<String, Object> messages = SimpleYaml.section(result.root, "messages");
        Map<String, Object> status = SimpleYaml.section(result.root, "status");
        Map<String, Object> latency = SimpleYaml.section(status, "latency");
        Map<String, Object> security = SimpleYaml.section(result.root, "security");
        Map<String, Object> rate = SimpleYaml.section(security, "rate-limit");

        BridgeConfig cfg = defaults();
        cfg.httpEnabled = SimpleYaml.bool(http, "enabled", cfg.httpEnabled);
        cfg.httpHost = SimpleYaml.string(http, "host", cfg.httpHost);
        cfg.httpPort = SimpleYaml.integer(http, "port", cfg.httpPort);
        cfg.pathPrefix = normalizePrefix(SimpleYaml.string(http, "path-prefix", cfg.pathPrefix));
        cfg.token = SimpleYaml.string(http, "token", cfg.token);
        cfg.maxBodyBytes = Math.max(256, SimpleYaml.integer(http, "max-body-bytes", cfg.maxBodyBytes));
        cfg.corsAllowOrigin = SimpleYaml.string(http, "cors-allow-origin", cfg.corsAllowOrigin);
        cfg.httpThreads = Math.max(1, SimpleYaml.integer(http, "threads", cfg.httpThreads));

        cfg.messageBufferSize = Math.max(10, SimpleYaml.integer(messages, "buffer-size", cfg.messageBufferSize));
        cfg.includeConsole = SimpleYaml.bool(messages, "include-console", cfg.includeConsole);
        cfg.stripAnsi = SimpleYaml.bool(messages, "strip-ansi", cfg.stripAnsi);
        List<String> levels = SimpleYaml.stringList(messages, "console-levels");
        if (!levels.isEmpty()) {
            cfg.consoleLevels.clear();
            for (String level : levels) {
                cfg.consoleLevels.add(level.toUpperCase(Locale.ROOT));
            }
        }
        cfg.includeSystem = SimpleYaml.bool(messages, "include-system", cfg.includeSystem);
        cfg.includeConsoleCommands = SimpleYaml.bool(messages, "include-console-commands", cfg.includeConsoleCommands);
        cfg.webPrefix = SimpleYaml.string(messages, "web-prefix", cfg.webPrefix);
        cfg.maxMessageLength = Math.max(16, SimpleYaml.integer(messages, "max-length", cfg.maxMessageLength));
        cfg.blockWords.clear();
        cfg.blockWords.addAll(SimpleYaml.stringList(messages, "block-words"));
        cfg.requireOnline = SimpleYaml.bool(messages, "require-online", cfg.requireOnline);

        cfg.statusRefreshSeconds = Math.max(1, SimpleYaml.integer(status, "refresh-seconds", cfg.statusRefreshSeconds));
        cfg.latencyMode = SimpleYaml.string(latency, "mode", cfg.latencyMode).toLowerCase(Locale.ROOT);
        cfg.latencyClientStaleSeconds = Math.max(5, SimpleYaml.integer(latency, "client-stale-seconds", cfg.latencyClientStaleSeconds));

        cfg.rateLimitPerMinute = Math.max(1, SimpleYaml.integer(rate, "max-per-minute", cfg.rateLimitPerMinute));
        cfg.sendPerMinute = Math.max(1, SimpleYaml.integer(rate, "send-per-minute", cfg.sendPerMinute));
        cfg.logRequests = SimpleYaml.bool(security, "log-requests", cfg.logRequests);

        cfg.serverName = SimpleYaml.string(result.root, "server-name", cfg.serverName);
        cfg.motdKeepColorCodes = SimpleYaml.bool(result.root, "motd-keep-color-codes", cfg.motdKeepColorCodes);

        Map<String, Object> outbound = SimpleYaml.section(result.root, "outbound");
        cfg.outboundMode = SimpleYaml.string(outbound, "mode", cfg.outboundMode).toLowerCase(Locale.ROOT);
        cfg.outboundUrl = SimpleYaml.string(outbound, "url", cfg.outboundUrl);
        cfg.outboundAckUrl = SimpleYaml.string(outbound, "ack-url", cfg.outboundAckUrl);
        cfg.outboundToken = SimpleYaml.string(outbound, "token", cfg.outboundToken);
        cfg.outboundIntervalSeconds = Math.max(1, SimpleYaml.integer(outbound, "interval-seconds", cfg.outboundIntervalSeconds));
        cfg.outboundConnectTimeoutMs = Math.max(500, SimpleYaml.integer(outbound, "connect-timeout-ms", cfg.outboundConnectTimeoutMs));
        cfg.outboundReadTimeoutMs = Math.max(1000, SimpleYaml.integer(outbound, "read-timeout-ms", cfg.outboundReadTimeoutMs));
        cfg.outboundQueueMax = Math.max(10, SimpleYaml.integer(outbound, "queue-max", cfg.outboundQueueMax));
        cfg.outboundEnabledOnStart = SimpleYaml.bool(outbound, "enabled-on-start", cfg.outboundEnabledOnStart);
        return cfg;
    }

    /** 生成配置快照（用于 /web2serverbridge status 输出） */
    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("http.enabled", httpEnabled);
        map.put("http.host", httpHost);
        map.put("http.port", httpPort);
        map.put("http.pathPrefix", pathPrefix);
        map.put("http.tokenConfigured", !isTokenPlaceholder());
        map.put("messages.bufferSize", messageBufferSize);
        map.put("messages.includeConsole", includeConsole);
        map.put("messages.stripAnsi", stripAnsi);
        map.put("messages.consoleLevels", new ArrayList<>(consoleLevels));
        map.put("messages.maxLength", maxMessageLength);
        map.put("messages.requireOnline", requireOnline);
        map.put("status.refreshSeconds", statusRefreshSeconds);
        map.put("status.latencyMode", latencyMode);
        map.put("security.rateLimitPerMinute", rateLimitPerMinute);
        map.put("security.sendPerMinute", sendPerMinute);
        map.put("serverName", serverName);
        map.put("outbound.mode", outboundMode);
        map.put("outbound.url", outboundUrl);
        map.put("outbound.intervalSeconds", outboundIntervalSeconds);
        return map;
    }

    /** 主动上报是否启用（mode=push/both 且配置了 url） */
    public boolean outboundActive() {
        if ("pull".equals(outboundMode)) {
            return false;
        }
        return outboundUrl != null && !outboundUrl.isBlank();
    }

    /** 生效的推送令牌：outbound.token 优先，缺省复用 http.token */
    public String effectiveOutboundToken() {
        if (outboundToken != null && !outboundToken.isBlank()) {
            return outboundToken;
        }
        return token;
    }

    /** 推导确认地址：把 .../report 换成 .../ack */
    public String effectiveAckUrl() {
        if (outboundAckUrl != null && !outboundAckUrl.isBlank()) {
            return outboundAckUrl;
        }
        if (outboundUrl == null) {
            return "";
        }
        if (outboundUrl.endsWith("/report")) {
            return outboundUrl.substring(0, outboundUrl.length() - "/report".length()) + "/ack";
        }
        return outboundUrl;
    }

    /** 令牌是否仍是占位值（用于启动告警） */
    public boolean isTokenPlaceholder() {
        if (token == null || token.isBlank() || token.length() < 16) {
            return true;
        }
        // 默认占位符（CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN）以及任何 CHANGE_ME 前缀的示例值
        return PLACEHOLDER_TOKEN.equalsIgnoreCase(token.trim()) || token.startsWith("CHANGE_ME");
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/api";
        }
        String p = prefix.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /* ---------------- getters ---------------- */

    public boolean httpEnabled() {
        return httpEnabled;
    }

    public String httpHost() {
        return httpHost;
    }

    public int httpPort() {
        return httpPort;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public String token() {
        return token;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public String corsAllowOrigin() {
        return corsAllowOrigin;
    }

    public int httpThreads() {
        return httpThreads;
    }

    public int messageBufferSize() {
        return messageBufferSize;
    }

    public boolean includeConsole() {
        return includeConsole;
    }

    public List<String> consoleLevels() {
        return consoleLevels;
    }

    public boolean stripAnsi() {
        return stripAnsi;
    }

    public boolean includeSystem() {
        return includeSystem;
    }

    public boolean includeConsoleCommands() {
        return includeConsoleCommands;
    }

    public String webPrefix() {
        return webPrefix;
    }

    public int maxMessageLength() {
        return maxMessageLength;
    }

    public List<String> blockWords() {
        return blockWords;
    }

    public boolean requireOnline() {
        return requireOnline;
    }

    public int statusRefreshSeconds() {
        return statusRefreshSeconds;
    }

    public String latencyMode() {
        return latencyMode;
    }

    public int latencyClientStaleSeconds() {
        return latencyClientStaleSeconds;
    }

    public int rateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public int sendPerMinute() {
        return sendPerMinute;
    }

    public boolean logRequests() {
        return logRequests;
    }

    public String serverName() {
        return serverName;
    }

    public boolean motdKeepColorCodes() {
        return motdKeepColorCodes;
    }

    public String outboundMode() {
        return outboundMode;
    }

    public String outboundUrl() {
        return outboundUrl;
    }

    public String outboundAckUrl() {
        return outboundAckUrl;
    }

    public String outboundToken() {
        return outboundToken;
    }

    public int outboundIntervalSeconds() {
        return outboundIntervalSeconds;
    }

    public int outboundConnectTimeoutMs() {
        return outboundConnectTimeoutMs;
    }

    public int outboundReadTimeoutMs() {
        return outboundReadTimeoutMs;
    }

    public int outboundQueueMax() {
        return outboundQueueMax;
    }

    public boolean outboundEnabledOnStart() {
        return outboundEnabledOnStart;
    }
}
