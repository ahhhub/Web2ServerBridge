package top.nekolove.web2serverbridge.listener;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;

import top.nekolove.web2serverbridge.message.BridgeMessage;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.util.TextUtil;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * 控制台监听：捕获「除 WARN 以外」的服务端控制台输出。
 *
 * <p>两条来源：</p>
 * <ol>
 *   <li><b>标准输出/错误流重定向</b>：启动时把 {@code System.out}/{@code System.err} 换成
 *       带缓冲的包装流，逐行回调。服务端绝大多数控制台输出（含插件通过
 *       {@code getLogger().info()} 写的日志）都会经过这里。</li>
 *   <li><b>控制台命令</b>：{@link ServerCommandEvent}，记录后台执行了什么命令。</li>
 * </ol>
 *
 * <p>WARN 级别一律丢弃（需求明确要求"监听除 WARN 的控制台消息"），
 * 其余级别按 {@code messages.console-levels} 白名单过滤。</p>
 *
 * <p>为避免递归与性能问题：写入回调只做「级别判断 + 入环」，不做任何打印；
 * 包装流自身不会再写回原始流之外的内容（原样透传，保持控制台行为不变）。</p>
 */
public final class ConsoleListener {

    private final MessageRing ring;
    private final List<String> allowedLevels;
    private final boolean enabled;
    private final boolean stripAnsi;
    private final int maxLength;
    private final Consumer<String> debugSink;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private PrintStream wrappedOut;
    private PrintStream wrappedErr;

    /** 后台命令的日志格式（来自 messages.yml 的 console.command-log） */
    final String commandLogTemplate;
    /** 控制台发送者消息前缀（来自 messages.yml 的 console.sender-prefix） */
    private final String senderPrefix;
    /** 是否采集后台命令（默认 false：游戏内看不到，不同步到网页） */
    private final boolean includeCommands;

    /** 是否启用后台命令采集（供自检/命令输出查询） */
    public boolean includesCommands() {
        return includeCommands;
    }

    public ConsoleListener(MessageRing ring, List<String> allowedLevels, boolean enabled,
                           boolean stripAnsi, int maxLength, Consumer<String> debugSink) {
        this(ring, allowedLevels, enabled, stripAnsi, maxLength, debugSink, null, false);
    }

    public ConsoleListener(MessageRing ring, List<String> allowedLevels, boolean enabled,
                           boolean stripAnsi, int maxLength, Consumer<String> debugSink,
                           top.nekolove.web2serverbridge.config.Messages messages) {
        this(ring, allowedLevels, enabled, stripAnsi, maxLength, debugSink, messages, false);
    }

    public ConsoleListener(MessageRing ring, List<String> allowedLevels, boolean enabled,
                           boolean stripAnsi, int maxLength, Consumer<String> debugSink,
                           top.nekolove.web2serverbridge.config.Messages messages,
                           boolean includeCommands) {
        this.ring = ring;
        this.allowedLevels = allowedLevels;
        this.enabled = enabled;
        this.stripAnsi = stripAnsi;
        this.maxLength = maxLength;
        this.includeCommands = includeCommands;
        this.debugSink = debugSink == null ? s -> {
        } : debugSink;
        this.commandLogTemplate = messages == null
                ? "[{command}]"
                : messages.rawOr("console.command-log", "[{command}]");
        this.senderPrefix = messages == null ? "" : messages.rawOr("console.sender-prefix", "");
    }

    /** 安装输出流包装（插件启用时调用） */
    public void install() {
        if (!enabled || wrappedOut != null) {
            return;
        }
        originalOut = System.out;
        originalErr = System.err;
        wrappedOut = new LineBufferedPrintStream(originalOut, this::acceptLine, false);
        wrappedErr = new LineBufferedPrintStream(originalErr, this::acceptLine, true);
        System.setOut(wrappedOut);
        System.setErr(wrappedErr);
    }

    /** 卸载（插件禁用/重载时调用，恢复原始流） */
    public void uninstall() {
        if (wrappedOut != null) {
            System.setOut(originalOut);
            wrappedOut.flush();
            wrappedOut = null;
        }
        if (wrappedErr != null) {
            System.setErr(originalErr);
            wrappedErr.flush();
            wrappedErr = null;
        }
    }

    /**
     * 控制台命令监听（默认关闭，由 {@code messages.include-console-commands} 控制）。
     *
     * <p><b>默认关闭的原因</b>：后台执行的命令属于控制台专有信息，
     * 游戏内玩家看不到，把它同步到网页会造成"网页看得到、游戏内看不到"的信息不对称，
     * 也与「控制台只同步游戏内可见内容」的采集原则冲突。
     * 需要审计后台命令时再打开。</p>
     */
    public static final class CommandListener implements Listener {
        private final ConsoleListener owner;

        public CommandListener(ConsoleListener owner) {
            this.owner = owner;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCommand(ServerCommandEvent event) {
            if (!owner.enabled || !owner.includeCommands) {
                return;
            }
            String cmd = event.getCommand();
            if (cmd == null || cmd.isBlank()) {
                return;
            }
            owner.acceptLine(
                    top.nekolove.web2serverbridge.config.Messages.format(
                            owner.commandLogTemplate, "command", cmd.trim()),
                    "INFO");
        }
    }

    /**
     * 供自检使用：走与后台命令监听完全相同的分支（便于断言"默认不采集"）。
     * 生产代码路径由 {@link CommandListener} 调用同样的 acceptLine。
     */
    public boolean acceptCommandForTest(String command) {
        if (!enabled || !includeCommands || command == null || command.isBlank()) {
            return false;
        }
        return acceptLine(
                top.nekolove.web2serverbridge.config.Messages.format(commandLogTemplate, "command", command.trim()),
                "INFO");
    }

    /** 控制台发送者文本（部分插件的消息通过 ConsoleCommandSender#sendMessage 输出） */
    public boolean acceptSenderMessage(String message, boolean error) {
        if (!enabled || message == null || message.isBlank()) {
            return false;
        }
        String text = senderPrefix == null || senderPrefix.isEmpty() ? message : senderPrefix + message;
        return acceptLine(text, error ? "ERROR" : "INFO");
    }

    /** 按行内容自动识别级别（内部流回调与命令路径使用） */
    public boolean acceptLine(String line) {
        return acceptLine(line, TextUtil.detectLevel(line));
    }

    /**
     * 处理一行控制台输出。
     *
     * <p>默认只采 INFO：ERROR 是控制台专有输出（游戏内玩家看不到），
     * WARN 无论如何都不采集。</p>
     *
     * @return 是否被采集
     */
    public boolean acceptLine(String line, String level) {
        if (!enabled || line == null || line.isBlank()) {
            return false;
        }
        String lvl = level == null ? "INFO" : level.toUpperCase();
        // 需求：WARN 与 ERROR 都不采集（ERROR 属于控制台专有输出）
        if ("WARN".equals(lvl) || "WARNING".equals(lvl) || "ERROR".equals(lvl) || "FATAL".equals(lvl)) {
            return false;
        }
        if (!allowedLevels.isEmpty() && !allowedLevels.contains(lvl)) {
            return false;
        }
        String clean = TextUtil.truncate(TextUtil.cleanConsole(line, stripAnsi), maxLength);
        if (clean.isEmpty()) {
            return false;
        }
        ring.add(BridgeMessage.TYPE_CONSOLE, BridgeMessage.SOURCE_SERVER, null, clean, false);
        return true;
    }

    /**
     * 行缓冲输出流：原样透传字节，同时按行回调。
     * 只在遇到 \n 时切分，避免把半行内容当作完整日志。
     */
    private static final class LineBufferedPrintStream extends PrintStream {
        private final Consumer<String> lineConsumer;
        private final StringBuilder buffer = new StringBuilder(256);
        private final boolean errorStream;

        LineBufferedPrintStream(PrintStream delegate, Consumer<String> lineConsumer, boolean errorStream) {
            super(delegate);
            this.lineConsumer = lineConsumer;
            this.errorStream = errorStream;
        }

        @Override
        public void write(int b) {
            super.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                if (buffer.length() < 4096) {
                    buffer.append((char) (b & 0xff));
                }
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            for (int i = off; i < off + len; i++) {
                int b = buf[i] & 0xff;
                if (b == '\n') {
                    flushLine();
                } else if (b != '\r') {
                    if (buffer.length() < 4096) {
                        buffer.append((char) b);
                    }
                }
            }
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null) {
                // println 可能走 write(String)，这里补一次确保不丢行
                if (buffer.length() == 0) {
                    synchronized (buffer) {
                        if (buffer.length() == 0) {
                            lineConsumer.accept(x);
                        }
                    }
                }
            }
        }

        private void flushLine() {
            String line;
            synchronized (buffer) {
                line = buffer.toString();
                buffer.setLength(0);
            }
            if (!line.isBlank()) {
                try {
                    lineConsumer.accept(line);
                } catch (Throwable ignored) {
                    // 采集失败绝不影响服务端输出
                }
            }
            if (errorStream) {
                // 保留错误流语义，不做额外处理
                return;
            }
        }
    }

    /** 供命令反馈使用：Bukkit 控制台发送者（可能为 null） */
    public static ConsoleCommandSender consoleSender() {
        try {
            return Bukkit.getConsoleSender();
        } catch (Throwable t) {
            return null;
        }
    }
}
