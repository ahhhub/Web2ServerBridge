package top.nekolove.web2serverbridge.dev;

import top.nekolove.web2serverbridge.config.BridgeConfig;
import top.nekolove.web2serverbridge.config.Messages;
import top.nekolove.web2serverbridge.listener.ConsoleListener;
import top.nekolove.web2serverbridge.message.BridgeMessage;
import top.nekolove.web2serverbridge.message.MessageRing;
import top.nekolove.web2serverbridge.util.Json;
import top.nekolove.web2serverbridge.util.SimpleYaml;
import top.nekolove.web2serverbridge.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件内置自检（不依赖 Bukkit、不依赖 MC 服务端）。
 *
 * <p>覆盖无法通过 HTTP 观察到的纯逻辑：控制台级别过滤（WARN/ERROR 必须丢弃）、
 * ANSI 清洗、消息环序号与截断语义、JSON 读写、配置解析。</p>
 *
 * <p>运行：{@code java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer --selftest}
 * 返回码 0 = 全部通过，1 = 有失败。</p>
 */
public final class SelfTest {

    private SelfTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static boolean run() {
        System.out.println("=== Web2ServerBridge 插件自检 ===");

        testConsoleLevelFilter();
        testAnsiStripping();
        testMessageRingBasics();
        testMessageRingTruncation();
        testJsonRoundTrip();
        testJsonEscaping();
        testYamlConfig();
        testTextUtil();
        testStatusSnapshotTimeLabel();
        testMessagesLayer();
        testPlaceholders();
        testYamlBlockScalar();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        for (String f : failures) {
            System.out.println("  ✘ " + f);
        }
        return failed == 0;
    }

    /* ---------------- 各测试 ---------------- */

    private static void testConsoleLevelFilter() {
        MessageRing ring = new MessageRing(50);
        // 只允许 INFO（与默认配置一致）
        ConsoleListener listener = new ConsoleListener(ring, List.of("INFO"), true, true, 256, s -> {
        });

        check("控制台 INFO 被采集", listener.acceptLine("[12:00:00] [Server thread/INFO]: Done (1.2s)!") == true);
        check("控制台 WARN 被丢弃", listener.acceptLine("[12:00:00] [Server thread/WARN]: Can't keep up!") == false);
        check("控制台 ERROR 被丢弃（控制台专有输出）",
                listener.acceptLine("[12:00:00] [Server thread/ERROR]: Something broke") == false);
        check("控制台 FATAL 被丢弃", listener.acceptLine("[12:00:00] [Server thread/FATAL]: fatal") == false);
        check("小写 warn 同样丢弃", listener.acceptLine("this is a warn line") == false);
        check("小写 error 同样丢弃", listener.acceptLine("this is an error line") == false);

        List<BridgeMessage> items = ring.snapshot();
        check("只有 1 条被采集（INFO）", items.size() == 1);
        if (!items.isEmpty()) {
            check("采集到的是 INFO 内容", items.get(0).text().contains("Done"));
            check("控制台消息 type=console", BridgeMessage.TYPE_CONSOLE.equals(items.get(0).type()));
        }

        // ERROR 与 WARN 属于控制台专有输出（游戏内玩家看不到），即使配置里写了也不采集
        MessageRing ring2 = new MessageRing(50);
        ConsoleListener listener2 = new ConsoleListener(ring2, List.of("INFO", "ERROR"), true, true, 256, s -> {
        });
        check("显式列入 ERROR 也仍然丢弃（控制台专有输出）",
                listener2.acceptLine("[12:00:00] [Server thread/ERROR]: x") == false);
        check("显式允许后 WARN 仍被丢弃", listener2.acceptLine("[12:00:00] [Server thread/WARN]: y") == false);
        check("默认配置不含 ERROR 与 WARN", !BridgeConfig.defaults().consoleLevels().contains("ERROR")
                && !BridgeConfig.defaults().consoleLevels().contains("WARN"));

        // 关闭采集
        MessageRing ring3 = new MessageRing(50);
        ConsoleListener off = new ConsoleListener(ring3, List.of("INFO"), false, true, 256, s -> {
        });
        check("include-console=false 时不采集", off.acceptLine("normal info line") == false);
        check("关闭后消息环为空", ring3.size() == 0);

        // 后台命令：默认不采集（游戏内玩家看不到，不应同步到网页）
        MessageRing ring4 = new MessageRing(50);
        ConsoleListener noCmd = new ConsoleListener(ring4, List.of("INFO"), true, true, 256, s -> {
        }, DevServer.devMessages());
        check("默认不采集后台命令", noCmd.includesCommands() == false);
        check("后台命令默认不入消息环",
                noCmd.acceptCommandForTest("setblock 1 -61 -2 grass_block") == false && ring4.size() == 0);

        // 显式打开时才采集（审计场景）
        MessageRing ring5 = new MessageRing(50);
        ConsoleListener withCmd = new ConsoleListener(ring5, List.of("INFO"), true, true, 256, s -> {
        }, DevServer.devMessages(), true);
        check("显式打开后采集后台命令",
                withCmd.acceptCommandForTest("setblock 1 -61 -2 grass_block") == true && ring5.size() == 1);
    }



    private static void testAnsiStripping() {
        String colored = "\u001B[32m[12:00:00 INFO]:\u001B[0m \u001B[36mDone\u001B[0m (\u001B[33m1.2s\u001B[0m)!";
        String stripped = TextUtil.stripAnsi(colored);
        check("ANSI 转义被清除", !stripped.contains("\u001B"));
        check("ANSI 清除后保留文本", stripped.contains("Done") && stripped.contains("1.2s"));

        MessageRing ring = new MessageRing(10);
        ConsoleListener listener = new ConsoleListener(ring, List.of("INFO"), true, true, 256, s -> {
        });
        listener.acceptLine(colored, "INFO");
        check("进环的控制台文本无 ANSI", !ring.snapshot().get(0).text().contains("\u001B"));
    }

    private static void testMessageRingBasics() {
        MessageRing ring = new MessageRing(5);
        long s1 = ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_GAME, "A", "第一条", false);
        long s2 = ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_WEB, "B", "第二条", true);
        check("序号从 1 递增", s1 == 1 && s2 == 2);
        check("seq() 反映最新序号", ring.seq() == 2);
        check("since(0) 返回全部", ring.since(0, 0).size() == 2);
        check("since(1) 只返回更新的", ring.since(1, 0).size() == 1);
        check("since 返回项序号正确", ring.since(1, 0).get(0).seq() == 2);
        check("web 标记透传", ring.since(0, 0).get(1).web());

        ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_GAME, "C", "第三", false);
        check("容量内不截断", !ring.isTruncated(1));

        for (int i = 0; i < 30; i++) {
            ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_GAME, "X", "刷屏" + i, false);
        }
        check("超出容量后只保留容量上限条数", ring.size() == ring.capacity());
        check("容量下限为 10", ring.capacity() == 10);
        check("oldestSeq 已推进", ring.oldestSeq() > 0);
        check("请求被淘汰的序号会标记截断", ring.isTruncated(1));

        List<BridgeMessage> limited = ring.since(0, 2);
        check("limit 生效且保留最新两条", limited.size() == 2 && limited.get(1).seq() == ring.seq());
    }

    private static void testMessageRingTruncation() {
        MessageRing ring = new MessageRing(10);
        check("空环不算截断", !ring.isTruncated(5));
        ring.add(BridgeMessage.TYPE_CHAT, BridgeMessage.SOURCE_GAME, "A", "x", false);
        check("请求未来序号不算截断", !ring.isTruncated(999));
    }

    private static void testJsonRoundTrip() {
        Json.Writer w = Json.writer().beginObject();
        w.put("ok", true);
        w.put("num", 12.345678);
        w.put("int", 42);
        w.put("text", "中文 with \"quotes\" and \\ slash");
        w.put("nil", (String) null);
        w.name("arr").beginArray().value(1).value("two").value(true).endArray();
        w.name("nested").beginObject().put("a", 1).endObject();
        w.endObject();

        String json = w.toString();
        Map<String, Object> parsed = Json.parseObject(json);
        check("JSON ok 字段", Boolean.TRUE.equals(parsed.get("ok")));
        check("JSON 小数被规整为 2 位", "12.35".equals(String.valueOf(parsed.get("num"))));
        check("JSON 整数", ((Number) parsed.get("int")).intValue() == 42);
        check("JSON 中文与转义往返", "中文 with \"quotes\" and \\ slash".equals(parsed.get("text")));
        check("JSON null", parsed.get("nil") == null);
        check("JSON 数组", parsed.get("arr") instanceof List && ((List<?>) parsed.get("arr")).size() == 3);
        check("JSON 嵌套对象", ((Map<?, ?>) parsed.get("nested")).get("a").equals(1));

        // 超长小数与 NaN 处理
        Json.Writer w2 = Json.writer().beginObject();
        w2.put("big", 19.980000000000004);
        w2.put("nan", Double.NaN);
        w2.endObject();
        String json2 = w2.toString();
        check("浮点噪声被消除", json2.contains("\"big\":19.98"));
        check("NaN 被替换为 -1", json2.contains("\"nan\":-1"));
    }

    private static void testJsonEscaping() {
        Json.Writer w = Json.writer().beginObject();
        w.put("ctrl", "line1\nline2\ttab");
        w.endObject();
        String json = w.toString();
        check("换行被转义", json.contains("\\n") && !json.contains("line1\nline2"));
        Map<String, Object> parsed = Json.parseObject(json);
        check("转义往返一致", "line1\nline2\ttab".equals(parsed.get("ctrl")));

        boolean threw = false;
        try {
            Json.parse("{bad json");
        } catch (Json.JsonException e) {
            threw = true;
        }
        check("非法 JSON 抛出 JsonException", threw);
    }

    private static void testYamlConfig() {
        String yaml = "http:\n"
                + "  enabled: true\n"
                + "  host: \"0.0.0.0\"\n"
                + "  port: 28069\n"
                + "  path-prefix: \"/api\"\n"
                + "  token: \"abc123\"\n"
                + "messages:\n"
                + "  buffer-size: 300\n"
                + "  include-console: true\n"
                + "  strip-ansi: false\n"
                + "  console-levels:\n"
                + "    - INFO\n"
                + "    - ERROR\n"
                + "  max-length: 200\n"
                + "  require-online: true\n"
                + "status:\n"
                + "  refresh-seconds: 3\n"
                + "  latency:\n"
                + "    mode: \"tick\"\n"
                + "    client-stale-seconds: 45\n"
                + "security:\n"
                + "  rate-limit:\n"
                + "    max-per-minute: 99\n"
                + "    send-per-minute: 7\n"
                + "server-name: \"测试服\"\n";

        BridgeConfig cfg = BridgeConfig.fromYaml(yaml);
        check("YAML 端口", cfg.httpPort() == 28069);
        check("YAML 主机", "0.0.0.0".equals(cfg.httpHost()));
        check("YAML 令牌", "abc123".equals(cfg.token()));
        check("YAML 前缀", "/api".equals(cfg.pathPrefix()));
        check("YAML 缓冲容量", cfg.messageBufferSize() == 300);
        check("YAML strip-ansi=false", !cfg.stripAnsi());
        check("YAML 控制台级别列表", cfg.consoleLevels().equals(List.of("INFO", "ERROR")));
        check("YAML 最大长度", cfg.maxMessageLength() == 200);
        check("YAML require-online", cfg.requireOnline());
        check("YAML 刷新间隔", cfg.statusRefreshSeconds() == 3);
        check("YAML 时延模式", "tick".equals(cfg.latencyMode()));
        check("YAML 时延过期秒数", cfg.latencyClientStaleSeconds() == 45);
        check("YAML 限流", cfg.rateLimitPerMinute() == 99 && cfg.sendPerMinute() == 7);
        check("YAML 服务器名", "测试服".equals(cfg.serverName()));

        // 默认值：只采 INFO（不含 ERROR）
        BridgeConfig def = BridgeConfig.defaults();
        check("默认只采集 INFO", def.consoleLevels().equals(List.of("INFO")));
        check("默认端口 28069", def.httpPort() == 28069);
        check("默认 strip-ansi 开启", def.stripAnsi());

        // 注释与引号
        SimpleYaml.Result r = SimpleYaml.parse("a: 1 # 注释\nb: \"x # not comment\"\nc: 'y'\n");
        check("YAML 行内注释被忽略", "1".equals(String.valueOf(r.root.get("a"))));
        check("YAML 引号内 # 保留", "x # not comment".equals(r.root.get("b")));
        check("YAML 单引号解析", "y".equals(r.root.get("c")));

        // 缩进嵌套
        SimpleYaml.Result nested = SimpleYaml.parse("out:\n  mode: push\n  url: \"https://x/y\"\n");
        Map<String, Object> out = SimpleYaml.section(nested.root, "out");
        check("YAML 嵌套映射", "push".equals(out.get("mode")));
        check("YAML URL 不被注释截断", "https://x/y".equals(out.get("url")));
    }

    private static void testTextUtil() {
        check("§ 颜色代码被清除", "Hello".equals(TextUtil.stripColors("§aHello")));
        check("多段颜色代码被清除", "AB".equals(TextUtil.stripColors("§aA§lB")));
        check("换行压缩为空格", "a b".equals(TextUtil.clean("a\nb")));
        check("连续空白压缩", "a b".equals(TextUtil.clean("a    b")));
        check("首尾空白去除", "x".equals(TextUtil.clean("  x  ")));
        check("截断生效", TextUtil.truncate("abcdef", 3).equals("abc"));
        check("不超长不截断", TextUtil.truncate("ab", 5).equals("ab"));
        check("敏感词命中", "bad".equals(TextUtil.matchBlockedWord("this is BAD word", List.of("bad"))));
        check("敏感词未命中", TextUtil.matchBlockedWord("hello", List.of("bad")) == null);
        check("级别识别 WARN", "WARN".equals(TextUtil.detectLevel("[x/WARN]: y")));
        check("级别识别 ERROR", "ERROR".equals(TextUtil.detectLevel("[x/ERROR]: y")));
        check("级别识别 INFO", "INFO".equals(TextUtil.detectLevel("[x/INFO]: y")));
        check("随机令牌长度", TextUtil.randomToken(48).length() == 48);
        check("随机令牌不重复", !TextUtil.randomToken(32).equals(TextUtil.randomToken(32)));
    }

    private static void testStatusSnapshotTimeLabel() {
        Messages msgs = DevServer.devMessages();
        check("0 刻=日出", msgs.rawOr("status-fields.time-sunrise", "Sunrise").equals(top.nekolove.web2serverbridge.status.StatusSnapshot.timeLabel(0, msgs)));
        check("6000 刻=正午", msgs.rawOr("status-fields.time-noon", "Noon").equals(top.nekolove.web2serverbridge.status.StatusSnapshot.timeLabel(6000, msgs)));
        check("13000 刻=日落", msgs.rawOr("status-fields.time-sunset", "Sunset").equals(top.nekolove.web2serverbridge.status.StatusSnapshot.timeLabel(13000, msgs)));
        check("18000 刻=深夜", msgs.rawOr("status-fields.time-midnight", "Midnight").equals(top.nekolove.web2serverbridge.status.StatusSnapshot.timeLabel(18000, msgs)));
        check("负数取模安全", msgs.rawOr("status-fields.time-midnight", "Midnight").equals(top.nekolove.web2serverbridge.status.StatusSnapshot.timeLabel(-6000, msgs)));

        var snap = DevServer.sampleSnapshot();
        Map<String, Object> map = snap.toMap();
        check("快照 ready", Boolean.TRUE.equals(map.get("ready")));
        check("快照含 players", map.get("players") instanceof Map);
        check("快照含 performance", map.get("performance") instanceof Map);
        check("快照含 world", map.get("world") instanceof Map);
    }

    /* ---------------- 文本层 / 占位符 ---------------- */

    private static void testMessagesLayer() {
        Messages msgs = DevServer.devMessages();

        check("messages 能读到嵌套键", msgs.has("http.unauthorized"));
        check("messages 条数合理", msgs.size() > 20);

        // 内置 {key} 占位符替换
        String disabled = msgs.raw("lifecycle.outbound-disabled");
        check("无占位符文本原样返回", "Outbound disabled".equals(disabled));

        String listening = msgs.raw("lifecycle.http-listening", "url", "http://127.0.0.1:28069/api");
        check("内置占位符被替换", listening.contains("http://127.0.0.1:28069/api"));
        check("占位符标记被移除", !listening.contains("{url}"));

        // 缺失键：raw 返回 [key] 占位并记录，rawOr 用兜底文本
        String missing = msgs.raw("no.such.key");
        check("缺失键返回 [key] 形式", missing.equals("[no.such.key]"));
        check("缺失键被记录", msgs.missing().containsKey("no.such.key"));
        check("rawOr 使用兜底文本", "fallback-text".equals(msgs.rawOr("no.such.key", "fallback-text")));

        // 颜色代码转换：mc 用于游戏内，raw 用于 HTTP/日志
        String mcFormatted = msgs.mc("game.web-message-format", "player", "Steve", "text", "hi");
        check("mc() 把 & 转成 §", mcFormatted.contains("\u00A7b"));
        check("mc() 替换了内置占位符", mcFormatted.contains("Steve") && mcFormatted.contains("hi"));
        check("mc() 不含原始 & 颜色代码", !mcFormatted.contains("&b"));

        String rawFormatted = msgs.raw("game.web-message-format", "player", "Steve", "text", "hi");
        check("raw() 保留 & 原样（HTTP 侧）", rawFormatted.contains("&b"));
        check("raw() 同样替换占位符", rawFormatted.contains("Steve"));

        // 多参数占位符
        String ok = msgs.raw("command.push-ok", "messages", 7, "delivered", 3);
        check("多占位符全部替换", ok.contains("7") && ok.contains("3") && !ok.contains("{"));
    }

    private static void testPlaceholders() {
        // 内置占位符
        String out = top.nekolove.web2serverbridge.util.PlaceholderResolver.format(
                "玩家 {player} 在 {world}", "player", "Alex", "world", "world_nether");
        check("内置占位符多值替换", "玩家 Alex 在 world_nether".equals(out));

        // 未知内置占位符保持原样（便于排查配置错误）
        String unknown = top.nekolove.web2serverbridge.util.PlaceholderResolver.format("x={unknown}", "a", "b");
        check("未知内置占位符保持原样", unknown.equals("x={unknown}"));

        // 颜色代码
        check("颜色代码转换", "\u00A7aOK".equals(top.nekolove.web2serverbridge.util.PlaceholderResolver.colorize("&aOK")));
        check("非法 & 不转换", "A & B".equals(top.nekolove.web2serverbridge.util.PlaceholderResolver.colorize("A & B")));

        // PAPI 占位符：未安装 PlaceholderAPI 时必须原样保留且不抛异常
        boolean papi = top.nekolove.web2serverbridge.util.PapiPlaceholders.isAvailable();
        String papiText = top.nekolove.web2serverbridge.util.PapiPlaceholders.apply(null, "在线 %server_online% 人");
        if (papi) {
            check("PAPI 可用时能解析（或保持原样）", papiText != null && !papiText.isEmpty());
        } else {
            check("PAPI 未安装时原样保留 %占位符%", papiText.equals("在线 %server_online% 人"));
        }
        check("PAPI 无 % 时不做处理", "no placeholders".equals(
                top.nekolove.web2serverbridge.util.PapiPlaceholders.apply(null, "no placeholders")));
        check("PAPI 未安装时上下文为 NONE",
                papi || top.nekolove.web2serverbridge.util.PapiPlaceholders.contextForName("Steve")
                        == top.nekolove.web2serverbridge.util.PlaceholderContext.NONE);

        // 渲染顺序：先内置 {} 再 PAPI %% 最后着色
        String mixed = top.nekolove.web2serverbridge.util.PlaceholderResolver.render(
                "&a{prefix}%server_online%", top.nekolove.web2serverbridge.util.PlaceholderContext.NONE,
                true, "prefix", "P-");
        check("混合渲染先替换内置占位符", mixed.contains("\u00A7aP-"));
        check("混合渲染保留 PAPI 占位符（未装 PAPI 时）",
                papi || mixed.contains("%server_online%"));

        // 自定义上下文（模拟 PAPI 已安装的行为，验证外挂解析链路可用）
        top.nekolove.web2serverbridge.util.PlaceholderContext fake =
                text -> text.replace("%server_online%", "42");
        String resolved = top.nekolove.web2serverbridge.util.PlaceholderResolver.render(
                "在线 {label}%server_online%", fake, false, "label", "人数:");
        check("自定义上下文能解析 %占位符%", "在线 人数:42".equals(resolved));
    }

    private static void testYamlBlockScalar() {
        String yaml = "a:\n"
                + "  multi: |-\n"
                + "    第一行\n"
                + "    第二行\n"
                + "  folded: >-\n"
                + "    折行 文本\n"
                + "    继续\n"
                + "  single: \"普通\"\n";
        SimpleYaml.Result r = SimpleYaml.parse(yaml);
        Map<String, Object> root = SimpleYaml.section(r.root, "a");
        String multi = String.valueOf(root.get("multi"));
        check("块标量保留换行", multi.contains("第一行") && multi.contains("第二行") && multi.contains("\n"));
        String folded = String.valueOf(root.get("folded"));
        check("折叠标量合成一行", folded.contains("折行 文本") && folded.contains("继续") && !folded.contains("\n"));
        check("块标量不影响后续键", "普通".equals(root.get("single")));
    }

    /* ---------------- 断言工具 ---------------- */

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✔ " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("  ✘ " + name);
        }
    }
}
