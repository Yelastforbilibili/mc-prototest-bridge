# examples

The scripts this project was built with: each one compares what a Minecraft client *believes* with what the
server *reports*, so a claim like "26.3 support" becomes a pass/fail.

They were written against [`mc-proto-bot`](https://github.com/ImWuMie/mc-proto-bot) — a Python protocol
client — and the parts that touch the client are clearly marked at the top of each file. Point them at any
client by replacing the "client side" section; the "server side" (HTTP against `prototest-bridge`) does not
change.

| Script | Asserts | Needs |
| --- | --- | --- |
| `compare_blocks.py` | Whole-table diff: `GET /registry/blocks` vs a client's block state data file (`{version, states: {id: {name, shapes, properties}}}`). Reports ids/names/properties only present on one side, plus id gaps. | bridge only |
| `probe_shapes.py` | Places 39 representative states via `POST /setblock`, reads the authoritative collision boxes from `GET /block`, compares with the client's `block_collision_boxes`. | bridge + client |
| `probe_bot.py` | Connects a real bot, then checks join, spawn coordinates, `send_look` → server yaw/pitch, hotbar selection, command, chat, block state-id decoding, collision boxes; prints a capability matrix. | bridge + client + a running server |
| `run_all.ps1` | Runs the three above and prints a summary. | as above |

## Typical output

```
server states: 35723   bot states: 35723
only in server: 0   only in bot: 0
id present on both sides but different name: 0
RESULT: identical
```

```
oak_stairs[facing=north]   server=[[0,0,0,1,0.5,1],[0,0.5,0,1,1,0.5]]   client=[[0,0,0,1,1,1]]
oak_slab[type=bottom]      server=[[0,0,0,1,0.5,1]]                       client=[[0,0,0,1,1,1]]
short_grass                server=[]                                      client=[[0,0,0,1,1,1]]
```

## Running them

```powershell
# bridge only
python compare_blocks.py --blocks artifacts/registry-blocks-26.3.txt

# everything (cursor path to the client's interpreter)
pwsh -File run_all.ps1 -Python 'C:\path\to\client\.venv\Scripts\python.exe' -ClientRepo 'C:\path\to\client'
```

Two rules these scripts follow, both learned the hard way:

1. **Never call the bridge with a blocking HTTP client from inside an async client's event loop.** A blocking
   `urllib` call starves the socket writer and the client silently stops flushing packets — which looks
   exactly like "the server ignores my packets". Everything here goes through `asyncio.to_thread`.
2. **Create fixtures with the bridge, not with console commands.** With no players online the server keeps no
   chunks loaded, so `setblock` fails with `argument.pos.unloaded`; `POST /setblock` loads the chunk first.

Also: run the test server with `difficulty=peaceful`. A hostile mob killing the client mid-run produces the
most confusing "sometimes it works" failures, because a dead player's packets are largely ignored.
