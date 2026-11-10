#!/usr/bin/env python3
"""Run a separate test-only DEX against the actual module and embedded production APKs.

Does not install apps, change map preferences, enable framework scopes or load native hooks.
The same Android test mains are used by run-android.ps1. ADB scratch files are owned
by a unique invocation directory and removed after the run.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PREFIX = "io.github.ldxm666."
ENTRY = "assets/lspatch/modules/io.github.ldxm666.mapadkiller.apk"
AMAP = ["mapadkiller." + name + "Test" for name in (
    "AmapData", "AmapTaxiData", "AmapTaxiSkin", "AmapNaviScale", "AmapCruise",
    "CfgStore", "AmapPlaceData", "AmapTabLayout", "AmapConfigRefresh", "AmapStartup", "AmapPoiCategory", "AmapSplash", "AmapHomeGeometry")]
BAIDU = ["bmapclean." + name + "Test" for name in (
    "BaiduMineCards", "BaiduConfigRefresh", "BaiduCacheIdentity", "BaiduLabelStyle",
    "BaiduTabRouting", "BaiduPanelLayout", "BaiduCommute", "BaiduPayment")]
TMAP = ["tmapclean." + name + "Test" for name in (
    "TmapStateRecovery", "TmapAnnotations", "TmapPanelLayout", "TmapPoiCategory")]
COMMON = ["mapclean.NativeLibraryCacheTest"]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def command(args: list[str], timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(list(map(str, args)), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def checked(args: list[str], timeout: int = 180) -> str:
    result = command(args, timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} exit {result.returncode}:\n"
                           + result.stdout[-4000:] + result.stderr[-4000:])
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ModuleApk", type=Path, required=True)
    parser.add_argument("--AmapApk", type=Path, required=True)
    parser.add_argument("--AmapPlayDirectory", type=Path, required=True)
    parser.add_argument("--BaiduApk", type=Path, required=True)
    parser.add_argument("--TencentApk", type=Path, required=True)
    parser.add_argument("--Adb", default="adb")
    parser.add_argument("--Serial", required=True)
    parser.add_argument("--AndroidSdk", type=Path, required=True)
    parser.add_argument("--BuildToolsVersion", default="35.0.0")
    parser.add_argument("--OutputDirectory", type=Path, default=ROOT / "tests/build/production")
    args = parser.parse_args()
    args.OutputDirectory.mkdir(parents=True, exist_ok=True)
    report_path = args.OutputDirectory / "production-regression.json"
    report = {"serial": args.Serial, "variants": {}, "allPassed": False,
              "limitations": "Production DEX regression; not framework injection or vendor UI acceptance."}
    adb = [args.Adb, "-s", args.Serial]
    remote = "/data/local/tmp/mapclean-production-" + uuid.uuid4().hex[:12]
    remote_files: list[str] = []
    try:
        sys.path.insert(0, str(ROOT / "tools"))
        from build_nonroot import dex_definitions
        checked(adb + ["get-state"])
        checked(adb + ["shell", "mkdir", remote])
        with tempfile.TemporaryDirectory(prefix="mapclean-production-") as tmp:
            work = Path(tmp)
            tests = work / "test-dex"
            tests.mkdir()
            d8 = args.AndroidSdk / "build-tools" / args.BuildToolsVersion / ("d8.bat" if os.name == "nt" else "d8")
            checked([str(d8), "--min-api", "26", "--lib",
                     str(args.AndroidSdk / "platforms/android-34/android.jar"), "--output", str(tests),
                     str(ROOT / "tests/build/tests.jar"), str(ROOT / "tests/build/api-test.jar")])
            if len(list(tests.glob("classes*.dex"))) != 1:
                raise RuntimeError("Expected a single test-only DEX")
            test_definitions = set(dex_definitions((tests / "classes.dex").read_bytes()))
            test_remote = remote + "/tests.dex"
            checked(adb + ["push", str(tests / "classes.dex"), test_remote])
            remote_files.append(test_remote)
            full_tests = re.findall(r"'(" + re.escape(PREFIX) + r"[\w.]+Test)'",
                                    (ROOT / "tests/run-android.ps1").read_text(encoding="utf-8-sig"))
            if not full_tests or len(full_tests) != len(set(full_tests)):
                raise RuntimeError("Missing or duplicate regression classes in Android runner")
            variants = {"module": (args.ModuleApk, full_tests)}
            for name, apk, groups in (
                    ("amap-cn", args.AmapApk, AMAP),
                    ("amap-play", args.AmapPlayDirectory / "base.apk", AMAP),
                    ("baidu", args.BaiduApk, BAIDU),
                    ("tencent", args.TencentApk, TMAP)):
                with zipfile.ZipFile(apk) as archive:
                    data = archive.read(ENTRY)
                embedded = work / (name + ".apk")
                embedded.write_bytes(data)
                variants[name] = (embedded, [PREFIX + g for g in groups + COMMON])
            for name, (apk, groups) in variants.items():
                raw = apk.read_bytes()
                with zipfile.ZipFile(apk) as archive:
                    bad = archive.testzip()
                    if bad:
                        raise RuntimeError(f"Invalid ZIP payload {name}: {bad}")
                    dex = {n: sha(archive.read(n)) for n in sorted(archive.namelist())
                           if re.fullmatch(r"classes(?:\d+)?\.dex", n)}
                    if not dex:
                        raise RuntimeError(f"No production DEX in {name}")
                    definitions = set()
                    for entry in dex:
                        definitions.update(dex_definitions(archive.read(entry)))
                    overlap = test_definitions & definitions
                    if overlap:
                        raise RuntimeError(f"Test DEX shadows production classes in {name}: {sorted(overlap)}")
                row = {"moduleSha256": sha(raw), "dexSha256": dex, "tests": []}
                report["variants"][name] = row
                apk_remote = remote + "/" + name + ".apk"
                checked(adb + ["push", str(apk), apk_remote])
                remote_files.append(apk_remote)
                for cls in groups:
                    result = command(adb + ["shell", "CLASSPATH=" + test_remote + ":" + apk_remote,
                                            "app_process", "/", PREFIX + "mapclean.RegressionHarness", cls])
                    marker = "REGRESSION_OK " + cls
                    success = result.returncode == 0 and marker in result.stdout
                    output = result.stdout + result.stderr
                    row["tests"].append({"class": cls, "exitCode": result.returncode,
                                         "passed": success, "output": output})
                    print(f"{name} {cls.rsplit('.', 1)[-1]} exit={result.returncode} "
                          + ("PASS" if success else "FAIL"), flush=True)
                    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                    if not success:
                        raise RuntimeError(output[-6000:])
            report["amapCnPlayDexIdentical"] = (report["variants"]["amap-cn"]["dexSha256"]
                                                 == report["variants"]["amap-play"]["dexSha256"])
            if not report["amapCnPlayDexIdentical"]:
                raise RuntimeError("Domestic and Play feature DEX differ")
            report["allPassed"] = True
            print("PASS all production variants; CN/Play feature DEX identical", flush=True)
    except Exception as error:
        report["error"] = str(error)
        print("FAIL " + str(error), flush=True)
        return 1
    finally:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if remote_files:
            command(adb + ["shell", "rm", "-f", *remote_files])
        command(adb + ["shell", "rmdir", remote])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
