"""Compare the client's bundled block-state table against the running server's registry.

The server side comes from ``/registry/blocks`` on the prototest bridge (an ``id<TAB>name[props]``
dump built from the real vanilla registry in the running server). The client side is a
``blocks-<version>.json.gz`` document, ``{version, states: {id: {name, shapes, ...}}}``.

Both directions matter: a state the server knows and the client does not is a state the client cannot
name (it will guess), and a state the client knows with a different id is a desync that breaks every
chunk-section decode.

    python compare_blocks.py --client-repo C:\\path\\to\\client \\
        --registry artifacts/registry-blocks-26.3.txt
    python compare_blocks.py --client-repo . --registry-url http://127.0.0.1:25963

Exit code 0 means identical, 1 means differences were found.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

LINE = re.compile(r"^(?P<id>\d+)\t(?P<name>[a-z0-9_.\-]+:[a-z0-9_./\-]+)(?:\[(?P<props>.*)\])?$")
VERSION = os.environ.get("MC_VERSION", "26.3")


def load_server(path: Path | None, url: str | None) -> tuple[list[str], dict[str, list[int]]]:
    """Return (names by id, ids by name) parsed from a bridge registry dump."""
    if url:
        with urllib.request.urlopen(url.rstrip("/") + "/registry/blocks?fresh=1", timeout=120) as response:
            text = response.read().decode("utf-8")
    elif path and path.is_file():
        text = path.read_text(encoding="utf-8")
    else:
        raise SystemExit("pass --registry <file> or --registry-url <bridge base url>")

    names: list[str] = []
    by_name: dict[str, list[int]] = {}
    errors: list[str] = []
    for raw in text.splitlines():
        if raw.startswith("#"):
            if raw.startswith("#total"):
                print(f"server: {raw}")
            elif "ERR" in raw:
                errors.append(raw)
            continue
        if not raw.strip():
            continue
        m = LINE.match(raw)
        if not m:
            errors.append(f"unparsed: {raw[:80]}")
            continue
        sid = int(m.group("id"))
        if sid != len(names):
            errors.append(f"gap: expected id {len(names)}, saw {sid}")
        names.append(m.group("name"))
        by_name.setdefault(m.group("name"), []).append(sid)
    if errors:
        print(f"server: {len(errors)} parse problems, first 5:")
        for e in errors[:5]:
            print("  " + e)
    return names, by_name


def load_client(path: Path) -> dict[int, str]:
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        doc = json.load(fh)
    return {int(k): v["name"] for k, v in doc["states"].items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--registry", type=Path, default=Path("artifacts/registry-blocks-26.3.txt"),
                        help="bridge registry dump to read (default: artifacts/registry-blocks-%s.txt)" % VERSION)
    parser.add_argument("--registry-url", default=os.environ.get("MC_BRIDGE_URL"),
                        help="fetch the registry from a live bridge instead of reading a file")
    parser.add_argument("--bridge", default=None, help="alias for --registry-url")
    parser.add_argument("--client-repo", type=Path, default=Path("."),
                        help="client checkout containing protobot/data/")
    parser.add_argument("--client-data", type=Path, default=None,
                        help="explicit path to blocks-<version>.json.gz")
    args = parser.parse_args(argv)

    data = args.client_data or args.client_repo / "protobot" / "data" / f"blocks-{VERSION}.json.gz"
    if not data.is_file():
        raise SystemExit(f"client block data not found: {data} (pass --client-data)")

    server_names, server_by_name = load_server(args.registry, args.registry_url or args.bridge)
    client = load_client(data)
    print(f"server states: {len(server_names)}   client states: {len(client)}")

    client_ids = set(client)
    server_ids = set(range(len(server_names)))
    only_server = sorted(server_ids - client_ids)
    only_client = sorted(client_ids - server_ids)
    print(f"only in server: {len(only_server)}   only in client: {len(only_client)}")
    for sid in only_server[:5]:
        print(f"  server-only {sid} = {server_names[sid]}")
    for sid in only_client[:5]:
        print(f"  client-only {sid} = {client[sid]}")

    mismatch = [i for i in sorted(client_ids & server_ids) if client[i] != server_names[i]]
    print(f"id present on both sides but different name: {len(mismatch)}")
    for sid in mismatch[:20]:
        print(f"  id {sid}: server={server_names[sid]}  client={client[sid]}")

    # Name coverage the other way: a client block name the server does not know at all is a stale table.
    stale = sorted(set(client.values()) - set(server_by_name))
    print(f"client names the server never reports: {len(stale)}")
    for n in stale[:20]:
        print(f"  {n}")

    unmodeled = sorted(set(server_by_name) - set(client.values()))
    print(f"server names the client never reports: {len(unmodeled)}")
    for n in unmodeled[:20]:
        print(f"  {n}")

    ok = not only_server and not only_client and not mismatch and not stale and not unmodeled
    print("RESULT:", "identical" if ok else "DIFFERENCES FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
