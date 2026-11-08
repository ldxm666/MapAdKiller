#!/usr/bin/env python3
"""Cross-check a module's hook anchors against a target APK — static, read-only, no device.

For every (class, method) pair a module hooks, confirm the class exists in the target build and
declares a method with that name. A typo in a hook string otherwise only surfaces as
`miss <id>` in logcat on a real device, long after the build looked fine.

Anchors live in a small JSON file so the check stays declarative:

    [
      {"label": "底栏",
       "class": "com.tencent.map.ama.mainpage.frame.tab.LiquidTabBarGroup",
       "methods": ["setTabViews", "onClick"]},
      ...
    ]

Usage
-----
    python tools/check_anchors.py --Apk <target.apk> --Anchors <anchors.json>
    python tools/check_anchors.py --Apk <target.apk> --Anchors <anchors.json> --Report out.json

Exit code 0 = every anchor present; 1 = at least one missing (details on stdout).
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_DEXQ = HERE / "dex_query.py"

# "  public   setTabViews(Ljava/util/List;)V"  ->  setTabViews
METHOD_RE = re.compile(r"^\s+(?:public|package|static|private|protected)\s+.*?([\w$<>]+)\(", re.M)


def check(dexq: Path, apk: Path, anchors: list) -> dict:
    results = []
    for entry in anchors:
        label = entry.get("label") or entry["class"]
        cls = entry["class"]
        methods = entry.get("methods") or []
        desc = "L" + cls.replace(".", "/") + ";"
        proc = subprocess.run(
            [sys.executable, str(dexq), "--Apk", str(apk), "--Class", desc, "--Methods"],
            capture_output=True, text=True, timeout=600)
        out = proc.stdout
        if proc.returncode:
            results.append({"label": label, "class": cls, "ok": False,
                            "missing": methods, "reason": f"DEX query failed: exit {proc.returncode}"})
            continue
        if "NOT FOUND" in out:
            results.append({"label": label, "class": cls, "ok": False,
                            "missing": methods, "reason": "class not found"})
            continue
        declared = set(METHOD_RE.findall(out))
        missing = [m for m in methods if m not in declared]
        results.append({"label": label, "class": cls, "ok": not missing,
                        "missing": missing, "declared": len(declared)})
    return {"anchors": results,
            "ok": all(r["ok"] for r in results),
            "checked": sum(len(entry.get("methods") or []) for entry in anchors)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--Apk", type=Path, required=True, help="target APK to check against")
    ap.add_argument("--Anchors", type=Path, required=True, help="anchor JSON (see module docstring)")
    ap.add_argument("--DexQuery", type=Path, default=DEFAULT_DEXQ)
    ap.add_argument("--Report", type=Path, help="optional JSON report path")
    args = ap.parse_args()

    apk = args.Apk.resolve(strict=True)
    dexq = args.DexQuery.resolve(strict=True)
    anchors = json.loads(args.Anchors.read_text(encoding="utf-8"))
    if isinstance(anchors, dict):          # {"anchors": [...]} 形式
        anchors = anchors.get("anchors") or []

    result = check(dexq, apk, anchors)
    for r in result["anchors"]:
        if r["ok"]:
            print(f"  ok {r['label']}: {r['class']} ({len(r.get('missing', []))} missing, "
                  f"{r.get('declared', '?')} declared)")
        else:
            print(f"  XX {r['label']}: {r['class']} -> {r['reason'] if 'reason' in r else 'missing ' + str(r['missing'])}")
    print("\nRESULT:", "ALL ANCHORS PRESENT" if result["ok"] else "ANCHORS MISSING")
    if args.Report:
        args.Report.parent.mkdir(parents=True, exist_ok=True)
        args.Report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
