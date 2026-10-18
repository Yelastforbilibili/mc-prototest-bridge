"""Live end-to-end probe for the ProtoBot library.

Drives the real client against the local Paper 26.3 server and checks every result
against the server's authoritative truth, read through the ``prototest-bridge``
plugin (default http://127.0.0.1:25963).  Each check therefore compares what the
library *believes* with what the server *saw*, which is exactly what a library change
has to preserve.

Run from the repository root with the project venv:

    .venv\\Scripts\\python.exe ..\\artifacts\\probe_bot.py

Exit code 0 means every truth check passed; 1 means at least one regressed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Live client probe against a Paper server + prototest-bridge.")
    parser.add_argument("--client-repo", type=Path,
                        default=Path(os.environ.get("MC_CLIENT_REPO", Path(__file__).resolve().parent.parent / "repo")),
                        help="checkout to import the client library from")
    parser.add_argument("--bridge", default=os.environ.get("MC_BRIDGE_URL", "http://127.0.0.1:25963"))
    parser.add_argument("--host", default=os.environ.get("MC_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MC_PORT", "25565")))
    parser.add_argument("--username", default=os.environ.get("MC_USERNAME", "ProtoProbe"))
    parser.add_argument("--version", default=os.environ.get("MC_PROTOCOL_VERSION", "26.3"))
    return parser.parse_args()


ARGS = _parse_args()
REPO = ARGS.client_repo.resolve()
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))

from protobot.client import Bot  # noqa: E402

BRIDGE = ARGS.bridge.rstrip("/")
HOST = ARGS.host
PORT = ARGS.port
VERSION = ARGS.version
USERNAME = ARGS.username

failures: list[str] = []
notes: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'}  {label}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(label)
    return ok


def note(label: str, detail: str) -> None:
    print(f"INFO  {label}: {detail}")
    notes.append(f"{label}: {detail}")


def _bridge_blocking(path: str, method: str = "GET", body: dict | None = None, **query: object) -> dict:
    url = BRIDGE + path
    if query:
        url += "?" + urllib.parse.urlencode({k: str(v) for k, v in query.items()})
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())


async def bridge(path: str, method: str = "GET", body: dict | None = None, **query: object) -> dict:
    """Call the bridge without blocking the event loop (the bot needs it to flush)."""
    return await asyncio.to_thread(_bridge_blocking, path, method, body, **query)


async def wait_ticks(ticks: int) -> int:
    return (await bridge("/tick/wait", ticks=ticks)).get("tick", 0)


async def events_since(cursor: int, kind: str | None = None, limit: int = 200) -> tuple[list[dict], int]:
    response = await bridge("/events", since=cursor, limit=limit, **({"type": kind} if kind else {}))
    return response.get("entries", []), response.get("next", cursor)


def find_block(entries: list[dict], needles: list[str]) -> dict | None:
    for entry in entries:
        blob = json.dumps(entry)
        if all(needle in blob for needle in needles):
            return entry
    return None


CAPABILITIES: dict[str, tuple[str, ...]] = {
    "swing arm": ("swing", "swing_arm", "send_swing"),
    "attack entity": ("attack", "attack_entity", "hit_entity"),
    "interact entity": ("interact_entity", "interact", "right_click_entity"),
    "use item on block (place)": ("use_item_on", "place_block", "use_item_on_block"),
    "break block": ("break_block", "dig", "dig_block", "start_digging"),
    "stop digging": ("stop_digging", "abort_digging", "stop_break"),
    "use item": ("use_item",),
    "drop item": ("drop_item", "drop_inventory_item"),
    "select hotbar slot": ("select_hotbar_slot",),
    "click container": ("click_container",),
    "send command": ("send_command",),
    "send message": ("send_message",),
    "respawn": ("respawn",),
    "raycast / line of sight": ("raycast", "ray_trace", "line_of_sight", "target_block"),
}


async def main() -> int:
    status = await bridge("/status")
    check("bridge reports the Paper 26.3 server", "26.3" in status.get("serverVersion", ""), status.get("serverVersion", ""))
    check("bridge exposes the vanilla block state palette", status.get("blockStateCount") == 35723, str(status.get("blockStateCount")))

    bot = Bot(HOST, port=PORT, username=USERNAME, version=VERSION, resolve_srv=False)
    try:
        await bot.start()
        await bot.wait_ready(timeout=20.0)
        await bot.wait_world(timeout=20.0)
        await wait_ticks(2)

        # ---- join is visible to the server -------------------------------------
        players = await bridge("/players")
        names = [p.get("name") for p in players.get("players", [])]
        check("server sees the bot join", USERNAME in names, str(names))

        # ---- the bot must be alive, or every later check is meaningless --------
        alive = (await bridge("/player", name=USERNAME)).get("player", {})
        if bot.player.dead or (alive.get("health") is not None and alive["health"] < 20.0):
            note("guard", f"bot was damaged/dead (health={alive.get('health')} dead={bot.player.dead}); respawning")
            try:
                await bot.respawn()
            except Exception as exc:  # noqa: BLE001 - diagnostic probe
                note("guard", f"respawn failed: {exc!r}")
            await wait_ticks(10)
            alive = (await bridge("/player", name=USERNAME)).get("player", {})
        check(
            "bot is alive on the server",
            alive.get("health") == 20.0 and not alive.get("dead"),
            f"health={alive.get('health')} dead={alive.get('dead')}",
        )

        join_entries, cursor = await events_since(0, "join")
        joined = find_block(join_entries, [USERNAME])
        check(
            "join event carries the world position and game mode",
            joined is not None and joined.get("world") == "world" and isinstance(joined.get("x"), (int, float)),
            json.dumps(joined)[:200] if joined else "no join event",
        )

        # ---- position agreement (bot belief vs server truth) -------------------
        server_player = (await bridge("/player", name=USERNAME)).get("player", {})
        bx, by, bz = bot.player.position
        sx, sy, sz = server_player.get("x", 0.0), server_player.get("y", 0.0), server_player.get("z", 0.0)
        drift = max(abs(bx - sx), abs(by - sy), abs(bz - sz))
        check("bot and server agree on the spawn position", drift <= 2.0, f"bot=({bx:.2f},{by:.2f},{bz:.2f}) server=({sx:.2f},{sy:.2f},{sz:.2f})")
        note("spawn position", f"bot=({bx:.2f},{by:.2f},{bz:.2f}) server=({sx:.2f},{sy:.2f},{sz:.2f})")

        # ---- serverbound look rotates the server-side player -------------------
        await bot.send_look(90.0, 25.0)
        await wait_ticks(4)
        rotated = (await bridge("/player", name=USERNAME)).get("player", {})
        yaw, pitch = rotated.get("yaw", 0.0), rotated.get("pitch", 0.0)
        check(
            "send_look reaches the server (yaw/pitch)",
            abs(((yaw - 90.0 + 180) % 360) - 180) < 3.0 and abs(pitch - 25.0) < 3.0,
            f"server yaw={yaw:.1f} pitch={pitch:.1f}",
        )

        # ---- held slot mirrors on the server -----------------------------------
        await bot.select_hotbar_slot(3)
        await wait_ticks(3)
        held = (await bridge("/player", name=USERNAME)).get("player", {}).get("heldSlot")
        check("select_hotbar_slot reaches the server", held == 3, str(held))

        # ---- command round trip (server-side preprocess event) ------------------
        token = f"probe{int(time.time()) % 100000}"
        await bot.send_command(f"list {token}")
        await wait_ticks(6)
        command_entries, cursor = await events_since(0, "command")
        check("send_command reaches the server", find_block(command_entries, [token]) is not None, json.dumps(command_entries[-1:])[:200])

        # ---- chat round trip ----------------------------------------------------
        chat_token = f"hello-{token}"
        await bot.send_message(chat_token)
        await wait_ticks(6)
        chat_entries, cursor = await events_since(0, "chat")
        check("send_message reaches the server", find_block(chat_entries, [chat_token]) is not None, json.dumps(chat_entries[-1:])[:200])

        # ---- world truth: place a block server-side, read it through the client --
        px, py, pz = int(bx) + 2, int(by) - 1, int(bz)
        placed = await bridge(
            "/setblock",
            method="POST",
            body={"data": "minecraft:stone"},
            world="world",
            x=px,
            y=py,
            z=pz,
        )
        ok_place = placed.get("ok") is True
        check("server-side /setblock prime", ok_place, json.dumps(placed)[:160])
        await wait_ticks(6)
        bot_state = bot.world.get_state_id(px, py, pz)
        server_block = (await bridge("/block", world="world", x=px, y=py, z=pz)).get("block", {})
        server_state = server_block.get("stateId")
        check(
            "client decodes the block the server placed",
            bot_state == server_state == 1,
            f"bot state={bot_state} server state={server_state} data={server_block.get('data')}",
        )

        # ---- world truth: sample the decoded chunks against the server ----------
        samples = [(int(bx) + dx, int(by) + dy, int(bz) + dz) for dx in (0, 1, -1) for dy in (-1, 0) for dz in (0, 1)]
        mismatches = []
        for gx, gy, gz in samples:
            truth = (await bridge("/block", world="world", x=gx, y=gy, z=gz)).get("block", {}).get("stateId")
            mine = bot.world.get_state_id(gx, gy, gz)
            if truth is None or mine != truth:
                mismatches.append(f"({gx},{gy},{gz}) bot={mine} server={truth}")
        check("bot block state ids match the server at sampled coordinates", not mismatches, "; ".join(mismatches[:4]))

        # ---- collision truth ----------------------------------------------------
        boxes = (await bridge("/block", world="world", x=px, y=py, z=pz)).get("block", {}).get("collision") or []

        def as_floats(box: object) -> list[float]:
            """The bridge may report a box as a JSON array or as space separated text."""
            if isinstance(box, str):
                return [float(part) for part in box.split()]
            if isinstance(box, (list, tuple)):
                return [float(part) for part in box]
            return [float(part) for part in str(box).strip("[]").split(",")]

        client_boxes = bot.world.block_collision_boxes(px, py, pz)

        def local(box: list[float], ox: int, oy: int, oz: int) -> list[float]:
            """The client reports world-space boxes; the server reports block-local ones."""
            return [round(box[0] - ox, 4), round(box[1] - oy, 4), round(box[2] - oz, 4),
                    round(box[3] - ox, 4), round(box[4] - oy, 4), round(box[5] - oz, 4)]

        server_box = as_floats(boxes[0]) if boxes else []
        client_box = (
            local([client_boxes[0].min_x, client_boxes[0].min_y, client_boxes[0].min_z,
                   client_boxes[0].max_x, client_boxes[0].max_y, client_boxes[0].max_z], px, py, pz)
            if client_boxes else []
        )
        check(
            "collision shape of a stone block agrees",
            len(boxes) == len(client_boxes) == 1 and all(abs(a - b) < 1e-6 for a, b in zip(server_box, client_box)),
            f"server={server_box} client={client_box}",
        )

        # ---- collision truth for a non-cube shape (stairs) ----------------------
        sx_, sy_, sz_ = px + 1, py, pz
        stairs = await bridge("/setblock", method="POST", body={"data": "minecraft:oak_stairs[facing=north]"},
                              world="world", x=sx_, y=sy_, z=sz_)
        if stairs.get("ok"):
            await wait_ticks(6)
            stair_boxes = stairs.get("block", {}).get("collision") or []
            client_stair = bot.world.block_collision_boxes(sx_, sy_, sz_)
            mine = sorted(
                local([b.min_x, b.min_y, b.min_z, b.max_x, b.max_y, b.max_z], sx_, sy_, sz_)
                for b in client_stair
            )
            theirs = sorted([round(v, 4) for v in as_floats(box)] for box in stair_boxes)
            check(
                "collision shape of oak stairs agrees with the server",
                mine == theirs,
                f"server={theirs} client={mine}",
            )
        else:
            check("server-side stairs placement", False, json.dumps(stairs)[:160])

        # ---- capability matrix (the P0/P1 gaps the evaluation reported) --------
        # These are documented gaps, not regressions, so they are reported rather
        # than failed: a *new* gap here means a capability disappeared.
        gaps: list[str] = []
        for capability, names in CAPABILITIES.items():
            present = [name for name in names if hasattr(bot, name)]
            if present:
                note(f"capability {capability}", "present via " + ", ".join(present))
            else:
                gaps.append(capability)
                note(f"capability {capability}", "MISSING (" + ", ".join(names) + ")")
        note("missing capabilities", f"{len(gaps)}/{len(CAPABILITIES)}: " + ", ".join(gaps))

        return 1 if failures else 0
    finally:
        try:
            await bot.close()
        except Exception:
            pass


if __name__ == "__main__":
    started = time.time()
    code = asyncio.run(main())
    print(f"\n{'RESULT: all checks passed' if code == 0 else 'RESULT: ' + str(len(failures)) + ' failed check(s)'} in {time.time() - started:.1f}s")
    if failures:
        for item in failures:
            print(f"  - {item}")
    raise SystemExit(code)
