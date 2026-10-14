package top.nekolove.web2serverbridge.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端时延（ping）样本登记表。
 *
 * <p>时延必须由"该服务器的客户端"实测才有意义：配套客户端 mod 每隔一段时间向插件上报
 * 自己的 ping（见 README「配套客户端通信」）。这里保存每个玩家的最近上报值，
 * 超过过期时间自动剔除。</p>
 *
 * <p>没有任何客户端样本时，{@link #summary} 返回 available=false，
 * 由上层决定是否用 tick 抖动做退化估算。</p>
 */
public final class LatencyRegistry {

    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    /** 后端探测得到的"后端 ↔ 本 MC 服务端"HTTP 往返时延（毫秒），-1 表示未知 */
    private volatile double transportRttMs = -1;
    private volatile long transportAt = 0;

    public static final class Sample {
        public final String player;
        public final int pingMs;
        public final long at;

        Sample(String player, int pingMs, long at) {
            this.player = player;
            this.pingMs = pingMs;
            this.at = at;
        }
    }

    public static final class Summary {
        public final boolean available;
        public final double avg;
        public final int max;
        public final int min;
        public final int count;
        public final long staleSeconds;
        public final String source;

        Summary(boolean available, double avg, int max, int min, int count, long staleSeconds, String source) {
            this.available = available;
            this.avg = avg;
            this.max = max;
            this.min = min;
            this.count = count;
            this.staleSeconds = staleSeconds;
            this.source = source;
        }
    }

    /** 记录一次客户端上报（HTTP 线程调用） */
    public void report(String player, int pingMs, long now) {
        if (player == null || player.isBlank()) {
            return;
        }
        samples.put(player.toLowerCase(), new Sample(player, Math.max(0, pingMs), now));
    }

    /**
     * 已知的链路时延（后端 ↔ 插件所在 MC 服务端）的毫秒数。
     *
     * <p>由后端在探测 <code>/api/timing</code> 后写入，用于在前端展示
     * "前端 → MC 服务器"的真实通信时延。</p>
     */
    public void reportTransport(double rttMs, long now) {
        if (rttMs < 0) {
            return;
        }
        this.transportRttMs = Math.round(rttMs * 10.0) / 10.0;
        this.transportAt = now;
    }

    public double transportRttMs() {
        return transportRttMs;
    }

    public long transportAt() {
        return transportAt;
    }

    /** 剔除过期样本，返回剔除数量 */
    public int prune(long now, long staleSeconds) {
        long cutoff = now - staleSeconds * 1000L;
        int removed = 0;
        for (Map.Entry<String, Sample> e : samples.entrySet()) {
            if (e.getValue().at < cutoff) {
                if (samples.remove(e.getKey()) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 汇总当前有效样本 */
    public Summary summary(long now, long staleSeconds) {
        long cutoff = now - staleSeconds * 1000L;
        int count = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        long sum = 0;
        for (Sample s : samples.values()) {
            if (s.at < cutoff) {
                continue;
            }
            count++;
            sum += s.pingMs;
            min = Math.min(min, s.pingMs);
            max = Math.max(max, s.pingMs);
        }
        if (count == 0) {
            return new Summary(false, -1, -1, -1, 0, staleSeconds, "none");
        }
        double avg = Math.round((sum / (double) count) * 10.0) / 10.0;
        return new Summary(true, avg, max, min == Integer.MAX_VALUE ? 0 : min, count, staleSeconds, "client_report");
    }

    /** 供状态接口输出每位玩家的样本（可选，用于排查） */
    public Map<String, Object> detail(long now, long staleSeconds) {
        long cutoff = now - staleSeconds * 1000L;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Sample s : samples.values()) {
            if (s.at < cutoff) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pingMs", s.pingMs);
            item.put("ageSeconds", Math.max(0, (now - s.at) / 1000));
            out.put(s.player, item);
        }
        return out;
    }

    public int size() {
        return samples.size();
    }

    public void clear() {
        samples.clear();
    }

    public void remove(String player) {
        if (player != null) {
            samples.remove(player.toLowerCase());
        }
    }
}
