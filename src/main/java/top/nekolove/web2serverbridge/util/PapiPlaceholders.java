package top.nekolove.web2serverbridge.util;

import java.lang.reflect.Method;

/**
 * PlaceholderAPI（{@code %xxx%}）支持。
 *
 * <p><b>本类不导入任何 Bukkit 类型</b>：所有与服务端的交互都通过反射完成。
 * 这一点很重要——如果方法签名里出现 {@code org.bukkit.OfflinePlayer}，
 * 那么在只加载 {@link #isAvailable()} 时 JVM 也会尝试解析该类型，
 * 在没有服务端的环境（内置自检 / 单元测试 / 文档生成）就会直接抛
 * {@code NoClassDefFoundError}。完全反射则不存在这个问题。</p>
 *
 * <p>对 PlaceholderAPI 同样没有硬依赖：未安装时 {@link #isAvailable()} 返回 false，
 * 占位符原样保留，插件照常运行。</p>
 *
 * <p>用法：把 {@link #contextForName(String)} 返回的 {@link PlaceholderContext}
 * 传给 {@code Messages.raw(key, context, kv...)}，即可在 messages.yml 中直接使用
 * {@code %player_name%}、{@code %server_online%}、{@code %vault_eco_balance%} 等占位符。</p>
 */
public final class PapiPlaceholders {

    private static volatile Boolean available;
    private static Method setPlaceholdersOffline;   // PlaceholderAPI#setPlaceholders(OfflinePlayer, String)
    private static Method setPlaceholdersPlayer;    // PlaceholderAPI#setPlaceholders(Player, String)
    private static Method serverGetPlayerExact;     // Server#getPlayerExact(String)
    private static Method serverGetPlayer;          // Server#getPlayer(String)
    private static Method serverGetOfflinePlayerName; // Server#getOfflinePlayer(String)
    private static Object serverInstance;

    private PapiPlaceholders() {
    }

    /** PlaceholderAPI 是否已安装且可用 */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            try {
                setPlaceholdersOffline = papi.getMethod("setPlaceholders", Class.forName("org.bukkit.OfflinePlayer"), String.class);
            } catch (Throwable ignored) {
                setPlaceholdersOffline = null;
            }
            try {
                setPlaceholdersPlayer = papi.getMethod("setPlaceholders", Class.forName("org.bukkit.entity.Player"), String.class);
            } catch (Throwable ignored) {
                setPlaceholdersPlayer = null;
            }
            // 插件必须已启用
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class)
                    .invoke(pluginManager, "PlaceholderAPI");
            result = plugin != null && (setPlaceholdersOffline != null || setPlaceholdersPlayer != null);
            if (result) {
                cacheServerAccessors(bukkit);
            }
        } catch (Throwable ignored) {
            result = false;
        }
        available = result;
        return result;
    }

    private static void cacheServerAccessors(Class<?> bukkit) {
        try {
            serverInstance = bukkit.getMethod("getServer").invoke(null);
        } catch (Throwable ignored) {
            serverInstance = null;
        }
        if (serverInstance == null) {
            return;
        }
        Class<?> serverClass = serverInstance.getClass();
        // 可能是 CraftServer，逐级向上找接口
        for (Class<?> c = serverClass; c != null; c = c.getSuperclass()) {
            if (serverGetPlayerExact == null) {
                try {
                    serverGetPlayerExact = c.getMethod("getPlayerExact", String.class);
                } catch (Throwable ignored) {
                    // 继续向上找
                }
            }
        }
        if (serverGetPlayerExact == null) {
            try {
                serverGetPlayerExact = Class.forName("org.bukkit.Server").getMethod("getPlayerExact", String.class);
            } catch (Throwable ignored) {
                serverGetPlayerExact = null;
            }
        }
        try {
            serverGetPlayer = Class.forName("org.bukkit.Server").getMethod("getPlayer", String.class);
        } catch (Throwable ignored) {
            serverGetPlayer = null;
        }
        try {
            serverGetOfflinePlayerName = Class.forName("org.bukkit.Server").getMethod("getOfflinePlayer", String.class);
        } catch (Throwable ignored) {
            serverGetOfflinePlayerName = null;
        }
    }

    /** 重置探测缓存（插件 reload 后调用，便于中途装上 PAPI 无需重启） */
    public static void reset() {
        available = null;
        setPlaceholdersOffline = null;
        setPlaceholdersPlayer = null;
        serverInstance = null;
        serverGetPlayerExact = null;
        serverGetPlayer = null;
        serverGetOfflinePlayerName = null;
    }

    /**
     * 解析文本中的 PAPI 占位符。
     *
     * @param playerObject 服务端的玩家对象（OfflinePlayer/Player 实例），可为 null
     * @param text         原始文本
     * @return 解析后的文本；PAPI 不可用时原样返回
     */
    public static String apply(Object playerObject, String text) {
        if (text == null || text.isEmpty() || text.indexOf('%') < 0 || !isAvailable()) {
            return text;
        }
        try {
            if (playerObject != null && setPlaceholdersOffline != null) {
                Object result = setPlaceholdersOffline.invoke(null, playerObject, text);
                if (result instanceof String s) {
                    return s;
                }
            }
            if (setPlaceholdersOffline != null) {
                Object result = setPlaceholdersOffline.invoke(null, (Object) null, text);
                if (result instanceof String s) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败不影响主流程
        }
        return text;
    }

    /* ---------------- 上下文构造 ---------------- */

    /** 以指定玩家对象为上下文 */
    public static PlaceholderContext contextFor(Object playerObject) {
        if (!isAvailable()) {
            return PlaceholderContext.NONE;
        }
        return text -> apply(playerObject, text);
    }

    /** 以玩家名为上下文（在线优先，其次离线档案） */
    public static PlaceholderContext contextForName(String name) {
        if (!isAvailable()) {
            return PlaceholderContext.NONE;
        }
        Object player = resolvePlayer(name);
        return text -> apply(player, text);
    }

    /** 服务器级上下文（不含玩家相关占位符） */
    public static PlaceholderContext serverContext() {
        if (!isAvailable()) {
            return PlaceholderContext.NONE;
        }
        return text -> apply(null, text);
    }

    /** 通过反射按名字取玩家对象（在线优先，其次离线档案）；取不到返回 null */
    public static Object resolvePlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (!isAvailable()) {
            return null;
        }
        try {
            if (serverInstance != null && serverGetPlayerExact != null) {
                Object player = serverGetPlayerExact.invoke(serverInstance, name);
                if (player != null) {
                    return player;
                }
            }
            if (serverInstance != null && serverGetPlayer != null) {
                Object player = serverGetPlayer.invoke(serverInstance, name);
                if (player != null) {
                    return player;
                }
            }
            if (serverInstance != null && serverGetOfflinePlayerName != null) {
                return serverGetOfflinePlayerName.invoke(serverInstance, name);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 该玩家当前是否在线（不依赖 Bukkit 静态方法） */
    public static boolean isOnline(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            if (serverInstance != null && serverGetPlayerExact != null) {
                return serverGetPlayerExact.invoke(serverInstance, name) != null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }
}
