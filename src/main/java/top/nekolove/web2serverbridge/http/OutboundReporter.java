package top.nekolove.web2serverbridge.http;

import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.status.StatusSnapshot;
import top.nekolove.web2serverbridge.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 主动上报器（push 模式）——**面板服的关键**。
 *
 * <p>面板服/容器/NAT 环境下，插件监听的端口外部无法访问，因此改为由插件
 * 主动把状态 POST 给后端。每次上报的<b>响应体</b>里带回后端排队的"网页消息"，
 * 插件广播到游戏后再回调 ack 确认，因此无需任何入站端口。</p>
 *
 * <p>上报流程：</p>
 * <pre>
 *   POST {outbound.url}   Body: 状态快照 + 自上次以来的消息增量 + 已广播消息 id
 *   ← { "ok": true, "deliver": [ {id, username, text} ], "config": {...} }
 *   POST {outbound.ack}   Body: { "ids": [...] }
 * </pre>
 *
 * <p>可靠性：</p>
 * <ul>
 *   <li>消息增量按序号游标发送，后端不会重复收到同一条消息。</li>
 *   <li>待 ack 队列有上限（{@code outbound.queue-max}），超出丢弃最旧并记日志。</li>
 *   <li>后端不可达时只记录限频日志（默认 30 秒一条），不影响服务端运行。</li>
 * </ul>
 */
public final class OutboundReporter {

    /** 网页消息投递项 */
    public record DeliverItem(long id, String username, String text) {
    }

    private final BridgeConfig config;
    private final Messages messages;
    private final MessageRing ring;
    private final Supplier<StatusSnapshot> snapshotSupplier;
    private final Consumer<DeliverItem> deliverHandler;   // 由插件实现：广播到游戏
    private final Consumer<String> logger;                // 普通日志
    private final Consumer<String> warnLogger;            // 告警日志

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 防止"立即上报"被重复触发 */
    private final AtomicBoolean immediate = new AtomicBoolean(false);
    private final AtomicLong lastCursor = new AtomicLong(0);
    private final Deque<Long> awaitingAck = new ArrayDeque<>();
    private final Object ackLock = new Object();

    private volatile long lastErrorLogAt = 0;
    private volatile long lastSuccessAt = 0;
    private volatile String lastError = "";
    private volatile int consecutiveFailures = 0;

    /** 后端可下发的运行时参数（如上报间隔） */
    private volatile int remoteIntervalSeconds = -1;

    public OutboundReporter(BridgeConfig config,
                            Messages messages,
                            MessageRing ring,
                            Supplier<StatusSnapshot> snapshotSupplier,
                            Consumer<DeliverItem> deliverHandler,
                            Consumer<String> logger,
                            Consumer<String> warnLogger) {
        this.config = config;
        this.messages = messages == null ? Messages.defaults() : messages;
        this.ring = ring;
        this.snapshotSupplier = snapshotSupplier;
        this.deliverHandler = deliverHandler;
        this.logger = logger == null ? s -> {
        } : logger;
        this.warnLogger = warnLogger == null ? s -> {
        } : warnLogger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.outboundConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "w2sb-outbound");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    /* ---------------- 生命周期 ---------------- */

    public void start() {
        if (!config.outboundActive()) {
            logger.accept(messages.raw("lifecycle.outbound-disabled",
                    "mode", config.outboundMode(),
                    "url", config.outboundUrl().isEmpty() ? "-" : config.outboundUrl()));
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 首次上报对齐当前游标，避免把历史消息一次性全推给后端
        lastCursor.set(ring.seq());
        long period = Math.max(1, effectiveIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::tickQuietly, 2, period, TimeUnit.SECONDS);
        logger.accept(messages.raw("lifecycle.outbound-enabled",
                "url", config.outboundUrl(), "seconds", period));
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            scheduler.shutdownNow();
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    /**
     * 生效的上报间隔（秒）。
     *
     * 后端可在响应里通过 reportIntervalSeconds 下发该值（用于统一调节"消息/状态实时性"）；
     * 允许范围 1~10 秒——超过 10 秒网页消息的送达延迟会明显到不可接受。
     */
    private int effectiveIntervalSeconds() {
        int remote = remoteIntervalSeconds;
        if (remote >= 1 && remote <= 10) {
            return remote;
        }
        return Math.min(10, Math.max(1, config.outboundIntervalSeconds()));
    }

    /* ---------------- 上报循环 ---------------- */

    private void tickQuietly() {
        if (!started.get()) {
            return;
        }
        try {
            tick();
        } catch (Throwable t) {
            // 任何异常都不能让调度线程退出
            noteFailure(messages.rawOr("lifecycle.outbound-exception",
                    "report exception: {error}",
                    "error", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    /** 执行一次上报（也可由命令手动触发） */
    public ReportResult reportOnce() {
        return tick();
    }

    public record ReportResult(boolean ok, int delivered, int messagesSent, String error) {
    }

    private ReportResult tick() {
        long cursor = lastCursor.get();
        List<top.nekolove.web2serverbridge.message.BridgeMessage> newMessages = ring.since(cursor, 200);
        long newCursor = cursor;
        for (var m : newMessages) {
            newCursor = Math.max(newCursor, m.seq());
        }

        List<Long> ackIds;
        synchronized (ackLock) {
            ackIds = new ArrayList<>(awaitingAck);
        }

        String body = buildPayload(newMessages, newCursor, ackIds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.outboundUrl()))
                .timeout(Duration.ofMillis(config.outboundReadTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "Web2ServerBridge/" + PluginVersion.VERSION)
                .header("Authorization", "Bearer " + config.effectiveOutboundToken())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            noteFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            return new ReportResult(false, 0, 0, lastError);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            noteFailure(messages.raw("lifecycle.outbound-unauthorized", "status", response.statusCode()));
            return new ReportResult(false, 0, 0, "unauthorized");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            noteFailure(messages.raw("lifecycle.outbound-http-error",
                    "status", response.statusCode(), "body", brief(response.body())));
            return new ReportResult(false, 0, 0, "http_" + response.statusCode());
        }

        // 成功：推进游标
        lastCursor.set(newCursor);
        onSuccess();

        int delivered = 0;
        try {
            Map<String, Object> parsed = Json.parseObject(response.body());
            remoteIntervalSeconds = Json.integer(parsed, "reportIntervalSeconds", -1);

            Object deliverObj = parsed.get("deliver");
            if (deliverObj instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) raw;
                    long id = ((Number) item.getOrDefault("id", 0)).longValue();
                    String username = Json.string(item, "username", "");
                    String text = Json.string(item, "text", "");
                    if (text.isBlank()) {
                        continue;
                    }
                    try {
                        deliverHandler.accept(new DeliverItem(id, username, text));
                        delivered++;
                    } catch (Throwable t) {
                        warnLogger.accept(messages.raw("lifecycle.outbound-broadcast-failed",
                                "id", id, "error", t.getMessage()));
                    }
                }
            }
        } catch (Exception e) {
            // 响应体解析失败不影响上报本身（数据已到后端）
            warnLogger.accept(messages.raw("lifecycle.outbound-response-parse-failed", "error", e.getMessage()));
        }

        if (delivered > 0) {
            sendAck(collectDeliveredIds(response.body()));
        }
        return new ReportResult(true, delivered, newMessages.size(), null);
    }

    private List<Long> collectDeliveredIds(String responseBody) {
        List<Long> ids = new ArrayList<>();
        try {
            Map<String, Object> parsed = Json.parseObject(responseBody);
            Object deliverObj = parsed.get("deliver");
            if (deliverObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw && raw.get("id") instanceof Number n) {
                        ids.add(n.longValue());
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return ids;
    }

    private void sendAck(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String ackUrl = config.effectiveAckUrl();
        if (ackUrl == null || ackUrl.isBlank() || ackUrl.equals(config.outboundUrl())) {
            return;
        }
        try {
            Json.Writer w = Json.writer().beginObject();
            w.name("ids").beginArray();
            for (Long id : ids) {
                w.value(id);
            }
            w.endArray();
            w.endObject();
            HttpRequest req = HttpRequest.newBuilder(URI.create(ackUrl))
                    .timeout(Duration.ofMillis(config.outboundReadTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + config.effectiveOutboundToken())
                    .POST(HttpRequest.BodyPublishers.ofString(w.toString(), StandardCharsets.UTF_8))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            warnLogger.accept(messages.raw("lifecycle.outbound-ack-failed", "error", e.getMessage()));
        }
    }

    /**
     * 请求尽快执行一次上报（用于"刚广播完网页消息，尽快让后端确认"的场景）。
     * 已在上报中或未启用时直接忽略，不会造成请求风暴。
     */
    public void requestImmediateReport() {
        if (!started.get()) {
            return;
        }
        if (!immediate.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                immediate.set(false);
                try {
                    tick();
                } catch (Throwable t) {
                    noteFailure(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        } catch (Throwable ignored) {
            immediate.set(false);
        }
    }

    /** 登记"网页消息已广播"，等待后端确认（push 模式下后端已直接出队） */
    public void trackAwaiting(long id) {
        if (id <= 0) {
            return;
        }
        synchronized (ackLock) {
            awaitingAck.addLast(id);
            while (awaitingAck.size() > config.outboundQueueMax()) {
                awaitingAck.pollFirst();
            }
        }
    }

    /** 后端确认后移除 */
    public void confirm(long id) {
        synchronized (ackLock) {
            awaitingAck.remove(id);
        }
    }

    public int awaitingCount() {
        synchronized (ackLock) {
            return awaitingAck.size();
        }
    }

    /* ---------------- 负载构造 ---------------- */

    String buildPayload(List<top.nekolove.web2serverbridge.message.BridgeMessage> messages,
                        long cursor, List<Long> ackIds) {
        Json.Writer w = Json.writer().beginObject();
        w.put("schema", 1);
        w.put("ok", true);
        w.put("mode", "push");
        w.put("pluginVersion", PluginVersion.VERSION);
        w.put("sentAt", System.currentTimeMillis());

        StatusSnapshot snapshot = snapshotSupplier.get();
        w.name("status").beginObject();
        if (snapshot == null) {
            w.put("ready", false);
        } else {
            snapshot.writeJson(w);
        }
        w.endObject();

        w.name("messages").beginObject();
        w.put("seq", cursor);
        w.put("oldestSeq", ring.oldestSeq());
        w.put("truncated", false);
        w.name("items").beginArray();
        for (var m : messages) {
            writeMessage(w, m);
        }
        w.endArray();
        w.endObject();

        w.name("pending").beginObject();
        w.name("delivered").beginArray();
        for (Long id : ackIds) {
            w.value(id);
        }
        w.endArray();
        w.endObject();

        w.endObject();
        return w.toString();
    }

    static void writeMessage(Json.Writer w, top.nekolove.web2serverbridge.message.BridgeMessage m) {
        w.beginObject();
        w.put("seq", m.seq());
        w.put("time", m.time());
        w.put("type", m.type());
        w.put("source", m.source());
        w.put("player", m.player());
        w.put("text", m.text());
        w.put("web", m.web());
        w.endObject();
    }

    /* ---------------- 状态与日志 ---------------- */

    private void onSuccess() {
        lastSuccessAt = System.currentTimeMillis();
        consecutiveFailures = 0;
        lastError = "";
    }

    private void noteFailure(String message) {
        lastError = message;
        consecutiveFailures++;
        long now = System.currentTimeMillis();
        // 限频：30 秒最多一条错误日志，避免后端长时间不可用把日志刷满
        if (now - lastErrorLogAt > 30_000L) {
            lastErrorLogAt = now;
            warnLogger.accept(messages.raw("lifecycle.outbound-report-failed",
                    "count", consecutiveFailures, "error", message));
        }
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    public Map<String, Object> state() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("started", isStarted());
        map.put("url", config.outboundUrl());
        map.put("intervalSeconds", effectiveIntervalSeconds());
        map.put("cursor", lastCursor.get());
        map.put("awaitingAck", awaitingCount());
        map.put("lastSuccessAt", lastSuccessAt);
        map.put("lastError", lastError);
        map.put("consecutiveFailures", consecutiveFailures);
        return map;
    }
}
