# mc-prototest-bridge

**A server-authoritative truth channel for Minecraft bot development.**

Two halves of the same idea:

| Half | What it is | What it answers |
| --- | --- | --- |
| [`prototest-bridge/`](prototest-bridge) | A Paper 26.3 server plugin | "What does the *server* think?" — vanilla block state ids, collision boxes, player/entity state, container contents, and a recorded event log of everything that actually reached the server, all over loopback HTTP/JSON. |
| [`mc-bridge-dsh/`](mc-bridge-dsh) | A [DSH](https://github.com/ImWuMie) bundle | Exposes that HTTP surface to an AI agent as three tools (`mc_server`, `mc_watch`, `mc_bridge`) so it can *observe* the server instead of guessing. |

Writing a Minecraft protocol client (or debugging one) is mostly a guessing game: your code believes it
broke a block, opened a container or attacked an entity, and nothing on the client side can tell you
whether the packet was well-formed, whether the server accepted it, or whether it was silently dropped.
This project gives the other side of the conversation a voice.

Built to test [`mc-proto-bot`](https://github.com/ImWuMie/mc-proto-bot), a zero-dependency Python
Minecraft client, but nothing here is specific to that client: any bot that speaks the normal protocol to
`127.0.0.1:25565` can be observed.

```
┌──────────────────────┐   Minecraft protocol (25565)   ┌───────────────────────────────────┐
│  your bot / client   │ ─────────────────────────────► │  Paper 26.3 server                │
│  (Python, Java, …)   │ ◄───────────────────────────── │  + prototest-bridge plugin        │
└──────────────────────┘                                └───────────────┬───────────────────┘
          ▲                                                             │ HTTP/JSON
          │ assertion: what the bot believes                            │ 127.0.0.1:25963
          │ vs what the server reports                                  ▼
   ┌──────┴──────────────────────────────────────────────────────────────────────┐
   │  GET /block  →  {"stateId":1,"collision":[[0,0,0,1,1,1]],"data":"minecraft:stone", …}
   │  GET /events →  [{"seq":42,"type":"break","block":"minecraft:oak_log", …}]
   │  POST /setblock, POST /command, GET /registry/blocks (all 35723 vanilla states)
   └─────────────────────────────────────────────────────────────────────────────┘
                                        ▲
                                        │ HTTP
                              ┌─────────┴─────────┐
                              │  DSH: mc-bridge   │ → mc_server / mc_watch / mc_bridge
                              └───────────────────┘
```

---

## Contents

- [Why](#why)
- [Quick start](#quick-start)
- [What the bridge can answer](#what-the-bridge-can-answer)
- [The two plugins](#the-two-plugins)
- [Block state ids and section decoding](#block-state-ids-and-section-decoding)
- [A verification session, end to end](#a-verification-session-end-to-end)
- [Repository layout](#repository-layout)
- [Building](#building)
- [Compatibility and limitations](#compatibility-and-limitations)
- [Troubleshooting](#troubleshooting)
- [Findings](#findings)
- [License](#license)

---

## Why

A protocol client has two faces, and only one of them is testable from the inside:

* **What it sends.** Unit-testable — assemble bytes, compare with a hexdump.
* **What the server decides.** *Not* testable from the client. Did the server accept
  `ServerboundPlayerAction`? Did it apply the block change? Did it open the container, or did it reject
  the click because the window id was stale? Did that chat message arrive, and with which signature?

The usual answer is "read the server console and eyeball it". That does not scale to turning a
hand-written test into an assertion, because the console prints *human text*, not *state*.

This bridge answers in machine-readable state, from inside the server process, on the authority side:

* **Vanilla registry truth.** The plugin does not ship a copy of the block state table — it reads
  `Block.BLOCK_STATE_REGISTRY` (the same `IdMapper` the server uses to serialise chunks), so
  `stateId` is guaranteed to be the number that appears on the wire. `GET /registry/blocks` dumps all
  **35723** states of 26.3 as `id<TAB>minecraft:name[properties]`.
* **Collision boxes as the server computes them.** `GET /block` returns the real `VoxelShape`
  bounding boxes, so a client's physics model can be checked against the geometry the server actually
  uses (`oak_stairs[facing=north]` → two boxes, `short_grass` → none).
* **An event log of what arrived.** 30 recorded event types — `break`, `place`, `interact`,
  `interact_entity`, `attack`, `swing`, `consume`, `drop`, `pickup`, `click`, `chat`, `command`,
  `death`, `respawn`, … — each with a monotonic `seq` cursor, so "did my packet land?" is one HTTP GET.
* **A world you control.** `POST /setblock`, `POST /fill`, `POST /command`, `GET /tick/wait`, so a test
  can build a fixture, wait exactly N ticks, and then read back the server's answer.

## Quick start

### 1. A Paper 26.3 test server

Any Paper 26.3 server works. A throwaway one that a bot can join without authentication:

```properties
# server.properties
online-mode=false
enforce-secure-profile=false
allow-flight=true
spawn-protection=0
difficulty=peaceful          # a hostile mob killing the bot mid-test costs an hour of confusion
spawn-monsters=false
level-type=minecraft:flat
```

`peaceful` matters: with mobs enabled, a slime can kill the bot between two test steps, and the server
ignores most packets from a dead player — the classic source of flaky "sometimes it works" results.

### 2. The plugin

```powershell
cd prototest-bridge
# Windows, no network needed beyond the server you already have:
pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' -Deploy
```

`-Deploy` copies the jar into `<server>/plugins/`. On start-up the log shows:

```
[prototest-bridge] prototest bridge listening on http://127.0.0.1:25963
```

```bash
curl http://127.0.0.1:25963/status
```

### 3. (Optional) the agent tools

```bash
cd mc-bridge-dsh
node verify.mjs      # offline self-check against a live bridge, no DSH required
```

Install it into a DSH profile with `plugin_manager install_bundle <path-to-mc-bridge-dsh>`, then restart
DSH; the `mc_server`, `mc_watch` and `mc_bridge` tools appear.

### 4. Point a bot at it

Connect any client to `127.0.0.1:25565` (offline mode: any username, no auth). Then ask the server what
it saw:

```bash
curl "http://127.0.0.1:25963/players"
curl "http://127.0.0.1:25963/events?since=0&limit=50"
```

## What the bridge can answer

33 endpoints, all JSON except the three registry dumps (tab-separated text, easier to diff and stream).
`GET /` on the bridge lists them all. Full reference with parameters and response shapes:
[`docs/endpoints.md`](docs/endpoints.md).

| Need | Endpoint |
| --- | --- |
| Is the server up, what version, which worlds | `GET /status` `GET /worlds` `GET /world?name=` |
| Who is online, where exactly | `GET /players` `GET /player?name=` `GET /inventory?name=` |
| One block with vanilla id + collision shape | `GET /block?world=&x=&y=&z=` |
| Many blocks at once / a whole box | `POST /blocks` `GET /region?…&dx=&dy=&dz=` |
| A 16³ section as raw state ids (chunk format) | `GET /section?world=&x=&y=&z=` |
| Entities near a point | `GET /entities?world=&x=&y=&z=&radius=` |
| Change the world (fixtures) | `POST /setblock` `POST /fill` `POST /spawn` |
| Chunk bookkeeping (why did my `setblock` no-op?) | `GET /chunk` `GET /world/forceload` |
| Run a console command **and get its output** | `POST /command {"command":"…"}` |
| What actually happened on the server | `GET /events?since=&type=&limit=` |
| Per-tick movement trace of one player | `POST /trace/start?player=&limit=` `GET /trace/data` |
| Vanilla registry truth | `GET /registry/blocks` `GET /registry/items` `GET /registry/entities` |
| Server log lines (JUL + `logs/latest.log`) | `GET /log` `GET /log/file?tail=` |
| Wait for N ticks | `POST /tick/wait?ticks=` |

Every game-state read is executed on the server's main thread (`callSyncMethod`, 15 s timeout), so the
answers are consistent snapshots rather than torn reads from a background HTTP thread.

### The event log

`/events` returns `{ok, since, next, count, entries}`. Pass `next` back as `since` to poll incrementally.

```json
{"seq":42,"t":1760000000000,"tick":4801,"type":"break","player":"ProtoBot",
 "block":"minecraft:oak_log","type_before":"minecraft:oak_log","dataBefore":"minecraft:oak_log[axis=y]"}
```

Recorded types: `join quit swing interact interact_entity attack damage break place consume drop pickup
chat command death respawn container_open container_close click click_after held_change swap_hands
toggle_flight toggle_sneak toggle_sprint game_mode exp food effect book_edit sign_change trace_start
trace_stop`. `click_after` is a follow-up snapshot one tick after `click`, which is what makes container
transfer assertions stable.

## The two plugins

### `prototest-bridge/` — Paper 26.3 server plugin

Java 25, Paper API 26.3, **no third-party dependencies** (HTTP comes from the JDK's
`com.sun.net.httpserver`, JSON is a 300-line hand-written encoder/parser). One jar, binds loopback only.

| Class | Role |
| --- | --- |
| `PrototestBridge` | Plugin lifecycle, config, `/prototest` command (status, url, log, events, trace, state, selfcheck) |
| `BridgeServer` | HTTP routing, query/body parsing, thread hop to the main thread, auth token |
| `BridgeState` | Authoritative projections: status, player, inventory, block (+`stateId`, `collision`), region, section, registries |
| `EventRecorder` | 30 event types, 6000-entry ring, `seq` cursor, per-tick movement traces |
| `LogCapture` | Server log ring (re-attaches each second, because Paper resets the JUL manager) |
| `Json`, `Plain`, `BridgeCommandSender` | Zero-dependency JSON, component→text, command-output capture |

Config (`plugins/prototest-bridge/config.yml`):

```yaml
host: "127.0.0.1"   # loopback by default; the bridge has full world-write power
port: 25963
token: ""           # when set, every request needs ?token= or X-Bridge-Token
debug: false        # one log line per request
```

### `mc-bridge-dsh/` — DSH bundle

`@local/mc-bridge`, three tools, no dependencies (only the global `fetch`):

| Tool | Purpose |
| --- | --- |
| `mc_server` | One snapshot: version, tick, TPS, online players with position/health/game mode, worlds |
| `mc_watch` | The event log with a `seq` cursor, optionally after advancing N ticks |
| `mc_bridge` | Raw passthrough to any endpoint — the escape hatch the other two are built on |

Why a raw passthrough next to two conveniences: the interesting checks are usually one specific endpoint
(`/block` for a state id, `/section` for chunk decoding, `/trace/data` for movement), and inventing a tool
per endpoint would just hide the API. `mc_bridge` keeps the whole surface visible; `mc_server`/`mc_watch`
cover the two calls made on almost every check.

Config lives in the bundle row of `cordis.patch.yml` (`baseUrl`, `token`, `timeoutMs`).

## Block state ids and section decoding

`GET /registry/blocks` returns all **35723** states of Minecraft 26.3:

```
0	minecraft:air
1	minecraft:stone
2	minecraft:granite
3	minecraft:polished_granite
4	minecraft:diorite
5	minecraft:polished_diorite
6	minecraft:andesite
7	minecraft:polished_andesite
8	minecraft:grass_block[snowy=true]
9	minecraft:grass_block[snowy=false]
10	minecraft:dirt
…
#total	35723
```

Landed properties are normalised the way the wire normalises them (all properties, explicit values,
sorted by name), so the text is directly comparable with a client's own table — see
[`examples/compare_blocks.py`](examples/compare_blocks.py), which diffs this dump against a client's data
file and reports id/name/property mismatches.

`GET /section?world=&x=&y=&z=` (`x,y,z` = block coordinates of the section's minimum corner) returns the
4096 ids of one 16³ section, indexed `(y*16 + z)*16 + x` — the order a chunk section's palette-packed
long array decodes to *after* un-packing. Handy for validating chunk decoding without a real client.

## A verification session, end to end

`examples/` holds the actual scripts used to validate a client, minus the client:

| Script | What it asserts |
| --- | --- |
| [`probe_bot.py`](examples/probe_bot.py) | Connects a real bot, then checks join, spawn coordinates, `send_look` → server yaw/pitch, hotbar selection, command, chat, state-id decoding, collision boxes, and prints a capability matrix |
| [`probe_shapes.py`](examples/probe_shapes.py) | Places 39 representative states on the server, reads their authoritative collision boxes, compares with the client's model |
| [`compare_blocks.py`](examples/compare_blocks.py) | Whole-table diff: `GET /registry/blocks` vs a client's block data file |

Shape of a check (from `probe_shapes.py`), and the reason the bridge exists:

```python
POST /setblock  {"data": "minecraft:oak_stairs[facing=north]"}
GET  /block     → collision = [[0,0,0,1,0.5,1], [0,0.5,0,1,1,0.5]]   # server truth
client model   → [[0,0,0,1,1,1]]                                    # wrong: full cube
```

A plain `curl` version of the same idea:

```bash
curl -s -X POST 'http://127.0.0.1:25963/setblock?world=world&x=5&y=-60&z=5' \
     -H 'content-type: application/json' -d '{"data":"minecraft:oak_stairs[facing=north]"}'
curl -s 'http://127.0.0.1:25963/block?world=world&x=5&y=-60&z=5'
curl -s -X POST 'http://127.0.0.1:25963/tick/wait?ticks=20'
curl -s 'http://127.0.0.1:25963/events?since=0&type=place'
```

Two rules learned the hard way (both cost real debugging time):

1. **Do not call the bridge from a blocking context inside an async client's event loop.** A blocking
   `urllib` call starves the socket writer, and the bot stops flushing packets — which looks exactly like
   "the server ignores my packets". Use `asyncio.to_thread`.
2. **Do not use console `setblock`/`fill` for fixtures.** With no players online, Paper keeps no chunks
   loaded, so the command fails with `argument.pos.unloaded` while the plugin's `/setblock` loads the
   chunk first and always works. (`setKeepSpawnInMemory` and `spawnChunkRadius` no longer do anything in
   26.3; `/world/forceload` is the working alternative.)

## Repository layout

```
mc-prototest-bridge/
├── prototest-bridge/           Paper 26.3 plugin (Java 25, no dependencies)
│   ├── src/main/java/com/prototest/bridge/
│   ├── src/main/resources/{plugin.yml,config.yml}
│   ├── build.ps1               ECJ build + jar + optional deploy (the supported path)
│   ├── fetch-ecj.ps1           downloads the Eclipse compiler used by build.ps1
│   ├── restart-server.ps1      kill → wait → start a local test server
│   ├── build.gradle.kts        Gradle path (needs -PserverDir=…, see Building)
│   └── README.md
├── mc-bridge-dsh/              DSH bundle: mc_server / mc_watch / mc_bridge
│   ├── index.js                the three tools, zero imports
│   ├── cordis.patch.yml        bundle patch (baseUrl/token/timeoutMs)
│   ├── verify.mjs              offline harness, 11 checks, no DSH needed
│   ├── locale/, icon.svg
│   └── README.md
├── examples/                   the validation scripts
└── docs/
    ├── endpoints.md            full HTTP API reference
    └── findings-26.3.md        what this harness found in one client's 26.3 support
```

## Building

### `build.ps1` (supported, verified)

Compiles against the **server's own jars** (`versions/**/*.jar` + `libraries/**/*.jar`), because the
plugin reads vanilla internals (`Block.BLOCK_STATE_REGISTRY`, `BuiltInRegistries`, `CraftBlockData`) that
do not exist in the `paper-api` artifact.

```powershell
pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' [-Deploy]
```

It uses **ECJ** (Eclipse compiler) rather than `javac` on purpose:

* `javac` 25.0.3 crashes on this source set —
  `NullPointerException: Cannot read field "kind" because "tree.sym" is null` in
  `Flow$AssignAnalyzer.visitIdent`, a compiler bug, not a source error.
* ECJ 3.42 (fetched by `fetch-ecj.ps1` if absent) compiles the same sources, and only has to *read*
  Java 25 bytecode (it emits Java 24 class files, which the Java 25 runtime loads fine).

### Gradle (needs the server jar)

```powershell
./gradlew build -PserverDir='C:\path\to\paper-server'
```

The `serverDir` property adds that server's jars to the compile classpath. Without it the build fails on
the `net.minecraft.*` imports — those classes live in the server, not in `paper-api`. This path is left in
the repository for CI-style builds; `build.ps1` is what was actually verified.

## Compatibility and limitations

* **Minecraft 26.3 only** as published: the plugin declares `api-version: "26.3"` and touches Mojang
  internals (`net.minecraft.world.level.block.Block`, `BuiltInRegistries`). Porting to another version is
  usually a matter of changing the `paper-api` version and re-checking the three NMS call sites
  (`BridgeState.blockStates`, `items`, `sectionIds`); everything else is Bukkit API.
* **Java 25** runtime (Paper 26.3 requirement).
* **Loopback only by default.** The bridge can write blocks and run commands as console — do not expose
  it. If you must bind wider, set `token`.
* **Paper, not Folia.** The bridge hops to the global region scheduler; `plugin.yml` does not claim Folia
  support.
* Registry dumps are cached after the first call (`?fresh=1` rebuilds); the block table is immutable at
  runtime, so this is only useful while developing the plugin.
* Log capture is best-effort (Paper resets the JUL manager during start-up); `/log/file` reads
  `logs/latest.log` directly and is the reliable fallback.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `POST /setblock` has no effect, console `setblock` says `argument.pos.unloaded` | No chunk loaded (no players online). Use the plugin's `/setblock` or `/fill`, or `GET /world/forceload?cx=&cz=&radius=1`. |
| Bridge answers `{"ok":false,…}` with HTTP 500 | The main-thread call threw; the message is the exception text. Check `/log/file?tail=50`. |
| `Cannot read properties of undefined (reading 'filter')` from a harness wrapping the scripts | Some wrapper parses script JSON output; verify server state directly (`/status`), the script itself succeeded. |
| `PKIX path building failed` in Java | The JDK key store does not trust your TLS interception proxy. Start Java with `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` (Windows) or import the proxy CA. |
| Bot "sometimes" acts (hotbar, chat, commands) | The bot died (mobs), or the bridge is being called with a blocking HTTP client inside the event loop. See the two rules above. |
| `PKIX path building failed` while Gradle downloads its wrapper | Same TLS issue; use `build.ps1`. |

## Findings

[`docs/findings-26.3.md`](docs/findings-26.3.md) is the report this harness produced for one client
(`mc-proto-bot`) on its first 26.3 run: the whole-table state palette matched the server exactly, the
shipped collision data did not (a placeholder table: every state stored as a full cube), and seven of the
14 interaction capabilities the client needs were absent. That is what the bridge is for: a claim about
"26.3 support" becomes a diff, a JSON field, or an event line.

## License

MIT — see [LICENSE](LICENSE).
