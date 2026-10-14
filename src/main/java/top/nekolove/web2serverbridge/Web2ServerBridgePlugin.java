package top.nekolove.web2serverbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import top.nekolove.web2serverbridge.command.BridgeCommand;
import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.http.BridgeHttpServer;
import top.nekolove.web2serverbridge.http.HttpRouter;
import top.nekolove.web2serverbridge.http.OutboundReporter;
import top.nekolove.web2serverbridge.listener.ConsoleListener;
import top.nekolove.web2serverbridge.listener.MessageListener;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.status.LatencyRegistry;
import top.nekolove.web2serverbridge.status.StatusCollector;
import top.nekolove.web2serverbridge.util.TextUtil;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Web2ServerBridge 插件主类。
 *
 * <p>职责：装配各组件、注册监听器、驱动状态刷新任务、暴露对外状态。</p>
 *
 * <p>两种对外通信模式（详见 README「为什么设计成双向模式」）：</p>
 * <ul>
 *   <li><b>pull</b>：启动内置 HTTP 服务（默认 28069），后端来拉取。</li>
 *   <li><b>push</b>：主动 POST 到后端，面板服/NAT 环境无需任何入站端口。</li>
 * </ul>
 */
public final class Web2ServerBridgePlugin extends JavaPlugin {

    private BridgeConfig config;
    private Messages messages;
    private MessageRing ring;
    private LatencyRegistry latencyRegistry;
    private StatusCollector statusCollector;

    private BridgeHttpServer httpServer;
    private HttpRouter router;
    private OutboundReporter outboundReporter;

    private ConsoleListener consoleListener;
    private MessageListener messageListener;
    private BukkitTask refreshTask;
    private BukkitTask pruneTask;

    private final long startedAt = System.currentTimeMillis();
    private volatile boolean httpFailed = false;
    private volatile String httpFailReason = "";

    /* ==================== 生命周期 ==================== */

    @Override
    public void onEnable() {
        config = BridgeConfig.from(this);
        messages = loadMessages();
        if (config.isTokenPlaceholder()) {
            getLogger().warning(messages.raw("lifecycle.token-placeholder-warning"));
        }

        ring = new MessageRing(config.messageBufferSize());
        latencyRegistry = new LatencyRegistry();
        statusCollector = new StatusCollector(latencyRegistry, startedAt);

        // 先采集一次状态，避免首个请求拿到空数据
        statusCollector.refresh(config.serverName(), config.latencyMode(),
                config.latencyClientStaleSeconds(), config.motdKeepColorCodes(), messages);

        // 控制台监听（包装 System.out/err，过滤 WARN）
        installConsoleListener();

        // 游戏事件监听
        messageListener = new MessageListener(ring, latencyRegistry, config.maxMessageLength(),
                config.includeSystem(), true, messages);
        Bukkit.getPluginManager().registerEvents(messageListener, this);
        Bukkit.getPluginManager().registerEvents(new ConsoleListener.CommandListener(consoleListener), this);

        // HTTP 路由
        router = new HttpRouter(config, messages, ring,
                () -> statusCollector.current(),
                this::isPlayerOnline,
                this::broadcastWebMessage,
                msg -> getLogger().info(msg));
        router.setLatencySink((player, ping) -> latencyRegistry.report(player, ping, System.currentTimeMillis()));
        router.setAfterSendHook(() -> {
            if (outboundReporter != null) {
                outboundReporter.requestImmediateReport();
            }
        });

        // pull 模式：内置 HTTP 服务
        if (config.httpEnabled() && wantsPull()) {
            httpServer = new BridgeHttpServer(config, messages, router, msg -> getLogger().info(msg));
            try {
                httpServer.start();
                httpFailed = false;
            } catch (Exception e) {
                httpFailed = true;
                httpFailReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                getLogger().warning(messages.raw("lifecycle.http-start-failed",
                        "port", config.httpPort(), "error", httpFailReason));
            }
        } else if (!config.httpEnabled()) {
            getLogger().info(messages.raw("lifecycle.http-disabled"));
        }

        // push 模式：主动上报
        outboundReporter = new OutboundReporter(config, messages, ring,
                () -> statusCollector.current(),
                item -> {
                    // 后端下发的网页消息：广播到游戏
                    broadcastWebMessage(item.username(), item.text());
                    outboundReporter.confirm(item.id());
                },
                msg -> getLogger().info(msg),
                msg -> getLogger().warning(msg));
        if (config.outboundEnabledOnStart() && config.outboundActive()) {
            outboundReporter.start();
        }

        // 定时刷新状态
        scheduleRefresh();

        // 命令
        PluginCommand command = getCommand("web2serverbridge");
        if (command != null) {
            BridgeCommand executor = new BridgeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info(messages.raw("lifecycle.enabled",
                "mode", config.outboundMode(),
                "http", httpServer != null && httpServer.isRunning() ? String.valueOf(config.httpPort()) : "-",
                "outbound", outboundReporter.isStarted() ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (outboundReporter != null) {
            outboundReporter.stop();
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (consoleListener != null) {
            consoleListener.uninstall();
        }
        HandlerList.unregisterAll(this);
        getLogger().info(messages.raw("lifecycle.disabled"));
    }

    /** 热重载配置（端口/地址变更需重启插件） */
    public void reload() {
        int oldPort = config.httpPort();
        String oldHost = config.httpHost();
        boolean oldHttpEnabled = config.httpEnabled();

        if (outboundReporter != null) {
            outboundReporter.stop();
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (consoleListener != null) {
            consoleListener.uninstall();
        }
        cancelTasks();

        config = BridgeConfig.from(this);
        messages = loadMessages();
        installConsoleListener();

        // 监听器引用旧配置内容（阈值在读取时取新 config 实例），重建以保证一致
        if (messageListener != null) {
            HandlerList.unregisterAll(messageListener);
        }
        messageListener = new MessageListener(ring, latencyRegistry, config.maxMessageLength(),
                config.includeSystem(), true, messages);
        Bukkit.getPluginManager().registerEvents(messageListener, this);
        Bukkit.getPluginManager().registerEvents(new ConsoleListener.CommandListener(consoleListener), this);

        router = new HttpRouter(config, messages, ring,
                () -> statusCollector.current(),
                this::isPlayerOnline,
                this::broadcastWebMessage,
                msg -> getLogger().info(msg));
        router.setLatencySink((player, ping) -> latencyRegistry.report(player, ping, System.currentTimeMillis()));
        router.setAfterSendHook(() -> {
            if (outboundReporter != null) {
                outboundReporter.requestImmediateReport();
            }
        });

        if (config.httpEnabled() && wantsPull()) {
            httpServer = new BridgeHttpServer(config, messages, router, msg -> getLogger().info(msg));
            try {
                httpServer.start();
                httpFailed = false;
            } catch (Exception e) {
                httpFailed = true;
                httpFailReason = e.getMessage();
                getLogger().warning(messages.raw("lifecycle.http-start-failed",
                        "port", config.httpPort(), "error", httpFailReason));
            }
        }

        outboundReporter = new OutboundReporter(config, messages, ring,
                () -> statusCollector.current(),
                item -> {
                    broadcastWebMessage(item.username(), item.text());
                    outboundReporter.confirm(item.id());
                },
                msg -> getLogger().info(msg),
                msg -> getLogger().warning(msg));
        if (config.outboundActive()) {
            outboundReporter.start();
        }

        scheduleRefresh();

        if (oldPort != config.httpPort() || !oldHost.equals(config.httpHost()) || oldHttpEnabled != config.httpEnabled()) {
            getLogger().info(messages.raw("lifecycle.config-reloaded-http-changed",
                    "old", oldHost + ":" + oldPort,
                    "new", config.httpHost() + ":" + config.httpPort()));
        }
        getLogger().info(messages.raw("lifecycle.config-reloaded"));
    }

    private void installConsoleListener() {
        consoleListener = new ConsoleListener(ring, config.consoleLevels(), config.includeConsole(),
                config.stripAnsi(), config.maxMessageLength(), getLogger()::fine, messages,
                config.includeConsoleCommands());
        consoleListener.install();
    }

    private void scheduleRefresh() {
        cancelTasks();
        // 每秒判断一次是否到达配置的刷新间隔，保证配置变更后立即生效
        final int[] elapsed = {0};
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            elapsed[0]++;
            if (elapsed[0] >= config.statusRefreshSeconds()) {
                elapsed[0] = 0;
                try {
                    statusCollector.refresh(config.serverName(), config.latencyMode(),
                            config.latencyClientStaleSeconds(), config.motdKeepColorCodes(), messages);
                } catch (Throwable t) {
                    getLogger().warning(messages.raw("lifecycle.status-collect-failed", "error", t.getMessage()));
                }
            }
        }, 20L, 20L);

        // 每 30 秒清理一次过期的时延样本与限流窗口
        pruneTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                latencyRegistry.prune(System.currentTimeMillis(), config.latencyClientStaleSeconds());
            } catch (Throwable ignored) {
                // 忽略
            }
        }, 600L, 600L);
    }

    private void cancelTasks() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
    }

    private boolean wantsPull() {
        return !"push".equals(config.outboundMode());
    }

    /* ==================== 对外能力 ==================== */

    /** 玩家是否在线（供 require-online 校验） */
    public boolean isPlayerOnline(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Player player = Bukkit.getPlayerExact(name);
        return player != null && player.isOnline();
    }

    /**
     * 把网页消息广播到游戏内。
     *
     * @return 插件是否成功处理（玩家数为 0 时仍返回 true，表示"已受理但没有听众"）
     */
    public boolean broadcastWebMessage(String username, String text) {
        // 网页消息格式来自 messages.yml（game.web-message-format），支持占位符与 PAPI
        String line = messages.mc("game.web-message-format",
                top.nekolove.web2serverbridge.util.PapiPlaceholders.contextForName(username),
                "player", username, "text", text);

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        try {
            Component component = LegacyComponentSerializer.legacySection().deserialize(line);
            Bukkit.getServer().sendMessage(component);
        } catch (Throwable t) {
            // 退化到逐玩家发送，兼容较老实现
            try {
                for (Player p : players) {
                    p.sendMessage(line);
                }
            } catch (Throwable t2) {
                getLogger().warning(messages.raw("lifecycle.web-broadcast-failed", "error", t2.getMessage()));
                return false;
            }
        }
        getLogger().info(messages.rawOr("game.web-message-log",
                "[web] {player}: {text}", "player", username, "text", text));
        return true;
    }

    /* ==================== 状态查询（命令/调试用） ==================== */

    public BridgeConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    /**
     * 加载 messages.yml。
     * 首次运行会把 jar 内默认文件释放到插件数据目录；
     * 解析失败时回退到空表（各调用点都有内置兜底文本），保证插件仍可启动。
     */
    private Messages loadMessages() {
        try {
            saveResource("messages.yml", false);
            java.io.File file = new java.io.File(getDataFolder(), "messages.yml");
            String yaml = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Messages loaded = Messages.fromYaml(yaml);
            getLogger().info(loaded.rawOr("lifecycle.messages-loaded",
                    "messages.yml loaded, {count} entries", "count", loaded.size()));
            top.nekolove.web2serverbridge.util.PapiPlaceholders.reset();
            if (top.nekolove.web2serverbridge.util.PapiPlaceholders.isAvailable()) {
                getLogger().info(loaded.rawOr("lifecycle.papi-detected",
                        "PlaceholderAPI detected: %placeholders% in messages.yml will be resolved"));
            }
            return loaded;
        } catch (Throwable t) {
            getLogger().warning(Messages.defaults().rawOr("lifecycle.messages-load-failed",
                    "Failed to load messages.yml, falling back to built-in texts: {error}",
                    "error", t.getMessage()));
            return Messages.defaults();
        }
    }

    public MessageRing ring() {
        return ring;
    }

    public StatusCollector statusCollector() {
        return statusCollector;
    }

    public LatencyRegistry latencyRegistry() {
        return latencyRegistry;
    }

    public OutboundReporter outboundReporter() {
        return outboundReporter;
    }

    public boolean httpRunning() {
        return httpServer != null && httpServer.isRunning();
    }

    public int httpPort() {
        return httpServer == null ? -1 : httpServer.port();
    }

    public boolean httpFailed() {
        return httpFailed;
    }

    public String httpFailReason() {
        return httpFailReason;
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }

    /** 手动触发一次主动上报（/web2serverbridge push） */
    public OutboundReporter.ReportResult pushNow() {
        if (outboundReporter == null || !config.outboundActive()) {
            return new OutboundReporter.ReportResult(false, 0, 0, "outbound_not_configured");
        }
        return outboundReporter.reportOnce();
    }

    /** 生成并写入新令牌，返回新令牌（写入 config.yml 后热重载） */
    public String rotateToken() {
        String token = TextUtil.randomToken(48);
        getConfig().set("http.token", token);
        saveConfig();
        reload();
        return token;
    }

    /** 给命令输出用的一行摘要（文案来自 messages.yml 的 command.status-line*） */
    public String statusLine() {
        var snapshot = statusCollector.current();
        int online = snapshot == null ? 0 : snapshot.playersOnline;
        double tps = snapshot == null ? -1 : snapshot.tps1m;
        String http = httpRunning()
                ? messages.rawOr("command.status-line-http-on", "running({port})", "port", httpPort())
                : (httpFailed()
                        ? messages.rawOr("command.status-line-http-failed", "start failed")
                        : messages.rawOr("command.status-line-http-off", "off"));
        String outbound = outboundReporter != null && outboundReporter.isStarted()
                ? messages.rawOr("command.status-line-on", "on")
                : messages.rawOr("command.status-line-off", "off");
        return messages.rawOr("command.status-line",
                "Online {online}/{max} | TPS {tps} | HTTP {http} | Outbound {outbound} | Messages {count}",
                "online", online,
                "max", snapshot == null ? 0 : snapshot.playersMax,
                "tps", tps,
                "http", http,
                "outbound", outbound,
                "count", ring.size());
    }

    public static void send(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(message);
        }
    }
}
