# mc-bridge (DSH bundle)

Agent-side half of the Minecraft truth channel. A [DSH](https://github.com/ImWuMie) bundle that registers
three tools reading the [`prototest-bridge`](../prototest-bridge) HTTP plugin on a local Paper 26.3 server,
so an agent can ask the server what is actually true instead of trusting a client-side model of the world.

| Tool | Purpose |
| --- | --- |
| `mc_server` | One snapshot: server version, tick, TPS, online players with position/health/game mode, worlds with spawn/time/gamerules. |
| `mc_watch` | The server's recorded event log (join, move, swing, interact, attack, break, place, use, chat, command, click, death, respawn) with a monotonic `seq` cursor, optionally after advancing N ticks. |
| `mc_bridge` | Raw request against any bridge endpoint — block/region state with vanilla state ids and collision boxes, registry dumps, inventory, console commands, traces, chunk loading. `GET /` lists every endpoint. |

## Why a raw passthrough next to two conveniences

The Python bot under test (`mc-proto-bot`) matters mostly for what it *sends*. The server is the oracle for
what *arrived*: a bot that believes it broke a block but never sent `ServerboundPlayerAction` is
indistinguishable from a working one until the server's block state and event log say otherwise.
`mc_bridge` therefore exposes the whole surface unchanged, while `mc_server` and `mc_watch` cover the two
calls made on almost every check.

## Configuration

The bundle row `mc-bridge` in `cordis.patch.yml` carries the config:

| Field | Default | Meaning |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:25963` | Bridge base URL. |
| `token` | `""` | Value for the `x-bridge-token` header when the Paper side sets one. |
| `timeoutMs` | `20000` | Default per-request timeout. |

```yaml
- insert:
    - id: mc-bridge
      name: '@local/mc-bridge'
      config:
        baseUrl: 'http://127.0.0.1:25963'
        timeoutMs: 20000
```

## Install

From a local checkout:

```bash
plugin_manager install_bundle <path-to-this-directory>   # DSH restarts to load new bundles
```

The tool names appear after the restart. To check the tool logic without a restart (and without DSH at all),
run the offline harness against a live bridge:

```bash
node verify.mjs
```

It fakes the tools registry, calls every tool for real and asserts 11 things — registration, `/status`,
`/block` with a vanilla state id and collision box, query encoding, `/tick/wait`, the `seq` cursor, the
rendered text block, and the two error paths (HTTP 404, missing `path`).

## Example prompts

* `mc_server` → "which world is the bot in, and at what coordinates?" is the reply's `players[]`.
* `mc_watch {"since": 0, "type": "break"}` → did the server accept the dig at all?
* `mc_bridge {"path": "/block", "query": {"world": "world", "x": 5, "y": -60, "z": 5}}` → the vanilla
  `stateId` and the authoritative collision boxes of that block.
* `mc_bridge {"path": "/registry/blocks"}` → the whole 35723-state palette as text, diffable against a
  client's data file.
* `mc_bridge {"path": "/setblock", "method": "POST", "query": {"world": "world", "x": 5, "y": -60, "z": 5},
  "body": {"data": "minecraft:oak_stairs[facing=north]"}}` → build a fixture, then read `/block` back.

## Requirements

* A Paper 26.3 server with the `prototest-bridge` plugin enabled (see `../prototest-bridge`), listening on
  the game port (`25565` by default) and on the bridge port (`25963`).
* Nothing else: the tools never speak the Minecraft protocol themselves, and the bundle has no dependencies
  (only the runtime's global `fetch`).

## License

MIT.
