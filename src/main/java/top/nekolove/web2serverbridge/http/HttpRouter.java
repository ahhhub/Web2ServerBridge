package top.nekolove.web2serverbridge.http;

import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.message.BridgeMessage;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.status.StatusSnapshot;
import top.nekolove.web2serverbridge.util.Json;
import top.nekolove.web2serverbridge.util.TextUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HTTP 请求路由：把 {@code /api/status}、{@code /api/messages}、{@code /api/send}、
 * {@code /api/report} 四个端点映射到插件内部功能。
 *
 * <p>与具体 HTTP 服务器解耦（只依赖普通对象），便于单元测试。</p>
 *
 * <p>安全：令牌校验 + 单 IP 频率限制 + 消息长度/敏感词过滤 + 可选在线校验。
 * 网页消息以配置的前缀广播到游戏，并进入消息环（前端能立刻看到自己发的）。</p>
 *
 * <p><b>所有面向调用方的文本都来自 messages.yml</b>（{@link Messages}），
 * 代码中不硬编码提示文案。</p>
 */
public final class HttpRouter {

    /** 发送消息的结果 */
    public record SendResult(boolean ok, int status, String error, String message, long seq, boolean broadcast) {
        public static SendResult success(long seq, boolean broadcast) {
            return new SendResult(true, 200, null, null, seq, broadcast);
        }

        public static SendResult fail(int status, String error, String message) {
            return new SendResult(false, status, error, message, 0, false);
        }
    }

    /** 通用响应 */
    public record Response(int status, String contentType, String body) {
        public static Response json(int status, String body) {
            return new Response(status, "application/json; charset=utf-8", body);
        }
    }

    private final BridgeConfig config;
    private final Messages messages;
    private final MessageRing ring;
    private final Supplier<StatusSnapshot> snapshotSupplier;
    private final Function<String, Boolean> onlineChecker;
    private final Consumer<String> logger;
    private final BiFunction<String, String, Boolean> broadcaster;

    /** IP -> 窗口内计数（简化令牌桶：每分钟清零） */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Window> sendWindows = new ConcurrentHashMap<>();
    private BiConsumer<String, Integer> latencySink;

    private static final class Window {
        long start = System.currentTimeMillis();
        int count = 0;

        synchronized boolean allow(int max) {
            long now = System.currentTimeMillis();
            if (now - start > 60_000L) {
                start = now;
                count = 0;
            }
            count++;
            return count <= max;
        }
    }

    public HttpRouter(BridgeConfig config,
                      Messages messages,
                      MessageRing ring,
                      Supplier<StatusSnapshot> snapshotSupplier,
                      Function<String, Boolean> onlineChecker,
                      BiFunction<String, String, Boolean> broadcaster,
                      Consumer<String> logger) {
        this.config = config;
        this.messages = messages == null ? Messages.defaults() : messages;
        this.ring = ring;
        this.snapshotSupplier = snapshotSupplier;
        this.onlineChecker = onlineChecker == null ? name -> true : onlineChecker;
        this.broadcaster = broadcaster;
        this.logger = logger == null ? s -> {
        } : logger;
    }

    /** 时延上报回调（由插件注入 LatencyRegistry） */
    public void setLatencySink(BiConsumer<String, Integer> sink) {
        this.latencySink = sink;
    }

    /**
     * 网页消息广播后的回调（由插件注入）。
     *
     * 用途：push 模式下收到后端直推的消息后，立即触发一次上报，
     * 让后端尽快收到确认、把消息从待投递队列里出队，从而把"排队中"的提示时间压到最短。
     */
    public void setAfterSendHook(Runnable hook) {
        this.afterSendHook = hook;
    }

    private Runnable afterSendHook;

    /* ==================== 入口 ==================== */

    public Response handle(String method, String path, Map<String, String> query, String body,
                           String clientIp, String authHeader, String tokenHeader) {
        String prefix = config.pathPrefix();
        String route = path;
        if (route.startsWith(prefix)) {
            route = route.substring(prefix.length());
        }
        if (route.isEmpty()) {
            route = "/";
        }
        String normalized = route.toLowerCase(Locale.ROOT);
        String queryToken = query == null ? null : query.get("token");

        // 健康探测与延迟测量不需要令牌（便于反代/监控探活与 RTT 采样）
        if (normalized.equals("/ping") || normalized.equals("/health")) {
            Json.Writer w = Json.writer().beginObject();
            w.put("ok", true);
            w.put("service", "web2serverbridge");
            w.put("version", PluginVersion.VERSION);
            w.endObject();
            return Response.json(200, w.toString());
        }

        /*
         * 延迟测量端点：后端用它测量「后端 ↔ 插件所在 MC 服务端」的 HTTP 往返时延。
         *
         * 为什么需要：网页上展示的"时延"应当反映前端到 MC 服务器的通信延迟。
         * 但插件运行在服务端进程内，无法直接测"玩家客户端的 ping"（那需要配套客户端模组上报）。
         * 因此这里提供可精确测量的链路 RTT：后端连续调用两次取最小值（消除单次抖动），
         * 最终网页展示"浏览器→后端 + 后端→MC + 轮询间隔"的合计时延。
         */
        if (normalized.equals("/timing")) {
            Json.Writer w = Json.writer().beginObject();
            w.put("ok", true);
            w.put("now", System.currentTimeMillis());
            w.put("seq", ring.seq());
            w.endObject();
            return Response.json(200, w.toString());
        }

        if (!authorized(authHeader, tokenHeader, queryToken)) {
            return error(401, "unauthorized", messages.raw("http.unauthorized"));
        }

        if (!rateLimit(clientIp)) {
            return error(429, "rate_limited", messages.raw("http.rate-limited"));
        }

        try {
            if (normalized.equals("/status")) {
                if (!isGet(method)) {
                    return methodNotAllowed();
                }
                return statusResponse(query);
            }
            if (normalized.equals("/messages")) {
                if (!isGet(method)) {
                    return methodNotAllowed();
                }
                return messagesResponse(query);
            }
            if (normalized.equals("/send")) {
                if (!isPost(method)) {
                    return methodNotAllowed();
                }
                return sendResponse(body);
            }
            if (normalized.equals("/report")) {
                if (!isPost(method)) {
                    return methodNotAllowed();
                }
                return reportResponse(body);
            }
            return error(404, "not_found", messages.raw("http.not-found", "path", route));
        } catch (Json.JsonException e) {
            return error(400, "bad_request", messages.raw("http.json-parse-failed", "error", e.getMessage()));
        } catch (Throwable t) {
            logger.accept(messages.raw("http.internal-error") + " (" + method + " " + path + "): " + t);
            return error(500, "internal_error", messages.raw("http.internal-error"));
        }
    }

    private Response methodNotAllowed() {
        return error(405, "method_not_allowed", messages.raw("http.method-not-allowed"));
    }

    private static boolean isGet(String method) {
        return method != null && method.equalsIgnoreCase("GET");
    }

    private static boolean isPost(String method) {
        return method != null && method.equalsIgnoreCase("POST");
    }

    /* ==================== 各端点 ==================== */

    /** GET /api/status */
    public Response statusResponse(Map<String, String> query) {
        long since = parseLong(query, "since", 0);

        Json.Writer w = Json.writer().beginObject();
        w.put("ok", true);
        w.put("schema", PluginVersion.SCHEMA);
        w.put("pluginVersion", PluginVersion.VERSION);
        w.put("serverTime", System.currentTimeMillis());

        StatusSnapshot snapshot = snapshotSupplier.get();
        if (snapshot == null || !snapshot.ready) {
            // 插件已在响应，但状态尚未采集完成：服务器本身是"在线"的
            w.put("ready", false);
            w.name("server").beginObject();
            w.put("online", true);
            w.put("statusLabel", messages.rawOr("status-fields.server-online", "Online"));
            w.endObject();
            w.endObject();
            return Response.json(503, w.toString());
        }

        w.name("server").beginObject();
        w.put("name", snapshot.serverName);
        w.put("motd", snapshot.motd);
        w.put("version", snapshot.version);
        // 能返回状态即表示 MC 服务器在线（插件运行在服务端进程内）
        w.put("online", true);
        w.put("statusLabel", messages.rawOr("status-fields.server-online", "Online"));
        w.put("collectedAt", snapshot.collectedAt);
        w.put("uptimeSeconds", snapshot.uptimeSeconds);
        w.put("startedAt", snapshot.startedAt);
        w.endObject();

        w.name("players").beginObject();
        w.put("online", snapshot.playersOnline);
        w.put("max", snapshot.playersMax);
        w.name("list").beginArray();
        for (String name : snapshot.playerList) {
            w.value(name);
        }
        w.endArray();
        w.endObject();

        w.name("performance").beginObject();
        w.put("tps1m", snapshot.tps1m);
        w.put("tps5m", snapshot.tps5m);
        w.put("tps15m", snapshot.tps15m);
        w.put("mspt", snapshot.mspt);
        w.put("msptMax", snapshot.msptMax);
        // 展示用：前端 ↔ MC 服务器的通信时延
        w.put("latencyMs", snapshot.latency.valueMs);
        w.put("latencyMaxMs", snapshot.latency.maxMs);
        w.put("latencySource", snapshot.latency.source);
        w.put("latencySamples", snapshot.latency.samples);
        // 明细：链路往返（后端探测）与玩家客户端 ping（模组上报）
        w.put("transportMs", snapshot.latency.transportMs);
        w.put("clientPingMs", snapshot.latency.clientPingMs);
        w.put("clientPingSamples", snapshot.latency.clientPingSamples);
        w.endObject();

        w.name("world").beginObject();
        w.put("worldName", snapshot.worldName);
        w.put("gameMode", snapshot.gameMode);
        w.put("difficulty", snapshot.difficulty);
        w.put("tick", snapshot.tick);
        w.put("day", snapshot.day);
        w.put("timeTicks", snapshot.timeTicks);
        w.put("timeLabel", localizedTimeLabel(snapshot.timeTicks));
        w.put("weather", localizedWeather(snapshot.weather));
        w.put("weatherRaw", snapshot.weather);
        w.name("playerList").beginArray();
        for (String name : snapshot.playerList) {
            w.value(name);
        }
        w.endArray();
        w.endObject();

        writeMessages(w, since, parseLimit(query, 100));
        w.endObject();
        return Response.json(200, w.toString());
    }

    /** GET /api/messages */
    public Response messagesResponse(Map<String, String> query) {
        long since = parseLong(query, "since", 0);
        Json.Writer w = Json.writer().beginObject();
        w.put("ok", true);
        w.put("serverTime", System.currentTimeMillis());
        writeMessages(w, since, parseLimit(query, 100));
        w.endObject();
        return Response.json(200, w.toString());
    }

    private void writeMessages(Json.Writer w, long since, int limit) {
        List<BridgeMessage> items = ring.since(since, limit);
        w.name("messages").beginObject();
        w.put("seq", ring.seq());
        w.put("oldestSeq", ring.oldestSeq());
        w.put("truncated", ring.isTruncated(since));
        w.put("count", items.size());
        w.name("items").beginArray();
        for (BridgeMessage m : items) {
            OutboundReporter.writeMessage(w, m);
        }
        w.endArray();
        w.endObject();
    }

    /** POST /api/send —— 网页消息以玩家身份广播到游戏 */
    public Response sendResponse(String body) {
        if (body == null || body.isBlank()) {
            return error(400, "bad_request", messages.raw("http.bad-request"));
        }
        Map<String, Object> payload = Json.parseObject(body);
        String username = Json.string(payload, "username", "").trim();
        String text = Json.string(payload, "text", "");
        String ip = Json.string(payload, "ip", "");

        SendResult result = send(username, text, ip);
        if (result.ok()) {
            Json.Writer w = Json.writer().beginObject();
            w.put("ok", true);
            w.put("seq", result.seq());
            w.put("broadcast", result.broadcast());
            w.endObject();
            return Response.json(200, w.toString());
        }
        return error(result.status(), result.error(), result.message());
    }

    /** 发消息核心逻辑（pull 的 /api/send 与 push 的 deliver 复用同一套校验） */
    public SendResult send(String username, String text, String ip) {
        if (username == null || username.isBlank()) {
            return SendResult.fail(400, "bad_request", messages.raw("http.send-missing-username"));
        }
        String name = username.trim();
        if (name.length() > 32) {
            return SendResult.fail(400, "bad_request", messages.raw("http.send-username-too-long"));
        }
        if (text == null || text.isBlank()) {
            return SendResult.fail(400, "bad_request", messages.raw("http.send-empty-text"));
        }

        String clean = TextUtil.truncate(TextUtil.clean(text), config.maxMessageLength());
        if (clean.isEmpty()) {
            return SendResult.fail(400, "bad_request", messages.raw("http.send-invalid-text"));
        }

        String blocked = TextUtil.matchBlockedWord(clean, config.blockWords());
        if (blocked != null) {
            return SendResult.fail(400, "blocked", messages.raw("http.send-blocked"));
        }

        if (!sendLimit(ip)) {
            return SendResult.fail(429, "rate_limited", messages.raw("http.send-rate-limited"));
        }

        if (config.requireOnline() && !Boolean.TRUE.equals(onlineChecker.apply(name))) {
            return SendResult.fail(409, "not_online", messages.raw("http.send-not-online"));
        }

        boolean broadcast = false;
        if (broadcaster != null) {
            try {
                broadcast = Boolean.TRUE.equals(broadcaster.apply(name, clean));
            } catch (Throwable t) {
                logger.accept("broadcast failed: " + t);
            }
        }
        long seq = ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_WEB, name, clean, true);
        if (afterSendHook != null) {
            try {
                afterSendHook.run();
            } catch (Throwable ignored) {
                // 立即上报失败不影响本次发送结果
            }
        }
        return SendResult.success(seq, broadcast);
    }

    /** POST /api/report —— 配套客户端上报时延 */
    public Response reportResponse(String body) {
        if (body == null || body.isBlank()) {
            return error(400, "bad_request", messages.raw("http.bad-request"));
        }
        Map<String, Object> payload = Json.parseObject(body);
        String username = Json.string(payload, "username", "").trim();
        int pingMs = Json.integer(payload, "pingMs", -1);
        if (username.isEmpty() || pingMs < 0) {
            return error(400, "bad_request", messages.raw("http.report-missing-fields"));
        }
        if (latencySink == null) {
            return error(503, "not_ready", messages.raw("http.report-not-ready"));
        }
        latencySink.accept(username, Math.min(pingMs, 60_000));
        Json.Writer w = Json.writer().beginObject();
        w.put("ok", true);
        w.endObject();
        return Response.json(200, w.toString());
    }

    /* ==================== 本地化字段 ==================== */

    private String localizedTimeLabel(long ticks) {
        long t = ((ticks % 24000) + 24000) % 24000;
        if (t < 1000) {
            return messages.rawOr("status-fields.time-sunrise", "Sunrise");
        }
        if (t < 5000) {
            return messages.rawOr("status-fields.time-morning", "Morning");
        }
        if (t < 7000) {
            return messages.rawOr("status-fields.time-noon", "Noon");
        }
        if (t < 11000) {
            return messages.rawOr("status-fields.time-afternoon", "Afternoon");
        }
        if (t < 13500) {
            return messages.rawOr("status-fields.time-sunset", "Sunset");
        }
        if (t < 18000) {
            return messages.rawOr("status-fields.time-night", "Night");
        }
        return messages.rawOr("status-fields.time-midnight", "Midnight");
    }

    private String localizedWeather(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "CLEAR" -> messages.rawOr("status-fields.weather-clear", "Clear");
            case "RAIN" -> messages.rawOr("status-fields.weather-rain", "Rain");
            case "THUNDER" -> messages.rawOr("status-fields.weather-thunder", "Thunder");
            default -> raw;
        };
    }

    /* ==================== 鉴权与限流 ==================== */

    private boolean authorized(String authHeader, String tokenHeader, String queryToken) {
        String expected = config.token();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String provided = null;
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            provided = tokenHeader.trim();
        } else if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = authHeader.substring(7).trim();
        } else if (queryToken != null && !queryToken.isBlank()) {
            provided = queryToken.trim();
        }
        if (provided == null) {
            return false;
        }
        return constantTimeEquals(expected, provided);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private boolean rateLimit(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        Window window = windows.computeIfAbsent(ip, k -> new Window());
        boolean allowed = window.allow(config.rateLimitPerMinute());
        if (allowed && windows.size() > 2048) {
            pruneWindows(windows);
        }
        return allowed;
    }

    private boolean sendLimit(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        Window window = sendWindows.computeIfAbsent(ip, k -> new Window());
        boolean allowed = window.allow(config.sendPerMinute());
        if (allowed && sendWindows.size() > 2048) {
            pruneWindows(sendWindows);
        }
        return allowed;
    }

    private static void pruneWindows(Map<String, Window> map) {
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Window> e : map.entrySet()) {
            if (now - e.getValue().start > 120_000L) {
                stale.add(e.getKey());
            }
        }
        for (String key : stale) {
            map.remove(key);
        }
    }

    /* ==================== 工具 ==================== */

    private static long parseLong(Map<String, String> query, String key, long def) {
        if (query == null) {
            return def;
        }
        String v = query.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseLimit(Map<String, String> query, int def) {
        long v = parseLong(query, "limit", def);
        if (v <= 0) {
            return def;
        }
        return (int) Math.min(v, 500);
    }

    private Response error(int status, String code, String message) {
        Json.Writer w = Json.writer().beginObject();
        w.put("ok", false);
        w.put("error", code);
        w.put("message", message == null ? "" : message);
        w.endObject();
        return Response.json(status, w.toString());
    }

    /** 解析 URL 查询串 */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(urlDecode(key), urlDecode(value));
        }
        return out;
    }

    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 读取输入流（带大小上限） */
    public static String readBody(InputStream in, int maxBytes) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(1024, maxBytes));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException("payload too large: " + maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
