package top.nekolove.web2serverbridge.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.message.BridgeMessage;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.status.LatencyRegistry;
import top.nekolove.web2serverbridge.util.TextUtil;

/**
 * 游戏事件监听：
 * <ul>
 *   <li>玩家聊天（Paper 异步聊天事件）→ chat 消息</li>
 *   <li>加入 / 离开 / 死亡 → system 消息</li>
 * </ul>
 * 玩家离开时顺带清理其客户端时延样本。
 * <p>加入 / 离开 / 死亡的兜底文案来自 messages.yml（game.player-joined / player-left / player-died），
 * 原版事件自带播报文本时优先使用原版文本。</p>
 */
public final class MessageListener implements Listener {

    private final MessageRing ring;
    private final LatencyRegistry latencyRegistry;
    private final int maxLength;
    private final boolean includeSystem;
    private final boolean includeChat;
    private final Messages messages;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public MessageListener(MessageRing ring, LatencyRegistry latencyRegistry,
                           int maxLength, boolean includeSystem, boolean includeChat) {
        this(ring, latencyRegistry, maxLength, includeSystem, includeChat, null);
    }

    public MessageListener(MessageRing ring, LatencyRegistry latencyRegistry,
                           int maxLength, boolean includeSystem, boolean includeChat,
                           Messages messages) {
        this.ring = ring;
        this.latencyRegistry = latencyRegistry;
        this.maxLength = maxLength;
        this.includeSystem = includeSystem;
        this.includeChat = includeChat;
        this.messages = messages;
    }

    /** 兜底文案：优先 messages.yml，缺键/未注入时用英文兜底（与 rawOr 语义一致） */
    private String localized(String key, String fallback, String playerName) {
        if (messages == null) {
            return Messages.format(fallback, "player", playerName);
        }
        return messages.rawOr(key, fallback, "player", playerName);
    }

    /* ---------------- 聊天 ---------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!includeChat) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        String text = plain(event.message());
        String clean = TextUtil.truncate(TextUtil.clean(text), maxLength);
        if (clean.isEmpty()) {
            return;
        }
        ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_GAME, player.getName(), clean, false);
    }

    /* ---------------- 系统消息 ---------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!includeSystem) {
            return;
        }
        Player player = event.getPlayer();
        String text = plain(event.joinMessage());
        if (text == null || text.isBlank()) {
            text = localized("game.player-joined", "{player} joined the game", player.getName());
        }
        ring.add(BridgeMessage.TYPE_SYSTEM, BridgeMessage.SOURCE_SERVER, player.getName(),
                TextUtil.truncate(TextUtil.clean(text), maxLength), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 无论是否采集系统消息，都要清理时延样本
        latencyRegistry.remove(player.getName());
        if (!includeSystem) {
            return;
        }
        String text = plain(event.quitMessage());
        if (text == null || text.isBlank()) {
            text = localized("game.player-left", "{player} left the game", player.getName());
        }
        ring.add(BridgeMessage.TYPE_SYSTEM, BridgeMessage.SOURCE_SERVER, player.getName(),
                TextUtil.truncate(TextUtil.clean(text), maxLength), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!includeSystem) {
            return;
        }
        Player player = event.getEntity();
        String text = plain(event.deathMessage());
        if (text == null || text.isBlank()) {
            text = localized("game.player-died", "{player} died", player.getName());
        }
        ring.add(BridgeMessage.TYPE_SYSTEM, BridgeMessage.SOURCE_SERVER, player.getName(),
                TextUtil.truncate(TextUtil.clean(text), maxLength), false);
    }

    private static String plain(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return PLAIN.serialize(component);
        } catch (Throwable t) {
            return String.valueOf(component);
        }
    }
}
