package top.nekolove.web2serverbridge.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * 内置 HTTP 服务（pull 模式）：把 {@link HttpRouter} 挂到 JDK 的
 * {@code com.sun.net.httpserver.HttpServer} 上。
 *
 * <p>这是"可选"能力：面板服/NAT 环境下外部连不进来时，
 * 用 {@link OutboundReporter} 主动上报即可，本服务可以保持关闭。</p>
 */
public final class BridgeHttpServer {

    private final BridgeConfig config;
    private final Messages messages;
    private final HttpRouter router;
    private final Consumer<String> logger;

    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(BridgeConfig config, Messages messages, HttpRouter router, Consumer<String> logger) {
        this.config = config;
        this.messages = messages == null ? Messages.defaults() : messages;
        this.router = router;
        this.logger = logger == null ? s -> {
        } : logger;
    }

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** 启动监听；失败时抛出 IOException 由插件决定是否降级（转为纯 push 模式） */
    public void start() throws IOException {
        if (server != null) {
            return;
        }
        InetSocketAddress address = new InetSocketAddress(config.httpHost(), config.httpPort());
        HttpServer created = HttpServer.create(address, 64);
        created.createContext("/", this::onExchange);

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "w2sb-http");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.httpThreads()), tf);
        created.setExecutor(executor);
        created.start();
        this.server = created;
        logger.accept(messages.raw("lifecycle.http-listening",
                "url", "http://" + config.httpHost() + ":" + port() + config.pathPrefix()));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void onExchange(HttpExchange exchange) {
        long startedAt = System.nanoTime();
        String clientIp = resolveClientIp(exchange);
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = HttpRouter.parseQuery(exchange.getRequestURI().getRawQuery());

            String body = "";
            if ("POST".equalsIgnoreCase(method)) {
                try {
                    body = HttpRouter.readBody(exchange.getRequestBody(), config.maxBodyBytes());
                } catch (IllegalStateException tooLarge) {
                    write(exchange, 413, "application/json; charset=utf-8",
                            errorJson("payload_too_large",
                                    messages.raw("http.payload-too-large", "max", config.maxBodyBytes())));
                    return;
                }
            }

            HttpRouter.Response response = router.handle(
                    method,
                    path,
                    query,
                    body,
                    clientIp,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Bridge-Token"));

            write(exchange, response.status(), response.contentType(), response.body());

            if (config.logRequests()) {
                long ms = (System.nanoTime() - startedAt) / 1_000_000L;
                logger.accept(method + " " + path + " -> " + response.status() + " (" + ms + "ms, " + clientIp + ")");
            }
        } catch (Throwable t) {
            logger.accept(messages.rawOr("lifecycle.http-handler-error",
                    "HTTP handler error: {error}", "error", String.valueOf(t)));
            try {
                write(exchange, 500, "application/json; charset=utf-8",
                        errorJson("internal_error", messages.raw("http.internal-error")));
            } catch (Throwable ignored) {
                // 已经无法回复
            }
        } finally {
            exchange.close();
        }
    }

    private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType == null ? "application/json; charset=utf-8" : contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        String cors = config.corsAllowOrigin();
        if (cors != null && !cors.isBlank()) {
            headers.set("Access-Control-Allow-Origin", cors);
            headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Bridge-Token");
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        }

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 生成错误 JSON（文本来自 messages.yml） */
    private static String errorJson(String code, String message) {
        return "{\"ok\":false,\"error\":\"" + code + "\",\"message\":\""
                + escape(message) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    /** 取真实客户端 IP：优先 X-Forwarded-For 最后一跳（离网关最近） */
    private static String resolveClientIp(HttpExchange exchange) {
        String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "";
    }
}
