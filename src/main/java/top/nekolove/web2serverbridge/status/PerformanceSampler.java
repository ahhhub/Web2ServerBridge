package top.nekolove.web2serverbridge.status;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;

/**
 * TPS / MSPT 采集器。
 *
 * Paper 提供 {@code Bukkit.getTPS()}（1m/5m/15m 三档）与 {@code Bukkit.getTickTimes()}（纳秒），
 * 但不同版本方法略有差异，因此用反射容错获取；取不到时返回 -1 表示"不可用"。
 *
 * MSPT = 最近 tick 平均耗时（毫秒）；超过 50ms 表示服务器跟不上 20TPS。
 */
public final class PerformanceSampler {

    private static Method getTpsMethod;
    private static Method getTickTimesMethod;
    private static boolean reflected = false;

    private final double[] tps = {-1, -1, -1};
    private double mspt = -1;
    private double msptMax = -1;
    private long updatedAt = 0;

    private PerformanceSampler() {
    }

    public static PerformanceSampler defaults() {
        return new PerformanceSampler();
    }

    /** 采样一次（主线程调用，开销极低） */
    public PerformanceSampler sample() {
        reflect();
        this.updatedAt = System.currentTimeMillis();

        double[] t = readTps();
        if (t != null && t.length >= 3) {
            this.tps[0] = t[0];
            this.tps[1] = t[1];
            this.tps[2] = t[2];
        }

        long[] tickTimes = readTickTimes();
        if (tickTimes != null && tickTimes.length > 0) {
            long sum = 0;
            long max = 0;
            for (long v : tickTimes) {
                sum += v;
                if (v > max) {
                    max = v;
                }
            }
            this.mspt = round2(sum / (double) tickTimes.length / 1_000_000.0);
            this.msptMax = round2(max / 1_000_000.0);
        }
        return this;
    }

    private static void reflect() {
        if (reflected) {
            return;
        }
        reflected = true;
        try {
            getTpsMethod = Bukkit.class.getMethod("getTPS");
        } catch (NoSuchMethodException e) {
            getTpsMethod = null;
        }
        try {
            getTickTimesMethod = Bukkit.class.getMethod("getTickTimes");
        } catch (NoSuchMethodException e) {
            getTickTimesMethod = null;
        }
    }

    private static double[] readTps() {
        if (getTpsMethod == null) {
            return null;
        }
        try {
            Object result = getTpsMethod.invoke(null);
            if (result instanceof double[] arr && arr.length >= 3) {
                return new double[]{round2(arr[0]), round2(arr[1]), round2(arr[2])};
            }
        } catch (Throwable ignored) {
            // 服务端实现差异，视为不可用
        }
        return null;
    }

    private static long[] readTickTimes() {
        if (getTickTimesMethod == null) {
            return null;
        }
        try {
            Object result = getTickTimesMethod.invoke(null);
            if (result instanceof long[] arr) {
                return arr;
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public double tps1m() {
        return tps[0];
    }

    public double tps5m() {
        return tps[1];
    }

    public double tps15m() {
        return tps[2];
    }

    public double mspt() {
        return mspt;
    }

    public double msptMax() {
        return msptMax;
    }

    public long updatedAt() {
        return updatedAt;
    }
}
