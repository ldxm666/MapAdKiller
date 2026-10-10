#!/usr/bin/env python3
"""Read-only validation of a built modern Xposed module; never uses ADB."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

from build_nonroot import dex_definitions, inspect_module, run, sdk_tools, sha256, tool

ROOT = Path(__file__).resolve().parent.parent


def verify_dependency_jars(source: Path) -> dict:
    manifest = json.loads((source / "app/libs/dependencies.json").read_text(encoding="utf-8"))
    for artifact in manifest["artifacts"]:
        path = source / "app/libs" / artifact["localJar"]
        if sha256(path) != artifact["jarSha256"]:
            raise ValueError(f"Official dependency JAR SHA256 mismatch: {artifact['localJar']}")
    if (source / "app/libs/libxposed-service-aidl.dex").exists():
        raise ValueError("The obsolete manually extracted AIDL DEX is still present")
    return manifest


def verify(apk: Path, source: Path, java: Path, build_tools: Path,
           version: str, version_code: int, native_abis: list[str]) -> dict:
    dependencies = verify_dependency_jars(source)
    result = inspect_module(apk, build_tools, java)
    if result["versionName"] != version or result["versionCode"] != str(version_code):
        raise ValueError(f"Expected {version}/{version_code}, got {result['versionName']}/{result['versionCode']}")
    if set(result["nativeLibraries"]) != set(native_abis):
        raise ValueError(f"Expected native ABIs {native_abis}, got {list(result['nativeLibraries'])}")
    with zipfile.ZipFile(apk) as archive:
        definitions = set()
        for entry in archive.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", entry):
                definitions.update(dex_definitions(archive.read(entry)))
        for artifact in dependencies["artifacts"]:
            if not artifact["packaged"]:
                continue
            with zipfile.ZipFile(source / "app/libs" / artifact["localJar"]) as jar:
                expected = {"L" + name[:-6] + ";" for name in jar.namelist() if name.endswith(".class")}
            missing = expected - definitions
            if missing:
                raise ValueError(f"Official {artifact['localJar']} classes missing from DEX: {sorted(missing)}")
        scopes = [x.strip() for x in archive.read("META-INF/xposed/scope.list").decode().splitlines()
                  if x.strip() and not x.lstrip().startswith("#")]
        if not {"com.autonavi.minimap", "com.baidu.BaiduMap"}.issubset(scopes):
            raise ValueError("Both map packages must be declared in the module scope")
        properties = dict(line.split("=", 1) for line in archive.read("META-INF/xposed/module.prop").decode().splitlines()
                          if "=" in line and not line.lstrip().startswith("#"))
        if properties.get("minApiVersion") != "102":
            raise ValueError("The minimum framework API must be 102")
    manifest = run([tool(build_tools, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if "io.github.libxposed.service.XposedProvider" not in manifest or result["package"] + ".XposedService" not in manifest:
        raise ValueError("The modern companion Provider or authority is missing")
    result.update({"dependencyJarsVerified": True, "completeOfficialServiceInterfaceClasses": True,
                   "scope": scopes, "minApiVersion": 102, "staticVerificationPassed": True,
                   "deviceRuntimeTested": False})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ModuleApk", type=Path, required=True)
    parser.add_argument("--SourceDirectory", type=Path, default=ROOT)
    parser.add_argument("--ExpectedVersion", default="2.1.2")
    parser.add_argument("--ExpectedVersionCode", type=int, default=212)
    parser.add_argument("--NativeAbis", nargs="+", default=["arm64-v8a", "armeabi-v7a", "x86", "x86_64"])
    parser.add_argument("--Java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--AndroidSdk", type=Path,
                        default=Path(os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME") or
                                     (Path(os.getenv("LOCALAPPDATA", "")) / "Android" / "Sdk")))
    parser.add_argument("--BuildToolsVersion")
    parser.add_argument("--OutputReport", type=Path)
    args = parser.parse_args()
    result = verify(args.ModuleApk.resolve(strict=True), args.SourceDirectory.resolve(strict=True),
                    args.Java, sdk_tools(args.AndroidSdk, args.BuildToolsVersion), args.ExpectedVersion,
                    args.ExpectedVersionCode, args.NativeAbis)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.OutputReport:
        args.OutputReport.parent.mkdir(parents=True, exist_ok=True)
        args.OutputReport.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
