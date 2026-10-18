# prototest-bridge

A Paper **26.3** server plugin that exposes the server's own truth over loopback HTTP/JSON — for testing
Minecraft protocol clients and bots.

* **Vanilla block state ids.** `GET /registry/blocks` dumps all 35723 states as
  `id<TAB>minecraft:name[props]`, read from `Block.BLOCK_STATE_REGISTRY` so the ids are the ones that travel
  on the wire. `GET /section` returns one 16³ section as 4096 ids in chunk order.
* **Collision boxes as the server computes them.** `GET /block` returns the real `VoxelShape` boxes, so a
  client's physics model can be checked against geometry the server actually uses.
* **An event log of what arrived.** `GET /events` records 30 event types (break, place, interact, attack,
  swing, click, chat, command, death, respawn, …) with a monotonic `seq` cursor.
* **A world you control.** `POST /setblock`, `POST /fill`, `POST /command` (with its output),
  `POST /tick/wait`, movement traces, chunk force-loading.

Full HTTP reference: [`../docs/endpoints.md`](../docs/endpoints.md). Overview: [`../README.md`](../README.md).

## Build

The plugin reads vanilla internals (`Block.BLOCK_STATE_REGISTRY`, `BuiltInRegistries`, `CraftBlockData`) that
the `paper-api` artifact does not expose, so it compiles against **the server's own jars**. Point the build
at a server directory that has started at least once (`versions/` and `libraries/` exist after that).

```powershell
# supported path (verified): Eclipse compiler, no network except the one-time ECJ download
pwsh -File build.ps1 -ServerDir 'C:\path\to\paper-server' -Deploy

# Gradle alternative (needs the same server directory)
./gradlew build -PserverDir='C:\path\to\paper-server'
```

`build.ps1` writes `build/libs/prototest-bridge-<version>.jar`; `-Deploy` copies it to
`<server>/plugins/` and tells you to restart.

Why ECJ and not `javac`: `javac` 25.0.3 crashes on this source set with an internal
`NullPointerException … Flow$AssignAnalyzer.visitIdent(Flow.java:3189)` — a compiler bug, not a source
error. ECJ 3.42 (downloaded by `fetch-ecj.ps1`) compiles the same sources; it only has to *read* Java 25
class files, so its Java 24 output is fine on the Java 25 runtime.

## Install and configure

1. Drop `prototest-bridge-0.1.0.jar` into `plugins/`, restart.
2. The log shows `[prototest-bridge] prototest bridge listening on http://127.0.0.1:25963`.
3. Edit `plugins/prototest-bridge/config.yml` if needed:

```yaml
host: "127.0.0.1"   # loopback by default: the bridge can write blocks and run console commands
port: 25963
token: ""           # when set, every request needs ?token=… or X-Bridge-Token
debug: false
```

4. In game: `/prototest status | url | log [n] | events [n] | trace start|stop | state [player] | selfcheck`
   (`selfcheck` makes one HTTP call back into the bridge, to prove the whole path works).

## Layout

| Path | Contents |
| --- | --- |
| `src/main/java/com/prototest/bridge/PrototestBridge.java` | plugin lifecycle, config, `/prototest` command |
| `…/BridgeServer.java` | HTTP routing, query/body parsing, main-thread hop, auth |
| `…/BridgeState.java` | authoritative projections (status, player, inventory, block, region, section, registries) |
| `…/EventRecorder.java` | 30 event types, 6000-entry ring, `seq` cursor, movement traces |
| `…/LogCapture.java` | server log ring, re-attached every second (Paper resets the JUL manager) |
| `…/Json.java`, `…/Plain.java`, `…/BridgeCommandSender.java` | zero-dependency JSON, component→text, command output capture |
| `build.ps1`, `fetch-ecj.ps1`, `restart-server.ps1` | local build and rig helpers |
| `build.gradle.kts`, `gradlew*` | Gradle path (requires `-PserverDir`) |

## Notes

* Paper only (not Folia): the bridge hops to the global region scheduler.
* Every game-state read runs on the main thread (`callSyncMethod`, 15 s timeout), so replies are consistent
  snapshots.
* `setKeepSpawnInMemory` and the `spawnChunkRadius` gamerule no longer keep chunks alive in 26.3; use
  `/world/forceload`, or the plugin's own `/setblock` and `/fill`, which load the chunk first.
* Log capture is best-effort; `GET /log/file?tail=N` reads `logs/latest.log` and is the reliable fallback.

## License

MIT.
