"""Extract the module's hook-point inventory from source.

Read-only. Produces a Markdown table of every hook registration (id, target class,
method, and the declaring file/line) so the deliverable can document the exact
runtime extension points without hand-copying them out of 20+ files.
"""
import os
import re

SRC = r"D:\pojie应用\nixiang-aaa\work\mak\app\src\org\navkit"

# H.hookSig(<class expr>, "method", "id", ...)
HOOKSIG = re.compile(
    r'H\.hookSig\(\s*(.+?),\s*"([^"]+)"\s*,\s*"([^"]+)"',
    re.S)
HOOKALL = re.compile(r'H\.hookAll(?:Bool0)?\(\s*(.+?),\s*"([^"]+)"\s*,\s*"([^"]+)"', re.S)


def short(expr):
    """Collapse a (possibly multi-line) class expression to one readable token."""
    expr = " ".join(expr.split())
    # H.cls(cl, "com.x.Y") -> com.x.Y
    m = re.search(r'H\.cls\([^,]+,\s*"([^"]+)"\)', expr)
    if m:
        return m.group(1)
    return expr


ASSIGN = re.compile(r'Class<\?>\s+(\w+)\s*=\s*H\.cls\([^,]+,\s*(.+?)\)\s*;')


def resolve_aliases(text):
    """Map local variable names to the class names they were loaded from.

    Handles three shapes used across this codebase:
      Class<?> a = H.cls(cl, "com.x.Y");
      Class<?> a = H.cls(cl, P + "splash.SplashAdManager");   // P is a String const
      Class<?> a = H.cls(cl, CLS_BAR);                        // const holds a literal
    """
    consts = dict(re.findall(r'(?:private|public|static|final|\s)*String\s+(\w+)\s*=\s*"([^"]+)"', text))
    out = {}
    for name, raw in ASSIGN.findall(text):
        expr = " ".join(raw.split())
        literal = re.fullmatch(r'"([^"]+)"', expr)
        if literal:
            out[name] = literal.group(1)
            continue
        # P + "a.b.C"  ->  <value of P> + a.b.C
        concat = re.fullmatch(r'(\w+)\s*\+\s*"([^"]+)"', expr)
        if concat:
            out[name] = consts.get(concat.group(1), concat.group(1)) + concat.group(2)
            continue
        # a bare constant identifier
        if re.fullmatch(r'\w+', expr):
            out[name] = consts.get(expr, expr)
    return out


def main():
    rows = []
    for root, _dirs, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            aliases = resolve_aliases(text)
            for regex, kind in ((HOOKSIG, "hookSig"), (HOOKALL, "hookAll")):
                for m in regex.finditer(text):
                    line = text[:m.start()].count("\n") + 1
                    cls = short(m.group(1))
                    # A bare identifier is a local Class<?> variable: resolve it.
                    if re.fullmatch(r"\w+", cls) and cls in aliases:
                        cls = aliases[cls]
                    rows.append({
                        "file": name,
                        "line": line,
                        "kind": kind,
                        "cls": cls,
                        "method": m.group(2),
                        "id": m.group(3),
                    })
    rows.sort(key=lambda r: (r["file"], r["line"]))
    print(f"total hook registrations: {len(rows)}\n")
    print("| # | id | target class | method | file:line |")
    print("|---|---|---|---|---|")
    for i, r in enumerate(rows, 1):
        print(f"| {i} | `{r['id']}` | `{r['cls']}` | `{r['method']}` | "
              f"{r['file']}:{r['line']} |")
    print("\n=== by file ===")
    per = {}
    for r in rows:
        per[r["file"]] = per.get(r["file"], 0) + 1
    for f in sorted(per):
        print(f"  {f:36s} {per[f]}")


if __name__ == "__main__":
    main()
