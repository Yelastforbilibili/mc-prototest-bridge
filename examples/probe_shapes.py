"""Sample server collision truth for a set of block states and compare with the library.

The server is the oracle: ``/setblock`` writes a state, ``/block`` reports the shape the
server actually collides with.  The library reports its own prediction through
``World.block_collision_boxes``.  A disagreement means the shipped block data (or the
shape loader) is wrong for that state.

The comparison only starts once the client has decoded the very block state the server
placed, so a stale chunk can never be mistaken for a data bug.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare server collision truth with a client's block shapes.")
    parser.add_argument("--client-repo", type=Path,
                        default=Path(os.environ.get("MC_CLIENT_REPO", Path(__file__).resolve().parent.parent / "repo")))
    parser.add_argument("--bridge", default=os.environ.get("MC_BRIDGE_URL", "http://127.0.0.1:25963"))
    parser.add_argument("--host", default=os.environ.get("MC_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MC_PORT", "25565")))
    parser.add_argument("--username", default=os.environ.get("MC_USERNAME", "ProtoShapes"))
    parser.add_argument("--version", default=os.environ.get("MC_PROTOCOL_VERSION", "26.3"))
    return parser.parse_args()


ARGS = _parse_args()
REPO = ARGS.client_repo.resolve()
sys.path.insert(0, str(REPO))

from protobot.client import Bot  # noqa: E402

BRIDGE = ARGS.bridge.rstrip("/")

BLOCKS = [
    "minecraft:grass_block",
    "minecraft:oak_stairs[facing=north]",
    "minecraft:oak_slab[type=bottom]",
    "minecraft:oak_slab[type=top]",
    "minecraft:oak_fence",
    "minecraft:oak_fence_gate[open=false,facing=north]",
    "minecraft:stone_button[face=wall,facing=north]",
    "minecraft:oak_pressure_plate",
    "minecraft:oak_door[facing=north,half=lower,open=false]",
    "minecraft:oak_trapdoor[facing=north,half=bottom,open=false]",
    "minecraft:glass_pane",
    "minecraft:chest[facing=north]",
    "minecraft:torch",
    "minecraft:ladder[facing=north]",
    "minecraft:white_carpet",
    "minecraft:snow[layers=2]",
    "minecraft:anvil[facing=north]",
    "minecraft:cauldron",
    "minecraft:lever[face=floor,facing=north]",
    "minecraft:chain[axis=y]",
    "minecraft:iron_bars",
    "minecraft:cobweb",
    "minecraft:soul_sand",
    "minecraft:farmland[moisture=0]",
    "minecraft:cactus[age=0]",
    "minecraft:scaffolding[bottom=true]",
    "minecraft:hopper[facing=down]",
    "minecraft:enchanting_table",
    "minecraft:oak_sign[rotation=0]",
    "minecraft:white_bed[facing=north,part=head]",
    "minecraft:cobblestone_wall",
    "minecraft:rail[shape=north_south]",
    "minecraft:lantern[hanging=false]",
    "minecraft:brewing_stand",
    "minecraft:stonecutter[facing=north]",
    "minecraft:lectern[facing=north]",
    "minecraft:bell[attachment=floor,facing=north]",
    "minecraft:water[level=0]",
    "minecraft:short_grass",
]


def call(path: str, method: str = "GET", body: dict | None = None, **query: object) -> dict:
    url = BRIDGE + path
    if query:
        url += "?" + urllib.parse.urlencode({k: str(v) for k, v in query.items()})
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())


async def acall(path: str, method: str = "GET", body: dict | None = None, **query: object) -> dict:
    return await asyncio.to_thread(call, path, method, body, **query)


def floats(box: object) -> list[float]:
    if isinstance(box, str):
        return [float(part) for part in box.split()]
    return [float(part) for part in box]  # type: ignore[arg-type]


def normalise(boxes: list[list[float]], origin: tuple[int, int, int]) -> list[list[float]]:
    ox, oy, oz = origin
    return sorted(
        [round(b[0] - ox, 3), round(b[1] - oy, 3), round(b[2] - oz, 3),
         round(b[3] - ox, 3), round(b[4] - oy, 3), round(b[5] - oz, 3)]
        for b in boxes
    )


async def main() -> int:
    bot = Bot(ARGS.host, port=ARGS.port, username=ARGS.username, version=ARGS.version, resolve_srv=False)
    await bot.start()
    await bot.wait_ready(timeout=20.0)
    await bot.wait_world(timeout=20.0)
    await acall("/tick/wait", ticks=10)

    bx, by, bz = (int(round(v)) for v in bot.player.position)
    x, y, z = bx + 3, by, bz          # air block next to the bot, chunk already decoded
    await acall("/world/forceload", name="world", cx=x >> 4, cz=z >> 4, radius=1, force=True)

    agree = 0
    misses: list[tuple[str, list[list[float]], list[list[float]]]] = []
    for spec in BLOCKS:
        placed = await acall("/setblock", method="POST", body={"data": spec}, world="world", x=x, y=y, z=z)
        if placed.get("ok") is not True:
            misses.append((spec, [["ERR"]] , [[str(placed.get("error"))]]))  # type: ignore[list-item]
            continue
        state = placed.get("block", {}).get("stateId")
        deadline = time.time() + 3.0
        while time.time() < deadline and bot.world.get_state_id(x, y, z) != state:
            await asyncio.sleep(0.05)
        decoded = bot.world.get_state_id(x, y, z)
        server = sorted([round(v, 3) for v in floats(box)] for box in placed.get("block", {}).get("collision") or [])
        client = normalise(
            [[b.min_x, b.min_y, b.min_z, b.max_x, b.max_y, b.max_z] for b in bot.world.block_collision_boxes(x, y, z)],
            (x, y, z),
        )
        if decoded == state and server == client:
            agree += 1
        else:
            misses.append((spec, server, client if decoded == state else [["state", decoded, state]]))  # type: ignore[list-item]

    await bot.close()
    print(f"placed at ({x},{y},{z}); agreed {agree}/{len(BLOCKS)} states")
    for spec, server, client in misses:
        print(f"  MISS {spec}\n        server={server}\n        client={client}")
    return 0 if agree == len(BLOCKS) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
