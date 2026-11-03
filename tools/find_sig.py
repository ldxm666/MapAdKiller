import sys, struct, zipfile

def parse(blob):
    if blob[:4] != b"dex\n": return None
    str_size, str_off = struct.unpack_from("<II", blob, 0x38)
    type_size, type_off = struct.unpack_from("<II", blob, 0x40)
    proto_size, proto_off = struct.unpack_from("<II", blob, 0x48)
    meth_size, meth_off = struct.unpack_from("<II", blob, 0x58)
    S = []
    for i in range(str_size):
        p = struct.unpack_from("<I", blob, str_off + i * 4)[0]
        while blob[p] & 0x80: p += 1
        p += 1
        e = blob.find(b"\x00", p)
        S.append(blob[p:e].decode("utf-8", "replace"))
    T = [S[struct.unpack_from("<I", blob, type_off + i * 4)[0]] for i in range(type_size)]
    def tlist(off):
        if not off: return []
        n = struct.unpack_from("<I", blob, off)[0]
        return [T[struct.unpack_from("<H", blob, off + 4 + i * 2)[0]] for i in range(n)]
    P = []
    for i in range(proto_size):
        r = struct.unpack_from("<I", blob, proto_off + i * 12 + 4)[0]
        po = struct.unpack_from("<I", blob, proto_off + i * 12 + 8)[0]
        P.append((T[r], tlist(po)))
    out = []
    for i in range(meth_size):
        c, pr, nm = struct.unpack_from("<HHI", blob, meth_off + i * 8)
        rt, ps = P[pr]
        out.append((T[c], S[nm], f"{S[nm]}({''.join(ps)}){rt}"))
    return out

apk, want = sys.argv[1], sys.argv[2]
proto = sys.argv[3] if len(sys.argv) > 3 else None
z = zipfile.ZipFile(apk)
for n in z.namelist():
    if not n.endswith(".dex"): continue
    try: rows = parse(z.read(n))
    except Exception as e:
        sys.stderr.write(f"skip {n}: {e}\n"); continue
    if not rows: continue
    for cls, name, sig in rows:
        if name != want: continue
        if proto and sig != proto: continue
        print(f"{n:16} {cls:72} {sig}")

