#!/usr/bin/env python3
"""Read/write MapAdKiller's hook-side preferences on a real device.

Why this exists
---------------
The 免 root 内置版 has no module app process: the patcher hands the host a writable
``RemotePreferences`` binder, and LSPatch persists it as a small SQLite database at::

    /data/user/0/<host>/databases/lspatch-xposed-remote.db

Each row stores the value as a **Java-serialized** blob, so ``sqlite3`` alone is not
enough to flip a switch. This tool decodes and encodes those blobs, which makes switch
A/B tests on a real device scriptable instead of ten taps in the floating panel.

The host process caches preferences in memory, so every write must be followed by a
force-stop; the caller is responsible for that (``--restart`` does it).

Usage
-----
    python tools/device_prefs.py dump
    python tools/device_prefs.py get home_chips map_poi
    python tools/device_prefs.py set home_chips=true tool_步行=true --restart
    python tools/device_prefs.py clear
"""
from __future__ import annotations

import argparse
import os
import struct
import subprocess
import sqlite3
import sys
import tempfile
from pathlib import Path

ADB = os.environ.get("MAK_ADB", r"D:\Tools\platform-tools\adb.exe")
SERIAL = os.environ.get("MAK_SERIAL", "975aaf17")
HOST = os.environ.get("MAK_HOST", "com.autonavi.minimap")
MODULE = "org.navkit.geoassist"
GROUP = "nk_amap_prefs"

REMOTE_DB = f"/data/user/0/{HOST}/databases/lspatch-xposed-remote.db"
SCRATCH = f"/sdcard/nk-prefs.db"

BOOL_HDR = (b"\xac\xed\x00\x05sr\x00\x11java.lang.Boolean\xcd\x20r\x80\xd5\x9c\xfa\xee"
            b"\x02\x00\x01Z\x00\x05valuexp")
INT_HDR = (b"\xac\xed\x00\x05sr\x00\x11java.lang.Integer\x12\xe2\xa0\xa4\xf7\x81\x878"
           b"\x02\x00\x01I\x00\x05valuexr\x00\x10java.lang.Number\x86\xac\x95\x1d\x0b"
           b"\x94\xe0\x8b\x02\x00\x00xp")
STR_HDR = b"\xac\xed\x00\x05t"


def adb(*args: str, shell: bool = False, su: bool = False) -> str:
    command = [ADB, "-s", SERIAL]
    if shell or su:
        command.append("shell")
    if su:
        command.append("su -c " + shell_quote(str(args[0])))
        args = args[1:]
    else:
        command.extend(args)
    done = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace")
    if done.returncode:
        raise RuntimeError(f"adb failed: {' '.join(command)}\n{done.stdout}{done.stderr}")
    return done.stdout


def shell_quote(text: str) -> str:
    return "'" + text.replace("'", "'\\''") + "'"


# ---------------------------------------------------------------- serialization

def decode(blob: bytes | None):
    """Decode the subset of Java serialization LSPatch's RemotePreferences writes."""
    if blob is None:
        return None
    if len(blob) < 4 or blob[:4] != b"\xac\xed\x00\x05":
        return {"raw": blob.hex()}
    if blob[4:5] == b"t":
        length = struct.unpack_from(">H", blob, 5)[0]
        return blob[7:7 + length].decode("utf-8", "replace")
    if blob.startswith(BOOL_HDR):
        return blob[len(BOOL_HDR):len(BOOL_HDR) + 1] == b"\x01"
    if blob.startswith(INT_HDR):
        return struct.unpack_from(">i", blob, len(INT_HDR))[0]
    if blob[4:5] == b"s":
        head = 5
        assert blob[head:head + 1] == b"r"
        size = struct.unpack_from(">H", blob, head + 1)[0]
        name = blob[head + 3:head + 3 + size].decode()
        return {"object": name, "bytes": blob.hex()}
    return {"raw": blob.hex()}


def encode(value):
    if isinstance(value, bool):
        return BOOL_HDR + (b"\x01" if value else b"\x00")
    if isinstance(value, int):
        return INT_HDR + struct.pack(">i", value)
    text = value.encode("utf-8")
    return STR_HDR + struct.pack(">H", len(text)) + text


def parse(text: str):
    low = text.strip().lower()
    if low in ("true", "on", "1", "yes"):
        return True
    if low in ("false", "off", "0", "no"):
        return False
    try:
        return int(text)
    except ValueError:
        return text


# ------------------------------------------------------------------- transport

def pull(destination: Path) -> None:
    adb(f"cp {REMOTE_DB} {SCRATCH}; chmod 666 {SCRATCH}", su=True)
    done = subprocess.run([ADB, "-s", SERIAL, "pull", SCRATCH, str(destination)],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if done.returncode:
        raise RuntimeError(f"adb pull failed:\n{done.stdout}{done.stderr}")


def push(source: Path) -> None:
    done = subprocess.run([ADB, "-s", SERIAL, "push", str(source), SCRATCH],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if done.returncode:
        raise RuntimeError(f"adb push failed:\n{done.stdout}{done.stderr}")
    # LSPatch owns the file as the host's uid: keep the ownership/mode intact.
    adb(f"am force-stop {HOST}", shell=True)
    adb(f"cp {SCRATCH} {REMOTE_DB}; "
        f"chown $(stat -c %u:%g /data/user/0/{HOST}) {REMOTE_DB}; "
        f"chmod 660 {REMOTE_DB}; rm -f {SCRATCH}",
        su=True)


def open_db(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(str(path))
    connection.execute("PRAGMA journal_mode=DELETE")
    return connection


def rows(connection: sqlite3.Connection):
    return connection.execute(
        'SELECT module_pkg_name, user_id, "group", key, data FROM configs'
    ).fetchall()


def write_rows(connection, updates: dict) -> int:
    changed = 0
    for key, value in updates.items():
        blob = encode(value)
        existing = connection.execute(
            'SELECT data FROM configs WHERE module_pkg_name=? AND user_id=0 AND "group"=? AND key=?',
            (MODULE, GROUP, key)).fetchone()
        if existing is None:
            connection.execute(
                'INSERT INTO configs(module_pkg_name,user_id,"group",key,data) VALUES(?,0,?,?,?)',
                (MODULE, GROUP, key, blob))
        else:
            connection.execute(
                'UPDATE configs SET data=? WHERE module_pkg_name=? AND user_id=0 AND "group"=? AND key=?',
                (blob, MODULE, GROUP, key))
        changed += 1
    return changed


def restart() -> None:
    adb(f"am force-stop {HOST}", shell=True)
    adb(f"am start -n {HOST}/com.autonavi.map.activity.SplashActivity", shell=True)
    print("restarted " + HOST)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("dump", help="print every stored key")

    getter = sub.add_parser("get", help="print selected keys")
    getter.add_argument("keys", nargs="+")

    setter = sub.add_parser("set", help="assign key=value pairs")
    setter.add_argument("pairs", nargs="+")
    setter.add_argument("--restart", action="store_true",
                        help="force-stop and relaunch the host afterwards")

    unset = sub.add_parser("clear", help="delete selected keys (or all)")
    unset.add_argument("keys", nargs="*")
    unset.add_argument("--restart", action="store_true")

    args = parser.parse_args()

    folder = Path(tempfile.mkdtemp(prefix="mak-prefs-"))
    try:
        return dispatch(args, folder)
    finally:
        # Windows refuses to delete a file with a live sqlite handle, so the connection is
        # always closed before the scratch directory goes away.
        import gc
        import shutil
        gc.collect()
        shutil.rmtree(folder, ignore_errors=True)


def dispatch(args, folder: Path) -> int:
    local = folder / "remote.db"
    pull(local)
    connection = open_db(local)
    try:
        stored = {}
        for module, user, group, key, data in rows(connection):
            if module == MODULE and group == GROUP:
                stored[key] = decode(data)

        if args.command == "dump":
            for key in sorted(stored):
                print(f"{key:34s} = {stored[key]!r}")
            print(f"\n{len(stored)} keys")
            return 0

        if args.command == "get":
            for key in args.keys:
                print(f"{key:34s} = {stored.get(key, '<unset>')!r}")
            return 0

        if args.command == "set":
            updates = {}
            for pair in args.pairs:
                if "=" not in pair:
                    raise SystemExit(f"expected key=value, got {pair!r}")
                key, _, value = pair.partition("=")
                updates[key] = parse(value)
            write_rows(connection, updates)
            connection.commit()
            for key, value in updates.items():
                print(f"{key:34s} {stored.get(key, '<unset>')!r} -> {value!r}")
        else:
            targets = args.keys or list(stored)
            for key in targets:
                connection.execute(
                    'DELETE FROM configs WHERE module_pkg_name=? AND user_id=0 AND "group"=? AND key=?',
                    (MODULE, GROUP, key))
            connection.commit()
            print(f"cleared {len(targets)} keys")

        connection.commit()
    finally:
        connection.close()

    push(local)
    if getattr(args, "restart", False):
        restart()
    return 0


if __name__ == "__main__":
    sys.exit(main())
