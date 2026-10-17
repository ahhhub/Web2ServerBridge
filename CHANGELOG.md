# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-14

First public release.

### Added

- **Live server status over HTTP** — players online/max + player list, MOTD, server version,
  uptime, TPS (1m/5m/15m), MSPT (current/peak), world name, game mode, difficulty,
  game tick, in-game time and weather, plus a rolling chat/console message stream.
- **Two transport modes** so the plugin works behind NAT and on panel hosts:
  - `pull` — built-in HTTP server (default `0.0.0.0:28069`, prefix `/api`).
  - `push` — the plugin POSTs to your backend and reads queued web messages from the
    response, so **no inbound port is required** (MCSManager, Pterodactyl, NAT, containers).
  - `both` — both at the same time.
- **HTTP API** (pull mode): `GET /api/ping`, `GET /api/health`, `GET /api/timing`,
  `GET /api/status`, `GET /api/messages`, `POST /api/send`, `POST /api/report`.
- **Push protocol**: state + message delta + delivery acks to `outbound.url`,
  queued web messages returned in the response body (`deliver[]`), optional ack
  endpoint (`outbound.ack-url`, auto-derived from `/report` → `/ack`).
- **Web-to-game chat** — a website can speak as a player; the message is broadcast
  in game with a configurable prefix and immediately appended to the message stream.
- **Security** — constant-time bearer token comparison
  (`Authorization: Bearer`, `X-Bridge-Token` or `?token=`), per-IP rate limits
  (requests/minute and messages/minute), body size cap, message length cap,
  optional block-word list, optional "sender must be online" check, CORS origin control.
- **Fully externalized texts** — every user-visible string lives in `messages.yml`
  (lifecycle logs, game broadcast, command replies, HTTP error messages, status field
  labels); supports `{built-in}` placeholders, PlaceholderAPI `%placeholders%`
  (left untouched when PAPI is not installed), YAML block scalars, and `/w2sb reload`.
- **Management command** `/web2serverbridge` (`/w2sb`, `/bridge`) with
  `status`, `config`, `token`, `push`, `reload`.
- **Console collection scope** — INFO only by default; WARN and ERROR are always excluded
  (console-only output that in-game players cannot see). Background commands are off by default.
- **Companion client latency reporting** — `POST /api/report` accepts real player ping
  samples; `status.latency.mode` = `auto` / `client` / `tick`.
- **Built-in self-test** — `java -cp target/classes ...DevServer --selftest` runs the
  offline logic suite without a Minecraft server.
- **Offline dev server** — `DevServer` serves fake status/messages for frontend work.

### Build

- Maven build against `paper-api 26.2.build.119-stable` with `provided` scope:
  external users can build with a plain `mvn -B clean package`, no local `systemPath`.
- Compiles on JDK 25 and targets Java 21 bytecode (`<maven.compiler.release>21</maven.compiler.release>`).
- Repositories declared explicitly (`papermc` + Maven Central).
- No third-party runtime dependency: JSON and YAML handling are built in, so the jar
  is a single small file and works fully offline (`mvn -o -B clean package`).

[1.0.0]: https://github.com/ahhhub/Web2ServerBridge/releases/tag/v1.0.0

## 1.1.0

- Package namespace republished under 	op.nekolove.
- Version aligned with the website plugin download center.