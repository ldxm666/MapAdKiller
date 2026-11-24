"""Compute the source closure needed for an AMap-only module build.

Read-only. Parses package declarations, imports, and simple-name references to work
out which .java files a given entry point actually needs, so the slim build can drop
the Baidu/Tencent half without guessing.

Usage:
  python tools/source_closure.py --Root app/src --Entry org.navkit.core.MainHook
  python tools/source_closure.py --Root app/src --Entry ... --Exclude org.navkit.bgeo
"""
import argparse
import re
from pathlib import Path

PKG = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;', re.M)
TYPE_DECL_OLD = None  # replaced by the column-0 rule above
# Top-level type declarations only: nested classes are indented, top-level ones start at column 0.
TYPE_DECL = re.compile(r'^(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum)\s+(\w+)', re.M)
# bare qualified references appearing in code, e.g. org.navkit.bgeo.Spec
FQCN_USE = re.compile(r'\b((?:[a-z]\w*\.){2,}\w+(?:\.\w+)*)\b')
# Capitalised identifiers, including single letters such as H / P.
IDENT = re.compile(r'\b([A-Z]\w*)\b')


def load(root: Path):
    """fqcn -> file, plus per-file info."""
    files = {}
    for path in sorted(root.rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        m = PKG.search(text)
        if not m:
            continue
        pkg = m.group(1)
        decls = TYPE_DECL.findall(text)
        for decl in decls:
            files[f"{pkg}.{decl}"] = {
                "path": path, "pkg": pkg, "text": text,
                "imports": IMPORT.findall(text),
                "src": text,
            }
    return files


def closure(files, entries, excludes):
    """Transitive closure over imports, same-package names, and inline FQCNs."""
    # name -> fqcn index
    by_simple = {}
    for fqcn in files:
        by_simple.setdefault(fqcn.rsplit(".", 1)[1], []).append(fqcn)
    by_pkg = {}
    for fqcn, info in files.items():
        by_pkg.setdefault(info["pkg"], []).append(fqcn.rsplit(".", 1)[1])
    import_owner = {}
    for fqcn, info in files.items():
        for imp in info["imports"]:
            if not imp.endswith(".*"):
                import_owner.setdefault((info["pkg"], imp.rsplit(".", 1)[1]), set()).add(imp)

    seen, queue = set(), list(entries)
    missing = set()
    while queue:
        fqcn = queue.pop()
        if fqcn in seen:
            continue
        if any(fqcn == e or fqcn.startswith(e + ".") for e in excludes):
            continue
        seen.add(fqcn)
        info = files.get(fqcn)
        if info is None:
            missing.add(fqcn)
            continue
        pkg, text = info["pkg"], info["src"]

        # 1) explicit imports
        for imp in info["imports"]:
            if imp.endswith(".*"):
                wildcard_pkg = imp[:-2]
                for ident in set(IDENT.findall(text)):
                    cand = f"{wildcard_pkg}.{ident}"
                    if cand in files:
                        queue.append(cand)
            elif imp in files:
                queue.append(imp)

        # 2) same-package simple names
        for ident in set(IDENT.findall(text)):
            cand = f"{pkg}.{ident}"
            if cand in files:
                queue.append(cand)

        # 3) inline fully-qualified uses
        for cand in set(FQCN_USE.findall(text)):
            if cand in files:
                queue.append(cand)
            else:
                # trim one segment at a time: com.x.Y.Z -> com.x.Y
                parts = cand.split(".")
                for cut in range(len(parts) - 1, 1, -1):
                    shorter = ".".join(parts[:cut])
                    if shorter in files:
                        queue.append(shorter)
                        break
    return seen, missing


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--Root", type=Path, required=True)
    ap.add_argument("--Entry", action="append", required=True)
    ap.add_argument("--Exclude", action="append", default=[])
    args = ap.parse_args()

    root = args.Root.resolve(strict=True)
    files = load(root)
    print(f"scanned {len(files)} classes under {root}\n")

    seen, missing = closure(files, args.Entry, args.Exclude)

    kept = sorted(seen)
    allf = sorted(files)
    dropped = [f for f in allf if f not in seen]

    print(f"NEEDED  : {len(kept)}")
    print(f"DROPPED : {len(dropped)}")
    if missing:
        print(f"external/unknown refs (not in this tree): {len(missing)}")
        for m in sorted(missing)[:40]:
            print(f"    {m}")
    print("\n=== needed files ===")
    for fqcn in kept:
        print(f"  {fqcn}")
    print("\n=== dropped files ===")
    for fqcn in dropped:
        print(f"  {fqcn}")


if __name__ == "__main__":
    main()
