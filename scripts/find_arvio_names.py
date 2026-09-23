#!/usr/bin/env python3
"""
Derive ARVIO's R8 rename table for one specific ARVIO APK.

Why: ARVIO's release build renames kotlin-stdlib classes (e.g. kotlin.coroutines.Continuation
-> j7.d in v1.9.983, -> o7.a in v2.0.0). The names change with EVERY ARVIO build, and so does
WHICH classes get renamed. Our plugin overrides (load/loadLinks/search) must use exactly the
host's names, so the table has to be re-derived for every ARVIO release.

How: com.lagradost.** is kept un-obfuscated in ARVIO. We compare the method descriptors of those
classes in the APK with the same methods in the un-obfuscated cloudstream library jar. Where the
library says 'Lkotlin/coroutines/Continuation;' and the APK says 'Lo7/a;' at the same position,
that is the rename. Found kotlin classes are then aligned the same way against kotlin-stdlib
(transitively), as long as their method names survived in the APK.

Only types from the RENAME_UNIVERSE are written out. A type is written as a rename only when the
APK uses an obfuscated name for it; types the APK keeps under their real name, or that are not
derivable, are left alone (the plugin then uses its own bundled copy).

Usage: find_arvio_names.py <arvio.apk> <cloudstream-library.jar> <kotlin-stdlib.jar> <out.txt>
Output: one line per rename: '<real/internal/Name> <obf/Name>'
"""
import re
import struct
import sys
import zipfile

RENAME_UNIVERSE = re.compile(r'^(kotlin/coroutines/[^/]+|kotlin/jvm/functions/[^/]+|okhttp3/Interceptor)$')


# ---------------------------------------------------------------- DEX (host APK)
def read_dex(data, classes):
    u = lambda o: struct.unpack_from('<I', data, o)[0]
    so, ts, to, po, mo, co, cs = u(60), u(64), u(68), u(76), u(92), u(100), u(96)

    def uleb(o):
        r = s = 0
        while True:
            b = data[o]; o += 1; r |= (b & 0x7f) << s; s += 7
            if b < 0x80:
                return r, o

    cache = {}

    def st(i):
        if i not in cache:
            o = u(so + 4 * i); _, o = uleb(o); e = data.index(b'\0', o)
            cache[i] = data[o:e].decode('utf-8', 'replace')
        return cache[i]

    ty = lambda i: st(u(to + 4 * i))[1:-1] if st(u(to + 4 * i)).startswith('L') else st(u(to + 4 * i))

    def tdesc(i):
        return st(u(to + 4 * i))

    def proto(i):
        b = po + 12 * i; ret = tdesc(u(b + 4)); pp = u(b + 8); ps = []
        if pp:
            n = u(pp); ps = [tdesc(struct.unpack_from('<H', data, pp + 4 + 2 * k)[0]) for k in range(n)]
        return ps, ret

    for c in range(cs):
        b = co + 32 * c
        name = ty(u(b)); cd = u(b + 24)
        methods = []
        if cd:
            o = cd
            sf, o = uleb(o); inf, o = uleb(o); dm, o = uleb(o); vm, o = uleb(o)
            for _ in range(sf + inf):
                _, o = uleb(o); _, o = uleb(o)
            for n in (dm, vm):
                idx = 0
                for _ in range(n):
                    di, o = uleb(o); _, o = uleb(o); _, o = uleb(o); idx += di
                    cl, pr, nm = struct.unpack_from('<HHI', data, mo + 8 * idx)
                    ps, ret = proto(pr)
                    methods.append((st(nm), ps, ret))
        classes.setdefault(name, []).extend(methods)


# ---------------------------------------------------------------- .class (library jars)
DESC_RE = re.compile(r'\[*(?:L[^;]+;|[ZBCSIJFDV])')


def split_desc(d):
    params, ret = d[1:].split(')')
    return DESC_RE.findall(params), ret


def read_class(data):
    cnt = struct.unpack_from('>H', data, 8)[0]; pos = 10; utf = {}; cls = {}; i = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while i < cnt:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack_from('>H', data, pos + 1)[0]
            utf[i] = data[pos + 3:pos + 3 + ln].decode('utf-8', 'replace'); pos += 3 + ln
        else:
            if tag == 7:
                cls[i] = struct.unpack_from('>H', data, pos + 1)[0]
            pos += 1 + sizes[tag]
            if tag in (5, 6):
                i += 1
        i += 1
    this = utf[cls[struct.unpack_from('>H', data, pos + 2)[0]]]
    pos += 6
    ic = struct.unpack_from('>H', data, pos)[0]; pos += 2 + 2 * ic

    def skip_members(p, collect):
        n = struct.unpack_from('>H', data, p)[0]; p += 2; out = []
        for _ in range(n):
            _, ni, di, ac = struct.unpack_from('>HHHH', data, p); p += 8
            for _ in range(ac):
                ln = struct.unpack_from('>I', data, p + 2)[0]; p += 6 + ln
            if collect:
                ps, ret = split_desc(utf[di]); out.append((utf[ni], ps, ret))
        return p, out

    pos, _ = skip_members(pos, False)
    _, methods = skip_members(pos, True)
    return this, methods


def read_jar(path, prefix_ok):
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith('.class') and prefix_ok(n):
                name, ms = read_class(z.read(n))
                out[name] = ms
    return out


# ---------------------------------------------------------------- alignment
def cls_of(desc):
    d = desc.lstrip('[')
    return d[1:-1] if d.startswith('L') else None


def main(apk, libjar, stdjar, outpath):
    host = {}
    with zipfile.ZipFile(apk) as z:
        for n in sorted(z.namelist()):
            if re.match(r'classes\d*\.dex$', n):
                read_dex(z.read(n), host)
    lib = read_jar(libjar, lambda n: n.startswith('com/lagradost/'))
    lib.update(read_jar(stdjar, lambda n: n.startswith('kotlin/')))

    known = {}  # real internal name -> host internal name
    for name in lib:
        if name in host:
            known[name] = name
    progress = True
    while progress:
        progress = False
        votes = {}
        for real, hname in list(known.items()):
            if real not in lib or hname not in host:
                continue
            hms = host[hname]
            for (n, ps, ret) in lib[real]:
                cands = []
                for (hn, hps, hret) in hms:
                    if hn != n or len(hps) != len(ps):
                        continue
                    pairs = list(zip(ps + [ret], hps + [hret]))
                    ok = True; tent = {}
                    for a, b in pairs:
                        ca, cb = cls_of(a), cls_of(b)
                        if ca is None or cb is None:
                            ok = ok and a == b; continue
                        if a.count('[') != b.count('['):
                            ok = False; continue
                        if ca in known:
                            ok = ok and known[ca] == cb
                        elif ca.startswith(('java/', 'android/', 'com/lagradost/')):
                            ok = ok and ca == cb
                        else:
                            tent[ca] = cb
                    if ok:
                        cands.append(tent)
                if len(cands) == 1:
                    for a, b in cands[0].items():
                        votes.setdefault(a, {}).setdefault(b, 0)
                        votes[a][b] += 1
        for a, bs in votes.items():
            if a in known:
                continue
            b, cnt = max(bs.items(), key=lambda kv: kv[1])
            if len(bs) == 1 or cnt >= 2 * sorted(bs.values())[-2]:
                known[a] = b; progress = True

    renames = sorted((a, b) for a, b in known.items() if a != b and RENAME_UNIVERSE.match(a))
    kept = sorted(a for a, b in known.items() if a == b and RENAME_UNIVERSE.match(a))
    with open(outpath, 'w') as f:
        for a, b in renames:
            f.write(f'{a} {b}\n')
    print(f'{len(renames)} renames written to {outpath}')
    for a, b in renames:
        print(f'  {a} -> {b}')
    print(f'{len(kept)} kept under their real name: ' + ', '.join(k.split("/")[-1] for k in kept))
    if not any(a == 'kotlin/coroutines/Continuation' for a, _ in renames) and \
            'kotlin/coroutines/Continuation' not in kept:
        sys.exit('ERROR: could not determine kotlin.coroutines.Continuation - refusing to build')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
