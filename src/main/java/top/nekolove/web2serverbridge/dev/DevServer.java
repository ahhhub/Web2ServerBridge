package top.nekolove.web2serverbridge.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.http.HttpRouter;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.status.LatencyRegistry;
import top.nekolove.web2serverbridge.status.StatusCollector;
import top.nekolove.web2serverbridge.status.StatusSnapshot;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 开发/测试用的独立 HTTP 服务（<b>不依赖 Bukkit</b>）。
 *
 * <p>用途：在没有 MC 服务端的环境下，验证 {@link HttpRouter} 的全部接口行为
 * （鉴权、限流、状态输出、发消息、时延上报、控制台级别过滤）。
 * 也可用于给后端的本地联调提供假数据源。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer [port] [token] [configFile]
 * </pre>
 * 不带参数时使用默认端口 28069、令牌 dev-token-please-change、内存默认配置。
 */
public final class DevServer {

    /** 开发环境用的最小 messages 模板（模拟真实 messages.yml 的关键键） */
    static final String DEFAULT_MESSAGES_YAML = ""
            + "lifecycle:\n"
            + "  http-listening: \"HTTP listening: {url}\"\n"
            + "  outbound-enabled: \"Outbound enabled -> {url} ({seconds}s)\"\n"
            + "  outbound-disabled: \"Outbound disabled\"\n"
            + "  outbound-report-failed: \"Report failed ({count}): {error}\"\n"
            + "  outbound-unauthorized: \"Backend rejected token (HTTP {status})\"\n"
            + "  outbound-http-error: \"Backend HTTP {status}: {body}\"\n"
            + "  outbound-broadcast-failed: \"Broadcast failed (id={id}): {error}\"\n"
            + "  outbound-response-parse-failed: \"Parse response failed: {error}\"\n"
            + "  outbound-ack-failed: \"Ack failed: {error}\"\n"
            + "  web-broadcast-failed: \"Web broadcast failed: {error}\"\n"
            + "  enabled: \"Bridge enabled | mode={mode} | http={http} | outbound={outbound}\"\n"
            + "  disabled: \"Bridge disabled\"\n"
            + "  config-reloaded: \"Config reloaded\"\n"
            + "  http-disabled: \"HTTP disabled\"\n"
            + "  http-start-failed: \"HTTP failed: {error}\"\n"
            + "  token-placeholder-warning: \"Token is placeholder!\"\n"
            + "  status-collect-failed: \"Status collect failed: {error}\"\n"
            + "game:\n"
            + "  web-message-format: \"&b[Web] &f{player}&7: &f{text}\"\n"
            + "  web-message-log: \"[web] {player}: {text}\"\n"
            + "console:\n"
            + "  command-log: \"[console command] /{command}\"\n"
            + "  sender-prefix: \"\"\n"
            + "command:\n"
            + "  push-ok: \"push ok: {messages} messages, {delivered} delivered\"\n"
            + "  push-failed: \"push failed: {error}\"\n"
            + "http:\n"
            + "  unauthorized: \"Invalid or missing token\"\n"
            + "  rate-limited: \"Too many requests, please retry later\"\n"
            + "  not-found: \"Unknown endpoint: {path}\"\n"
            + "  method-not-allowed: \"Method not allowed\"\n"
            + "  bad-request: \"Bad request\"\n"
            + "  payload-too-large: \"Payload exceeds {max} bytes\"\n"
            + "  internal-error: \"Internal server error\"\n"
            + "  json-parse-failed: \"JSON parse failed: {error}\"\n"
            + "  not-ready: \"Service not ready\"\n"
            + "  send-missing-username: \"Missing username\"\n"
            + "  send-username-too-long: \"Username too long\"\n"
            + "  send-empty-text: \"Message text is empty\"\n"
            + "  send-invalid-text: \"Invalid message text\"\n"
            + "  send-blocked: \"Message contains blocked content\"\n"
            + "  send-rate-limited: \"Sending too fast, please retry later\"\n"
            + "  send-not-online: \"Player is not online\"\n"
            + "  report-missing-fields: \"Missing username or pingMs\"\n"
            + "  report-not-ready: \"Latency collection not ready\"\n"
            + "status-fields:\n"
            + "  time-sunrise: \"Sunrise\"\n"
            + "  time-morning: \"Morning\"\n"
            + "  time-noon: \"Noon\"\n"
            + "  time-afternoon: \"Afternoon\"\n"
            + "  time-sunset: \"Sunset\"\n"
            + "  time-night: \"Night\"\n"
            + "  time-midnight: \"Midnight\"\n"
            + "  weather-clear: \"Clear\"\n"
            + "  weather-rain: \"Rain\"\n"
            + "  weather-thunder: \"Thunder\"\n";

    private final MessageRing ring;
    private final LatencyRegistry latencyRegistry;
    private final StatusCollector collector;
    private final BridgeConfig config;
    private final Messages messages;
    private final HttpRouter router;
    private HttpServer server;

    public DevServer(BridgeConfig config, StatusSnapshot fakeSnapshot) {
        this.config = config;
        this.messages = Messages.fromYaml(DEFAULT_MESSAGES_YAML);
        this.ring = new MessageRing(config.messageBufferSize());
        this.latencyRegistry = new LatencyRegistry();
        this.collector = new StatusCollector(latencyRegistry, System.currentTimeMillis() - 86_400_000L);

        // 注入假快照（DevServer 不连 Bukkit）
        if (fakeSnapshot != null) {
            injectSnapshot(fakeSnapshot);
        }

        this.router = new HttpRouter(config, this.messages, ring,
                () -> getSnapshot(),
                name -> true,   // 测试环境不校验在线
                (name, text) -> true,
                msg -> System.out.println("[dev] " + msg));
        router.setLatencySink((player, ping) -> latencyRegistry.report(player, ping, System.currentTimeMillis()));
    }

    private StatusSnapshot cached;

    private StatusSnapshot getSnapshot() {
        return cached != null ? cached : collector.current();
    }

    /** 用反射把假快照塞进 collector（避免为测试改动生产 API） */
    private void injectSnapshot(StatusSnapshot snapshot) {
        this.cached = snapshot;
    }

    public void start() throws Exception {
        InetSocketAddress address = new InetSocketAddress(config.httpHost(), config.httpPort());
        HttpServer created = HttpServer.create(address, 64);
        created.createContext("/", this::handle);
        created.setExecutor(Executors.newFixedThreadPool(2));
        created.start();
        this.server = created;
        System.out.println("[dev] listening http://" + config.httpHost() + ":" + created.getAddress().getPort()
                + config.pathPrefix() + " (token: " + config.token() + ")");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public MessageRing ring() {
        return ring;
    }

    public LatencyRegistry latencyRegistry() {
        return latencyRegistry;
    }

    public void setSnapshot(StatusSnapshot snapshot) {
        this.cached = snapshot;
    }

    private void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = HttpRouter.parseQuery(exchange.getRequestURI().getRawQuery());
            String body = "POST".equalsIgnoreCase(method)
                    ? HttpRouter.readBody(exchange.getRequestBody(), config.maxBodyBytes())
                    : "";
            String ip = exchange.getRemoteAddress().getAddress().getHostAddress();

            HttpRouter.Response response = router.handle(
                    method, path, query, body, ip,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Bridge-Token"));

            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            try {
                byte[] bytes = ("{\"ok\":false,\"error\":\"dev_error\",\"message\":\"" + e.getMessage() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (Exception ignored) {
                // 忽略
            }
        } finally {
            exchange.close();
        }
    }

    /* ==================== main：假服务端 / 自检 ==================== */

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--selftest".equals(args[0])) {
            System.exit(SelfTest.run() ? 0 : 1);
        }

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 28069;
        String token = args.length > 1 ? args[1] : "dev-token-please-change-0123456789";
        String configFile = args.length > 2 ? args[2] : null;

        BridgeConfig config;
        if (configFile != null) {
            String yaml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(configFile)),
                    StandardCharsets.UTF_8);
            config = BridgeConfig.fromYaml(yaml);
        } else {
            config = BridgeConfig.defaults();
        }
        // 允许命令行覆盖端口与令牌，便于测试
        config = override(config, port, token);

        DevServer server = new DevServer(config, sampleSnapshot());
        server.start();

        // 预置几条消息，方便联调前端
        server.ring().add("system", "server", null, "Server ready (dev mock)", false);
        server.ring().add("chat", "game", "Steve", "Hello from dev mock player", false);
        server.ring().add("console", "server", null, "Done (1.234s)! For help, type \"help\"", false);
        server.latencyRegistry().report("Steve", 37, System.currentTimeMillis());

        System.out.println("[dev] seeded 3 messages, press Ctrl+C to exit");
        Thread.currentThread().join();
    }

    private static BridgeConfig override(BridgeConfig base, int port, String token) {
        // BridgeConfig 无 setter，这里通过 YAML 重新构造一份
        String yaml = "http:\n"
                + "  enabled: true\n"
                + "  host: \"127.0.0.1\"\n"
                + "  port: " + port + "\n"
                + "  path-prefix: \"/api\"\n"
                + "  token: \"" + token + "\"\n"
                + "messages:\n"
                + "  buffer-size: " + base.messageBufferSize() + "\n"
                + "  include-console: " + base.includeConsole() + "\n"
                + "  strip-ansi: " + base.stripAnsi() + "\n"
                + "  include-system: " + base.includeSystem() + "\n"
                + "  web-prefix: \"" + base.webPrefix() + "\"\n"
                + "  max-length: " + base.maxMessageLength() + "\n"
                + "  require-online: " + base.requireOnline() + "\n"
                + "status:\n"
                + "  refresh-seconds: " + base.statusRefreshSeconds() + "\n"
                + "  latency:\n"
                + "    mode: \"" + base.latencyMode() + "\"\n"
                + "    client-stale-seconds: " + base.latencyClientStaleSeconds() + "\n"
                + "security:\n"
                + "  rate-limit:\n"
                + "    max-per-minute: " + base.rateLimitPerMinute() + "\n"
                + "    send-per-minute: " + base.sendPerMinute() + "\n"
                + "server-name: \"" + base.serverName() + "\"\n";
        return BridgeConfig.fromYaml(yaml);
    }

    /** 供自检与测试使用的文本配置（解析内置最小模板） */
    public static Messages devMessages() {
        return Messages.fromYaml(DEFAULT_MESSAGES_YAML);
    }

    /** 构造一份样例状态快照，供开发与测试使用 */
    public static StatusSnapshot sampleSnapshot() {
        StatusSnapshot s = new StatusSnapshot();
        s.ready = true;
        s.collectedAt = System.currentTimeMillis();
        s.startedAt = System.currentTimeMillis() - 3_600_000L;
        s.uptimeSeconds = 3600;
        s.serverName = "dev-mock server";
        s.motd = "§bDev mock server §7· §aSurvival §7/ §eSkyBlock";
        s.version = "Purpur 26.2 (MC 1.21.x)";
        s.playersOnline = 3;
        s.playersMax = 20;
        s.playerList = new java.util.ArrayList<>(java.util.List.of("Steve", "Alex", "Herobrine"));
        s.tps1m = 19.98;
        s.tps5m = 20.0;
        s.tps15m = 19.97;
        s.mspt = 12.4;
        s.msptMax = 51.2;
        s.latency.valueMs = 37;
        s.latency.maxMs = 62;
        s.latency.minMs = 21;
        s.latency.samples = 3;
        s.latency.source = "client_report";
        s.worldName = "world";
        s.gameMode = "SURVIVAL";
        s.difficulty = "NORMAL";
        s.tick = 1234567L;
        s.day = 42;
        s.timeTicks = 6000;
        s.timeLabel = "Noon";
        s.weather = "CLEAR";
        return s;
    }
}
