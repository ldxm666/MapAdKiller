#!/usr/bin/env python3
"""Build the AMap-only ("免 root 内置版") module variant.

Produces a slimmed module APK that contains only the 高德 half of the module, then feeds
it to the pinned official LSPatch CLI to produce an integrated (rootless) host APK whose
only entry point is the in-app floating orb.

What "slimmed" means here
-------------------------
The full module carries three vendor adapters (高德 / 百度 / 腾讯) plus the standalone
settings Activity. This variant keeps:

  * io.github.ldxm666.mapadkiller.*  -- but only the 高德 files (Amap*.java + shared core)
  * io.github.ldxm666.mapclean.*     -- shared motion/UI kit + the floating layer
  * the libxposed service/interface jars and the native label library

and drops every 百度 / 腾讯 source file, the standalone settings Activity, and the
old "long-press More Tools" embedded entry (replaced by the floating orb).

Cross-boundary calls are satisfied with generated no-op stubs rather than runtime
reflection, so the slim build stays statically verifiable.

Usage:
  python tools/build_variant.py --ModuleApk output/MapClean-lsp-v2.1.3.apk \
      --OriginalApk <amap-original.apk> --OutputDirectory output/nonroot-amap
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 交接包是 src\ 布局，2.3.0 树是 app\ 布局
APP = (ROOT / "app") if (ROOT / "app").is_dir() else (ROOT / "src")

TAG = "v1.2"
CLI_NAME = "lspatch-v1.2-487-release.jar"
CLI_SHA256 = "d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165"
RELEASE_URL = f"https://github.com/JingMatrix/LSPatch/releases/tag/{TAG}"
SOURCE_COMMIT = "0dc50f42503711b14f5e2bb217c2bdd6321ce5be"
LICENSE_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"

# ABI folders that must receive the label library inside the patched host APK.
NATIVE_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
NATIVE_LIBRARY = "libmapclean_labels.so"
NATIVE_ENTRY = "lib/{abi}/" + NATIVE_LIBRARY

MODULE_PACKAGE = "io.github.ldxm666.mapadkiller"
AMAP = "com.autonavi.minimap"
BAIDU = "com.baidu.BaiduMap"
CONFIG_ENTRY = "assets/lspatch/config.json"
MODULE_ENTRY = f"assets/lspatch/modules/{MODULE_PACKAGE}.apk"
ORIGINAL_ENTRY = "assets/lspatch/origin.apk"

# ---- what the slim variant drops -------------------------------------------

# Whole packages that belong to other vendors.
DROP_PACKAGES = ("io.github.ldxm666.bmapclean",)

# 高德 package files that are not part of the 高德 hook path.
DROP_AMAP_FILES = {
    "BmapHooks.java", "TmapHooks.java",          # other vendors' adapters
    "Sweeper.java", "ViewKiller.java",           # unused by the amap baseline
    "SplashProbe.java", "SplashContainerHooks.java", "SplashPhase.java",
    "SettingsTile.java",                          # QS tile belongs to the standalone app
}

# mapclean files replaced by the floating layer or not needed in an embedded build.
DROP_MAPCLEAN_FILES = {
    "MainActivity.java",        # standalone settings page; the orb replaces it
    "EmbeddedSettings.java",    # old long-press entry; the orb replaces it
    "Pager.java",               # used only by MainActivity
    "Backdrop.java",            # photo backdrop for the glass settings page
    # NOTE: UpdateChecker.java is deliberately NOT dropped.
    # It was listed as standalone-app only, but the embedded build now shows the module
    # version + a check-for-updates action in the floating panel (user request).
    # It only needs android.* + java.net/java.util.regex, so a slim build compiles.
    "ConfigBackup.java",        # standalone settings import/export
    # NOTE: NativeLabelAssets.java is deliberately NOT dropped here.
    #
    # It was originally listed as "baidu label assets", and that was wrong: the class
    # gates on the process name and handles 高德 too --
    #     if ("com.autonavi.minimap".equals(process)) { map = 1; mask = ...K_MAP_POI ? 0 : 1; }
    # -- and it is the *only* thing that drives libmapclean_labels.so, whose AMap host
    # filters the map style protobuf (map_assets/style_*X_Main) to remove POI features while
    # leaving roads, water and place names intact.
    #
    # Dropping it silently disabled 地图标注 in this variant: the switch still wrote its
    # preference, nothing ever read it, and the panel looked broken.
}

# Stubs generated so the remaining sources still compile against dropped symbols.
# ---- generated stubs -------------------------------------------------------
#
# Every stub is a real file under tools/variant-stubs/, laid out like the source tree, so it can
# be read and reviewed like any other source. A slim build copies the selected ones over the
# files of the vendor it drops.
#
# The rule that matters: never delete a *caller* to make a slim build compile. One APK declares
# exactly one Xposed entry point and exactly one Application, so the shared code reaches the
# other vendor halves by name -- and a slim build satisfies that by generating a no-op, not by
# dropping the call. Deleting the 百度 forwarding is precisely how 2.1.4 shipped with 百度
# silently dead; app/build.ps1 now fails the build if the forwarding disappears.

STUB_DIR = ROOT / "tools" / "variant-stubs"

# Standalone-app surface: it has no home in an embedded build (the orb replaces it).
SHARED_STUBS = (
    "io/github/ldxm666/mapclean/EmbeddedSettings.java",
    "io/github/ldxm666/mapclean/MainActivity.java",
)
#
# ⚠ HostInfo 曾经在这里，2026-10 移出。
#   它在 mapadkiller 包里，而 **amap target 的 mapadkiller 是保留的** —— 把它放进 SHARED_STUBS
#   等于连高德也把真件顶成"isEmbedded() 恒 true"。后果不是少挂东西，而是多挂：
#   FloatingHooks.install / FloatingController.attach / AmapHooks 的 mine_entry 全部无条件走
#   内置分支，高德进程里平白多出 AmapMineEntry 的四个钩子 + 一个吞触摸的 resume 钩子，
#   同时"少挂这些钩子也能跑"的对照组被删掉 —— 白屏根本无从二分。
#   现在它只出现在 baidu / tmap 的 stubs 里（那两个 target 真的把整个包裁掉了）。

# Classes the shared code still names after a vendor package is dropped, so they get a no-op.
STUBBED_CLASSES = ("MainHook", "App", "Config", "HostInfo", "Baseline", "CaptureLog")

FLOATING_REQUIRED = (
    b"Lio/github/ldxm666/mapclean/FloatingBall;",
    b"Lio/github/ldxm666/mapclean/FloatingHooks;",
    b"Lio/github/ldxm666/mapclean/FloatingPanel;",
)

# ---- the vendor allow-list, checked in the compiled DEX ---------------------
#
# 免 root 内置版 的 service 投递整条链挂在一份厂商白名单上。slim 构建把
# mapclean.EmbeddedSettings 换成 tools/variant-stubs 的替身，替身的 enabledFor() 就是
# EmbeddedSettingsPolicy.isHostPackage()（app 源码里的真件语义相同）。白名单漏掉一个
# 宿主，mapclean.App.attachEmbedded() 就会在**最后一行 registerListener() 之前**抛
# IllegalStateException；FloatingConfig.ensureService() 的 catch (Throwable) 把它吞掉，
# 于是那个进程一条投递路径都没有：
#
#   * App.svc() 恒 null → 悬浮球面板恒显「○ 只读 · 服务未连接」，每个开关都写不进去；
#   * 该厂商的 App / 诊断 / token 持有者也拿不到 Application。
#
# 腾讯正是这么死的：2.1.6 装机包的 classes.dex 里 isHostPackage() 只有两条
# const-string（高德 / 百度），同一份 APK 里百度进程 registerListener ok、腾讯进程
# 只有一条 "embedded service gate closed"。
#
# 为什么必须读 DEX 而不是源码：产物里它就只是一个字符串常量，required/banned 那种
# 「类描述符在不在」的检查完全看不见它；而源码里的名单和真正编译进去的名单之间隔着
# 桩替换、javac 常量内联和 d8 —— 只有反读编译出来的方法体才是真的。
HOST_WHITELIST_CLASS = b"Lio/github/ldxm666/mapclean/EmbeddedSettingsPolicy;"
HOST_WHITELIST_METHOD = "isHostPackage"


def vendor_classes(package: str, keep) -> tuple:
    """DEX type descriptors for one vendor package, minus the classes we stub out."""
    folder = APP / "src" / Path(package.replace(".", "/"))
    prefix = b"L" + package.replace(".", "/").encode() + b"/"
    return tuple(prefix + f.stem.encode() + b";"
                 for f in sorted(folder.glob("*.java")) if f.stem not in keep)


# One entry per map app we ship an integrated build for. The two are mirror images: each drops
# the other vendor's whole package and substitutes no-ops for the few symbols still named.
TARGETS = {
    "amap": {
        "package": AMAP,
        "label": "高德地图",
        "artifact": "高德地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-amap-slim-{version}.apk",
        "dropPackages": ("io.github.ldxm666.bmapclean",),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/TmapHooks.java",
            "io/github/ldxm666/bmapclean/MainHook.java",
            "io/github/ldxm666/bmapclean/App.java",
            "io/github/ldxm666/mapclean/BaiduPanel.java",
            "io/github/ldxm666/mapclean/TmapPanel.java",
        ),
        # Same failure mode as 百度 2.1.4, one vendor over: a no-op stub for the 腾讯 forwarding
        # compiles, passes every structural check, and leaves 腾讯 with no UI hooks at all.
        # Checked on the generated source because the DEX cannot tell the difference.
        "invariants": (
            ("io/github/ldxm666/mapadkiller/MainHook.java",
             "tmapclean.MainHook.onPackageReady"),
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/mapadkiller/AmapHooks;",
            b"Lio/github/ldxm666/mapadkiller/AmapNativeHome;",
            # HostInfo 在 amap 变体里是**真件**（mapadkiller 包整个保留），它是内置/模块模式的
            # 唯一判据，也是 FloatingHooks / FloatingController / AmapMineEntry 的总闸。
            # 一旦有人把它又挪回 SHARED_STUBS，这条 required 会在构建期炸掉而不是等到真机排障。
            b"Lio/github/ldxm666/mapadkiller/HostInfo;",
            b"Lio/github/ldxm666/mapclean/BaiduPanel;",      # the generated no-op
            b"Lio/github/ldxm666/mapclean/TmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/bmapclean/MainHook;",       # the forwarding target
        ),
        "banned": vendor_classes("io.github.ldxm666.bmapclean", STUBBED_CLASSES) + (
            b"Lio/github/ldxm666/mapadkiller/BmapHooks;",
            b"Lcom/baidu/",
        ),
    },
    "tmap": {
        "package": "com.tencent.map",
        "label": "腾讯地图",
        "artifact": "腾讯地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-tmap-slim-{version}.apk",
        "dropPackages": ("io.github.ldxm666.mapadkiller", "io.github.ldxm666.bmapclean"),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/Config.java",
            "io/github/ldxm666/mapadkiller/App.java",
            "io/github/ldxm666/mapadkiller/MainHook.java",
                "io/github/ldxm666/mapadkiller/Baseline.java",
            # mapadkiller 整包被裁，HostInfo 只能用桩（内置版判据，见 variant-stubs 里的说明）。
            "io/github/ldxm666/mapadkiller/HostInfo.java",
            # FloatingPanel 无条件 import 这个类（面板上「日志抓取」按钮）。百度变体靠
            # keepFiles 保留真件，腾讯变体一直没给 —— 于是 --Target tmap 连编译都过不了
            # （javac: FloatingPanel.java 找不到符号 CaptureLog，共 7 处）。这里先用空实现
            # 把构建修通；腾讯要不要像百度那样保留真件（H + CaptureLog + AmapCruise 桩），
            # 是另一个决定，不该由"能不能编译"倒逼。
            "io/github/ldxm666/mapadkiller/CaptureLog.java",
            "io/github/ldxm666/bmapclean/App.java",
            "io/github/ldxm666/bmapclean/MainHook.java",
            "io/github/ldxm666/mapclean/AmapPanel.java",
            "io/github/ldxm666/mapclean/BaiduPanel.java",
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/tmapclean/MainHook;",
            b"Lio/github/ldxm666/tmapclean/TmapMapIcons;",
            b"Lio/github/ldxm666/mapclean/TmapPanel;",
            b"Lio/github/ldxm666/mapadkiller/MainHook;",
        ),
        "banned": vendor_classes("io.github.ldxm666.mapadkiller",
                                 STUBBED_CLASSES + ("Perf",))
                  + vendor_classes("io.github.ldxm666.bmapclean", STUBBED_CLASSES) + (
            b"Lcom/autonavi/",
            b"Lcom/baidu/",
        ),
    },
    "baidu": {
        "package": BAIDU,
        "label": "百度地图",
        "artifact": "百度地图-{version}-免root内置版.apk",
        "slimModule": "MapAdKiller-baidu-slim-{version}.apk",
        # 百度内置版不带腾讯适配层。
        #
        # 2.3.0 之前这里只 drop mapadkiller，tmapclean 整包跟着打进百度 APK（约 15 个类），
        # 而 banned 里也没有它 —— 于是"产物里不该有腾讯代码"这条约束在百度侧完全没人执行。
        # 代价不只是体积：mapclean.App 会真的把宿主 Application 转发给 tmapclean.App，
        # 腾讯那套 RemotePreferences/诊断/token 逻辑在百度进程里照样初始化。
        #
        # 能 drop 的前提（已核实）：bmapclean 对 tmapclean 的引用数 = 0；tmapclean 对
        # bmapclean 的引用数 = 0。唯一的编译期消费者是 mapclean.App / mapclean.MainHook，
        # 它们只碰 App.attach/onBind/onDied 与 MainHook.onModuleLoaded —— 下面两个桩全覆盖。
        "dropPackages": ("io.github.ldxm666.mapadkiller", "io.github.ldxm666.tmapclean"),
        # 这两个类与厂商无关（只依赖 android.* 与 libxposed API），但它们是**日志抓取**的全部实现：
        # H 是日志漏斗，CaptureLog 把每条日志抄进环形缓冲、在面板里落盘。整包丢掉会让百度内置版的
        # 面板里「日志抓取」按钮点了没反应（用户反馈问题拿不到日志），所以按文件保留，不用空实现顶掉。
        "keepFiles": (
            "io/github/ldxm666/mapadkiller/H.java",
            "io/github/ldxm666/mapadkiller/CaptureLog.java",
            # H.java 是两侧共用的日志漏斗，现在它在每个 hook 包装点里调 Perf 计时。
            # 不把 Perf 一起保留，百度/腾讯变体就会 javac 报「程序包 Perf 不存在」。
            # 它只有几 KB，不引入任何依赖（只用 android.util.Log + H）。
            "io/github/ldxm666/mapadkiller/Perf.java",
        ),
        "stubs": SHARED_STUBS + (
            "io/github/ldxm666/mapadkiller/Config.java",
            "io/github/ldxm666/mapadkiller/App.java",
            "io/github/ldxm666/mapadkiller/MainHook.java",
                "io/github/ldxm666/mapadkiller/Baseline.java",
                "io/github/ldxm666/mapadkiller/AmapCruise.java",
            # mapadkiller 整包被裁 ⇒ HostInfo 只能用桩
            "io/github/ldxm666/mapadkiller/HostInfo.java",
            # 腾讯包被裁后，mapclean.App / mapclean.MainHook 仍然按名字找这两个类
            "io/github/ldxm666/tmapclean/App.java",
            "io/github/ldxm666/tmapclean/MainHook.java",
            "io/github/ldxm666/mapclean/AmapPanel.java",
            "io/github/ldxm666/mapclean/TmapPanel.java",
        ),
        # A stub that is supposed to *forward* must actually forward. A no-op compiles and
        # passes every structural check, then leaves 百度 with no hooks at all -- the 2.1.4
        # failure mode. This is checked on the generated source, because the DEX cannot tell
        # the difference.
        "invariants": (
            ("io/github/ldxm666/mapadkiller/MainHook.java",
             "bmapclean.MainHook.onPackageReady"),
        ),
        "required": FLOATING_REQUIRED + (
            b"Lio/github/ldxm666/bmapclean/BaiduHooks;",
            b"Lio/github/ldxm666/bmapclean/BaiduPoiLayer;",
            # 百度侧唯一的配置后端。它没了 ⇒ 面板每个开关都只能退回远端通道 ⇒
            # 服务没绑上就是"只读"，这正是 2.2.8 用户报的那一条。
            b"Lio/github/ldxm666/bmapclean/Cfg;",
            b"Lio/github/ldxm666/mapclean/AmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/mapclean/TmapPanel;",       # the generated no-op
            b"Lio/github/ldxm666/mapadkiller/MainHook;",     # the forwarding target
            b"Lio/github/ldxm666/tmapclean/App;",            # the generated no-op
            b"Lio/github/ldxm666/tmapclean/MainHook;",       # the generated no-op
        ),
        "banned": vendor_classes(
            "io.github.ldxm666.mapadkiller",
            # Perf 同样是“被 keepFiles 保留但整包被 drop”的情形 ——
            # 它是 H.java 的开销计时依赖，必须留。不排除就会报
            # 「slim module still contains dropped vendor code: L.../Perf;」。
            STUBBED_CLASSES + ("H", "AmapCruise", "Perf"),   # CaptureLog 已在 STUBBED_CLASSES；H/AmapCruise/Perf 由 keepFiles 或空实现提供
        ) + vendor_classes("io.github.ldxm666.tmapclean", STUBBED_CLASSES) + (
            b"Lcom/autonavi/",
        ),
    },
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(args, **kw):
    result = subprocess.run([str(a) for a in args], capture_output=True,
                            encoding="utf-8", errors="replace", **kw)
    if result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed ({result.returncode}):\n"
                           + (result.stdout or "") + (result.stderr or ""))
    return (result.stdout or "") + (result.stderr or "")


def sdk_build_tools(sdk: Path, version: str | None) -> Path:
    versions = [x for x in (sdk / "build-tools").iterdir()
                if x.is_dir() and (x / ("aapt2.exe" if os.name == "nt" else "aapt2")).exists()]
    if version:
        versions = [x for x in versions if x.name == version]
    if not versions:
        raise ValueError(f"No Android SDK build-tools under {sdk}")
    return max(versions, key=lambda x: tuple(int(y) for y in re.findall(r"\d+", x.name)))


def tool(directory: Path, name: str) -> Path:
    """Resolve an SDK build-tool. On Windows d8/apksigner ship as .bat wrappers."""
    if os.name == "nt":
        for suffix in (".bat", ".exe", ""):
            candidate = directory / (name + suffix)
            if candidate.exists():
                return candidate
    return directory / (name + ".exe" if os.name == "nt" else name)


def pinned_download(directory: Path, name: str, expected: str, url: str) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    dest = directory / name
    if not dest.exists():
        tmp = dest.with_suffix(dest.suffix + ".download")
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "MapAdKiller-variant"})
            with urllib.request.urlopen(request, timeout=180) as r, tmp.open("wb") as out:
                shutil.copyfileobj(r, out)
            if sha256(tmp) != expected:
                raise ValueError(f"release SHA256 mismatch: {name}")
            tmp.replace(dest)
        finally:
            tmp.unlink(missing_ok=True)
    if sha256(dest) != expected:
        raise ValueError(f"cached release SHA256 mismatch: {name}")
    return dest


# ---------------------------------------------------------------- slim sources

def copy_slim_sources(dest: Path, target: dict) -> dict:
    """Copy app/ into dest, then remove the non-高德 half and write stubs.

    The signing keystore is carried over on purpose: the slim module must be signed with
    the same key as the full one, otherwise LSPatch's embedded module and the framework's
    signature checks disagree.
    """
    shutil.copytree(APP, dest, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("build", "dist"))

    src = dest / "src"
    removed, stubbed = [], []

    # 1) drop whole foreign packages
    keep = {Path(x).as_posix() for x in target.get("keepFiles", ())}
    for pkg in target["dropPackages"]:
        folder = src / Path(pkg.replace(".", "/"))
        if folder.is_dir():
            held = {}
            for f in folder.rglob("*.java"):
                rel = f.relative_to(src).as_posix()
                if rel in keep:
                    held[rel] = f.read_bytes()
                else:
                    removed.append(rel)
            shutil.rmtree(folder)
            # 整包删掉后再把保留的类放回去（路径按 src 相对，父目录可能已被删）
            for rel, data in held.items():
                back = src / rel
                back.parent.mkdir(parents=True, exist_ok=True)
                back.write_bytes(data)

    # 2) drop named files in the two kept packages
    for rel in sorted(DROP_AMAP_FILES):
        f = src / "io/github/ldxm666/mapadkiller" / rel
        if f.exists():
            removed.append(f.relative_to(src).as_posix())
            f.unlink()
    for rel in sorted(DROP_MAPCLEAN_FILES):
        f = src / "io/github/ldxm666/mapclean" / rel
        if f.exists():
            removed.append(f.relative_to(src).as_posix())
            f.unlink()

    # 3) write the stubs this target needs for symbols the kept sources still name
    for rel in target["stubs"]:
        f = src / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text((STUB_DIR / rel).read_text(encoding="utf-8"), encoding="utf-8")
        stubbed.append(rel)

    # 4) a stub that is meant to forward must actually forward
    for rel, needle in target.get("invariants", ()):
        body = (src / rel).read_text(encoding="utf-8")
        if needle not in body:
            raise ValueError(
                f"{rel} must contain {needle!r}: a silent no-op here would drop the forwarding "
                f"and leave that vendor with no hooks at all")
        stubbed.append(rel + " [invariant ok]")

    return {"removed": sorted(removed), "stubbed": sorted(stubbed)}


def rewrite_scope(dest: Path, target: dict) -> None:
    """A slim variant only ever runs inside its own map, so narrow the declared scope."""
    scope = dest / "META-INF" / "xposed" / "scope.list"
    scope.write_text(target["package"] + "\n", encoding="utf-8")


def rewrite_native_init(dest: Path) -> None:
    """
    Keep the native init list pointing at the label library.

    The framework calls each listed library's {@code native_init(entries)} at module load,
    which is where libmapclean_labels.so binds the framework's hook function. Without that
    pointer, its {@code configure()} JNI call reports {@code accepted=0} and the AMap style
    filter never installs -- so 地图标注 would silently do nothing.

    The slim variant does ship the library for every ABI (see build_slim_module), so the
    entry must stay. (An earlier revision blanked this file on the assumption that the
    library was Baidu-only; that assumption was wrong and broke 高德 地图标注.)
    """
    path = dest / "META-INF" / "xposed" / "native_init.list"
    if path.exists():
        path.write_text("libmapclean_labels.so\n", encoding="utf-8")


def drop_component(text: str, tag: str, name: str) -> tuple:
    """删除**恰好一个** {@code <tag ... android:name="name" .../>} 或 {@code <tag ...>…</tag>}。

    返回 (新文本, 是否删到)。找不到就原样返回 False —— 调用方负责把"该删的没删到"当错误。

    为什么不用正则：以前这里是
        re.sub(r"\\s*<!--\\s*百度侧：.*?</receiver>", "", text, flags=re.S)
    非贪婪匹配从「百度侧」那条注释一路吃到**第一个** `</receiver>`，而那个 `</receiver>`
    属于「高德侧」的 CaptureReportReceiver —— 于是删掉的是高德的抓取回报接收器，百度那条
    反倒留了下来（意图和结果正好相反），中间那条「高德侧」注释也一起没了。按注释文字定位组件的
    写法只要注释改一个字就静默失效，必须换成按组件名定位 + 成对匹配。
    """
    needle = f'android:name="{name}"'
    search = 0
    while True:
        hit = text.find(needle, search)
        if hit < 0:
            return text, False
        open_at = text.rfind(f"<{tag}", 0, hit)
        if open_at < 0:
            search = hit + len(needle)
            continue
        # `<tag` 必须真的开这个标签，不能是更长的标签名前缀（如 activity 之于 activity-alias）。
        after = open_at + 1 + len(tag)
        if after < len(text) and (text[after].isalnum() or text[after] in "-_"):
            search = hit + len(needle)
            continue
        tag_end = text.find(">", hit)
        if tag_end < 0:
            return text, False
        if text[tag_end - 1] == "/":
            end = tag_end + 1
        else:
            close = f"</{tag}>"
            close_at = text.find(close, tag_end)
            if close_at < 0:
                return text, False
            end = close_at + len(close)
        # 顺带吞掉紧贴在上面的注释块，避免留下「百度侧：…」这种指向已删组件的孤儿注释。
        # head 已经 rstrip 过 ⇒ head 结尾若是 `-->`，那么 head 里最后一个 `<!--` 就是那条
        # 紧邻的注释的开头（中间不可能再夹别的内容）。
        start = open_at
        head = text[:open_at].rstrip()
        if head.endswith("-->"):
            comment_at = head.rfind("<!--")
            if comment_at >= 0:
                start = comment_at
        return text[:start] + text[end:], True


def rewrite_manifest(dest: Path, version_name: str, version_code: int, target: dict) -> list:
    """Point the slim manifest at the floating entry and drop the standalone app surface.

    只改这几样，且每一样都断言：内置版**不许**动宿主 Application / 不许引入 sharedUserId。
    这份清单只影响内嵌的模块 APK（LSPatch 不会把模块 manifest 并进宿主），但留着错的条目
    等于给下一个人埋一个"清单里指着一个 dex 里不存在的类"的坑。
    """
    path = dest / "AndroidManifest.xml"
    text = path.read_text(encoding="utf-8")
    text = re.sub(r'android:versionCode="\d+"', f'android:versionCode="{version_code}"', text)
    text = re.sub(r'android:versionName="[^"]*"', f'android:versionName="{version_name}"', text)
    # label 按 target 走。以前写死「高德增强」，百度变体的内嵌模块也跟着叫高德增强。
    text = text.replace('android:label="MapAdKiller"',
                        f'android:label="{target["label"]}增强"')

    # 桌面入口 / QS 磁贴 / 三个回报 receiver：内置版里没有独立的模块 App 进程，
    # 这些组件只是 exported 面。保留会怎样：只要宿主进程活着，任何应用都能往
    # MAPADKILLER.CAPTURE_STATUS / bmapclean.REPORT 发广播，触发一次落盘 + 刷新。
    # 而 baidu 变体里 CaptureReportReceiver 的类根本不在 dex 里（mapadkiller 整包被裁）。
    drops = (
        ("activity-alias", ".DesktopAlias"),
        ("service", "io.github.ldxm666.mapadkiller.SettingsTile"),
        ("receiver", "io.github.ldxm666.mapadkiller.CaptureReportReceiver"),
        ("receiver", "io.github.ldxm666.bmapclean.ReportReceiver"),
        ("receiver", "io.github.ldxm666.tmapclean.ReportReceiver"),
    )
    for tag, name in drops:
        text, ok = drop_component(text, tag, name)
        if not ok:
            # 清单结构变了（组件改名/挪位置）。这里报错而不是静默跳过：跳过就等于把
            # 一个 exported 组件悄悄留在产物里，而那正是本次要收掉的东西。
            raise ValueError(f"manifest rewrite: <{tag} {name}> not found in {path.name}")

    # ---- 断言：只删上面那五样，别的一律不许动 ------------------------------
    if "sharedUserId" in text:
        raise ValueError("slim manifest must not declare sharedUserId")
    if 'android:name="io.github.ldxm666.mapclean.App"' not in text:
        raise ValueError("slim manifest lost the module Application class")
    if 'android:name="io.github.libxposed.service.XposedProvider"' not in text:
        raise ValueError("slim manifest lost the libxposed service provider")
    leaked = [name for _, name in drops if name in text]
    if leaked:
        raise ValueError("manifest components survived the drop: " + ", ".join(leaked))
    path.write_text(text, encoding="utf-8")
    return [name for _, name in drops]


def rewrite_module_prop(dest: Path, version_name: str, version_code: int, target: dict) -> None:
    path = dest / "META-INF" / "xposed" / "module.prop"
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            line = f"version={version_name}"
        elif line.startswith("versionCode="):
            line = f"versionCode={version_code}"
        elif line.startswith("description="):
            line = (f"description={target['label']}去广告与界面精简（免 root 内置版）："
                    "悬浮球入口，无 root 直接使用。")
        lines.append(line)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ------------------------------------------------------------------ build slim

def build_slim_module(slim: Path, out_apk: Path, java: Path, sdk: Path, build_tools: Path) -> dict:
    """Compile the slimmed sources into a module APK with the same pipeline as app/build.ps1."""
    aj = sdk / "platforms" / "android-34" / "android.jar"
    if not aj.exists():
        raise ValueError(f"android.jar not found: {aj}")
    bt = build_tools
    libs = slim / "libs"
    build = slim / "build"
    for d in ("stubs", "classes", "dex", "out"):
        (build / d).mkdir(parents=True, exist_ok=True)

    def java_files(folder):
        return [str(p) for p in Path(folder).rglob("*.java")]

    # 1) framework stubs (compile-only, never packaged)
    run([java.replace("java", "javac") if False else JAVA_JAVAC, "-encoding", "UTF-8", "-nowarn",
         "-source", "8", "-target", "8", "-bootclasspath", aj, "-d", build / "stubs",
         *java_files(slim / "stub-src")])
    # 2) module sources
    cp = os.pathsep.join([str(aj), str(libs / "api-102.0.0.jar"),
                          str(libs / "service-classes.jar"), str(libs / "interface-102.0.0.jar")])
    run([JAVA_JAVAC, "-encoding", "UTF-8", "-nowarn", "--release", "8",
         "-classpath", cp, "-d", build / "classes", *java_files(slim / "src")])

    # 3) dex
    run([JAVA_JAR, "-cf", build / "classes.jar", "-C", build / "classes", "."])
    shutil.copy2(libs / "service-classes.jar", build / "service-classes.jar")
    run([tool(bt, "d8"), "--min-api", "26", "--lib", aj,
         "--lib", libs / "api-102.0.0.jar", "--output", build / "dex",
         build / "classes.jar", build / "service-classes.jar", libs / "interface-102.0.0.jar"])

    # 4) resources + manifest
    run([tool(bt, "aapt2"), "compile", "--dir", slim / "res", "-o", build / "res.zip"])
    run([tool(bt, "aapt2"), "link", "-o", build / "out" / "module.apk",
         "--manifest", slim / "AndroidManifest.xml", "-I", aj,
         "--min-sdk-version", "26", "--target-sdk-version", "34", build / "res.zip"])

    # 5) add dex + metadata + native libs (STORED for the libs)
    #
    # ⚠ 全部 classes*.dex 都要写进去，不是只有 classes.dex。
    #   以前这里硬编码只写 classes.dex：d8 一旦因为方法数溢出产出 classes2.dex，那个文件
    #   会被**静默丢弃** —— 编译成功、签名成功、LSPatch 成功、verify 也成功（它当时只读
    #   classes.dex），装机后某个类第一次被解析就是 NoClassDefFoundError。
    #   "构建全绿、运行必崩"是最贵的一类缺陷，宁可在这里炸。
    def dex_order(p: Path) -> tuple:
        m = re.fullmatch(r"classes(\d*)\.dex", p.name)
        return (int(m.group(1)) if m and m.group(1) else 1, p.name)

    dex_files = sorted((build / "dex").glob("classes*.dex"), key=dex_order)
    if not dex_files:
        raise ValueError("d8 produced no classes*.dex")
    dex_names = [p.name for p in dex_files]
    expected = ["classes.dex"] + [f"classes{i}.dex" for i in range(2, len(dex_names) + 1)]
    if dex_names != expected:
        raise ValueError(f"unexpected dex set from d8: {dex_names}")
    with zipfile.ZipFile(build / "out" / "module.apk", "a", zipfile.ZIP_DEFLATED) as z:
        for dex in dex_files:
            z.write(dex, dex.name)
        for p in sorted((slim / "META-INF").rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(slim).as_posix())
    # 断言"写进去的 == d8 产出的"：zipfile 不会替我们检查漏写，重复条目也只在打开时才报。
    with zipfile.ZipFile(build / "out" / "module.apk") as packed:
        written = sorted((n for n in packed.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                         key=lambda n: dex_order(Path(n)))
    if written != dex_names:
        raise ValueError(f"dex packaging mismatch: built {dex_names}, packaged {written}")
    # Native libraries must live at lib/<abi>/ inside the APK.
    #
    # This used to be `jar -C libs <abi>`, which stores the directory itself and lands the
    # file at <abi>/libmapclean_labels.so -- the archive root, not the ABI directory.
    # Android only ever looks under lib/<abi>/, so the library was invisible to both
    # System.loadLibrary and the framework's native_init loader. That is why the 地图标注
    # switch could never arm its native style filter, in any build.
    #
    # Entries are STORED (not deflated) for the same reason the platform stores them:
    # the dynamic linker memory-maps the library straight out of the APK.
    with zipfile.ZipFile(build / "out" / "module.apk", "a", zipfile.ZIP_STORED) as z:
        existing = set(z.namelist())
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            lib = libs / abi / "libmapclean_labels.so"
            if not lib.exists():
                continue
            entry = f"lib/{abi}/libmapclean_labels.so"
            if entry not in existing:
                z.write(lib, entry)

    # 6) align + sign
    run([tool(bt, "zipalign"), "-P", "16", "-f", "4",
         build / "out" / "module.apk", build / "out" / "module-aligned.apk"])
    run([tool(bt, "zipalign"), "-c", "-P", "16", "4", build / "out" / "module-aligned.apk"])
    ks = slim / "debug.keystore"
    if not ks.exists():
        raise ValueError(f"keystore missing: {ks}")
    run([tool(bt, "apksigner"), "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--out", out_apk, build / "out" / "module-aligned.apk"])
    run([tool(bt, "apksigner"), "verify", "--print-certs", out_apk])
    run([tool(bt, "zipalign"), "-c", "-P", "16", "4", out_apk])

    # 签名之后再数一遍 dex：zipalign/apksigner 都不该动条目集合，这里顺手当成一次回归哨兵。
    with zipfile.ZipFile(out_apk) as z:
        signed_dex = sorted((n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                            key=lambda n: dex_order(Path(n)))
        dex_bytes = sum(z.getinfo(n).file_size for n in signed_dex)
    if signed_dex != dex_names:
        raise ValueError(f"signing changed the dex set: {signed_dex} != {dex_names}")
    return {"file": out_apk.name, "bytes": out_apk.stat().st_size, "sha256": sha256(out_apk),
            "dexBytes": dex_bytes, "dexEntries": signed_dex}


JAVA_JAVAC = "javac"
JAVA_JAR = "jar"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--Version", default=None, help="defaults to the version in Version.java")
    ap.add_argument("--OriginalApk", type=Path, required=True)
    ap.add_argument("--Target", choices=sorted(TARGETS), default="amap",
                    help="which map app this integrated build is for")
    ap.add_argument("--OutputDirectory", type=Path, default=None)
    ap.add_argument("--Java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
    ap.add_argument("--Javac", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javac")
    ap.add_argument("--Jar", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin" / "jar")
    ap.add_argument("--AndroidSdk", type=Path,
                    default=Path(os.environ.get("ANDROID_SDK_ROOT")
                                 or os.environ.get("ANDROID_HOME")
                                 or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    ap.add_argument("--BuildToolsVersion")
    ap.add_argument("--LspatchJar", type=Path)
    ap.add_argument("--CacheDirectory", type=Path)
    ap.add_argument("--SignatureBypass", type=int, choices=[0, 1, 2, 3], default=2)
    ap.add_argument("--KeepWork", action="store_true")
    args = ap.parse_args()

    global JAVA_JAVAC, JAVA_JAR
    if not args.Java.exists():
        args.Java = Path(shutil.which("java") or "java")
        args.Javac = Path(shutil.which("javac") or "javac")
        args.Jar = Path(shutil.which("jar") or "jar")
    JAVA_JAVAC, JAVA_JAR = str(args.Javac), str(args.Jar)

    version_file = (APP / "src/io/github/ldxm666/mapclean/Version.java").read_text(encoding="utf-8")
    version_name = args.Version or re.search(r'NAME\s*=\s*"([^"]+)"', version_file).group(1)
    version_code = int(re.search(r'CODE\s*=\s*(\d+)', version_file).group(1))

    sdk = args.AndroidSdk.resolve(strict=True)
    build_tools = sdk_build_tools(sdk, args.BuildToolsVersion)
    original = args.OriginalApk.resolve(strict=True)
    target = TARGETS[args.Target]
    out = (args.OutputDirectory or (ROOT / "output" / f"nonroot-{args.Target}")).resolve()
    out.mkdir(parents=True, exist_ok=True)

    print(f"variant    : {target['label']}-only (免 root 内置版)")
    print(f"scope      : {target['package']}")
    print(f"version    : {version_name} ({version_code})")
    print(f"original   : {original.name} ({original.stat().st_size:,} B)")
    print(f"build-tools: {build_tools.name}\n")

    work = Path(tempfile.mkdtemp(prefix="mak-variant-", dir=out if args.KeepWork else None))
    try:
        slim = work / "app"
        print("== 1/4 slim sources ==")
        plan = copy_slim_sources(slim, target)
        rewrite_scope(slim, target)
        rewrite_native_init(slim)
        rewrite_manifest(slim, version_name, version_code, target)
        rewrite_module_prop(slim, version_name, version_code, target)
        print(f"  removed {len(plan['removed'])} source files")
        for r in plan["removed"]:
            print(f"    - {r}")
        print(f"  generated {len(plan['stubbed'])} stubs")
        for s in plan["stubbed"]:
            print(f"    + {s}")

        print("\n== 2/4 build slim module ==")
        slim_apk = work / target["slimModule"].format(version=version_name)
        info = build_slim_module(slim, slim_apk, args.Java, sdk, build_tools)
        print(f"  {info['file']}  {info['bytes']:,} B  dex {info['dexBytes']:,} B")
        print(f"  sha256 {info['sha256']}")

        # The allow-list is baked into this DEX, so the cheap place to fail is right here --
        # before the ~200 MB LSPatch run, not after it.
        whitelist = check_host_whitelist(slim_apk, target)
        print(f"  host whitelist accepts {target['package']}  "
              f"[{' / '.join(whitelist)}]")

        print("\n== 3/4 patch host with LSPatch ==")
        cache = (args.CacheDirectory or out / ".build-tools").resolve()
        cli = (args.LspatchJar.resolve(strict=True) if args.LspatchJar
               else pinned_download(cache, CLI_NAME, CLI_SHA256,
                                    f"https://github.com/JingMatrix/LSPatch/releases/download/{TAG}/{CLI_NAME}"))
        if sha256(cli) != CLI_SHA256:
            raise ValueError("only the verified official LSPatch v1.2 CLI is accepted")
        license_file = pinned_download(cache, "LSPatch-LICENSE.txt", LICENSE_SHA256,
                                       f"https://raw.githubusercontent.com/JingMatrix/LSPatch/{SOURCE_COMMIT}/LICENSE")
        patch_dir = work / "patched"
        patch_dir.mkdir()
        # The patched APK is published exactly as LSPatch signs it.
        #
        # It is tempting to append the label library to the host's lib/<abi>/ afterwards, but
        # that cannot be done without rebuilding the archive, and this archive cannot be
        # rebuilt: LSPatch's central directory declares roughly 366 MB of entry data inside a
        # 200 MB file (about 166 MB of it overlapping), which is harmless for Android -- its
        # zip reader trusts the local header -- but makes zipalign and apksigner copy that much
        # per entry and emit a 367 MB APK. The library is therefore delivered from inside the
        # nested module APK and handed to the framework at runtime instead; see
        # NativeLabelAssets.askFramework() for the other half of that mechanism.
        print(run([args.Java, "-Xmx2g", "-jar", cli, "-o", patch_dir,
                   "-l", str(args.SignatureBypass), "-m", slim_apk, original]).strip())
        produced = list(patch_dir.glob("*.apk"))
        if len(produced) != 1:
            raise ValueError(f"LSPatch produced {len(produced)} APKs, expected 1")

        print("\n== 4/4 verify + publish ==")
        final = out / target["artifact"].format(version=version_name)
        shutil.copy2(produced[0], final)
        record = verify(final, original, slim_apk, args.SignatureBypass, build_tools, args.Java, target)
        record["module"] = info
        record["slimPlan"] = plan
        record["hostWhitelist"] = whitelist
        record["lspatch"] = {"release": RELEASE_URL, "cliSha256": CLI_SHA256,
                             "source": f"https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}"}
        (out / "variant-verification.json").write_text(
            json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(license_file, out / license_file.name)
        (out / "THIRD-PARTY-NOTICES.txt").write_text(
            "LSPatch v1.2 (build 487), JingMatrix / LSPosed contributors\n"
            "License: GNU General Public License v3. See LSPatch-LICENSE.txt.\n"
            f"Release: {RELEASE_URL}\n"
            f"Patcher source: https://github.com/JingMatrix/LSPatch/tree/{SOURCE_COMMIT}\n",
            encoding="utf-8")

        print(f"\n  {final.name}")
        print(f"  {record['size']:,} B")
        print(f"  sha256 {record['sha256']}")
        print(f"\n  settings entry: in-app floating orb (no extra permission)")
        return 0
    finally:
        if not args.KeepWork:
            shutil.rmtree(work, ignore_errors=True)


# ------------------------------------------------------------------- verify

def check_host_whitelist(module: Path, target: dict) -> list:
    """Fail the build unless the *compiled* allow-list accepts this variant's host.

    Reads the const-string table of {@code EmbeddedSettingsPolicy.isHostPackage} out of the
    slim module's DEX and requires the variant's own package to be one of its literals.
    See the HOST_WHITELIST_* block above for why this is a build-time invariant and not a
    code review note: the failure mode is a single missing string constant, and every
    structural check in this file is blind to it.

    Fail-closed on purpose: a missing method, or one whose body no longer carries any
    string literal (e.g. because a future toolchain inlined it away), is a build failure
    rather than a silent pass.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import dex_query

    host = target["package"]
    desc = HOST_WHITELIST_CLASS.decode()
    literals, method_seen = [], False
    for dex in dex_query.open_apk(module):
        if desc not in dex.classes():
            continue
        for m in dex.methods(desc):
            if m["name"] != HOST_WHITELIST_METHOD:
                continue
            method_seen = True
            literals.extend(s for _, s in dex.const_strings(m["code_off"]))

    if not method_seen:
        raise ValueError(
            f"{desc}->{HOST_WHITELIST_METHOD} is not in the slim module: the "
            f"{target['label']} build cannot decide whether it runs inside its own host")
    if host not in literals:
        raise ValueError(
            f"{desc}->{HOST_WHITELIST_METHOD} does not accept {host!r} -- the compiled "
            f"allow-list holds {literals or 'no string constants at all'}. Without it "
            f"mapclean.App.attachEmbedded() throws before its registerListener() call, "
            f"the exception is swallowed by FloatingConfig.ensureService(), and the "
            f"{target['label']} panel reads 只读·服务未连接 while every switch writes "
            f"nothing. Add {host!r} to EmbeddedSettingsPolicy.isHostPackage().")
    return literals


def local_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as src:
        src.seek(entry.header_offset)
        header = src.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("invalid ZIP local header")
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + name_len + extra_len


def stream_sha256(stream, length: int) -> str:
    digest = hashlib.sha256()
    remaining = length
    while remaining:
        block = stream.read(min(1 << 20, remaining))
        if not block:
            raise ValueError("truncated nested entry")
        digest.update(block)
        remaining -= len(block)
    return digest.hexdigest()


def badging(apk: Path, build_tools: Path) -> dict:
    text = run([tool(build_tools, "aapt2"), "dump", "badging", apk])
    m = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", text, re.M)
    if not m:
        raise ValueError(f"cannot read manifest: {apk.name}")
    lo = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    return {"package": m[1], "versionCode": m[2], "versionName": m[3],
            "minSdk": int(lo[1]) if lo else None}


def verify(apk: Path, original: Path, module: Path, bypass: int,
           build_tools: Path, java: Path, target: dict) -> dict:
    expected, actual = badging(original, build_tools), badging(apk, build_tools)
    for f in ("package", "versionCode", "versionName"):
        if actual[f] != expected[f]:
            raise ValueError(f"patched APK changed {f}")
    if actual["minSdk"] is None or actual["minSdk"] < 28:
        raise ValueError("rootless LSPatch requires Android 9+")
    xml = run([tool(build_tools, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub" not in xml:
        raise ValueError("LSPatch bootstrap factory missing")
    with zipfile.ZipFile(apk) as z:
        cfg = json.loads(z.read(CONFIG_ENTRY))
        if cfg.get("useManager") is not False:
            raise ValueError("expected an integrated (non-manager) build")
        if cfg.get("sigBypassLevel") != bypass:
            raise ValueError("signature bypass mismatch")
        entry = z.getinfo(ORIGINAL_ENTRY)
        if entry.compress_type != zipfile.ZIP_STORED or entry.file_size != entry.compress_size:
            raise ValueError("nested original must be STORED")
        with apk.open("rb") as src:
            src.seek(local_data_offset(apk, entry))
            if stream_sha256(src, entry.file_size) != sha256(original):
                raise ValueError("original host APK not preserved byte-for-byte")
        mods = [x for x in z.namelist() if x.startswith("assets/lspatch/modules/")]
        if mods != [MODULE_ENTRY]:
            raise ValueError(f"unexpected embedded modules: {mods}")
        with z.open(MODULE_ENTRY) as s:
            if stream_sha256(s, z.getinfo(MODULE_ENTRY).file_size) != sha256(module):
                raise ValueError("embedded module does not match the slim build")
        so = [x for x in z.namelist() if x.startswith("assets/lspatch/so/") and x.endswith(".so")]
        if not so:
            raise ValueError("LSPatch native runtime missing")
        # The slim module must keep the 高德 hooks and the floating layer, and must not
        # carry the dropped vendor code. DEX stores type *descriptors* (Lcom/x/Y;), so probe
        # with those rather than with dotted Java names.
        #
        # ⚠ required 曾经是死代码：算出来就没人用，present 只对 banned 求值。于是缺
        #   FloatingPanel / FloatingHooks / BaiduHooks 的包照样发布 —— 用户装上去是"能开、
        #   但面板/悬浮球/hook 全没有"。banned 只能证明"没有多余的东西"，证明不了
        #   "该有的东西还在"，两者必须都查。
        #
        # 多 dex：build_slim_module 现在会把 classes2.dex… 一起打包，所以这里也要把全部
        # classes*.dex 拼起来扫，否则被丢进第二个 dex 的类对这两项检查都是隐形的。
        with zipfile.ZipFile(module) as mz:
            dex_names = sorted((n for n in mz.namelist()
                                if re.fullmatch(r"classes\d*\.dex", n)),
                               key=lambda n: (len(n), n))
            if not dex_names:
                raise ValueError("slim module carries no classes*.dex")
            dex = b"".join(mz.read(n) for n in dex_names)
        missing = [r for r in target["required"] if r not in dex]
        if missing:
            raise ValueError("slim module is missing required classes: "
                             + ", ".join(m.decode() for m in missing))
        present = [b for b in target["banned"] if b in dex]
        if present:
            raise ValueError("slim module still contains dropped vendor code: "
                             + ", ".join(p.decode() for p in present))
    return {"file": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk),
            "package": actual["package"], "versionName": actual["versionName"],
            "originalSha256": sha256(original), "embeddedModuleSha256": sha256(module),
            "mode": "integrated", "sigBypassLevel": bypass,
            "nativeRuntimeEntries": so, "deviceRuntimeTested": False,
            "slimDexEntries": dex_names,
            "slimRequiredChecked": len(target["required"]),
            "slimBannedChecked": len(target["banned"]),
            "installation": (f"与原厂签名不同，不能覆盖安装官方{target['label']}；"
                             "需先卸载官方版（会清除登录态与离线地图）。"),
            "target": target["package"]}


if __name__ == "__main__":
    sys.exit(main())
