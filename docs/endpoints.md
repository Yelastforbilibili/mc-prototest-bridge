# prototest-bridge HTTP API reference

Base URL: `http://127.0.0.1:25963` (see `plugins/prototest-bridge/config.yml`).
`GET /` returns this list machine-readably (`{"ok":true,"endpoints":[…]}`).

Conventions:

* Every endpoint answers JSON with `ok` on success. Failures are `{"ok":false,"error":"…"}` with HTTP 400
  (bad request) or 500 (a main-thread call threw; `error` is the exception message).
* Query parameters are optional unless noted. `world` defaults to the first loaded world (`world`).
* The three `/registry/*` table dumps answer `text/plain` (tab-separated) instead of JSON — they are meant
  to be diffed, streamed and grepped.
* If `token` is set in the config, every request must carry `?token=…`, `X-Bridge-Token: …` or
  `Authorization: Bearer …`.
* Writes (`/setblock`, `/fill`, `/command`, `/op`, `/spawn`, `/trace/*`) really change the server. They are
  the point of a test rig, but do not point them at a world you care about.
* Coordinates are block coordinates unless stated otherwise; the world is a **flat 26.3 world** in the
  examples (ground at `y=-61`, feet at `y=-60`).
* All game-state reads run on the server main thread with a 15 s timeout, so a reply is a consistent
  snapshot, and a hung main thread surfaces as an HTTP 500 instead of a silent stall.

---

## Status and players

### `GET /status`

Server identity, tick, TPS, players, worlds, uptime, palette size.

```json
{"ok":true,"plugin":"prototest-bridge","pluginVersion":"0.1.0",
 "serverVersion":"26.3-205-a90108f (MC: 26.3)","bukkitVersion":"26.3.build.205-stable",
 "minecraftVersion":"26.3","port":25565,"ip":"","tick":12915,
 "tps":[19.99999743547077,19.999987262131093,20.00007761219525],"avgTickTimeMs":0.23,
 "maxPlayers":20,"onlinePlayers":0,"players":[],"worlds":[…],"uptimeMs":612345,
 "blockStateCount":35723,"url":"http://127.0.0.1:25963","endpoints":[…]}
```

`tps` is the 1/5/15-minute triple; `blockStateCount` comes from the vanilla state registry;
`uptimeMs` is since the plugin enabled.

### `GET /players`

`{"ok":true,"count":1,"players":[{…playerBrief…}]}`. `playerBrief` carries name, uuid, world, feet and eye
position, yaw/pitch, health, food, gamemode, pose, ping, dimension.

### `GET /player?name=<name|uuid>`

Full per-player projection plus inventory. Also available as `GET /player/<name>`.

```json
{"ok":true,"player":{"name":"ProtoBot","uuid":"…","alive":true,"dead":false,"health":20.0,
  "food":20,"saturation":5.0,"exhaustion":0.0,"exp":0.0,"level":0,"gameMode":"SURVIVAL",
  "world":"world","x":8.5,"y":-60.0,"z":8.5,"eyeY":-58.38,"yaw":0.0,"pitch":0.0,
  "onGround":true,"pose":"STANDING","velocity":[0.0,0.0,0.0],"fallDistance":0.0,
  "fireTicks":0,"freezeTicks":0,"air":300,"maxAir":300,"walkSpeed":0.1,"flySpeed":0.05,
  "attackCooldown":1.0,"handRaised":false,"activeItem":null,"heldSlot":0,
  "mainHand":{…item…},"offHand":null,"armor":[…8 entries…],"effects":[],
  "locale":"en_us","clientBrand":"vanilla","viewDistance":10},
 "inventory":{…}}
```

### `GET /inventory?name=<name|uuid>`

`{"ok":true,"inventory":{…},"equipment":{…},"heldItem":{…}|null}` — `inventory.slots` maps slot index to
item, plus `selected`, `offHand`, `armor`, `enderChest`. `equipment` keys: `mainHand`, `offHand`, `helmet`,
`chestplate`, `leggings`, `boots`.

### `GET /bots`

The spectator registry, i.e. who announced themselves as an automated client and who is watching whom.

```json
{"ok":true,"bots":["SuiteBot"],"registered":["SuiteBot","WatchedBot"],
 "views":[{"viewer":"RainBow","bot":"SuiteBot","pattern":"chest"}]}
```

`bots` lists the registered bots that are online right now, `registered` keeps every name that registered
during this server session (a bot re-registers when it reconnects), and `views` is the live `/see` sessions.
The in-game side is `/botregister`, `/see [bot|stop|list]` and `/seepattern chest|inv` — see the README.

---

## World state

### `GET /block?world=<w>&x=<x>&y=<y>&z=<z>`

The single most useful endpoint. Loads the chunk if needed.

```json
{"ok":true,"block":{"world":"world","x":5,"y":-60,"z":5,"type":"minecraft:oak_stairs",
  "data":"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
  "stateId":5474,
  "collision":[[0.0,0.0,0.0,1.0,0.5,1.0],[0.0,0.5,0.0,1.0,1.0,0.5]],
  "boundingBox":[5.0,-60.0,5.0,6.0,-59.0,6.0],
  "light":15,"skyLight":15,"blockLight":0,"biome":"minecraft:plains","temperature":0.8,
  "humidity":0.4,"power":0,"solid":true,"passable":false,"air":false,"liquid":false,
  "occluding":false,"replaceable":false,"hardness":2.0,"blastResistance":6.0}}
```

* `stateId` — the id the server would put on the wire (from `Block.BLOCK_STATE_REGISTRY`); `-1` if the
  lookup failed.
* `collision` — world-space boxes `[minX,minY,minZ,maxX,maxY,maxZ]` exactly as the server's `VoxelShape`
  reports them. Empty list = walk-through (grass, rails, water). Subtract the block's integer coordinates
  for a block-local shape.
* `boundingBox` — the block's unit box, for convenience.

### `POST /blocks`

Body `{"blocks":[[x,y,z],…]}` (+ optional `?world=`). Answers `{"ok":true,"world":"world","blocks":[{…block…},…]}`.

### `GET /region?world=&x=&y=&z=&dx=&dy=&dz=`

Cuboid of **block data strings** (not full block objects), nested `[dx][dy][dz]`. `dx*dy*dz` is capped at
200000. All touched chunks are loaded first.

### `GET /section?world=&x=&y=&z=`

4096 vanilla state ids of a 16³ section, `x,y,z` = block coordinates of the section's minimum corner,
indexed `(y*16 + z)*16 + x`:

```json
{"ok":true,"world":"world","x":0,"y":-64,"z":0,"order":"(y*16+z)*16+x",
 "ids":[88,88,…]}
```

This is the post-unpack order of a chunk section — use it to validate palette/long-array decoding without
a real client.

### `GET /chunk?world=&x=<cx>&z=<cz>&load=1&unload=0`

Chunk bookkeeping. `x,z` are **chunk** coordinates. `load=0` probes without loading, `unload=1` unloads.

```json
{"ok":true,"world":"world","cx":0,"cz":0,"wasLoaded":false,"loaded":true,"action":"load"}
```

### `GET /entities?world=&x=&y=&z=&radius=`

`{"ok":true,"world":"world","entities":[{…}]}` — entities near a point: uuid, type, name, position,
velocity, health if living, passengers, ticksLived. Unloaded chunks are not counted.

### `GET /worlds` / `GET /world?name=<w>`

World projection: name, uuid, environment, seed, spawn, time/fullTime, difficulty, pvp, weather,
minHeight (-64), maxHeight (320), seaLevel (-63), autoSave, keepSpawnLoaded, entity/living/player counts,
loadedChunks, and `gamerules` (a name→value object).

### `GET /world/keepspawn?name=<w>&keep=0|1`

Attempts to keep the spawn area loaded (and to set `spawnChunkRadius` when the server has that gamerule).
**Note:** in 26.3 both are effectively no-ops — `keepSpawnLoaded` stays `false` and no chunks stay loaded.
Kept for completeness; use `/world/forceload` instead.

### `GET /world/forceload?name=<w>&cx=<cx>&cz=<cz>&radius=<r>&force=0|1`

Force-loads (or releases) a `(2r+1)²` square of chunks around `cx,cz`, `r` ≤ 16. Returns `touched`,
`forceLoadedChunks`, `loadedChunks`. This is what makes console commands like `setblock` work in an
otherwise empty world.

### `GET /spawn?world=&x=&y=&z=&yaw=`

Reads the spawn point; sets it when `x` is present. The spawn point is also where joining players are
placed, so it is the cheapest way to put a bot somewhere specific.

---

## Writing the world

### `POST /setblock?world=&x=&y=&z=`

Body: `{"data":"minecraft:oak_stairs[facing=north]"}` (a body key or the `data` query parameter).
Loads the chunk, applies the block, answers the resulting `block` object.

```json
{"ok":true,"world":"world","set":"minecraft:oak_stairs[facing=north,half=bottom,…]",
 "block":{…stateId:5474, collision:[[…],[…]]…}}
```

Invalid block data answers `{"ok":false,"error":"bad block data: …"}` with HTTP 400.

### `POST /fill?world=&x1=&y1=&z1=&x2=&y2=&z2=`

Body: `{"data":"minecraft:stone"}`. Fills the cuboid (either corner order), cap 200000 blocks, loads all
touched chunks first. Answers `{"ok":true,"world":"world","data":"minecraft:stone","blocks":N}`.

---

## Commands, logs, events

### `POST /command`

Body: `{"command":"setblock 5 -60 5 minecraft:stone","as":"console|playerName"}`.

```json
{"ok":true,"command":"setblock 5 -60 5 minecraft:stone","as":"CONSOLE","dispatched":true,
 "output":["commands.setblock.success(5, -60, 5)"],"log":[]}
```

* `output` is the command's **feedback as the sender receives it** (translation keys with arguments, not
  rendered English — the bridge does not pull the server's translation tables). That is usually more useful
  for assertions anyway.
* `log` holds the log lines the server emitted while the command ran.
* `as` names an online player to run the command as (`player.performCommand`), which is what entity-scoped
  commands (`tp`, `give`, `gamemode`) need.

### `GET /log?since=<seq>&limit=&pattern=<regex>`

The in-process log ring (`{seq,t,level,source,msg}`), newest last. `next` is the cursor to poll from.
Best-effort: Paper resets the JUL manager during start-up, so the ring can miss early lines.

### `GET /log/file?tail=<n>`

The last `n` lines of `<server>/logs/latest.log` as `{"ok":true,"lines":[…]}`. Reliable fallback.

### `GET /events?since=<seq>&type=<type>&limit=`

The event truth log. `entries` is oldest-first; pass `next` back as `since`.

```json
{"ok":true,"since":0,"next":42,"count":2,"entries":[
 {"seq":41,"t":1760000000000,"tick":4801,"type":"join","player":"ProtoBot","world":"world",
  "x":8.5,"y":-60.0,"z":8.5,"address":"127.0.0.1","port":52344,"locale":"en_us",
  "clientBrand":null,"viewDistance":10,"gameMode":"SURVIVAL"},
 {"seq":42,"t":1760000000001,"tick":4810,"type":"break","player":"ProtoBot",
  "block":"minecraft:oak_log","dataBefore":"minecraft:oak_log[axis=y]","exp":0.0}]}
```

Types and their extra fields:

| type | extra fields |
| --- | --- |
| `join` / `quit` | `world,x,y,z`, `join`: `address,port,locale,clientBrand,viewDistance,gameMode`; `quit`: `reason` (`ERRONEOUS_STATE` = the client simply closed the socket) |
| `swing` | `animation` (`ARM_SWING`, `OFF_ARM_SWING`, …) |
| `interact` | `action`, `hand`, `block`, `face`, `item`, `useItemInHand`, `useInteractedBlock` |
| `interact_entity` | `entity`, `entityType`, `hand`, `point` |
| `attack` | `entity`, `entityType`, `damage`, `cause`, `critical` |
| `damage` | `damage`, `cause`, `health` |
| `break` | `block`, `dataBefore`, `exp` |
| `place` | `block`, `data`, `against`, `item` |
| `consume` / `drop` / `pickup` | `item`, `count` |
| `chat` | `message`, `sender` |
| `command` | `command` |
| `death` / `respawn` | `death`: `message`,`cause`,`killer`; `respawn`: `world,x,y,z` |
| `container_open` / `container_close` | `type`, `title`, `size` |
| `click` | `view`, `rawSlot`, `slot`, `clickType`, `action`, `before`, `cursor`, `result` |
| `click_after` | the same fields, snapshotted one tick later (stable container assertions) |
| `held_change` / `swap_hands` | `slot` / `previous`, `current` |
| `toggle_flight/sneak/sprint` | `state` |
| `game_mode` | `from`, `to` |
| `exp` / `food` / `effect` | `amount`, `total` / `level`, `delta` / `effect`, `duration`, `amplifier` |
| `book_edit` / `sign_change` | `pages` / `lines` |
| `trace_start` / `trace_stop` | `player`, `limit` |

Ring size 6000 events; the oldest are dropped, so poll with `since` rather than scanning everything.

### `POST /events/clear`

Empties the ring (`{"ok":true,"cleared":N}`) and restarts `seq` bookkeeping for the next poll.

---

## Movement traces

### `POST /trace/start?player=<name>&limit=<n>`

Starts recording `PlayerMoveEvent` for one player (default cap 20000 samples).

### `GET /trace`

`{"ok":true,"trace":{"active":true,"player":"ProtoBot","limit":20000,"startedAt":…,"dropped":0,"samples":123}}`

### `GET /trace/data?limit=<n>`

`{"ok":true,"state":{…trace…},"samples":[{"tick":…,"from":[x,y,z],"to":[x,y,z],"onGround":true,"yaw":…,"pitch":…,"velocity":[x,y,z]},…]}`

Per-tick movement is how physics questions get answered: compare the client's predicted position with what
the server accepted, and look for the tick where they diverge.

### `POST /trace/stop`

Stops the trace, returns its final state.

---

## Registries (vanilla truth)

### `GET /registry/blocks[?fresh=1]`

`text/plain`, one line per global block state id, all 35723 of them, plus a trailing `#total` line:

```
0	minecraft:air
1	minecraft:stone
…
8	minecraft:grass_block[snowy=true]
9	minecraft:grass_block[snowy=false]
…
#total	35723
```

Properties are explicit and sorted, matching the normalised wire form, so the dump can be diffed against a
client's own table line by line. On a lookup failure a line starts with `#ERR` (and the whole dump falls back
to the Bukkit registry enumeration with a `#fallback` marker).

### `GET /registry/items[?fresh=1]`

`id<TAB>key<TAB>maxStack<TAB>blockKey` for every item, `#total` last.

### `GET /registry/entities`

`id<TAB>key` for every entity type, in registry order, `#total` last.

### `GET /registry/item?key=minecraft:stone`

```json
{"ok":true,"item":{"key":"minecraft:stone","maxStackSize":64,"maxDurability":0,
 "hasBlockType":true,"blockType":"minecraft:stone","edible":false,"fuel":false,
 "burnDuration":0,"translationKey":"block.minecraft.stone"}}
```

### `GET /registry/block?key=minecraft:stone`

```json
{"ok":true,"block":{"key":"minecraft:stone","solid":true,"occluding":true,"gravity":false,
 "interactable":false,"hardness":1.5,"blastResistance":6.0,"slipperiness":0.6,"air":false,
 "flammable":false,"translationKey":"block.minecraft.stone","hasItemType":true,
 "itemType":"minecraft:stone","dataClass":"…CraftBlockData","stateCount":1,"collisionShape":[…]}}
```

---

## Utilities

### `POST /op`

Body `{"name":"ProtoBot"}` (or `?name=`). Ops the player and whitelists them if online.

### `POST /tick/wait?ticks=<n>` (body `{"ticks":n}` also accepted)

Waits `n` server ticks and answers `{"ok":true,"waitedTicks":20,"tick":4801}`. The cheap way to let a
just-sent packet land before reading state.
