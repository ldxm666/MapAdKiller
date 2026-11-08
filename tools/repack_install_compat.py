#!/usr/bin/env python3
"""Normalize compact LSPatch ZIPs using apksigner, preserving the host certificate.

This does not claim to reproduce every OEM installer. It removes overlapping ZIP
entry ranges and adds v1/v2/v3 signatures while checking every application payload.
Passwords are read by apksigner from a caller-selected environment variable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile


SIGNATURE = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.I)
ORIGIN = "assets/lspatch/origin.apk"


def run(command: list[str | Path]) -> str:
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        # Do not echo command arguments or arbitrary tool diagnostics that might
        # contain local signing details. The caller may inspect tools locally.
        raise ValueError(f"apksigner failed (exit {result.returncode})")
    return result.stdout


def certificates(text: str) -> list[str]:
    return sorted(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", text))


def digest(stream, length: int | None = None) -> str:
    result = hashlib.sha256()
    while length is None or length:
        data = stream.read(1 << 20 if length is None else min(length, 1 << 20))
        if not data:
            if length: raise ValueError("truncated entry")
            break
        result.update(data)
        if length is not None: length -= len(data)
    return result.hexdigest()


def offset(path: Path, entry: zipfile.ZipInfo) -> int:
    with path.open("rb") as stream:
        stream.seek(entry.header_offset)
        header = stream.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04": raise ValueError("invalid local ZIP header")
    name, extra = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + name + extra


def payloads(path: Path, *, standard: bool) -> dict[str, str]:
    result = {}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)): raise ValueError("duplicate ZIP names")
        if standard:
            bad = archive.testzip()
            if bad: raise ValueError(f"ZIP CRC failed: {bad}")
            intervals = sorted((offset(path, entry), offset(path, entry) + entry.compress_size)
                               for entry in archive.infolist() if entry.compress_size)
            if any(right > next_left for (_, right), (next_left, _) in zip(intervals, intervals[1:])):
                raise ValueError("overlapping ZIP payloads remain")
        for entry in archive.infolist():
            # The old Google Play source stamp is signer metadata, not app data.
            # apksigner removes it when the APK is signed by the LSPatch signer.
            if entry.is_dir() or SIGNATURE.fullmatch(entry.filename) or entry.filename == "stamp-cert-sha256": continue
            if entry.filename == "resources.arsc" and (entry.compress_type != zipfile.ZIP_STORED or offset(path, entry) % 4):
                raise ValueError("resources.arsc must be uncompressed and 4-byte aligned")
            if entry.filename == ORIGIN and not standard:
                if entry.compress_type != zipfile.ZIP_STORED: raise ValueError("unexpected compressed original")
                with path.open("rb") as stream:
                    stream.seek(offset(path, entry)); result[entry.filename] = digest(stream, entry.file_size)
            else:
                with archive.open(entry) as stream: result[entry.filename] = digest(stream)
    return result


def repack(source: Path, destination: Path, *, java: Path, apksigner: Path, keystore: Path,
           password_env: str = "MAPCLEAN_HOST_KEYSTORE_PASSWORD") -> dict:
    source, destination = source.resolve(), destination.resolve()
    if source == destination or destination.exists(): raise ValueError("output must be a new path distinct from input")
    if not os.environ.get(password_env): raise ValueError(f"missing signing password environment variable: {password_env}")
    base = [java, "-jar", apksigner]
    before_certs = certificates(run(base + ["verify", "--print-certs", source]))
    if not before_certs: raise ValueError("input has no verified signer")
    before = payloads(source, standard=False)
    if ORIGIN not in before: raise ValueError("expected an integrated LSPatch base APK")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mapclean-compat-") as tmp:
        output = Path(tmp) / "compatible.apk"
        run(base + ["sign", "--ks", keystore, "--ks-pass", f"env:{password_env}", "--key-pass", f"env:{password_env}",
                    "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                    "--v4-signing-enabled", "false", "--out", output, source])
        after_certs = certificates(run(base + ["verify", "--print-certs", output]))
        if after_certs != before_certs: raise ValueError("signing certificate changed; refusing incompatible update")
        after = payloads(output, standard=True)
        if after != before:
            missing = sorted(before.keys() - after.keys())
            added = sorted(after.keys() - before.keys())
            changed = sorted(k for k in before.keys() & after.keys() if before[k] != after[k])
            raise ValueError(f"application payload changed during ZIP normalization: missing={missing}, added={added}, changed={changed}")
        verified = []
        for sdk, scheme in ((23, "v1"), (24, "v2"), (28, "v3"), (33, "v3")):
            text = run(base + ["verify", "--verbose", "--min-sdk-version", str(sdk), "--max-sdk-version", str(sdk), output])
            if not re.search(r"Verified using " + scheme + r" scheme[^\n]*: true", text):
                raise ValueError(f"signature verification failed for {scheme}/API {sdk}")
            verified.append({"sdk": sdk, "scheme": scheme})
        # Exclusive creation prevents accidental overwrite even across concurrent calls.
        with output.open("rb") as src, destination.open("xb") as dst:
            import shutil
            shutil.copyfileobj(src, dst)
    with destination.open("rb") as stream: sha = digest(stream)
    return {"file": destination.name, "size": destination.stat().st_size, "sha256": sha,
            "signerSha256": before_certs, "payloadsPreserved": len(before),
            "standardZip": True, "signatureChecks": verified, "oemRuntimeTested": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--InputApk", type=Path, required=True)
    parser.add_argument("--OutputApk", type=Path, required=True)
    parser.add_argument("--Java", type=Path, required=True)
    parser.add_argument("--ApksignerJar", type=Path, required=True)
    parser.add_argument("--Keystore", type=Path, required=True)
    parser.add_argument("--PasswordEnv", default="MAPCLEAN_HOST_KEYSTORE_PASSWORD")
    args = parser.parse_args()
    result = repack(args.InputApk, args.OutputApk, java=args.Java, apksigner=args.ApksignerJar,
                    keystore=args.Keystore, password_env=args.PasswordEnv)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__": main()
