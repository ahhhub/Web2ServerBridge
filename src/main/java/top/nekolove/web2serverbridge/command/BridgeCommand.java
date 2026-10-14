package top.nekolove.web2serverbridge.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import top.nekolove.web2serverbridge.Web2ServerBridgePlugin;
import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.http.OutboundReporter;
import top.nekolove.web2serverbridge.status.StatusSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /web2serverbridge 管理命令。
 *
 * <pre>
 *   /w2sb status   查看桥接运行状态
 *   /w2sb config   查看关键配置
 *   /w2sb token    生成新的共享令牌并写入 config.yml（会热重载）
 *   /w2sb push     立即上报一次（验证后端连通性）
 *   /w2sb reload   热重载配置
 * </pre>
 *
 * <p><b>所有输出文本均来自 messages.yml</b>，代码中不含硬编码提示。</p>
 */
public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private final Web2ServerBridgePlugin plugin;

    public BridgeCommand(Web2ServerBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> printStatus(sender, label);
            case "config" -> printConfig(sender);
            case "token" -> rotateToken(sender);
            case "push" -> pushNow(sender);
            case "reload" -> {
                plugin.reload();
                reply(sender, plugin.messages().mc("lifecycle.config-reloaded"));
            }
            default -> {
                reply(sender, plugin.messages().mc("command.unknown-subcommand", "sub", args[0]));
                printHelp(sender, label);
            }
        }
        return true;
    }

    private void printHelp(CommandSender sender, String label) {
        Messages m = plugin.messages();
        reply(sender, m.mc("command.help-header"));
        reply(sender, m.mc("command.help-status", "command", label));
        reply(sender, m.mc("command.help-config", "command", label));
        reply(sender, m.mc("command.help-token", "command", label));
        reply(sender, m.mc("command.help-push", "command", label));
        reply(sender, m.mc("command.help-reload", "command", label));
    }

    private void printStatus(CommandSender sender, String label) {
        Messages m = plugin.messages();
        BridgeConfig cfg = plugin.config();
        StatusSnapshot s = plugin.statusCollector().current();

        reply(sender, m.mc("command.status-header"));
        reply(sender, m.mc("command.status-mode",
                "mode", cfg.outboundMode(),
                "uptime", plugin.uptimeSeconds()));

        if (plugin.httpRunning()) {
            reply(sender, m.mc("command.status-http-running",
                    "port", plugin.httpPort(), "prefix", cfg.pathPrefix()));
        } else if (plugin.httpFailed()) {
            reply(sender, m.mc("command.status-http-failed", "error", plugin.httpFailReason()));
        } else {
            reply(sender, m.mc("command.status-http-off"));
        }

        OutboundReporter reporter = plugin.outboundReporter();
        if (reporter != null && reporter.isStarted()) {
            Map<String, Object> state = reporter.state();
            reply(sender, m.mc("command.status-outbound-on",
                    "url", state.get("url"),
                    "seconds", state.get("intervalSeconds"),
                    "pending", state.get("awaitingAck")));
            Object err = state.get("lastError");
            if (err != null && !String.valueOf(err).isEmpty()) {
                reply(sender, m.mc("command.status-outbound-error", "error", err));
            }
        } else {
            reply(sender, m.mc("command.status-outbound-off"));
        }

        if (s != null && s.ready) {
            String latency = s.latency.valueMs >= 0
                    ? s.latency.valueMs + "ms"
                    : m.mc("command.status-latency-unknown");
            reply(sender, m.mc("command.status-players",
                    "current", s.playersOnline,
                    "max", s.playersMax,
                    "tps", s.tps1m,
                    "mspt", s.mspt,
                    "latency", latency,
                    "source", s.latency.source));
            reply(sender, m.mc("command.status-world",
                    "world", s.worldName,
                    "gamemode", s.gameMode,
                    "tick", s.tick,
                    "time", s.timeLabel,
                    "weather", s.weatherLabel == null || s.weatherLabel.isEmpty() ? s.weather : s.weatherLabel));
        } else {
            reply(sender, m.mc("command.status-not-ready"));
        }

        reply(sender, m.mc("command.status-messages",
                "size", plugin.ring().size(),
                "capacity", plugin.ring().capacity(),
                "seq", plugin.ring().seq(),
                "samples", plugin.latencyRegistry().size()));

        String tokenState = cfg.isTokenPlaceholder()
                ? m.mc("command.status-token-missing", "command", label)
                : m.mc("command.status-token-set");
        reply(sender, m.mc("command.config-line", "key", "token", "value", tokenState));
    }

    private void printConfig(CommandSender sender) {
        Messages m = plugin.messages();
        reply(sender, m.mc("command.config-header"));
        plugin.config().describe().forEach((k, v) ->
                reply(sender, m.mc("command.config-line", "key", k, "value", v)));
    }

    private void rotateToken(CommandSender sender) {
        Messages m = plugin.messages();
        String token = plugin.rotateToken();
        reply(sender, m.mc("command.token-header"));
        reply(sender, m.mc("command.token-value", "token", token));
        reply(sender, m.mc("command.token-hint"));
    }

    private void pushNow(CommandSender sender) {
        Messages m = plugin.messages();
        if (!plugin.config().outboundActive()) {
            reply(sender, m.mc("command.push-not-configured"));
            return;
        }
        OutboundReporter.ReportResult result = plugin.pushNow();
        if (result.ok()) {
            reply(sender, m.mc("command.push-ok",
                    "messages", result.messagesSent(), "delivered", result.delivered()));
        } else {
            reply(sender, m.mc("command.push-failed", "error", result.error()));
            reply(sender, m.mc("command.push-hint"));
        }
    }

    private static void reply(CommandSender sender, String message) {
        if (sender != null && message != null && !message.isEmpty()) {
            sender.sendMessage(message);
        }
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("status", "config", "token", "push", "reload"));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(s -> !s.startsWith(prefix));
            return options;
        }
        return List.of();
    }
}
