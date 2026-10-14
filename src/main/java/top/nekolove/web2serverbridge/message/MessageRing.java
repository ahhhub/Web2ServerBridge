package top.nekolove.web2serverbridge.message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 线程安全的消息环（定长）。
 *
 * HTTP 线程与游戏主线程都会访问，因此所有操作加锁；
 * 单条消息很小、操作极快，锁竞争可忽略。
 */
public final class MessageRing {

    private final int capacity;
    private final Deque<BridgeMessage> items;
    private long seq = 0;
    private long oldestSeq = 0;

    public MessageRing(int capacity) {
        this.capacity = Math.max(10, capacity);
        this.items = new ArrayDeque<>(this.capacity + 1);
    }

    /** 追加一条消息，返回其序号 */
    public synchronized long append(BridgeMessage message) {
        long next = ++seq;
        BridgeMessage stored = new BridgeMessage(next, message.time(), message.type(),
                message.source(), message.player(), message.text(), message.web());
        items.addLast(stored);
        while (items.size() > capacity) {
            BridgeMessage removed = items.pollFirst();
            if (removed != null) {
                oldestSeq = removed.seq();
            }
        }
        return next;
    }

    /** 便捷方法：直接追加文本消息 */
    public synchronized long add(String type, String source, String player, String text, boolean web) {
        return append(new BridgeMessage(0, System.currentTimeMillis(), type, source, player, text, web));
    }

    /** 当前最大序号 */
    public synchronized long seq() {
        return seq;
    }

    /** 当前最早序号（0 表示环内还没有消息） */
    public synchronized long oldestSeq() {
        return oldestSeq;
    }

    /** 全部消息快照（按时间顺序） */
    public synchronized List<BridgeMessage> snapshot() {
        return new ArrayList<>(items);
    }

    /**
     * 取序号大于 since 的消息。
     *
     * @param since 上次收到的最大序号；&lt;= 0 表示要全量
     * @param limit 最多返回条数（&lt;=0 表示不限）
     */
    public synchronized List<BridgeMessage> since(long since, int limit) {
        List<BridgeMessage> out = new ArrayList<>();
        for (BridgeMessage m : items) {
            if (m.seq() > since) {
                out.add(m);
            }
        }
        if (limit > 0 && out.size() > limit) {
            // 保留最新的 limit 条
            return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    /** 客户端请求的序号是否已被环淘汰（用于告知发生了截断） */
    public synchronized boolean isTruncated(long since) {
        return since > 0 && since < oldestSeq;
    }

    public int capacity() {
        return capacity;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void clear() {
        items.clear();
        // 序号保持单调，避免前端增量拉取逻辑错乱
    }
}
