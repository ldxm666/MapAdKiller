#!/usr/bin/env python3
"""Build and inspect rootless map APKs with the pinned official LSPatch release.

No ADB calls, uninstall, original-file edits, or publication happen here.
Only Python's standard library, Java 21+, and Android SDK build-tools are used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import urllib.request
import zipfile

TAG = "v1.2"
CLI_NAME = "lspatch-v1.2-487-release.jar"
CLI_SHA256 = "d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165"
MANAGER_NAME = "manager-v1.2-487-release.apk"
MANAGER_SHA256 = "e93e34c5170831aad51b2035e3cd94a1c3b5298711189fabc359b566bc34ad17"
RELEASE_URL = "https://github.com/JingMatrix/LSPatch/releases/tag/v1.2"
SOURCE_COMMIT = "0dc50f42503711b14f5e2bb217c2bdd6321ce5be"
LICENSE_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
MODULE_PACKAGE = "io.github.ldxm666.mapadkiller"
TARGETS = {"com.autonavi.minimap": "AMap", "com.baidu.BaiduMap": "BaiduMap"}
CONFIG_ENTRY = "assets/lspatch/config.json"
MODULE_ENTRY = f"assets/lspatch/modules/{MODULE_PACKAGE}.apk"
ORIGINAL_ENTRY = "assets/lspatch/origin.apk"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return stream_sha256(stream)


def stream_sha256(stream, length: int | None = None) -> str:
    digest = hashlib.sha256()
    remaining = length
    while remaining is None or remaining:
        block = stream.read(1024 * 1024 if remaining is None else min(1024 * 1024, remaining))
        if not block:
            if remaining:
                raise ValueError("Truncated APK data")
            break
        digest.update(block)
        if remaining is not None:
            remaining -= len(block)
    return digest.hexdigest()


def run(arguments: list[str | Path]) -> str:
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True,
                            encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{Path(str(arguments[0])).name} failed ({result.returncode}):\n"
                           + result.stdout + result.stderr)
    return result.stdout + result.stderr


def pinned_download(directory: Path, name: str, expected_sha256: str, source_url: str | None = None) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / name
    if not destination.exists():
        url = source_url or f"https://github.com/JingMatrix/LSPatch/releases/download/{TAG}/{name}"
        request = urllib.request.Request(url, headers={"User-Agent": "MapAdKiller-nonroot-build"})
        temporary = destination.with_suffix(destination.suffix + ".download")
        try:
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if sha256(temporary) != expected_sha256:
                raise ValueError(f"Official release SHA256 mismatch: {name}")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    if sha256(destination) != expected_sha256:
        raise ValueError(f"Cached release SHA256 mismatch: {name}")
    return destination


def sdk_tools(sdk: Path, version: str | None) -> Path:
    versions = [x for x in (sdk / "build-tools").iterdir()
                if x.is_dir() and (x / ("aapt2.exe" if os.name == "nt" else "aapt2")).exists()]
    if version:
        versions = [x for x in versions if x.name == version]
    if not versions:
        raise ValueError(f"No Android SDK build-tools found under {sdk}")
    return max(versions, key=lambda x: tuple(int(y) for y in re.findall(r"\d+", x.name)))


def tool(directory: Path, name: str) -> Path:
    return directory / (name + ".exe" if os.name == "nt" else name)


def badging(apk: Path, build_tools: Path) -> dict:
    text = run([tool(build_tools, "aapt2"), "dump", "badging", apk])
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", text, re.M)
    if not match:
        raise ValueError(f"Cannot read APK manifest: {apk.name}")
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    return {"package": match[1], "versionCode": match[2], "versionName": match[3],
            "minSdk": int(minimum[1]) if minimum else None,
            "targetSdk": int(target[1]) if target else None}


def verify_signature(apk: Path, java: Path, build_tools: Path) -> dict:
    text = run([java, "-jar", build_tools / "lib" / "apksigner.jar", "verify", "--verbose",
                "--print-certs", apk])
    certificates = sorted(set(re.findall(r"^[^\r\n]*certificate SHA-256 digest: (\S+)", text, re.M)))
    if not certificates:
        raise ValueError(f"No signer found: {apk.name}")
    return {"certificateSha256": certificates,
            "v2": "Verified using v2 scheme (APK Signature Scheme v2): true" in text,
            "v3": "Verified using v3 scheme (APK Signature Scheme v3): true" in text}


def local_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as source:
        source.seek(entry.header_offset)
        header = source.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Invalid ZIP local header")
    filename_len, extra_len = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + filename_len + extra_len


def original_asset_sha256(apk: Path, entry: zipfile.ZipInfo) -> str:
    # LSPatch links outer entries into a stored nested original. Python's ZipFile
    # overlap guard intentionally rejects that whole nested entry, even though
    # this is the patcher's documented layout. Read its bounded physical region.
    if entry.compress_type != zipfile.ZIP_STORED or entry.file_size != entry.compress_size:
        raise ValueError("The nested original must be stored without compression")
    start = local_data_offset(apk, entry)
    if start < 0 or start + entry.file_size > apk.stat().st_size:
        raise ValueError("Nested original extends beyond the APK")
    with apk.open("rb") as source:
        source.seek(start)
        return stream_sha256(source, entry.file_size)


def dex_definitions(data: bytes) -> set[str]:
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Invalid module DEX")
    string_size, string_offset = struct.unpack_from("<II", data, 56)
    type_size, type_offset = struct.unpack_from("<II", data, 64)
    class_size, class_offset = struct.unpack_from("<II", data, 96)
    for size, offset, width in [(string_size, string_offset, 4), (type_size, type_offset, 4),
                                (class_size, class_offset, 32)]:
        if offset + size * width > len(data):
            raise ValueError("Invalid DEX table bounds")
    definitions = set()
    for index in range(class_size):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        if type_index >= type_size:
            raise ValueError("Invalid DEX type index")
        string_index = struct.unpack_from("<I", data, type_offset + type_index * 4)[0]
        if string_index >= string_size:
            raise ValueError("Invalid DEX string index")
        position = struct.unpack_from("<I", data, string_offset + string_index * 4)[0]
        for _ in range(5):
            if position >= len(data):
                raise ValueError("Truncated DEX string length")
            value = data[position]
            position += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.index(b"\0", position)
        definitions.add(data[position:end].decode("utf-8", errors="replace"))
    return definitions


def inspect_elf(data: bytes) -> dict:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected a little-endian Android ELF library")
    wide = data[4] == 2
    if not wide and data[4] != 1:
        raise ValueError("Unsupported ELF class")
    program_offset = struct.unpack_from("<Q" if wide else "<I", data, 32 if wide else 28)[0]
    program_width, program_count = struct.unpack_from("<HH", data, 54 if wide else 42)
    section_offset = struct.unpack_from("<Q" if wide else "<I", data, 40 if wide else 32)[0]
    section_width, section_count = struct.unpack_from("<HH", data, 58 if wide else 46)
    if program_offset + program_width * program_count > len(data) or section_offset + section_width * section_count > len(data):
        raise ValueError("Invalid ELF table bounds")
    load_alignments = []
    for i in range(program_count):
        offset = program_offset + i * program_width
        if struct.unpack_from("<I", data, offset)[0] == 1:
            alignment = struct.unpack_from("<Q" if wide else "<I", data, offset + (48 if wide else 28))[0]
            load_alignments.append(alignment)
    if not load_alignments or min(load_alignments) < 16384:
        raise ValueError("Native ELF load segments do not support 16 KB pages")
    sections = []
    for i in range(section_count):
        offset = section_offset + i * section_width
        kind = struct.unpack_from("<I", data, offset + 4)[0]
        start, size = struct.unpack_from("<QQ" if wide else "<II", data, offset + (24 if wide else 16))
        link = struct.unpack_from("<I", data, offset + (40 if wide else 24))[0]
        entry_width = struct.unpack_from("<Q" if wide else "<I", data, offset + (56 if wide else 36))[0]
        sections.append((kind, start, size, link, entry_width))
    exported = set()
    for kind, start, size, link, width in sections:
        if kind != 11:
            continue
        if link >= len(sections) or width < (24 if wide else 16) or start + size > len(data):
            raise ValueError("Invalid ELF dynamic symbol table")
        _, strings_start, strings_size, _, _ = sections[link]
        if strings_start + strings_size > len(data):
            raise ValueError("Invalid ELF dynamic strings")
        for offset in range(start, start + size, width):
            name_offset = struct.unpack_from("<I", data, offset)[0]
            info, other, index = struct.unpack_from("<BBH", data, offset + (4 if wide else 12))
            if info >> 4 not in (1, 2) or info & 15 != 2 or other & 3 or index == 0:
                continue
            if name_offset >= strings_size:
                raise ValueError("Invalid ELF symbol string index")
            name_start = strings_start + name_offset
            name_end = data.index(b"\0", name_start, strings_start + strings_size)
            exported.add(data[name_start:name_end].decode("ascii"))
    if "native_init" not in exported:
        raise ValueError("Modern Native Hook callback native_init is not exported")
    return {"nativeApiEntrypoint": "native_init", "elfLoadAlignments": load_alignments}


def inspect_module(apk: Path, build_tools: Path, java: Path) -> dict:
    manifest = badging(apk, build_tools)
    if manifest["package"] != MODULE_PACKAGE:
        raise ValueError(f"Expected {MODULE_PACKAGE}, got {manifest['package']}")
    run([tool(build_tools, "zipalign"), "-c", "-P", "16", "4", apk])
    with zipfile.ZipFile(apk) as archive:
        props = dict(line.split("=", 1) for line in
                     archive.read("META-INF/xposed/module.prop").decode().splitlines()
                     if "=" in line and not line.lstrip().startswith("#"))
        if int(props.get("targetApiVersion", "0")) != 102:
            raise ValueError("This build requires the module to target official Xposed API 102")
        java_entries = [x.strip() for x in archive.read("META-INF/xposed/java_init.list").decode().splitlines()
                        if x.strip() and not x.lstrip().startswith("#")]
        native_entries = [x.strip() for x in archive.read("META-INF/xposed/native_init.list").decode().splitlines()
                          if x.strip() and not x.lstrip().startswith("#")]
        if not java_entries or not native_entries:
            raise ValueError("Module Java and native entry lists must both be present")
        libraries = {}
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[2] not in native_entries:
                continue
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"Vector cannot load a compressed native library: {entry.filename}")
            offset = local_data_offset(apk, entry)
            if offset % 16384:
                raise ValueError(f"Native library is not 16 KB aligned: {entry.filename}")
            library = archive.read(entry)
            libraries[parts[1]] = {"entry": entry.filename, "dataOffset": offset,
                                   "sha256": hashlib.sha256(library).hexdigest(), **inspect_elf(library)}
        if not libraries:
            raise ValueError("No uncompressed module native libraries found")
        defined = set()
        for entry in archive.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", entry):
                defined.update(dex_definitions(archive.read(entry)))
        if any(x.startswith("Lio/github/libxposed/api/") for x in defined):
            raise ValueError("Xposed API definitions must not be bundled in the module APK")
        if "Lio/github/libxposed/service/XposedServiceHelper;" not in defined:
            raise ValueError("Modern companion service client is missing")
    return {**manifest, "sha256": sha256(apk), "signature": verify_signature(apk, java, build_tools),
            "targetApiVersion": 102, "javaEntries": java_entries, "nativeEntries": native_entries,
            "nativeLibraries": libraries, "apiDefinitionsBundled": False}


def verify_patched(apk: Path, original: Path, module: Path, mode: str,
                   bypass: int, build_tools: Path, java: Path) -> dict:
    expected = badging(original, build_tools)
    manifest = badging(apk, build_tools)
    for field in ("package", "versionCode", "versionName"):
        if manifest[field] != expected[field]:
            raise ValueError(f"Patched APK changed {field}")
    if manifest["minSdk"] is None or manifest["minSdk"] < 28:
        raise ValueError("Rootless LSPatch requires Android 9 or newer")
    manifest_xml = run([tool(build_tools, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub" not in manifest_xml:
        raise ValueError("LSPatch bootstrap factory is missing from the manifest")
    with zipfile.ZipFile(apk) as archive:
        configuration = json.loads(archive.read(CONFIG_ENTRY))
        if configuration.get("useManager") != (mode == "manager"):
            raise ValueError("Patched APK mode differs from the requested mode")
        if configuration.get("sigBypassLevel") != bypass:
            raise ValueError("Signature bypass configuration mismatch")
        if mode == "manager" and configuration.get("managerPackageName") != "org.lsposed.lspatch":
            raise ValueError("Manager-mode patch does not refer to the supplied official manager")
        original_digest = original_asset_sha256(apk, archive.getinfo(ORIGINAL_ENTRY))
        if original_digest != sha256(original):
            raise ValueError("The original host APK was not preserved byte-for-byte")
        module_entries = [x for x in archive.namelist() if x.startswith("assets/lspatch/modules/")]
        embedded_digest = None
        if mode == "integrated":
            if module_entries != [MODULE_ENTRY]:
                raise ValueError(f"Unexpected embedded modules: {module_entries}")
            with archive.open(MODULE_ENTRY) as stream:
                embedded_digest = stream_sha256(stream)
            if embedded_digest != sha256(module):
                raise ValueError("Embedded module does not match the supplied latest module APK")
        elif module_entries:
            raise ValueError("A manager-mode build must not contain embedded modules")
        native_runtime = [x for x in archive.namelist()
                          if x.startswith("assets/lspatch/so/") and x.endswith(".so")]
        if mode == "integrated" and not native_runtime:
            raise ValueError("LSPatch native runtime is missing")
    return {**manifest, "file": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk),
            "signature": verify_signature(apk, java, build_tools), "mode": mode,
            "sigBypassLevel": bypass, "originalSha256": original_digest,
            "embeddedModuleSha256": embedded_digest, "nativeRuntimeEntries": native_runtime,
            "nativeRuntimeSource": "embedded" if mode == "integrated" else "official manager APK",
            "deviceRuntimeTested": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ModuleApk", "--module-apk", type=Path, required=True)
    parser.add_argument("--OriginalApks", "--original-apks", type=Path, nargs="+", required=True)
    parser.add_argument("--OutputDirectory", "--output-directory", type=Path, required=True)
    parser.add_argument("--Mode", "--mode", choices=["integrated", "manager"], default="integrated")
    parser.add_argument("--LspatchJar", "--lspatch-jar", type=Path)
    parser.add_argument("--CacheDirectory", "--cache-directory", type=Path)
    parser.add_argument("--Java", "--java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--AndroidSdk", "--android-sdk", type=Path,
                        default=Path(os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME") or
                                     (Path(os.getenv("LOCALAPPDATA", "")) / "Android" / "Sdk")))
    parser.add_argument("--BuildToolsVersion", "--build-tools-version")
    parser.add_argument("--SignatureBypass", "--signature-bypass", type=int, choices=[0, 1, 2, 3], default=2)
    parser.add_argument("--VerifyOnly", "--verify-only", action="store_true")
    args = parser.parse_args()
    java_version = re.search(r'version "(\d+)', run([args.Java, "-version"]))
    if not java_version or int(java_version[1]) < 21:
        raise ValueError("Official LSPatch v1.2 build 487 requires Java 21 or newer (class version 65)")
    module = args.ModuleApk.resolve(strict=True)
    originals = [x.resolve(strict=True) for x in args.OriginalApks]
    output = args.OutputDirectory.resolve()
    if len(set(originals)) != len(originals) or module in originals:
        raise ValueError("Original APK inputs must be unique and separate from the module APK")
    if any(x.is_relative_to(output) for x in originals + [module]):
        raise ValueError("Use a separate output directory, outside the input APK directory")
    build_tools = sdk_tools(args.AndroidSdk.resolve(), args.BuildToolsVersion)
    module_info = inspect_module(module, build_tools, args.Java)
    seen = set()
    for original in originals:
        package = badging(original, build_tools)["package"]
        if package not in TARGETS or package in seen:
            raise ValueError(f"Only one original APK per supported map is allowed: {package}")
        seen.add(package)
        with zipfile.ZipFile(original) as archive:
            abis = {x.split("/")[1] for x in archive.namelist()
                    if x.startswith("lib/") and x.endswith(".so")}
            if CONFIG_ENTRY in archive.namelist():
                raise ValueError("Pass the original unpatched map APK, not an existing LSPatch build")
        if not abis.issubset(module_info["nativeLibraries"]):
            raise ValueError(f"Module lacks host native ABI(s): {sorted(abis - module_info['nativeLibraries'].keys())}")
    cache = (args.CacheDirectory or output / ".build-tools").resolve()
    cli = args.LspatchJar.resolve(strict=True) if args.LspatchJar else pinned_download(cache, CLI_NAME, CLI_SHA256)
    if sha256(cli) != CLI_SHA256:
        raise ValueError("Only the verified official LSPatch v1.2 release CLI is accepted")
    license_file = pinned_download(cache, "LSPatch-LICENSE.txt", LICENSE_SHA256,
                                  f"https://raw.githubusercontent.com/JingMatrix/LSPatch/{SOURCE_COMMIT}/LICENSE")
    output.mkdir(parents=True, exist_ok=True)
    records = []
    with tempfile.TemporaryDirectory(prefix="mapclean-nonroot-", dir=output) as temporary:
        module_snapshot = Path(temporary) / "input" / module.name
        module_snapshot.parent.mkdir()
        shutil.copy2(module, module_snapshot)
        if sha256(module_snapshot) != module_info["sha256"]:
            raise ValueError("The module APK changed during inspection; retry after its build completes")
        for original in originals:
            package = badging(original, build_tools)["package"]
            destination = output / (f"{TARGETS[package]}-{badging(original, build_tools)['versionName']}"
                                    f"-MapAdKiller-{module_info['versionName']}-{args.Mode}.apk")
            if not args.VerifyOnly:
                command = [args.Java, "-Xmx2g", "-jar", cli, "-o", temporary, "-l", str(args.SignatureBypass)]
                command += ["--manager"] if args.Mode == "manager" else ["-m", module_snapshot]
                command += [original]
                print(run(command).strip())
                produced = list(Path(temporary).glob("*.apk"))
                if len(produced) != 1:
                    raise ValueError("LSPatch did not produce exactly one APK")
                # Verification happens before replacing any previous deliverable.
                record = verify_patched(produced[0], original, module_snapshot, args.Mode,
                                        args.SignatureBypass, build_tools, args.Java)
                produced[0].replace(destination)
                record["file"] = destination.name
            else:
                record = verify_patched(destination, original, module_snapshot, args.Mode,
                                        args.SignatureBypass, build_tools, args.Java)
            records.append(record)
            print(f"Verified: {destination.name} ({record['size']} bytes)")
        if args.Mode == "manager":
            manager = pinned_download(cache, MANAGER_NAME, MANAGER_SHA256)
            manager_destination = output / MANAGER_NAME
            if manager != manager_destination:
                shutil.copy2(manager, manager_destination)
            shutil.copy2(module_snapshot, output / f"MapAdKiller-{module_info['versionName']}-settings.apk")
            verify_signature(manager_destination, args.Java, build_tools)
    report = {"lspatchRelease": RELEASE_URL, "lspatchCliSha256": CLI_SHA256,
              "lspatchSource": f"https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}",
              "officialManagerSha256": MANAGER_SHA256 if args.Mode == "manager" else None,
              "mode": args.Mode, "module": module_info, "maps": records,
              "deviceRuntimeTested": False,
              "settings": ("Host-local settings: long press AMap homepage More Tools or Baidu bottom Mine. "
                           "Preferences belong to each patched map; a separately installed settings APK does not control embedded modules."
                           if args.Mode == "integrated" else
                           "Install the included official LSPatch manager and settings APK; enable this module for both maps in the manager."),
              "installation": "Original vendor-signed installs cannot be updated in place by these differently signed APKs. This tool never uninstalls or installs apps."}
    (output / "nonroot-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if license_file != output / license_file.name:
        shutil.copy2(license_file, output / license_file.name)
    (output / "THIRD-PARTY-NOTICES.txt").write_text(
        "LSPatch v1.2 (build 487), JingMatrix / LSPosed contributors\n"
        "License: GNU General Public License v3. See LSPatch-LICENSE.txt.\n"
        f"Release: {RELEASE_URL}\n"
        f"Patcher source: https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}\n"
        "Vector framework source: https://github.com/JingMatrix/Vector/tree/e00c5c5038bbcc14e0b382ab893301d6993e6989\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
