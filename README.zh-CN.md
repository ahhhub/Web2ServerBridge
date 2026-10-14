# Web2ServerBridge

[English](README.md) | **简体中文**

[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square&labelColor=1f2328)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&labelColor=1f2328&logo=openjdk&logoColor=white)](#环境要求)
[![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-2196F3?style=flat-square&labelColor=1f2328)](#环境要求)
[![Release 1.0.0](https://img.shields.io/badge/Release-1.0.0-2ea043?style=flat-square&labelColor=1f2328)](CHANGELOG.md)

| | |
| :-- | :-- |
| **平台** | Paper / Purpur **1.21+**（`api-version: 1.21`） |
| **运行环境** | 服务端 **Java 21+** · 构建需 **JDK 25**（Paper 26.2 为 class 版本 69） |
| **通信方式** | `pull`（内置 HTTP 服务）· `push`（主动上报，面板服/NAT 友好） |
| **运行时依赖** | 无 —— JSON/YAML 解析自研，jar 自包含 |
| **文本** | 100% 外置到 `messages.yml`（内置 `{占位符}` + PlaceholderAPI） |
| **许可证** | MIT |

一个 Minecraft 服务端插件：把**服务器实况与聊天消息以 JSON 经 HTTP 对外提供**，
并允许网站**以玩家身份在游戏内发言**——并且**不需要任何入站端口**。

> 适用于 NAT、Docker 与面板服（MCSManager / Pterodactyl / 宝塔等）：
> 插件可以主动把状态推送给你的后端，并在**同一个 HTTP 响应**里取回排队的网页消息。

---

## 目录

- [设计动机](#设计动机)
- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [构建](#构建)
- [安装](#安装)
- [配置规范](#配置规范)
  - [`config.yml`](#configyml)
  - [`messages.yml`](#messagesyml)
- [命令与权限](#命令与权限)
- [HTTP API（pull 模式）](#http-apipull-模式)
  - [通用约定](#通用约定)
  - [端点](#端点)
  - [错误码](#错误码)
  - [在线 / 离线判定](#在线--离线判定)
- [主动上报协议（push）](#主动上报协议push)
- [前端集成示例](#前端集成示例)
- [开发与自检](#开发与自检)
- [常见问题](#常见问题)
- [许可证](#许可证)

---

## 设计动机

大多数「网站 ↔ Minecraft」桥接方案都假设游戏服有公网 IP 且端口可入站，
而面板服与家宽并不满足。因此 Web2ServerBridge 支持两个相互独立的方向：

- **pull** —— 插件监听本地端口，由你的后端来拉取。
- **push** —— 插件每秒调用**你的**后端；排队的网页消息随 HTTP 响应返回，
  因此完全不需要外部能访问到游戏服。

两者可以同时开启（`outbound.mode: both`），并且都不需要配套插件、数据库或客户端模组。
配套客户端模组的时延上报是受支持的，但属于可选能力。

## 功能特性

- **实况状态**：在线/最大人数与玩家名单、MOTD、服务端版本、运行时长、
  TPS（1m/5m/15m）、MSPT（当前/峰值）、世界名、游戏模式、难度、
  游戏刻、游戏内时段与天气。
- **消息流**：游戏内聊天、加入/离开/死亡播报以及（可选）控制台输出，
  统一进入一个有序环形缓冲，带单调递增序号，前端可用 `?since=<seq>` 增量轮询。
- **网页发言**：`POST /api/send` 按可配置格式把网页消息广播到游戏内，
  随后写入消息流，发送者立刻能看到自己的消息。
- **双通道通信**：如上所述，含投递确认与有界待确认队列。
- **默认即安全**：常量时间令牌比较、单 IP 限流、请求体与消息长度上限、
  可选敏感词、可选在线校验、CORS 白名单。
- **所有外显文本均可配置**（`messages.yml`），包括状态字段标签、命令输出与
  HTTP 错误文案，支持 `{内置占位符}` 与 PlaceholderAPI `%占位符%`。
- **可离线构建**：本地仓库已预热时，`mvn -o -B clean package` 可直接构建；
  运行时不引入任何第三方依赖。

## 环境要求

| | |
| :-- | :-- |
| 服务端 | Paper、Purpur 或其他 Paper 分支 **1.21+**（使用 JDK 自带 `com.sun.net.httpserver`，无需额外插件） |
| 服务端 Java | **Java 21 或更高** |
| 构建 Java | **JDK 25** —— `paper-api 26.2` 编译目标为 class 版本 69；`<release>21</release>` 仍输出 Java 21 字节码 |
| 构建工具 | Maven 3.9+ |
| 可选 | [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)（用于解析 `messages.yml` 里的 `%占位符%`） |

## 构建

```bash
git clone https://github.com/ahhhub/Web2ServerBridge.git
cd Web2ServerBridge
mvn -B clean package
```

产物为 `target/Web2ServerBridge-1.0.0.jar`。

构建要点：

- 所有依赖（`paper-api`、Adventure、BungeeCord chat、JetBrains annotations）均为
  `<scope>provided</scope>`，仓库声明使用官方 `papermc` 源；不会打进 jar，
  也不存在与本机绑定的 `systemPath`。
- 本机仓库已预热时，可完全离线构建：

  ```bash
  mvn -o -B clean package
  ```

- Windows PowerShell + 自定义 JDK 25 路径：

  ```powershell
  $env:JAVA_HOME = "C:\path\to\jdk-25"
  mvn -B clean package
  ```

- 运行时不打包任何东西：JSON 读写（`util/Json.java`）与轻量 YAML 读取
  （`util/SimpleYaml.java`）都是插件自身的一部分。

## 安装

1. 把 `target/Web2ServerBridge-1.0.0.jar` 复制到服务端 `plugins/` 目录。
2. 启动（或重启）服务端。首次启动会生成 `plugins/Web2ServerBridge/config.yml`
   与 `plugins/Web2ServerBridge/messages.yml`。
3. 设置真实共享令牌。可以编辑 `config.yml` 的 `http.token`，或直接在控制台执行
   `/web2serverbridge token` —— 它会生成 48 位随机令牌、写入 `config.yml` 并重载插件。
4. 把后端指向插件：
   - **pull**：`http://<游戏服地址>:28069/api/…`，携带 `Authorization: Bearer <token>`。
   - **push**：把 `outbound.url` 设为你的后端端点，并保证两端 `http.token`
     （或 `outbound.token`）一致。
5. 改完配置执行 `/web2serverbridge reload`（host/port 变更会重启内置 HTTP 服务；
   修改 `messages.yml` 同样需要 reload）。

当令牌仍是默认占位值 `CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN` 时，插件会在启动时告警。

## 配置规范

### `config.yml`

#### `http` —— 内置 HTTP 服务（pull 模式）

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `http.enabled` | boolean | `true` | 是否启用内置 HTTP 服务。设为 `false` 即纯 push 运行。 |
| `http.host` | string | `0.0.0.0` | 监听地址。同机有反向代理时建议 `127.0.0.1`。 |
| `http.port` | int | `28069` | 监听端口。 |
| `http.path-prefix` | string | `/api` | 路由前剥离的前缀；会自动规范为以 `/` 开头且无结尾 `/`。 |
| `http.token` | string | `CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN` | 共享密钥。除 `/ping`、`/health`、`/timing` 外所有端点都要求校验。 |
| `http.max-body-bytes` | int | `16384` | 请求体上限（最小 256）。超限返回 `413 payload_too_large`。 |
| `http.cors-allow-origin` | string | `*` | `Access-Control-Allow-Origin` 的取值。若浏览器直连请收紧；推荐走后端同源代理。 |
| `http.threads` | int | `4` | 请求处理线程数（最小 1）。 |

#### `outbound` —— 主动上报（push 模式）

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `outbound.mode` | `both` \| `push` \| `pull` | `both` | `pull` 只监听、`push` 只上报（不启 HTTP 服务）、`both` 两者兼有。 |
| `outbound.url` | string | `""` | 插件 POST 的后端地址。**留空即关闭 push。** 示例：`https://example.com/api/bridge/report`。 |
| `outbound.ack-url` | string | `""` | 投递确认地址。留空时自动把 `url` 末尾的 `/report` 换成 `/ack`。 |
| `outbound.token` | string | `""` | push/ack 使用的令牌。留空则回退使用 `http.token`。 |
| `outbound.interval-seconds` | int | `1` | 上报周期（最小 1，实际钳制为 1–10）。后端可在响应里覆盖。 |
| `outbound.connect-timeout-ms` | int | `3000` | TCP 连接超时（最小 500）。 |
| `outbound.read-timeout-ms` | int | `5000` | 请求/响应超时（最小 1000）。 |
| `outbound.queue-max` | int | `500` | 等待确认的消息 id 上限，超出丢弃最旧（最小 10）。 |
| `outbound.enabled-on-start` | boolean | `true` | 插件启用后是否立即开始上报。 |

#### `messages` —— 消息采集

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `messages.buffer-size` | int | `200` | 环形缓冲容量（最小 10）。更早的条目被淘汰并标记 `truncated`。 |
| `messages.include-console` | boolean | `true` | 是否采集控制台输出。 |
| `messages.console-levels` | list | `[INFO]` | 采集的控制台级别。`WARN` 永不采集；`ERROR` 属于控制台专有输出、游戏内玩家看不到，默认同样不采。 |
| `messages.strip-ansi` | boolean | `true` | 是否清洗控制台行的 ANSI 颜色转义（面板服建议开启）。 |
| `messages.include-system` | boolean | `true` | 是否记录加入/离开/死亡播报（游戏内可见，网页上同样有意义）。 |
| `messages.include-console-commands` | boolean | `false` | 是否把后台命令同步进消息流（仅审计场景 —— 玩家在游戏内看不到）。 |
| `messages.web-prefix` | string | `"[网页] "` | 网页来源消息在游戏内的前缀。 |
| `messages.max-length` | int | `256` | 单条消息最大字符数（最小 16），超长截断。 |
| `messages.block-words` | list | `[]` | 大小写不敏感的屏蔽词；命中则拒绝发送并返回 `blocked`。留空表示不过滤。 |
| `messages.require-online` | boolean | `false` | 是否要求发言玩家当前在线。 |

#### `status` —— 状态采集

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `status.refresh-seconds` | int | `2` | 快照刷新间隔（最小 1，在主线程采样）。 |
| `status.latency.mode` | `auto` \| `client` \| `tick` | `auto` | `auto` 优先客户端上报样本、退化到后端实测链路 RTT；`client` 只用客户端样本（无样本时为 `-1`）；`tick` 忽略客户端样本。 |
| `status.latency.client-stale-seconds` | int | `30` | 超过该秒数的客户端时延样本被丢弃（最小 5）。 |

#### `security`

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `security.rate-limit.max-per-minute` | int | `60` | 单 IP 每分钟请求上限（最小 1）→ `429 rate_limited`。 |
| `security.rate-limit.send-per-minute` | int | `10` | 单 IP 每分钟发言上限（最小 1）→ `429 rate_limited`。 |
| `security.log-requests` | boolean | `false` | 是否每个 HTTP 请求记一行日志（方法、路径、状态、耗时、客户端 IP）。 |

#### 根级键

| 键 | 类型 | 默认值 | 说明 |
| :-- | :-- | :-- | :-- |
| `server-name` | string | `"Minecraft Server"` | 对外 JSON 中 `server.name` 的显示名；MOTD 来自服务端本身。 |
| `motd-keep-color-codes` | boolean | `true` | 是否在 `server.motd` 中保留 `§` 颜色代码供前端渲染；`false` 则剥离。 |

### `messages.yml`

所有人类可见的字符串都在 `messages.yml` 里 —— 插件代码不硬编码任何提示文本，
因此翻译或改写文案无需重新编译。

**分组**

| 分组 | 覆盖内容 |
| :-- | :-- |
| `lifecycle.*` | 启动/停用/重载日志、HTTP 启动失败、上报成功与失败、令牌告警、messages.yml 加载。 |
| `game.*` | 网页消息的游戏内格式（`web-message-format`）、插件日志格式、加入/离开/死亡兜底文本。 |
| `console.*` | 后台命令日志格式与控制台发送者前缀。 |
| `command.*` | `/web2serverbridge` 的全部输出：帮助、状态、配置、令牌轮换、手动上报。 |
| `http.*` | 返回给前端的每个 HTTP 错误 `message`（`unauthorized`、`rate-limited`、`send-*`、`report-*` 等）。 |
| `status-fields.*` | 本地化状态标签：`server-online`、`server-offline`、`server-unknown`、时段与天气标签。 |

**占位符**

- 内置 `{name}` 风格占位符按 key 传入。随插件发布的 `messages.yml` 使用的完整集合为：
  `{body}` `{capacity}` `{command}` `{count}` `{current}` `{delivered}` `{error}`
  `{gamemode}` `{http}` `{id}` `{key}` `{latency}` `{max}` `{messages}` `{mode}` `{mspt}`
  `{name}` `{new}` `{old}` `{online}` `{outbound}` `{path}` `{pending}` `{player}` `{port}`
  `{prefix}` `{samples}` `{seconds}` `{seq}` `{size}` `{source}` `{status}` `{sub}` `{text}`
  `{tick}` `{time}` `{token}` `{tps}` `{uptime}` `{url}` `{value}` `{version}` `{weather}` `{world}`。
  未知占位符保持原样，便于排查配置错误。
- **PlaceholderAPI** 的 `%占位符%`（如 `%server_online%`、`%player_name%`）在服务端装有
  PAPI 时自动解析。**未安装 PAPI 时原样返回** —— 不报错、不丢内容。
- 渲染顺序：内置 `{…}` → PlaceholderAPI `%…%` → `&` 颜色代码。`mc()` 输出把 `&a`
  转成游戏内使用的 `§a`；HTTP/JSON 响应保持原始 `&` 形式。
- 完全支持 YAML **块标量**多行写法，例如：

  ```yaml
  lifecycle:
    token-placeholder-warning: |-
      http.token 仍是默认占位值！
      请执行 /web2serverbridge token 并把新令牌同步到后端。
  ```

- 修改后执行 `/web2serverbridge reload`（或重启服务端）生效。
- 缺少某个 key 时，调用方会退化到内置英文兜底文本；文件格式错误时插件会退化为内置
  文本并打印告警，而不是启动失败。

## 命令与权限

| 命令 | 说明 |
| :-- | :-- |
| `/web2serverbridge status` | 显示运行模式、HTTP 状态、上报状态、人数/TPS/MSPT/时延、世界信息与令牌状态。 |
| `/web2serverbridge config` | 打印关键运行时配置。 |
| `/web2serverbridge token` | 生成 48 位随机令牌、写入 `config.yml` 并重载。 |
| `/web2serverbridge push` | 立即上报一次（后端连通性测试）。 |
| `/web2serverbridge reload` | 重载 `config.yml` 与 `messages.yml`；host/port 变更时重启 HTTP 服务。 |

- 别名：`/w2sb`、`/bridge`
- 权限：`web2serverbridge.admin`（默认 `op`）
- Tab 补全覆盖全部五个子命令。

## HTTP API（pull 模式）

### 通用约定

- 基础地址：`http://<host>:<http.port><http.path-prefix>`，默认
  `http://127.0.0.1:28069/api`。
- 请求/响应体：JSON，UTF-8。响应为 `application/json; charset=utf-8`，带
  `Cache-Control: no-store`。
- 鉴权 —— 三种方式任选其一：

  | 方式 | 示例 |
  | :-- | :-- |
  | `Authorization` 头 | `Authorization: Bearer <YOUR_TOKEN>` |
  | `X-Bridge-Token` 头 | `X-Bridge-Token: <YOUR_TOKEN>` |
  | 查询参数 | `?token=<YOUR_TOKEN>` |

  令牌比较为常量时间。`/ping`、`/health`、`/timing` **不需要**令牌。
- 限流用的客户端 IP 依次取 `X-Real-IP`、`X-Forwarded-For` 最后一跳、套接字地址。
- 当配置了 `http.cors-allow-origin` 时，`OPTIONS` 请求返回 `204` 与 CORS 头。
- `GET /status` 与 `GET /messages` 支持 `since`（序号游标，默认 `0`）与
  `limit`（默认 `100`，上限 `500`）。

### 端点

#### `GET /ping`、`GET /health` —— 存活探测（无需令牌）

```json
{ "ok": true, "service": "web2serverbridge", "version": "1.0.0" }
```

#### `GET /timing` —— 链路 RTT 探测（无需令牌）

```json
{ "ok": true, "now": 1760400000000, "seq": 1234 }
```

连续调用两次取最小值，即可估算「后端 ↔ 游戏服」的往返时延；`now` 为插件时钟（毫秒）。

#### `GET /status` —— 完整快照（需要令牌）

查询参数：`since`（long）、`limit`（int，≤ 500）。

```json
{
  "ok": true,
  "schema": 1,
  "pluginVersion": "1.0.0",
  "serverTime": 1760400000000,
  "server": {
    "name": "Minecraft Server",
    "motd": "§bA Minecraft Server",
    "version": "Purpur 26.2 (MC 1.21.x)",
    "online": true,
    "statusLabel": "在线",
    "collectedAt": 1760399999000,
    "uptimeSeconds": 3600,
    "startedAt": 1760396400000
  },
  "players": { "online": 3, "max": 20, "list": ["Alex", "Steve"] },
  "performance": {
    "tps1m": 19.98, "tps5m": 20.0, "tps15m": 19.97,
    "mspt": 12.4, "msptMax": 51.2,
    "latencyMs": 37, "latencyMaxMs": 62,
    "latencySource": "client_report", "latencySamples": 3,
    "transportMs": -1, "clientPingMs": 37, "clientPingSamples": 3
  },
  "world": {
    "worldName": "world", "gameMode": "SURVIVAL", "difficulty": "NORMAL",
    "tick": 1234567, "day": 42, "timeTicks": 6000,
    "timeLabel": "正午", "weather": "晴朗", "weatherRaw": "CLEAR",
    "playerList": ["Alex", "Steve"]
  },
  "messages": {
    "seq": 4211, "oldestSeq": 4000, "truncated": false, "count": 12,
    "items": [
      { "seq": 4200, "time": 1760399995000, "type": "chat",
        "source": "game", "player": "Steve", "text": "hi", "web": false }
    ]
  }
}
```

状态码：就绪时 `200`；插件存活但首次快照尚未采集完成时 `503`
（`ok: true`、`ready: false`、`server.online: true`）；令牌错误 `401`；限流 `429`。

字段说明：

- `performance.latencyMs` 是「前端 ↔ MC 服务端」的通信时延，`-1` 表示暂不可用。
  `latencySource` 取值：`client_report`（配套客户端模组经 `POST /api/report` 上报）、
  `backend_probe`（由嵌入方提供的链路 RTT）、`none`。
- `world.weather` 是 `messages.yml` 里的本地化标签；`weatherRaw` 为
  `CLEAR` / `RAIN` / `THUNDER`。`world.timeLabel` 同理本地化。
- `messages.truncated: true` 表示请求的 `since` 游标已被环形缓冲淘汰 ——
  请从 `oldestSeq` 重新拉取。

#### `GET /messages` —— 仅消息流（需要令牌）

```json
{ "ok": true, "serverTime": 1760400000000, "messages": { "seq": 4211, "oldestSeq": 4000,
  "truncated": false, "count": 12, "items": [ /* 与上面相同的条目结构 */ ] } }
```

消息条目字段：`seq`（单调递增）、`time`（epoch 毫秒）、`type`（`chat` | `system` |
`console`）、`source`（`game` | `web` | `server`）、`player`（可能为 null）、`text`、
`web`（`true` 表示来自网页）。

#### `POST /send` —— 以玩家身份在游戏内发言（需要令牌）

请求：

```json
{ "username": "Steve", "text": "hello from the website", "ip": "203.0.113.7" }
```

`ip` 可选，仅作信息记录。

成功：

```json
{ "ok": true, "seq": 4212, "broadcast": true }
```

`broadcast: false` 表示消息已受理，但当时没有在线玩家可接收。
消息一定会进入消息流（`type: chat`、`source: web`、`web: true`），
因此轮询的前端能立刻看到自己发的内容。

失败：`400 bad_request`（缺少/过长的用户名、空或非法文本）、
`400 blocked`（命中屏蔽词）、`409 not_online`（`messages.require-online: true`
且发送者不在线）、`429 rate_limited`。

#### `POST /api/report` —— 配套客户端时延样本（需要令牌）

```json
{ "username": "Steve", "pingMs": 37 }
```

`pingMs` 会被钳制到 `0..60000`。成功返回 `{ "ok": true }`；字段缺失返回
`400 bad_request`；插件未装配时延接收器时返回 `503 not_ready`。

### 错误码

| HTTP | `error` | 含义 |
| :-- | :-- | :-- |
| 400 | `bad_request` | JSON 格式错误或字段非法/缺失。 |
| 400 | `blocked` | 文本命中 `messages.block-words`。 |
| 401 | `unauthorized` | 令牌缺失或错误。 |
| 404 | `not_found` | 前缀下的未知路径。 |
| 405 | `method_not_allowed` | HTTP 方法不被该端点支持。 |
| 409 | `not_online` | 发送者不在线（`messages.require-online`）。 |
| 413 | `payload_too_large` | 请求体超过 `http.max-body-bytes`。 |
| 429 | `rate_limited` | 超出单 IP 请求或发言限流。 |
| 500 | `internal_error` | 插件内部异常（详情见服务端日志）。 |
| 503 | `not_ready` | 快照或时延接收器尚未就绪。 |

错误响应体：`{ "ok": false, "error": "<code>", "message": "<messages.yml 中的文本>" }`。
`error` 是稳定、机器可读的；`message` 是可展示文本，可在 `messages.yml` 中本地化。

### 在线 / 离线判定

- 插件运行在 Minecraft 服务端进程**内部**。只要它能响应 `/ping`、`/health` 或
  `/status`，游戏服就是**在线** —— 此时 `server.online` 为 `true`，
  即使是 `503`「尚未就绪」响应也如此。
- **由消费方**决定何时算离线：push 模式下，如果连续超过你自己设定的离线阈值
  （常见取 `outbound.interval-seconds` 的 3 倍，通常 3–10 秒）没有成功上报，
  就标记为离线，并继续渲染**最后一次收到的快照**，避免页面变空。
- 该状态的本地化文案在 `messages.yml`：
  `status-fields.server-online`、`status-fields.server-offline`、`status-fields.server-unknown`。

## 主动上报协议（push）

当 `outbound.mode` 为 `push` 或 `both` **且** `outbound.url` 非空时启用。

每隔 `outbound.interval-seconds`（1–10 秒，超范围被钳制）插件发送：

```http
POST <outbound.url>
Content-Type: application/json; charset=utf-8
Authorization: Bearer <outbound.token 或 http.token>
User-Agent: Web2ServerBridge/1.0.0
```

```json
{
  "schema": 1,
  "ok": true,
  "mode": "push",
  "pluginVersion": "1.0.0",
  "sentAt": 1760400000000,
  "status": {
    "ready": true, "collectedAt": 1760399999000,
    "server": { "name": "Minecraft Server", "motd": "…", "version": "…",
                "online": true, "uptimeSeconds": 3600, "startedAt": … },
    "players": { "online": 3, "max": 20, "list": ["Alex", "Steve"] },
    "performance": { "tps1m": 19.98, "…": "…" },
    "world": { "worldName": "world", "…": "…" }
  },
  "messages": {
    "seq": 4211, "oldestSeq": 4000, "truncated": false,
    "items": [ { "seq": 4210, "time": …, "type": "chat", "source": "game",
                 "player": "Steve", "text": "hi", "web": false } ]
  },
  "pending": { "delivered": [4188, 4190] }
}
```

- `messages.items` 只包含自上次成功上报以来的**增量**（基于游标），因此不会重复投递。
- `pending.delivered` 列出已在游戏内广播过的网页消息 id，你的后端可将其视为已确认
  （可从队列中移除）。

你的后端返回 `2xx` 与：

```json
{
  "ok": true,
  "reportIntervalSeconds": 2,
  "deliver": [ { "id": 4199, "username": "Steve", "text": "hello from the web" } ]
}
```

- `deliver[]` 是排队的网页消息。插件逐条广播到游戏内，然后进行确认。
- `reportIntervalSeconds`（可选，1–10）在运行时覆盖 `outbound.interval-seconds`，
  便于在不逐台改配置的情况下统一调节实时性。
- 确认是发往 `outbound.ack-url` 的独立请求（默认把 `url` 末尾的 `/report`
  替换为 `/ack`）：

  ```json
  { "ids": [4199] }
  ```

  即使响应体无法解析，本次上报本身仍算成功。
- 失败处理：`401`/`403` 与非 2xx 状态会被记录（限频为每 30 秒一条），且游标**不会**
  推进，因此不会丢消息；待确认队列受 `outbound.queue-max` 约束，超出的最旧 id 被丢弃。
- 通过 `POST /api/send` 接收网页消息后，插件会立即触发一次额外上报，
  让你的后端不必等满一个周期。

## 前端集成示例

**绝不要把 `http.token` 下发到浏览器。** 让浏览器访问你自己的后端，
由后端持有令牌并与插件通信（或接收推送上报）。

### 1. 后端代理（Node/Express，pull 模式）

```js
import express from "express";
const app = express();
app.use(express.json());

const MC_API = "http://127.0.0.1:28069/api";        // 游戏服
const MC_TOKEN = process.env.MC_BRIDGE_TOKEN;      // 只保存在服务端
const auth = { Authorization: `Bearer ${MC_TOKEN}` };

// 供浏览器轮询的状态端点（?since=<seq>）
app.get("/api/state", async (req, res) => {
  const since = Number(req.query.since ?? 0);
  const r = await fetch(`${MC_API}/status?since=${since}&limit=50`, { headers: auth });
  const data = await r.json();
  if (r.status === 503) return res.json({ ok: true, online: true, ready: false, messages: data.messages });
  if (!r.ok) return res.status(r.status).json(data);
  res.json({
    ok: true, ready: true, online: data.server.online,
    server: data.server, players: data.players,
    performance: data.performance, world: data.world,
    messages: data.messages,
  });
});

// 供浏览器发言的端点
app.post("/api/say", async (req, res) => {
  const r = await fetch(`${MC_API}/send`, {
    method: "POST",
    headers: { ...auth, "Content-Type": "application/json" },
    body: JSON.stringify({ username: req.body.username, text: req.body.text }),
  });
  res.status(r.status).json(await r.json());
});

app.listen(3000);
```

### 2. 浏览器轮询 + 发言

```js
let since = 0;
let failures = 0;
const OFFLINE_AFTER = 3;           // 连续失败次数 ⇒ 显示「离线」
const OFFLINE_THRESHOLD_SECONDS = 10;

async function tick() {
  try {
    const res = await fetch(`/api/state?since=${since}`);
    const data = await res.json();
    failures = 0;

    if (data.ready === false) return renderOnlinePending();

    since = data.messages.seq;                       // 增量游标
    const fresh = data.messages.items.filter(m => m.seq > lastRenderedSeq);
    renderStatus(data);                              // 人数、TPS、MSPT、世界……
    renderMessages(fresh);                           // 聊天/系统/控制台配色
    lastRenderedSeq = since;
    lastSeenAt = Date.now();

    if (data.messages.truncated) {                   // 游标被淘汰 —— 重新同步
      since = data.messages.oldestSeq;
    }
  } catch (e) {
    if (++failures >= OFFLINE_AFTER) renderOffline();
  }
}

async function say(username, text) {
  const res = await fetch("/api/say", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, text }),
  });
  const data = await res.json();
  if (!data.ok) showToast(data.message);             // 已由 messages.yml 本地化
}

setInterval(tick, 1000);
tick();
```

### 3. 离线判定

```js
let lastSnapshot = null;     // 最后一次成功的数据
let lastSeenAt = Date.now(); // 每次成功轮询时更新（见 tick()）

function renderStatus(payload) {
  lastSnapshot = payload;
  lastSeenAt = Date.now();
  setStatusDot(payload.online ? "green" : "red", payload.online ? "在线" : "离线");
  // ……人数、性能、世界、消息
}

function renderOffline() {
  // 保留最后一次快照，但置灰并切换状态标签。
  // 本地化文案来自 messages.yml ⇒ status-fields.server-offline。
  setStatusDot("red", "离线");
  dimPanel(lastSnapshot);
}

const offline = Date.now() - lastSeenAt > OFFLINE_THRESHOLD_SECONDS * 1000;
if (offline) renderOffline();
```

push 模式的后端用「上报到达时间」代替轮询失败做同样的判断。
请把「在线/离线」文案放进 `messages.yml`，让标签集中在一处维护。

## 开发与自检

插件自带离线开发服务与自检套件，不需要 Minecraft 服务端、也不需要网络：

```bash
# 1. 构建
mvn -o -B clean package

# 2. 运行内置自检（退出码 0 = 全部通过）
java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer --selftest

# 3. 为前端联调启动假桥接服务（默认 127.0.0.1:28069）
java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer
#    可选：DevServer <port> <token> [configFile]
```

`DevServer` 用假数据驱动真实的 `HttpRouter`，因此可以在插件真正装到服务器之前
就完成前端开发与联调。

## 常见问题

**Q：外部访问不到 HTTP 端口，是插件坏了吗？**
不是。NAT、Docker 与面板服下这是常态。把 `outbound.mode` 设为 `push`、
`outbound.url` 指向你的后端，插件就会主动上报，并在响应里取回网页消息，
完全不需要入站端口。

**Q：需要 PlaceholderAPI 吗？**
不需要。未安装 PAPI 时，`messages.yml` 里的 `%占位符%` 会原样保留。

**Q：为什么消息流里没有 WARN 与 ERROR 控制台行？**
它们是控制台专有输出，游戏内玩家永远看不到，同步到网页会造成信息不对称。
默认采集 INFO，可通过 `messages.console-levels` 调整；WARN 按设计永不采集。

**Q：为什么 `latencyMs` 是 `-1`？**
时延必须有真实测量来源：要么配套客户端模组向 `POST /api/report` 上报，
要么由嵌入方提供链路 RTT（此时 `latencySource` 为 `backend_probe`）。
在此之前该值为 `-1`，`latencySource` 为 `none`。

**Q：浏览器能直接调用插件接口吗？**
技术上可以（`http.cors-allow-origin`），但那样会把共享令牌暴露出去。
请一律通过你自己的后端代理。

**Q：jar 有多大？需要额外库吗？**
一个很小的 jar，运行时无第三方依赖 —— JSON 与 YAML 处理都是自带的。

**Q：改了 `http.port` 好像没生效？**
`/web2serverbridge reload` 会在 `http.host`、`http.port` 或 `http.enabled` 变化时
重启 HTTP 服务；若端口被占用，插件会打印告警并继续运行（push 模式不受影响）。

**Q：端口只绑在 `127.0.0.1`，还需要令牌吗？**
需要，请保持 `http.token` 已设置。否则本机进程与配置错误的反向代理都能
以任意玩家身份发言。

## 许可证

基于 [MIT License](LICENSE) 发布。

```
Copyright (c) 2026 ahhhub
```
