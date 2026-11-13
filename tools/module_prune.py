#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模块版裁剪（module prune）：把「免 root 内置版专属」的源码从**构建用的临时副本**里删掉。

用户要求：「必须要把免 root 的内置部分彻底移除」—— 不是运行时 `isEmbedded()` 判断，
而是**产物里根本不存在这些类**。本脚本与 `build_variant.py`（那是给免 root 变体裁掉
`MainActivity` 等）方向相反：这里给**模块版**裁掉内置版专属文件。

用法：
    python tools/module_prune.py --src app/src --out .build/module-src
    python tools/module_prune.py --src app/src --out .build/module-src --stubs tools/module-stubs

裁剪后的 `--out` 目录可直接交给 javac / 原构建脚本当源码根使用。

设计要点
--------
1. **只动副本**：源树永远不被修改（避免和别的分支/协作者打架）。
2. **删完必须有 stub 顶上**：被删类若仍被保留代码引用，用 `--stubs` 目录里的同名类顶替。
3. **KEEP_GUARD 兜底**：删完立刻校验"必须还在"的文件，缺一个就报错退出（防手滑）。
4. `--check` 只做校验不复制（给 CI / verify-runner 用）。
"""
from __future__ import print_function

import argparse
import os
import shutil
import sys

# ── 模块版要删掉的「免 root 内置版专属」文件（相对包根 io/github/ldxm666/）──────
# 每一条都经过依赖核实，见 MODULE_DROP_REASONS。
MODULE_DROP = [
    # ── 悬浮面板 UI 本体（79 KB：外观页 / 省电页 / 头像徽章 / 滑块全部在内）──
    # 数据类型已抽到 mapclean/PanelTypes.java，被 AmapPanel/BaiduPanel/TmapPanel 使用，
    # 所以删掉 FloatingPanel 不会影响任何保留代码（已 javac 验证）。
    "mapclean/FloatingPanel.java",
    "mapclean/FloatingBall.java",
    "mapclean/FloatingController.java",
    "mapclean/FloatingHooks.java",
    # GlassSlider is shared with standalone MainActivity; it is not embedded-only.
    # AvatarAsset / GithubMark are kept: the module settings page shows the author
    # avatar and the GitHub logo on its home/about tabs now.
    "mapclean/AvatarBadge.java",
    "mapadkiller/AmapMineEntry.java",
    # 百度版的长按「我的」入口：同样是内置版专属（模块模式用模块 App 的设置页）。
    "mapclean/BaiduMineEntry.java",
    "tmapclean/TmapEntry.java",
]

# 删除理由 + 依赖核实结论（写进构建日志，方便以后复核）
MODULE_DROP_REASONS = {
    "mapclean/FloatingHooks.java":
        "唯一调用点 MainHook:122 FloatingHooks.install(mod,pkg,true) -> 用 stub 空操作",
    "mapclean/FloatingController.java":
        "调用点 FloatingConfig:384 / FloatingPanel:1982 setEnabled(...) -> 用 stub 空操作",
    "mapclean/FloatingBall.java":
        "调用点 FloatingPanel 的 ball.setSizeDp/setAlphaPercent -> 用 stub 空方法",
    "mapadkiller/AmapMineEntry.java":
        "唯一调用点 AmapHooks:23 AmapMineEntry.install(cl) -> 用 stub 空操作",
}

# ── 删完**必须还在**的文件（root 用户的唯一入口 + 核心设施）──────────────────
KEEP_GUARD = [
    "mapclean/MainActivity.java",   # root 用户从桌面图标进的设置页
    "mapclean/PanelTypes.java",     # 面板数据类型（模块版必须保留）
    "mapclean/FloatingConfig.java",  # 悬浮窗配置键（被 AmapPanel/FloatingPanel 用，保留）
    "mapclean/UpdateChecker.java",  # 更新检查
    "mapclean/AmapPanel.java",      # 面板（MainActivity 也用）
    "mapclean/FeedCardConfig.java", # weather/non-weather switch migration and read rule
    "mapadkiller/Config.java",      # 配置后端（远程/本地/none）
    "mapadkiller/HostInfo.java",    # 宿主签名 + isEmbedded()
    "mapadkiller/MainHook.java",    # 框架入口
    "mapadkiller/AmapData.java",    # 协议适配
    "mapclean/GlassSlider.java",
]



# ── 模块版要**删掉的调用行**（删掉这些行后，被删类就不再需要 stub 顶替）──────────
# 每一条都是「保留代码里对被删类的唯一调用点」，删掉后整个类名从产物里彻底消失。
LINE_DROP = [
    ("mapclean/MainHook.java",
     r"FloatingHooks\.install\("),
    ("mapadkiller/MainHook.java",
     r"FloatingHooks\.install\("),
    ("mapadkiller/AmapHooks.java",
     r"AmapMineEntry\.install\("),
    ("mapclean/MainHook.java",
     r"BaiduMineEntry\.install\("),
    ("mapclean/App.java",
     r"FloatingController\.refreshEnabled\("),
    ("mapclean/FloatingConfig.java",
     r"FloatingController\.setEnabled\("),
    ("mapclean/FloatingPanel.java",
     r"FloatingController\.setEnabled\("),
    ("tmapclean/TmapTabBar.java", r"TmapEntry\.bind\("),
]

def source_path(root, rel):
    """The source migration changed .java to .kt, not the class ownership."""
    path = os.path.join(root, rel)
    if os.path.isfile(path):
        return path
    alternate = os.path.splitext(path)[0] + ".kt"
    return alternate if os.path.isfile(alternate) else path

def pkg_root(src):
    """定位包根：src 下应当有 io/github/ldxm666。"""
    cand = os.path.join(src, "io", "github", "ldxm666")
    if os.path.isdir(cand):
        return cand
    return src


def do_check(root, label, stubbed=None, require_dropped=True):
    stubbed = stubbed or set()
    missing = [f for f in KEEP_GUARD if not os.path.isfile(source_path(root, f))]
    if missing:
        print("[%s] FATAL 缺少必须保留的文件: %s" % (label, ", ".join(missing)))
        return False
    still = [] if not require_dropped else [f for f in MODULE_DROP
             if f.replace(os.sep, "/") not in stubbed and os.path.isfile(source_path(root, f))]
    if still:
        print("[%s] FATAL 这些文件本该被删掉但还在: %s" % (label, ", ".join(still)))
        return False
    print("[%s] OK keep_guard=%d drop=%d" % (label, len(KEEP_GUARD), len(MODULE_DROP)))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="原始源码根（app/src）")
    ap.add_argument("--out", required=True, help="裁剪后的输出目录")
    ap.add_argument("--stubs", default=None, help="stub 目录（tools/module-stubs）")
    ap.add_argument("--check", action="store_true", help="只校验 --src，不复制")
    args = ap.parse_args()

    root_src = pkg_root(args.src)
    if args.check:
        ok = do_check(root_src, "check", None, False)
        return 0 if ok else 1

    if os.path.isdir(args.out):
        shutil.rmtree(args.out)
    os.makedirs(args.out)

    # 1) 复制整棵树
    for dirpath, _dirnames, filenames in os.walk(args.src):
        for name in filenames:
            srcf = os.path.join(dirpath, name)
            rel = os.path.relpath(srcf, args.src)
            dstf = os.path.join(args.out, rel)
            d = os.path.dirname(dstf)
            if d and not os.path.isdir(d):
                os.makedirs(d)
            shutil.copy2(srcf, dstf)

    root_out = pkg_root(args.out)

    # 2) 删掉内置版专属文件
    removed = 0
    for rel in MODULE_DROP:
        path = source_path(root_out, rel)
        if os.path.isfile(path):
            os.remove(path)
            removed += 1
            print("DROP %s  (%s)" % (rel, MODULE_DROP_REASONS.get(rel, "")))
        else:
            print("WARN 未找到 %s（清单过期？）" % rel)

    # 2b) 删掉保留代码里对被删类的调用行（这样就不需要 stub，类名彻底消失）
    import re
    for rel, pat in LINE_DROP:
        path = source_path(root_out, rel)
        if not os.path.isfile(path):
            print("WARN LINE_DROP 未找到 %s" % rel)
            continue
        rx = re.compile(pat)
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        kept = [ln for ln in lines if not rx.search(ln)]
        dropped = len(lines) - len(kept)
        if dropped == 0:
            print("WARN LINE_DROP 未命中 %s /%s/" % (rel, pat))
            continue
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.writelines(kept)
        print("LINE_DROP %s x%d (/%s/)" % (rel, dropped, pat))

    # Kotlin rejects an unused import if its embedded-only class was removed.
    dropped_names = {os.path.splitext(os.path.basename(rel))[0] for rel in MODULE_DROP}
    for folder, _, files in os.walk(root_out):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(folder, name)
            with open(path, encoding="utf-8") as stream:
                lines = stream.readlines()
            kept = [line for line in lines if not (line.lstrip().startswith("import ") and
                    line.strip().rsplit(".", 1)[-1] in dropped_names)]
            if kept != lines:
                with open(path, "w", encoding="utf-8", newline="") as stream:
                    stream.writelines(kept)

    # 3) 用 stub 顶替
    added = 0
    stub_rel = set()
    if args.stubs and os.path.isdir(args.stubs):
        stub_root = pkg_root(args.stubs)
        for dirpath, _dirnames, filenames in os.walk(stub_root):
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                srcf = os.path.join(dirpath, name)
                rel = os.path.relpath(srcf, stub_root)
                dstf = os.path.join(root_out, rel)
                d = os.path.dirname(dstf)
                if d and not os.path.isdir(d):
                    os.makedirs(d)
                shutil.copy2(srcf, dstf)
                added += 1
                stub_rel.add(rel.replace(os.sep, "/"))
                print("STUB %s" % rel.replace(os.sep, "/"))

    # 4) 校验
    ok = do_check(root_out, "prune", stub_rel)
    print("SUMMARY removed=%d stubs=%d out=%s" % (removed, added, args.out))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())



