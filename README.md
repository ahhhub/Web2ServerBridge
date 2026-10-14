# Web2ServerBridge

**English** | [简体中文](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square&labelColor=1f2328)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&labelColor=1f2328&logo=openjdk&logoColor=white)](#requirements)
[![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-2196F3?style=flat-square&labelColor=1f2328)](#requirements)
[![Release 1.0.0](https://img.shields.io/badge/Release-1.0.0-2ea043?style=flat-square&labelColor=1f2328)](CHANGELOG.md)

| | |
| :-- | :-- |
| **Platform** | Paper / Purpur **1.21+** (`api-version: 1.21`) |
| **Runtime** | **Java 21+** on the server · built with **JDK 25** (Paper 26.2 is class file 69) |
| **Transport** | `pull` (built-in HTTP server) · `push` (outbound, NAT/panel friendly) |
| **Dependencies** | none at runtime — JSON/YAML handling is built in, jar is self-contained |
| **Texts** | 100% externalized to `messages.yml` (built-in `{placeholders}` + PlaceholderAPI) |
| **License** | MIT |

A Minecraft server plugin that exposes **live server status and chat as JSON over HTTP**,
and lets a website **talk in game as a player** — with no inbound port required.

> Works behind NAT, Docker and panel hosts (MCSManager / Pterodactyl / aaPanel):
> the plugin can push the state to your backend and pull queued web messages back
> in the very same HTTP response.

---

## Table of contents

- [Why](#why)
- [Features](#features)
- [Requirements](#requirements)
- [Building](#building)
- [Installation](#installation)
- [Configuration reference](#configuration-reference)
  - [`config.yml`](#configyml)
  - [`messages.yml`](#messagesyml)
- [Commands and permissions](#commands-and-permissions)
- [HTTP API (pull mode)](#http-api-pull-mode)
  - [Conventions](#conventions)
  - [Endpoints](#endpoints)
  - [Error codes](#error-codes)
  - [Online / offline semantics](#online--offline-semantics)
- [Push protocol (outbound)](#push-protocol-outbound)
- [Front-end integration example](#front-end-integration-example)
- [Development and self-test](#development-and-self-test)
- [FAQ](#faq)
- [License](#license)

---

## Why

Most "website ↔ Minecraft" bridges assume the game server has a public IP and an open
port. Panel hosts and home connections do not. Web2ServerBridge therefore supports two
independent directions:

- **pull** — the plugin listens on a local port and your backend polls it.
- **push** — the plugin calls *your* backend every second; queued web messages come
  back in the HTTP response, so nothing needs to reach the game server.

Both can be enabled together (`outbound.mode: both`), and neither requires a companion
plugin, a database, or a client mod. Latency samples from a companion client mod are
supported but optional.

## Features

- **Live status**: players online/max + name list, MOTD, server version, uptime,
  TPS (1m/5m/15m), MSPT (current/peak), world name, game mode, difficulty,
  game tick, in-game time label and weather.
- **Message stream**: in-game chat, join/leave/death notices and (optionally) console
  output, merged into one ordered ring buffer with a monotonic sequence number so a
  frontend can poll incrementally with `?since=<seq>`.
- **Web-to-game chat**: `POST /api/send` broadcasts a web message in game using a
  configurable format, then appends it to the stream so the sender sees it immediately.
- **Two-tone transport** as described above, including delivery acknowledgements and
  a bounded pending queue.
- **Hardened by default**: constant-time token comparison, per-IP rate limits, body and
  message size caps, optional block words, optional online check, CORS allow-list.
- **Every visible string is configurable** in `messages.yml` — including status labels,
  command output and HTTP error messages — with `{built-in}` placeholders and
  PlaceholderAPI `%placeholders%`.
- **Offline friendly build**: `mvn -o -B clean package` works from a warm local
  repository; no third-party runtime dependency is bundled.

## Requirements

| | |
| :-- | :-- |
| Server | Paper, Purpur or any Paper fork **1.21+** (uses `com.sun.net.httpserver`, no extra plugin) |
| Server Java | **Java 21 or newer** |
| Build Java | **JDK 25** — `paper-api 26.2` is compiled to class file version 69; `<release>21</release>` still emits Java 21 bytecode |
| Build tool | Maven 3.9+ |
| Optional | [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for `%placeholder%` resolution in `messages.yml` |

## Building

```bash
git clone https://github.com/ahhhub/Web2ServerBridge.git
cd Web2ServerBridge
mvn -B clean package
```

The artifact is `target/Web2ServerBridge-1.0.0.jar`.

Build notes:

- All dependencies (`paper-api`, Adventure, BungeeCord chat, JetBrains annotations) use
  `<scope>provided</scope>` and are declared against the official `papermc` repository —
  nothing is shaded into the jar, and there is no machine-specific `systemPath`.
- On a machine whose local repository is already warm, a fully offline build works:

  ```bash
  mvn -o -B clean package
  ```

- Windows PowerShell, JDK 25 at a custom location:

  ```powershell
  $env:JAVA_HOME = "C:\path\to\jdk-25"
  mvn -B clean package
  ```

- Nothing is bundled at runtime: the JSON writer/parser (`util/Json.java`) and the
  minimal YAML reader (`util/SimpleYaml.java`) are part of the plugin.

## Installation

1. Copy `target/Web2ServerBridge-1.0.0.jar` into your server's `plugins/` directory.
2. Start (or restart) the server. `plugins/Web2ServerBridge/config.yml` and
   `plugins/Web2ServerBridge/messages.yml` are created on first start.
3. Set a real shared token. Either edit `http.token` in `config.yml`, or run
   `/web2serverbridge token` in the console — it generates a random 48-character token,
   writes it to `config.yml` and reloads the plugin.
4. Point your backend at the plugin:
   - **pull**: `http://<game-host>:28069/api/…` with `Authorization: Bearer <token>`.
   - **push**: set `outbound.url` to your backend endpoint and keep `http.token`
     (or `outbound.token`) identical on both sides.
5. Reload after config changes with `/web2serverbridge reload` (a host/port change
   restarts the built-in HTTP server; changing `messages.yml` also needs a reload).

The plugin warns at startup while the token is still the default placeholder
`CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN`.

## Configuration reference

### `config.yml`

#### `http` — built-in HTTP server (pull mode)

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `http.enabled` | boolean | `true` | Enable the built-in HTTP server. Set `false` to run push-only. |
| `http.host` | string | `0.0.0.0` | Bind address. Use `127.0.0.1` when a reverse proxy runs on the same host. |
| `http.port` | int | `28069` | Listen port. |
| `http.path-prefix` | string | `/api` | Prefix stripped before routing; normalized to start with `/` and to have no trailing `/`. |
| `http.token` | string | `CHANGE_ME_PLEASE_SET_A_RANDOM_TOKEN` | Shared secret. Required on every endpoint except `/ping`, `/health`, `/timing`. |
| `http.max-body-bytes` | int | `16384` | Maximum request body size (min 256). Larger bodies get `413 payload_too_large`. |
| `http.cors-allow-origin` | string | `*` | Value of `Access-Control-Allow-Origin`. Tighten it if browsers talk to the plugin directly; prefer a same-origin backend proxy. |
| `http.threads` | int | `4` | Request handling threads (min 1). |

#### `outbound` — proactive reporting (push mode)

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `outbound.mode` | `both` \| `push` \| `pull` | `both` | `pull` = listen only, `push` = report only (no HTTP server), `both` = both. |
| `outbound.url` | string | `""` | Backend endpoint the plugin POSTs to. **Empty disables push.** Example: `https://example.com/api/bridge/report`. |
| `outbound.ack-url` | string | `""` | Delivery acknowledgement endpoint. Empty ⇒ derived from `url` by replacing a trailing `/report` with `/ack`. |
| `outbound.token` | string | `""` | Token used for push/ack. Empty ⇒ falls back to `http.token`. |
| `outbound.interval-seconds` | int | `1` | Report period (min 1, effectively clamped to 1–10). The backend may override it per response. |
| `outbound.connect-timeout-ms` | int | `3000` | TCP connect timeout (min 500). |
| `outbound.read-timeout-ms` | int | `5000` | Request/response timeout (min 1000). |
| `outbound.queue-max` | int | `500` | Max ids waiting for acknowledgement; the oldest are dropped beyond this (min 10). |
| `outbound.enabled-on-start` | boolean | `true` | Start reporting as soon as the plugin enables. |

#### `messages` — collection

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `messages.buffer-size` | int | `200` | Ring buffer capacity (min 10). Older entries are dropped and reported as `truncated`. |
| `messages.include-console` | boolean | `true` | Capture console output. |
| `messages.console-levels` | list | `[INFO]` | Console levels to capture. `WARN` is never captured; `ERROR` is console-only output that in-game players cannot see and is not captured by default either. |
| `messages.strip-ansi` | boolean | `true` | Strip ANSI colour escapes from console lines (recommended on panel hosts). |
| `messages.include-system` | boolean | `true` | Record join / leave / death notices (visible in game, so useful on the web). |
| `messages.include-console-commands` | boolean | `false` | Mirror background commands into the stream (audit only — players cannot see them). |
| `messages.web-prefix` | string | `"[网页] "` | Prefix used in game for web-originated messages. |
| `messages.max-length` | int | `256` | Maximum characters per message (min 16); longer text is truncated. |
| `messages.block-words` | list | `[]` | Case-insensitive block list; a match rejects the send with `blocked`. Empty = no filtering. |
| `messages.require-online` | boolean | `false` | Require the sender to be online before accepting a web message. |

#### `status` — status sampling

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `status.refresh-seconds` | int | `2` | Snapshot refresh interval (min 1, sampled on the main thread). |
| `status.latency.mode` | `auto` \| `client` \| `tick` | `auto` | `auto` prefers client-reported samples and falls back to the backend-measured link RTT; `client` uses client samples only (`-1` when none); `tick` ignores client samples. |
| `status.latency.client-stale-seconds` | int | `30` | Client latency samples older than this are discarded (min 5). |

#### `security`

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `security.rate-limit.max-per-minute` | int | `60` | Per-IP request limit per minute (min 1) → `429 rate_limited`. |
| `security.rate-limit.send-per-minute` | int | `10` | Per-IP message limit per minute (min 1) → `429 rate_limited`. |
| `security.log-requests` | boolean | `false` | Log one line per HTTP request (method, path, status, duration, client IP). |

#### Root keys

| Key | Type | Default | Description |
| :-- | :-- | :-- | :-- |
| `server-name` | string | `"Minecraft Server"` | Display name used as `server.name`. The MOTD comes from the server itself. |
| `motd-keep-color-codes` | boolean | `true` | Keep `§` colour codes in `server.motd` so the frontend can render them; `false` strips them. |

### `messages.yml`

Every string a human can see lives in `messages.yml` — nothing is hard-coded in the
plugin, so you can translate or reword without recompiling.

**Groups**

| Group | Covers |
| :-- | :-- |
| `lifecycle.*` | Startup/shutdown/reload logs, HTTP start failures, push success/failure, token warning, messages.yml loading. |
| `game.*` | In-game format of web messages (`web-message-format`), the plugin log format, and fallback join/leave/death texts. |
| `console.*` | Background command log format and the console sender prefix. |
| `command.*` | All `/web2serverbridge` output: help, status, config, token rotation, manual push. |
| `http.*` | Every HTTP error `message` value returned to your frontend (`unauthorized`, `rate-limited`, `send-*`, `report-*`, …). |
| `status-fields.*` | Localized status labels: `server-online`, `server-offline`, `server-unknown`, time-of-day and weather labels. |

**Placeholders**

- Built-in `{name}` style placeholders are supplied per key. The complete set used by the
  shipped `messages.yml` is:
  `{body}` `{capacity}` `{command}` `{count}` `{current}` `{delivered}` `{error}`
  `{gamemode}` `{http}` `{id}` `{key}` `{latency}` `{max}` `{messages}` `{mode}` `{mspt}`
  `{name}` `{new}` `{old}` `{online}` `{outbound}` `{path}` `{pending}` `{player}` `{port}`
  `{prefix}` `{samples}` `{seconds}` `{seq}` `{size}` `{source}` `{status}` `{sub}` `{text}`
  `{tick}` `{time}` `{token}` `{tps}` `{uptime}` `{url}` `{value}` `{version}` `{weather}` `{world}`.
  Unknown placeholders are left untouched so mistakes are easy to spot.
- **PlaceholderAPI** `%placeholder%` (e.g. `%server_online%`, `%player_name%`) is resolved
  automatically when the server has PlaceholderAPI installed. **Without PAPI the text is
  returned verbatim** — no error, no data loss.
- Render order: built-in `{…}` → PlaceholderAPI `%…%` → `&` colour codes. `mc()` output
  converts `&a` to `§a` for in-game text; HTTP/JSON responses keep the raw `&` form.
- YAML **block scalars** are fully supported for multi-line values, e.g.

  ```yaml
  lifecycle:
    token-placeholder-warning: |-
      http.token is still the default placeholder!
      Run /web2serverbridge token and sync it to your backend.
  ```

- Changes take effect after `/web2serverbridge reload` (or a server restart).
- If a key is missing, the calling code falls back to a built-in English string; a
  malformed file makes the plugin fall back to built-in texts and log a warning instead
  of failing to start.

## Commands and permissions

| Command | Description |
| :-- | :-- |
| `/web2serverbridge status` | Show mode, HTTP state, push state, players/TPS/MSPT/latency, world info and the token state. |
| `/web2serverbridge config` | Print the key runtime configuration values. |
| `/web2serverbridge token` | Generate a random 48-character token, write it to `config.yml`, reload. |
| `/web2serverbridge push` | Report once immediately (connectivity test for the backend). |
| `/web2serverbridge reload` | Reload `config.yml` and `messages.yml`; restarts the HTTP server if host/port changed. |

- Aliases: `/w2sb`, `/bridge`
- Permission: `web2serverbridge.admin` (default: `op`)
- Tab completion covers all five subcommands.

## HTTP API (pull mode)

### Conventions

- Base URL: `http://<host>:<http.port><http.path-prefix>`, default
  `http://127.0.0.1:28069/api`.
- Body format: JSON, UTF-8. Responses are `application/json; charset=utf-8` with
  `Cache-Control: no-store`.
- Authentication — any one of:

  | Mechanism | Example |
  | :-- | :-- |
  | `Authorization` header | `Authorization: Bearer <YOUR_TOKEN>` |
  | `X-Bridge-Token` header | `X-Bridge-Token: <YOUR_TOKEN>` |
  | Query parameter | `?token=<YOUR_TOKEN>` |

  Comparison is constant-time. `/ping`, `/health` and `/timing` do **not** require a token.
- Client IP for rate limiting follows `X-Real-IP`, then the last hop of
  `X-Forwarded-For`, then the socket address.
- `OPTIONS` requests answer `204` with CORS headers when `http.cors-allow-origin` is set.
- `GET /status` and `GET /messages` accept `since` (sequence cursor, default `0`) and
  `limit` (default `100`, capped at `500`).

### Endpoints

#### `GET /ping`, `GET /health` — liveness (no token)

```json
{ "ok": true, "service": "web2serverbridge", "version": "1.0.0" }
```

#### `GET /timing` — link RTT probe (no token)

```json
{ "ok": true, "now": 1760400000000, "seq": 1234 }
```

Use two back-to-back calls and keep the minimum to estimate the backend ↔ game-server
round trip; `now` is the plugin clock in milliseconds.

#### `GET /status` — full snapshot (token required)

Query: `since` (long), `limit` (int, ≤ 500).

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
    "statusLabel": "Online",
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
    "timeLabel": "Noon", "weather": "Clear", "weatherRaw": "CLEAR",
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

Status codes: `200` ready, `503` when the plugin is alive but the first sample has not
been collected yet (`ok: true`, `ready: false`, `server.online: true`), `401` bad token,
`429` rate limited.

Field notes:

- `performance.latencyMs` is the "frontend ↔ MC server" figure; `-1` means unavailable.
  `latencySource`: `client_report` (companion client mod via `POST /api/report`),
  `backend_probe` (link RTT supplied by an embedder), `none`.
- `world.weather` is the localized label from `messages.yml`; `weatherRaw` is
  `CLEAR` / `RAIN` / `THUNDER`. `world.timeLabel` is localized likewise.
- `messages.truncated: true` means the requested `since` cursor was already evicted from
  the ring buffer — refetch from `oldestSeq`.

#### `GET /messages` — message stream only (token required)

```json
{ "ok": true, "serverTime": 1760400000000, "messages": { "seq": 4211, "oldestSeq": 4000,
  "truncated": false, "count": 12, "items": [ /* same item shape as above */ ] } }
```

Message item fields: `seq` (monotonic), `time` (epoch ms), `type` (`chat` | `system` |
`console`), `source` (`game` | `web` | `server`), `player` (may be null), `text`,
`web` (`true` when it originated from the website).

#### `POST /send` — speak in game as a player (token required)

Request:

```json
{ "username": "Steve", "text": "hello from the website", "ip": "203.0.113.7" }
```

`ip` is optional and only informational.

Success:

```json
{ "ok": true, "seq": 4212, "broadcast": true }
```

`broadcast: false` means the message was accepted but no player was online to receive it.
The message is always appended to the stream (with `type: chat`, `source: web`,
`web: true`) so a polling frontend sees it right away.

Failures: `400 bad_request` (missing/too long username, empty or invalid text),
`400 blocked` (block word), `409 not_online` (`messages.require-online: true` and the
sender is offline), `429 rate_limited`.

#### `POST /api/report` — companion client latency sample (token required)

```json
{ "username": "Steve", "pingMs": 37 }
```

`pingMs` is clamped to `0..60000`. Success returns `{ "ok": true }`; missing fields give
`400 bad_request`; a plugin without a latency sink gives `503 not_ready`.

### Error codes

| HTTP | `error` | Meaning |
| :-- | :-- | :-- |
| 400 | `bad_request` | Malformed JSON or invalid/missing fields. |
| 400 | `blocked` | The text hit `messages.block-words`. |
| 401 | `unauthorized` | Token missing or wrong. |
| 404 | `not_found` | Unknown path under the prefix. |
| 405 | `method_not_allowed` | Wrong HTTP method for the endpoint. |
| 409 | `not_online` | Sender is not online (`messages.require-online`). |
| 413 | `payload_too_large` | Body exceeded `http.max-body-bytes`. |
| 429 | `rate_limited` | Per-IP request or send limit exceeded. |
| 500 | `internal_error` | Unexpected plugin error (details in the server log). |
| 503 | `not_ready` | Snapshot or latency sink not ready yet. |

Error bodies: `{ "ok": false, "error": "<code>", "message": "<text from messages.yml>" }`.
The `error` field is machine-readable and stable; `message` is presentation text you can
localize in `messages.yml`.

### Online / offline semantics

- The plugin runs **inside** the Minecraft server process. If it answers `/ping`,
  `/health` or `/status` at all, the game server is **online** — `server.online` is
  `true` in that case, even in the `503` "not ready yet" response.
- The **consumer** decides when the server is offline: with push mode, if no report
  succeeds for longer than your own offline threshold (a common choice is 3 ×
  `outbound.interval-seconds`, typically 3–10 s), mark the server offline and keep
  rendering the **last received snapshot** so the page does not go blank.
- Localized labels for that state live in `messages.yml`:
  `status-fields.server-online`, `status-fields.server-offline`, `status-fields.server-unknown`.

## Push protocol (outbound)

Enabled when `outbound.mode` is `push` or `both` **and** `outbound.url` is non-empty.

Every `outbound.interval-seconds` (1–10 s, clamped) the plugin sends:

```http
POST <outbound.url>
Content-Type: application/json; charset=utf-8
Authorization: Bearer <outbound.token or http.token>
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

- `messages.items` contains **only the delta** since the previous successful report
  (cursor based), so nothing is delivered twice.
- `pending.delivered` lists web message ids that were already broadcast in game. Your
  backend should treat them as acknowledged (it may drop them from its queue).

Your backend replies `2xx` with:

```json
{
  "ok": true,
  "reportIntervalSeconds": 2,
  "deliver": [ { "id": 4199, "username": "Steve", "text": "hello from the web" } ]
}
```

- `deliver[]` are queued web messages. The plugin broadcasts each one in game, then
  confirms it.
- `reportIntervalSeconds` (optional, 1–10) overrides `outbound.interval-seconds`
  at runtime — useful for tuning real-time feel without touching every server.
- Confirmation is a separate call to `outbound.ack-url` (default: `url` with a trailing
  `/report` replaced by `/ack`):

  ```json
  { "ids": [4199] }
  ```

  If the response body cannot be parsed, the report itself still counts as successful.
- Failure handling: `401`/`403` and non-2xx statuses are logged (rate-limited to one
  message per 30 s) and the cursor is **not** advanced, so no message is lost; the pending
  ack queue is bounded by `outbound.queue-max` and drops the oldest ids beyond it.
- After a web message is accepted through `POST /api/send`, the plugin immediately
  triggers one extra report so your backend learns about it without waiting a full tick.

## Front-end integration example

**Never ship `http.token` to the browser.** Let the browser talk to your own backend,
and let that backend hold the token and talk to the plugin (or receive push reports).

### 1. Backend proxy (Node/Express, pull mode)

```js
import express from "express";
const app = express();
app.use(express.json());

const MC_API = "http://127.0.0.1:28069/api";        // the game server
const MC_TOKEN = process.env.MC_BRIDGE_TOKEN;      // keep it server-side
const auth = { Authorization: `Bearer ${MC_TOKEN}` };

// Browser-friendly state endpoint (poll it with ?since=<seq>)
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

// Browser-friendly send endpoint
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

### 2. Browser polling + sending

```js
let since = 0;
let failures = 0;
const OFFLINE_AFTER = 3;           // consecutive failures ⇒ show "offline"
const OFFLINE_THRESHOLD_SECONDS = 10;

async function tick() {
  try {
    const res = await fetch(`/api/state?since=${since}`);
    const data = await res.json();
    failures = 0;

    if (data.ready === false) return renderOnlinePending();

    since = data.messages.seq;                       // incremental cursor
    const fresh = data.messages.items.filter(m => m.seq > lastRenderedSeq);
    renderStatus(data);                              // players, TPS, MSPT, world…
    renderMessages(fresh);                           // chat / system / console colours
    lastRenderedSeq = since;
    lastSeenAt = Date.now();

    if (data.messages.truncated) {                   // cursor was evicted — resync
      since = data.messages.oldestSeq;
    }
  } catch (e) {
    if (++failures >= OFFLINE_AFTER) renderOffline(lastSnapshot, lastSeenAt);
  }
}

async function say(username, text) {
  const res = await fetch("/api/say", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, text }),
  });
  const data = await res.json();
  if (!data.ok) showToast(data.message);             // already localized by messages.yml
}

setInterval(tick, 1000);
tick();
```

### 3. Offline detection

```js
let lastSnapshot = null;   // last successful payload
let lastSeenAt = Date.now(); // updated on every successful poll (see tick())

function renderStatus(payload) {
  lastSnapshot = payload;
  lastSeenAt = Date.now();
  setStatusDot(payload.online ? "green" : "red", payload.online ? "Online" : "Offline");
  // …players, performance, world, messages
}

function renderOffline() {
  // Keep the last snapshot on screen, but dim it and switch the label.
  // Localized label comes from messages.yml ⇒ status-fields.server-offline.
  setStatusDot("red", "Offline");
  dimPanel(lastSnapshot);
}

const offline = Date.now() - lastSeenAt > OFFLINE_THRESHOLD_SECONDS * 1000;
if (offline) renderOffline();
```

Push-mode backends do the same thing with report arrival timestamps instead of polling
errors. Provide localized `server-online` / `server-offline` texts in `messages.yml` so
labels stay in one place.

## Development and self-test

The plugin ships with an offline dev server and a self-test suite that need no
Minecraft server and no network:

```bash
# 1. build
mvn -o -B clean package

# 2. run the built-in self-test (exit code 0 = all checks passed)
java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer --selftest

# 3. run a fake bridge for frontend work (defaults to 127.0.0.1:28069)
java -cp target/classes top.nekolove.web2serverbridge.dev.DevServer
#    optional: DevServer <port> <token> [configFile]
```

`DevServer` exercises the real `HttpRouter` with fake data, so a frontend can be built
and tested before the plugin is ever installed on a server.

## FAQ

**Q: The HTTP port is not reachable from outside — is the plugin broken?**
No. That is the normal situation behind NAT, Docker and panel hosts. Set
`outbound.mode: push` and `outbound.url` to your backend; the plugin then reports
outwards and receives web messages in the response. No inbound port is needed.

**Q: Do I need PlaceholderAPI?**
No. `%placeholders%` in `messages.yml` are simply left as-is when PAPI is absent.

**Q: Why are WARN and ERROR console lines missing from the stream?**
They are console-only output: players in game never see them, so mirroring them to the
website would show a different reality. INFO is captured by default and can be adjusted
via `messages.console-levels`; WARN is never captured by design.

**Q: Why is `latencyMs` `-1`?**
Latency needs a real measurement. Options: a companion client mod posting to
`POST /api/report`, or an embedder supplying the link RTT (then `latencySource` becomes
`backend_probe`). Until then the value is `-1` and `latencySource` is `none`.

**Q: Can the browser call the plugin API directly?**
Technically yes (`http.cors-allow-origin`), but then the shared token is exposed. Always
proxy through your own backend.

**Q: How large is the jar, and does it need extra libraries?**
One small jar with no runtime dependencies — JSON and YAML handling are built in.

**Q: Changing `http.port` has no effect until restart?**
`/web2serverbridge reload` restarts the HTTP server when `http.host`, `http.port` or
`http.enabled` changed; if the port is occupied, the plugin logs a warning and keeps
running (push mode still works).

**Q: Is a token required if the port is bound to `127.0.0.1`?**
Yes — keep `http.token` set. Local processes and any misconfigured reverse proxy would
otherwise be able to send messages as any player.

## License

Released under the [MIT License](LICENSE).

```
Copyright (c) 2026 ahhhub
```
