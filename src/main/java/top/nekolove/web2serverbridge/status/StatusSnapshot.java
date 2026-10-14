package top.nekolove.web2serverbridge.status;

import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器状态快照（纯数据对象，不依赖 Bukkit，便于离线测试）。
 * 用 {@link #toMap()} 转成可序列化结构，{@link #writeJson(Json.Writer)} 写入 JSON。
 */
public final class StatusSnapshot {

    public static final class Latency {
        /** 面向"前端 ↔ MC 服务器"的通信时延（毫秒）。-1 表示暂不可用。 */
        public int valueMs = -1;
        public int maxMs = -1;
        public int minMs = -1;
        public int samples = 0;
        /**
         * 时延来源：
         * <ul>
         *   <li>{@code backend_probe} —— 后端探测插件 /api/timing 得到的真实链路往返（默认）</li>
         *   <li>{@code client_report} —— 配套客户端模组上报的真实玩家 ping（最准）</li>
         *   <li>{@code none} —— 还没测到</li>
         * </ul>
         */
        public String source = "none";
        /** 后端 ↔ 本 MC 服务端 的 HTTP 往返时延（毫秒），由后端写入 */
        public double transportMs = -1;
        /** 玩家客户端 ping（仅配套客户端模组上报时可用） */
        public int clientPingMs = -1;
        public int clientPingSamples = 0;
    }

    public String serverName = "";
    public String motd = "";
    public String version = "";
    public long uptimeSeconds = 0;
    public long startedAt = 0;

    public int playersOnline = 0;
    public int playersMax = 0;
    public List<String> playerList = new ArrayList<>();

    public double tps1m = -1;
    public double tps5m = -1;
    public double tps15m = -1;
    public double mspt = -1;
    public double msptMax = -1;
    public Latency latency = new Latency();

    public String worldName = "";
    public String gameMode = "";
    public String difficulty = "";
    public long tick = 0;
    public long day = 0;
    public long timeTicks = 0;
    public String timeLabel = "";
    public String weather = "";
    /** 已本地化的天气文本（来自 messages.yml 的 status-fields.weather-*） */
    public String weatherLabel = "";

    public long collectedAt = 0;
    /** 采集是否成功（未就绪时为 false，此时其它字段可能为默认值） */
    public boolean ready = true;

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ready", ready);
        root.put("collectedAt", collectedAt);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", serverName);
        server.put("motd", motd);
        server.put("version", version);
        server.put("online", ready);
        server.put("uptimeSeconds", uptimeSeconds);
        server.put("startedAt", startedAt);
        root.put("server", server);

        Map<String, Object> players = new LinkedHashMap<>();
        players.put("online", playersOnline);
        players.put("max", playersMax);
        players.put("list", playerList);
        root.put("players", players);

        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("tps1m", tps1m);
        perf.put("tps5m", tps5m);
        perf.put("tps15m", tps15m);
        perf.put("mspt", mspt);
        perf.put("msptMax", msptMax);
        perf.put("latencyMs", latency.valueMs);
        perf.put("latencyMaxMs", latency.maxMs);
        perf.put("latencySource", latency.source);
        perf.put("latencySamples", latency.samples);
        root.put("performance", perf);

        Map<String, Object> world = new LinkedHashMap<>();
        world.put("worldName", worldName);
        world.put("gameMode", gameMode);
        world.put("difficulty", difficulty);
        world.put("tick", tick);
        world.put("day", day);
        world.put("timeTicks", timeTicks);
        world.put("timeLabel", timeLabel);
        world.put("weather", weather);
        world.put("playerList", playerList);
        root.put("world", world);

        return root;
    }

    /** 直接写入已打开的 Json 对象（键名与 toMap 一致） */
    public void writeJson(Json.Writer w) {
        w.put("ready", ready);
        w.put("collectedAt", collectedAt);

        w.name("server").beginObject();
        w.put("name", serverName);
        w.put("motd", motd);
        w.put("version", version);
        w.put("online", ready);
        w.put("uptimeSeconds", uptimeSeconds);
        w.put("startedAt", startedAt);
        w.endObject();

        w.name("players").beginObject();
        w.put("online", playersOnline);
        w.put("max", playersMax);
        w.name("list").beginArray();
        for (String name : playerList) {
            w.value(name);
        }
        w.endArray();
        w.endObject();

        w.name("performance").beginObject();
        w.put("tps1m", tps1m);
        w.put("tps5m", tps5m);
        w.put("tps15m", tps15m);
        w.put("mspt", mspt);
        w.put("msptMax", msptMax);
        w.put("latencyMs", latency.valueMs);
        w.put("latencyMaxMs", latency.maxMs);
        w.put("latencySource", latency.source);
        w.put("latencySamples", latency.samples);
        w.put("transportMs", latency.transportMs);
        w.put("clientPingMs", latency.clientPingMs);
        w.put("clientPingSamples", latency.clientPingSamples);
        w.endObject();

        w.name("world").beginObject();
        w.put("worldName", worldName);
        w.put("gameMode", gameMode);
        w.put("difficulty", difficulty);
        w.put("tick", tick);
        w.put("day", day);
        w.put("timeTicks", timeTicks);
        w.put("timeLabel", timeLabel);
        w.put("weather", weather);
        w.name("playerList").beginArray();
        for (String name : playerList) {
            w.value(name);
        }
        w.endArray();
        w.endObject();
    }

    /**
     * 把世界时间刻转换为可读时段。
     *
     * @param messages 文本配置；为 null 时使用内置英文兜底（本方法不再硬编码中文）
     */
    public static String timeLabel(long ticks, Messages messages) {
        long t = ((ticks % 24000) + 24000) % 24000;
        String key;
        String fallback;
        if (t < 1000) {
            key = "status-fields.time-sunrise";
            fallback = "Sunrise";
        } else if (t < 5000) {
            key = "status-fields.time-morning";
            fallback = "Morning";
        } else if (t < 7000) {
            key = "status-fields.time-noon";
            fallback = "Noon";
        } else if (t < 11000) {
            key = "status-fields.time-afternoon";
            fallback = "Afternoon";
        } else if (t < 13500) {
            key = "status-fields.time-sunset";
            fallback = "Sunset";
        } else if (t < 18000) {
            key = "status-fields.time-night";
            fallback = "Night";
        } else {
            key = "status-fields.time-midnight";
            fallback = "Midnight";
        }
        return messages == null ? fallback : messages.rawOr(key, fallback);
    }

    /** 天气本地化 */
    public static String weatherLabel(String raw, Messages messages) {
        if (raw == null) {
            return "";
        }
        String key;
        String fallback;
        switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "CLEAR" -> {
                key = "status-fields.weather-clear";
                fallback = "Clear";
            }
            case "RAIN" -> {
                key = "status-fields.weather-rain";
                fallback = "Rain";
            }
            case "THUNDER" -> {
                key = "status-fields.weather-thunder";
                fallback = "Thunder";
            }
            default -> {
                return raw;
            }
        }
        return messages == null ? fallback : messages.rawOr(key, fallback);
    }
}
