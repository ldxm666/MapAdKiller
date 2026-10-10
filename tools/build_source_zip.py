#!/usr/bin/env python3
"""Package reviewable project source with an explicit allowlist, excluding device evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from build_nonroot import inspect_elf, sha256
from verify_module import verify_dependency_jars

ROOT = Path(__file__).resolve().parent.parent
ROOT_FILES = {".gitignore", "README.md", "LICENSE", "CHANGELOG.md"}
APP_FILES = {"app/AndroidManifest.xml", "app/build.ps1"}
SOURCE_TREES = {"app/src", "app/stub-src", "app/res", "app/META-INF", "app/native", "docs", "registry-pack"}
EXCLUDED_PARTS = {".git", ".codex", ".agents", ".aws", "__pycache__", ".idea", ".vscode", "evidence", "shots"}
EXCLUDED_SUFFIXES = {".apk", ".zip", ".keystore", ".jks", ".p12", ".log", ".db", ".sqlite", ".pyc", ".dex", ".o", ".obj"}
NATIVE_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def source_files(source: Path):
    dependencies = verify_dependency_jars(source)
    jars = {"app/libs/" + item["localJar"] for item in dependencies["artifacts"]}
    libs = jars | {"app/libs/README-aidl.md", "app/libs/dependencies.json"}
    libs |= {f"app/libs/{abi}/libmapclean_labels.so" for abi in NATIVE_ABIS}
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        if not path.resolve().is_relative_to(source):
            raise ValueError("Source contains a file link outside the project")
        relative = path.relative_to(source)
        name = relative.as_posix()
        if any(part in EXCLUDED_PARTS for part in relative.parts) or path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        selected = (name in ROOT_FILES or name in APP_FILES or name in libs or
                    any(name.startswith(tree + "/") for tree in SOURCE_TREES) or
                    (len(relative.parts) == 2 and relative.parts[0] in {"tests", "tools"}
                     and path.suffix.lower() in {".java", ".cpp", ".h", ".py", ".ps1", ".md"}))
        if not selected:
            continue
        # Native dependencies are restricted to this project's single own library.
        if path.suffix.lower() == ".so":
            if name not in libs:
                raise ValueError("Unexpected native library in source package")
            inspect_elf(path.read_bytes())
        # Reject accidental GitHub credentials without echoing their contents.
        if path.suffix.lower() in {".md", ".java", ".py", ".ps1", ".json", ".properties", ".txt", ".cpp", ".h", ".c", ".xml", ".prop"}:
            if re.search(rb"\b(?:github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})", path.read_bytes()):
                raise ValueError(f"Credential-shaped content detected in {name}; not packaged")
        yield path, name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--SourceDirectory", type=Path, default=ROOT)
    parser.add_argument("--OutputZip", type=Path, required=True)
    parser.add_argument("--DryRun", action="store_true")
    args = parser.parse_args()
    source = args.SourceDirectory.resolve(strict=True)
    files = list(source_files(source))
    names = {name for _, name in files}
    required = {"app/AndroidManifest.xml", "app/build.ps1", "app/native/style_proto.h", "app/native/label_assets.cpp",
                "app/native/native_hook_dispatch.h", "app/native/baidu_file_assets.h",
                "app/native/build.py", "app/META-INF/xposed/java_init.list", "app/META-INF/xposed/native_init.list",
                "app/libs/api-102.0.0.jar", "app/libs/service-classes.jar", "app/libs/interface-102.0.0.jar",
                "app/native/vendor/zstd/LICENSE", "app/native/vendor/zstd/COPYING"}
    required |= {f"app/libs/{abi}/libmapclean_labels.so" for abi in NATIVE_ABIS}
    if not required.issubset(names):
        raise ValueError(f"Required source/dependency files missing: {sorted(required - names)}")
    manifest = {"sourceFiles": len(files), "files": [{"path": name, "sha256": sha256(path)} for path, name in files],
                "excludes": "Device evidence, original map APKs, build outputs, signing keys, VCS metadata and credentials"}
    if args.DryRun:
        print(json.dumps({"sourceFiles": len(files), "sourceBytes": sum(p.stat().st_size for p, _ in files),
                          "paths": [name for _, name in files], "archiveCreated": False}, ensure_ascii=False, indent=2))
        return
    target = args.OutputZip.resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path, name in files:
                archive.write(path, "MapAdKiller-source/" + name)
            archive.writestr("SOURCE_MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        with zipfile.ZipFile(temporary) as archive:
            failed = archive.testzip()
            if failed is not None:
                raise ValueError(f"ZIP integrity failed: {failed}")
            for record in manifest["files"]:
                data = archive.read("MapAdKiller-source/" + record["path"])
                if hashlib.sha256(data).hexdigest() != record["sha256"]:
                    raise ValueError(f"Source content changed while packaging: {record['path']}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print(json.dumps({"zip": target.name, "sha256": sha256(target), "sourceFiles": len(files),
                      "bytes": target.stat().st_size}, ensure_ascii=False))


if __name__ == "__main__":
    main()
