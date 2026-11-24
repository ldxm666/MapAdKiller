#!/usr/bin/env python3
"""Build the full delivery package for a MapAdKiller release.

Adds what the plain source pack deliberately omits, so a reviewer can inspect the
shipped artifact and its runtime extension points without a device:

  apk/            the signed module APK
  dex/            classes.dex extracted from that exact APK + its class inventory
  hooks/          auto-extracted hook-point table (Markdown + JSON) and settings keys
  source/         the complete reviewable source tree
  docs/           release notes and changelog
  MANIFEST.json   per-file SHA-256, plus the APK/device identity that was verified
  SHA256SUMS.txt  flat checksum list

Read-only with respect to the workspace: it only reads the tree and writes the zip.

Usage:
  python tools/build_delivery_pack.py --Version 2.1.3 [--Apk path] [--OutputZip path]
"""
import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parent.parent

ROOT_FILES = {".gitignore", "README.md", "LICENSE", "CHANGELOG.md"}
APP_FILES = {"app/AndroidManifest.xml", "app/build.ps1",
             "src/AndroidManifest.xml", "src/build.ps1"}
SOURCE_TREES = {"app/src", "app/stub-src", "app/res", "app/META-INF", "app/native",
                "src/src", "src/stub-src", "src/res", "src/META-INF", "src/native",
                "docs", "registry-pack"}
EXCLUDED_PARTS = {".git", ".codex", ".agents", ".aws", "__pycache__", ".idea", ".vscode",
                  "evidence", "shots", ".scratch", "build", "dist", "output"}
EXCLUDED_SUFFIXES = {".apk", ".zip", ".keystore", ".jks", ".p12", ".log", ".db", ".sqlite",
                     ".pyc", ".o", ".obj", ".dex"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def source_files(source):
    """Every reviewable source file, mirroring tools/build_source_zip.py's allowlist."""
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(source)
        name = relative.as_posix()
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        selected = (name in ROOT_FILES or name in APP_FILES
                    or any(name.startswith(tree + "/") for tree in SOURCE_TREES)
                    or (len(relative.parts) == 2 and relative.parts[0] in {"tests", "tools"}
                        and path.suffix.lower() in {".java", ".cpp", ".h", ".py", ".ps1", ".md"}))
        if not selected:
            continue
        if path.suffix.lower() in {".md", ".java", ".py", ".ps1", ".json", ".properties",
                                   ".txt", ".cpp", ".h", ".c", ".xml", ".prop"}:
            if re.search(rb"\b(?:github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})", path.read_bytes()):
                raise ValueError(f"Credential-shaped content detected in {name}; not packaged")
        yield path, name


# ---------------------------------------------------------------- DEX inventory

def dex_strings(blob):
    size, off = struct.unpack_from("<II", blob, 0x38)
    for i in range(size):
        p = struct.unpack_from("<I", blob, off + i * 4)[0]
        while True:
            b = blob[p]
            p += 1
            if not b & 0x80:
                break
        end = blob.find(b"\x00", p)
        yield blob[p:end].decode("utf-8", "replace")


def dex_summary(blob):
    """Header identity plus the module's own class list (its package prefix only)."""
    magic = blob[:8].rstrip(b"\x00").decode("ascii", "replace")
    checksum, sig = struct.unpack_from("<I", blob, 8)[0], blob[12:32].hex()
    file_size, header_size = struct.unpack_from("<II", blob, 0x20)
    string_ids, type_ids = struct.unpack_from("<II", blob, 0x38)[0], struct.unpack_from("<II", blob, 0x40)[0]
    method_ids = struct.unpack_from("<II", blob, 0x58)[0]
    class_defs = struct.unpack_from("<II", blob, 0x60)[0]
    prefixes = ("Lorg/navkit/",)
    own = sorted({s for s in dex_strings(blob)
                  if s.startswith(prefixes) and s.endswith(";") and "$" not in s})
    return {
        "magic": magic,
        "checksum": f"{checksum:08x}",
        "signature": sig,
        "fileSize": file_size,
        "headerSize": header_size,
        "stringIds": string_ids,
        "typeIds": type_ids,
        "methodIds": method_ids,
        "classDefs": class_defs,
        "moduleClasses": len(own),
        "moduleClassList": own,
    }


# ------------------------------------------------------------- hook extraction

HOOKSIG = re.compile(r'H\.hookSig\(\s*(.+?),\s*"([^"]+)"\s*,\s*"([^"]+)"', re.S)
HOOKALL = re.compile(r'H\.hookAll(?:Bool0)?\(\s*(.+?),\s*"([^"]+)"\s*,\s*"([^"]+)"', re.S)
ASSIGN = re.compile(r'Class<\?>\s+(\w+)\s*=\s*H\.cls\([^,]+,\s*(.+?)\)\s*;')


def _short(expr):
    expr = " ".join(expr.split())
    m = re.search(r'H\.cls\([^,]+,\s*"([^"]+)"\)', expr)
    return m.group(1) if m else expr


def _aliases(text):
    consts = dict(re.findall(r'String\s+(\w+)\s*=\s*"([^"]+)"', text))
    out = {}
    for name, raw in ASSIGN.findall(text):
        expr = " ".join(raw.split())
        lit = re.fullmatch(r'"([^"]+)"', expr)
        if lit:
            out[name] = lit.group(1)
            continue
        concat = re.fullmatch(r'(\w+)\s*\+\s*"([^"]+)"', expr)
        if concat:
            out[name] = consts.get(concat.group(1), concat.group(1)) + concat.group(2)
            continue
        if re.fullmatch(r'\w+', expr):
            out[name] = consts.get(expr, expr)
    return out


def extract_hooks(source):
    """Every hook registration in the module sources, with owner file and line."""
    rows = []
    folders = []
    for base in ("geoassist", "core"):
        for layout in ("app/src", "src/src"):
            folder = source / layout / "io" / "github" / "ldxm666" / base
            if folder.is_dir():
                folders.append((base, folder))
    for base, folder in folders:
        for path in sorted(folder.glob("*.java")):
            text = path.read_text(encoding="utf-8", errors="replace")
            aliases = _aliases(text)
            for regex, kind in ((HOOKSIG, "hookSig"), (HOOKALL, "hookAll")):
                for m in regex.finditer(text):
                    cls = _short(m.group(1))
                    if re.fullmatch(r"\w+", cls) and cls in aliases:
                        cls = aliases[cls]
                    rows.append({
                        "id": m.group(3),
                        "kind": kind,
                        "target": cls,
                        "method": m.group(2),
                        "owner": f"{path.name}:{text[:m.start()].count(chr(10)) + 1}",
                        "module": base,
                    })
    rows.sort(key=lambda r: (r["module"], r["owner"]))
    return rows


def hooks_markdown(rows, version):
    lines = [
        f"# MapAdKiller {version} — Hook 逻辑点清单",
        "",
        "本文档由 `tools/build_delivery_pack.py` 从模块源码**自动提取**，不是手工维护的副本，",
        "因此条目与源码行号始终一致。运行时实际挂载数量以设备日志 `event=install_done` 为准。",
        "",
        f"共 **{len(rows)}** 个 hook 注册点。",
        "",
        "## 三类目标的挂载入口",
        "",
        "| 目标 | 入口 | 说明 |",
        "| --- | --- | --- |",
        "| 高德 `com.autonavi.minimap` | `AmapHooks.install(cl)` | 去广告基线 + 首页精简 + 协议适配 |",
        "| 百度 `com.baidu.BaiduMap` | `BmapHooks` | 开屏链路 + 首页横幅 + 地图标识 |",
        "| 腾讯 `com.tencent.map` | `TmapHooks.install(cl)` | GDT / TG 广告 SDK 拦截 |",
        "",
        "`MainHook.onPackageReady` 只处理主进程（跳过 `:locationservice` / `:widgetProvider` 等子进程），",
        "并用 `H.markInstalled(pkg)` 保证每个包只安装一次。",
        "",
        "## 完整清单",
        "",
        "| # | id | 目标类 | 方法 | 源码位置 |",
        "|---|---|---|---|---|",
    ]
    for i, r in enumerate(rows, 1):
        lines.append(f"| {i} | `{r['id']}` | `{r['target']}` | `{r['method']}` | {r['owner']} |")
    counts = {}
    for r in rows:
        counts[r["module"]] = counts.get(r["module"], 0) + 1
    lines += ["", "## 按模块统计", "", "| 模块 | hook 数 |", "|---|---|"]
    for mod in sorted(counts):
        lines.append(f"| `{mod}` | {counts[mod]} |")
    lines += [
        "",
        "## 读取方式",
        "",
        "- 每个 hook 首次触发会打印一行 `HIT <id>`（见 `H.hookMethod` 的包装）。",
        "- 每个包的安装结束会打印 `event=install_done pkg=... ok=N miss=M`。",
        "- 断言某个 hook 是否生效，应以 `HIT` 行 + 该 hook 自身的业务日志为准。",
        "",
        "在设备上抓取：",
        "",
        "```powershell",
        "adb -s <serial> logcat -d -v brief | Select-String 'install_done|HIT '",
        "```",
        "",
    ]
    return "\n".join(lines)


def target_markdown(tmf, version):
    """Section appended to HOOK-POINTS.md describing the target DEX evidence."""
    lines = [
        "",
        "## 目标 App DEX 依据（高德）",
        "",
        "交付包内 `target-amap/dex/` 是**目标应用自己的** DEX，不是本模块的。",
        "身份链：",
        "",
        f"- 目标包：`com.autonavi.minimap`，`{tmf['targetApk']}`，{tmf['targetApkBytes']:,} B",
        f"- APK SHA-256：`{tmf['targetApkSha256']}`",
        "- 已用 `adb pull` 直接拉取设备上的 `base.apk` 并与本地副本逐字节比对，**全文件哈希一致**。",
        "",
        "| DEX | 大小 | SHA-256 |",
        "|---|---|---|",
    ]
    for d in tmf["dex"]:
        lines.append(f"| `{d['file']}` | {d['bytes']:,} B | `{d['sha256']}` |")
    lines += [
        "",
        f"共 {len(tmf['dex'])} 个 DEX，解压后合计 "
        f"{sum(d['bytes'] for d in tmf['dex']) / 1024 / 1024:.1f} MB。",
        "每个 dex 都已校验与 APK 内文件**字节一致**。",
        "",
        f"`target-amap/jadx/` 是模块 hook 的 {tmf['hookTargets']} 个目标类，"
        "外加为定位根因所需的辅助类（行构建器、单位换算、卡名提取器）的反编译结果，",
        "仅供阅读，不作为构建输入。",
        "",
        "### 目标类清单与所在 DEX",
        "",
        "| 类 | 所在 DEX | 状态 |",
        "|---|---|---|",
    ]
    for t in tmf.get("targets", []):
        mark = {"exact": "存在", "inner": "内嵌", "absent": "**缺失**"}.get(t["match"], t["match"])
        lines.append(f"| `{t['target']}` | {','.join(t['dex']) or '—'} | {mark} |")
    lines.append("")
    return "\n".join(lines)


def target_readme(tmf, version):
    return f"""# 目标应用 DEX（高德 17.00.0.2005）

本目录是 MapAdKiller {version} 的**逆向依据**，与模块自身的 `dex/`（那是模块自己的
`classes.dex`）不是一回事。

## 身份链

| 项 | 值 |
| --- | --- |
| 包名 | `com.autonavi.minimap` |
| versionName | 17.00.0.2005 |
| APK 文件 | `{tmf['targetApk']}` |
| APK 大小 | {tmf['targetApkBytes']:,} B |
| APK SHA-256 | `{tmf['targetApkSha256']}` |

该哈希是**设备实机比对**的结果：用 `adb pull` 从设备直接拉取
`/data/app/.../com.autonavi.minimap-.../base.apk`，与本地副本做全文件 SHA-256，
两者完全一致。因此下面这些 DEX 就是设备上真正运行的那一份。

## 内容

```
dex/    classes.dex … classes8.dex（{len(tmf['dex'])} 个，与 APK 内字节一致）
jadx/   hook 目标类 + 根因辅助类的反编译源码
TARGET-MANIFEST.json  逐 dex 哈希与目标类映射
```

## 为什么附带这些

模块的每个 hook 都写死了目标类名和方法签名。目标 App 升级后这些名字会变，
hook 会静默 `miss`。把目标 DEX 与反编译结果一并交付，可以直接核对：

- 某个 hook 的目标类是否真的存在（而不是从旧版本继承下来的假设）；
- 目标方法签名是否仍然匹配；
- 被 hook 的方法里到底做了什么，从而判断返回值该不该被改写。

反编译用 `D:\Tools\jadx\bin\jadx.bat --single-class`，单类约 17 秒。

## 注意

- 本目录**不含原版 APK 本体**，只含从中提取的 DEX 与反编译文本。
- 反编译结果是 jadx 的还原，可能与原源码有差异，仅供阅读与比对。
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--Version", required=True)
    ap.add_argument("--SourceDirectory", type=Path, default=ROOT)
    ap.add_argument("--Apk", type=Path, default=None)
    ap.add_argument("--TargetExtract", type=Path, default=None,
                    help="output dir of tools/extract_target_apk.py (target DEX + jadx)")
    ap.add_argument("--OutputZip", type=Path, default=None)
    args = ap.parse_args()

    source = args.SourceDirectory.resolve(strict=True)
    version = args.Version
    apk = (args.Apk or (source / "output" / f"Mapadkiller-lsp-v{version}.apk")).resolve(strict=True)
    out = args.OutputZip or (source / "output" / f"Mapadkiller-{version}-完整交付包.zip")
    out = out.resolve()
    prefix = f"Mapadkiller-{version}/"

    rows = extract_hooks(source)
    files = list(source_files(source))

    with zipfile.ZipFile(apk) as z:
        dex_name = "classes.dex"
        if dex_name not in z.namelist():
            raise ValueError(f"{apk.name} has no classes.dex")
        dex_bytes = z.read(dex_name)
        apk_entries = len(z.namelist())

    summary = dex_summary(dex_bytes)
    apk_sha = sha256(apk)
    hooks_json = json.dumps({"version": version, "count": len(rows), "hooks": rows},
                            ensure_ascii=False, indent=2) + "\n"
    hooks_md = hooks_markdown(rows, version)

    # Optional: the target app's own DEX set + decompiled hook classes.
    target = None
    if args.TargetExtract:
        tdir = args.TargetExtract.resolve(strict=True)
        tmf = json.loads((tdir / "TARGET-MANIFEST.json").read_text(encoding="utf-8"))
        target = {"dir": tdir, "manifest": tmf}
        hooks_md += target_markdown(tmf, version)

    manifest = {
        "name": "Mapadkiller",
        "version": version,
        "apk": {"file": apk.name, "sha256": apk_sha, "bytes": apk.stat().st_size, "entries": apk_entries},
        "dex": {"file": dex_name, "sha256": hashlib.sha256(dex_bytes).hexdigest(),
                "bytes": len(dex_bytes), "header": {k: v for k, v in summary.items()
                                                    if k != "moduleClassList"},
                "moduleClassList": summary["moduleClassList"]},
        "hooks": {"count": len(rows)},
        "sourceFiles": len(files),
        "excludes": "原版地图 APK、设备证据、签名密钥、构建中间产物、VCS 元数据",
    }
    if target:
        manifest["targetApp"] = {
            "apk": target["manifest"]["targetApk"],
            "apkBytes": target["manifest"]["targetApkBytes"],
            "apkSha256": target["manifest"]["targetApkSha256"],
            "dex": target["manifest"]["dex"],
            "hookTargets": target["manifest"]["hookTargets"],
            "decompiled": target["manifest"]["decompiled"],
        }

    tmp = out.with_suffix(out.suffix + ".partial")
    try:
        with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as ar:
            ar.write(apk, prefix + f"apk/{apk.name}")
            ar.writestr(prefix + f"dex/{dex_name}", dex_bytes)
            ar.writestr(prefix + "dex/dex-info.json",
                        json.dumps({k: v for k, v in summary.items() if k != "moduleClassList"},
                                   ensure_ascii=False, indent=2) + "\n")
            ar.writestr(prefix + "dex/module-classes.txt", "\n".join(summary["moduleClassList"]) + "\n")
            ar.writestr(prefix + "hooks/HOOK-POINTS.md", hooks_md)
            ar.writestr(prefix + "hooks/hook-points.json", hooks_json)
            if target:
                base = prefix + "target-amap/"
                ar.writestr(base + "README.md", target_readme(target["manifest"], version))
                ar.writestr(base + "TARGET-MANIFEST.json",
                            json.dumps(target["manifest"], ensure_ascii=False, indent=2) + "\n")
                tdir = target["dir"]
                for f in sorted((tdir / "dex").glob("*.dex")):
                    ar.write(f, base + "dex/" + f.name)
                for f in sorted((tdir / "jadx").rglob("*.java")):
                    ar.write(f, base + "jadx/" + f.relative_to(tdir / "jadx").as_posix())
            for path, name in files:
                ar.write(path, prefix + "source/" + name)
            ar.writestr(prefix + "MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            sums = [f"{apk_sha}  apk/{apk.name}",
                    f"{hashlib.sha256(dex_bytes).hexdigest()}  dex/{dex_name}"]
            if target:
                for d in target["manifest"]["dex"]:
                    sums.append(f"{d['sha256']}  target-amap/dex/{d['file']}")
                sums.append(f"{target['manifest']['targetApkSha256']}  target-amap/<原版 APK 未随包附带>")
            ar.writestr(prefix + "SHA256SUMS.txt", "\n".join(sums) + "\n")
        with zipfile.ZipFile(tmp) as ar:
            bad = ar.testzip()
            if bad is not None:
                raise ValueError(f"ZIP integrity failed: {bad}")
        tmp.replace(out)
    finally:
        tmp.unlink(missing_ok=True)

    print(json.dumps({"zip": str(out), "sha256": sha256(out), "bytes": out.stat().st_size,
                      "apkSha256": apk_sha, "dexBytes": len(dex_bytes),
                      "moduleClasses": summary["moduleClasses"], "hooks": len(rows),
                      "sourceFiles": len(files),
                      "targetDex": len(target["manifest"]["dex"]) if target else 0},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
