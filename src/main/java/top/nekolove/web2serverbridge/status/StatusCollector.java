package top.nekolove.web2serverbridge.status;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import top.nekolove.web2serverbridge.config.Messages;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 状态采集器：在主线程刷新 {@link StatusSnapshot}（由插件定时任务驱动）。
 *
 * 采集内容：在线/最大人数、玩家名单、TPS、MSPT、当前世界信息（世界名/游戏模式/难度/
 * 游戏刻/世界时间/天气）、服务器 MOTD 与版本、运行时长。
 * 时延来自 {@link LatencyRegistry}（客户端上报），或按 tick 抖动退化估算。
 */
public final class StatusCollector {

    private final LatencyRegistry latencyRegistry;
    private final PerformanceSampler sampler = PerformanceSampler.defaults();
    private volatile StatusSnapshot snapshot = new StatusSnapshot();
    private final long startedAt;

    private static Method getVersionMethod;

    public StatusCollector(LatencyRegistry latencyRegistry, long startedAt) {
        this.latencyRegistry = latencyRegistry;
        this.startedAt = startedAt;
        StatusSnapshot initial = new StatusSnapshot();
        initial.ready = false;
        initial.startedAt = startedAt;
        this.snapshot = initial;
    }

    /** 当前快照（HTTP 线程读取，volatile 保证可见性） */
    public StatusSnapshot current() {
        return snapshot;
    }

    public long startedAt() {
        return startedAt;
    }

    public PerformanceSampler sampler() {
        return sampler;
    }

    /**
     * 刷新一次快照。必须在主线程调用（Bukkit API 线程约束）。
     *
     * @param serverName         对外显示名
     * @param latencyMode        auto | client | tick
     * @param latencyStaleSec    客户端样本过期秒数
     * @param keepColorCodes     MOTD 是否保留颜色代码
     */
    public void refresh(String serverName, String latencyMode, long latencyStaleSec, boolean keepColorCodes) {
        refresh(serverName, latencyMode, latencyStaleSec, keepColorCodes, null);
    }

    /**
     * 刷新一次快照（带文本配置，用于本地化时间/天气等字段）。
     */
    public void refresh(String serverName, String latencyMode, long latencyStaleSec, boolean keepColorCodes,
                        Messages messages) {
        long now = System.currentTimeMillis();
        sampler.sample();
        latencyRegistry.prune(now, latencyStaleSec);

        StatusSnapshot s = new StatusSnapshot();
        s.collectedAt = now;
        s.ready = true;
        s.startedAt = startedAt;
        s.uptimeSeconds = Math.max(0, (now - startedAt) / 1000);

        s.serverName = serverName == null || serverName.isBlank() ? "Minecraft Server" : serverName;
        String serverMotd = readMotd(keepColorCodes);
        s.motd = serverMotd.isBlank() ? s.serverName : serverMotd;
        s.version = readVersion();

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        s.playersOnline = online.size();
        s.playersMax = Bukkit.getMaxPlayers();
        List<String> names = new ArrayList<>(online.size());
        for (Player p : online) {
            names.add(p.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        s.playerList = names;

        s.tps1m = sampler.tps1m();
        s.tps5m = sampler.tps5m();
        s.tps15m = sampler.tps15m();
        s.mspt = sampler.mspt();
        s.msptMax = sampler.msptMax();

        /*
         * 时延：网页要展示的是"前端 ↔ MC 服务器"的通信时延，因此优先级为
         *   1) 配套客户端模组上报的真实玩家 ping（client_report）
         *   2) 后端探测插件 /api/timing 得到的链路往返（backend_probe）
         * 早期的 tick 抖动估算已删除——它在服务器健康时恒为 0，是错误指标。
         */
        LatencyRegistry.Summary summary = latencyRegistry.summary(now, latencyStaleSec);
        s.latency.clientPingMs = summary.available ? (int) Math.round(summary.avg) : -1;
        s.latency.clientPingSamples = summary.count;
        s.latency.transportMs = latencyRegistry.transportRttMs();
        s.latency.samples = summary.count;
        s.latency.maxMs = summary.available ? summary.max : -1;
        s.latency.minMs = summary.available ? summary.min : -1;

        boolean preferClient = summary.available && !"tick".equalsIgnoreCase(latencyMode);
        if (preferClient) {
            s.latency.valueMs = (int) Math.round(summary.avg);
            s.latency.source = "client_report";
        } else if (s.latency.transportMs >= 0) {
            s.latency.valueMs = (int) Math.round(s.latency.transportMs);
            s.latency.source = "backend_probe";
        } else {
            s.latency.valueMs = -1;
            s.latency.source = "none";
        }

        // 世界信息：以主世界为基准
        World world = pickWorld(online);
        if (world != null) {
            s.worldName = world.getName();
            s.difficulty = world.getDifficulty() == null ? "" : world.getDifficulty().name();
            s.tick = world.getFullTime();
            s.day = world.getFullTime() / 24000L;
            s.timeTicks = world.getTime();
            s.timeLabel = StatusSnapshot.timeLabel(world.getTime(), messages);
            s.weather = world.isThundering() ? "THUNDER" : (world.hasStorm() ? "RAIN" : "CLEAR");
            s.weatherLabel = StatusSnapshot.weatherLabel(s.weather, messages);
        }

        // 游戏模式：优先取第一名在线玩家的模式；无人在线时取默认世界的默认模式
        String gameMode = "";
        if (!online.isEmpty()) {
            GameMode gm = online.get(0).getGameMode();
            gameMode = gm == null ? "" : gm.name();
        } else if (world != null) {
            Difficulty d = world.getDifficulty();
            gameMode = d == null ? "" : "DEFAULT";
        }
        s.gameMode = gameMode;

        this.snapshot = s;
    }

    private static World pickWorld(List<Player> online) {
        if (!online.isEmpty()) {
            return online.get(0).getWorld();
        }
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                return worlds.get(0);
            }
        } catch (Throwable ignored) {
            // 世界尚未加载
        }
        return null;
    }

    /**
     * 读取服务端 MOTD（必须来自 MC 服务端本身，而不是 config.yml 里的 server-name）。
     *
     * <p>Paper 26.x 的 <code>Bukkit.getMotd()</code> 返回 Adventure Component，
     * 早期版本返回 String，因此统一走反射并用三种方式尝试取值：
     * 先试 String，再试 Component 的 plain serializer，最后退回 toString。</p>
     */
    private static String readMotd(boolean keepColorCodes) {
        Object value = invokeStatic(Bukkit.class, "getMotd");
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof String str) {
            text = str;
        } else {
            text = componentToText(value);
        }
        return keepColorCodes ? text : text.replaceAll("§.", "");
    }

    /** 把 Adventure Component 转成纯文本（不引入对方可选依赖，纯反射） */
    private static String componentToText(Object component) {
        // 1) Component#toString() 在多数实现里就是带 § 的 legacy 文本
        try {
            Method plain = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer")
                    .getMethod("plainText");
            Object serializer = plain.invoke(null);
            Method serialize = serializer.getClass().getMethod("serialize",
                    Class.forName("net.kyori.adventure.text.Component"));
            Object out = serialize.invoke(serializer, component);
            if (out != null) {
                return String.valueOf(out);
            }
        } catch (Throwable ignored) {
            // 继续尝试 legacy 序列化
        }
        // 2) legacy § 形式（保留颜色，便于前端自行渲染）
        try {
            Class<?> legacyClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            Object legacy = legacyClass.getMethod("legacySection").invoke(null);
            Method serialize = legacy.getClass().getMethod("serialize",
                    Class.forName("net.kyori.adventure.text.Component"));
            Object out = serialize.invoke(legacy, component);
            if (out != null) {
                return String.valueOf(out);
            }
        } catch (Throwable ignored) {
            // 最后的兜底
        }
        return String.valueOf(component);
    }

    private static Object invokeStatic(Class<?> owner, String name) {
        try {
            Method method = owner.getMethod(name);
            return method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readVersion() {
        try {
            if (getVersionMethod == null) {
                getVersionMethod = Bukkit.class.getMethod("getVersion");
            }
            Object v = getVersionMethod.invoke(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
