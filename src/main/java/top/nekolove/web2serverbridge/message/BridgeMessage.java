package top.nekolove.web2serverbridge.message;

/**
 * 一条对外广播的消息。
 *
 * @param seq    单调递增序号（前端用它做增量拉取）
 * @param time   毫秒时间戳
 * @param type   chat（玩家/网页消息）| system（加入离开死亡等）| console（非 WARN 控制台）
 * @param source game（来自游戏）| web（来自网页）| server（服务器自身）
 * @param player 相关玩家名，控制台消息为 null
 * @param text   文本内容
 * @param web    是否来自网页（前端高亮用）
 */
public record BridgeMessage(long seq, long time, String type, String source, String player, String text, boolean web) {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_SYSTEM = "system";
    public static final String TYPE_CONSOLE = "console";

    public static final String SOURCE_GAME = "game";
    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_SERVER = "server";

    public static BridgeMessage chat(long seq, long time, String player, String text, boolean fromWeb) {
        return new BridgeMessage(seq, time, TYPE_CHAT, fromWeb ? SOURCE_WEB : SOURCE_GAME, player, text, fromWeb);
    }

    public static BridgeMessage system(long seq, long time, String text) {
        return new BridgeMessage(seq, time, TYPE_SYSTEM, SOURCE_SERVER, null, text, false);
    }

    public static BridgeMessage console(long seq, long time, String text) {
        return new BridgeMessage(seq, time, TYPE_CONSOLE, SOURCE_SERVER, null, text, false);
    }
}
