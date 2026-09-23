#!/usr/bin/env python3
"""
Offline "will this .cs3 work in this ARVIO APK?" check.

ARVIO loads a .cs3 with a DexClassLoader whose parent is ARVIO itself, so every class name the
plugin uses is looked up in ARVIO FIRST, and only then in the plugin's own DEX. This script
replays that lookup for every class, method and field the plugin references and reports:

  * MISSING      - a class that exists neither in ARVIO nor in the plugin
  * NO-METHOD    - a method the plugin calls that the resolved class (and its supers) lacks
  * NO-FIELD     - same for fields
  * NO-OVERRIDE  - a plugin method named like a method of an ARVIO superclass that does NOT
                   override it (the exact bug of 09/2026: load(String, j7.d) vs load(String, o7.a))

Platform classes (java.*, android.*, ...) are assumed present.

Usage: check_against_arvio.py <arvio.apk> <plugin.cs3> [<plugin.cs3> ...]
Exit code 1 if any problem was found.
"""
import re
import struct
import sys
import zipfile

PLATFORM = ('java/', 'javax/', 'android/', 'dalvik/', 'org/json/', 'org/xml/', 'org/w3c/',
            'sun/', 'libcore/', 'org/apache/http/',
            # annotations / debug-agent hooks: referenced, never loaded at runtime
            'org/jetbrains/annotations/', 'org/intellij/lang/annotations/', '_COROUTINE/',
            'org/codehaus/mojo/animal_sniffer/', 'com/android/tools/r8/annotations/')
# The methods ARVIO calls on a provider. A plugin method with one of these names that does not
# override the ARVIO one is never called - the exact failure mode of 09/2026.
OVERRIDE_NAMES = {'load', 'loadLinks', 'search', 'quickSearch', 'getMainPage', 'getLoadUrl'}


class Dex:
    def __init__(self, data):
        self.d = data
        u = lambda o: struct.unpack_from('<I', data, o)[0]
        self.u = u
        self.so, self.to, self.po = u(60), u(68), u(76)
        self.fs, self.fo, self.ms, self.mo = u(80), u(84), u(88), u(92)
        self.cs, self.co = u(96), u(100)
        self._st = {}

    def uleb(self, o):
        r = s = 0
        while True:
            b = self.d[o]; o += 1; r |= (b & 0x7f) << s; s += 7
            if b < 0x80:
                return r, o

    def st(self, i):
        if i not in self._st:
            o = self.u(self.so + 4 * i); _, o = self.uleb(o); e = self.d.index(b'\0', o)
            self._st[i] = self.d[o:e].decode('utf-8', 'replace')
        return self._st[i]

    def tdesc(self, i):
        return self.st(self.u(self.to + 4 * i))

    def tname(self, i):
        t = self.tdesc(i)
        return t[1:-1] if t.startswith('L') else t

    def proto(self, i):
        b = self.po + 12 * i; ret = self.tdesc(self.u(b + 4)); pp = self.u(b + 8); ps = ''
        if pp:
            n = self.u(pp)
            ps = ''.join(self.tdesc(struct.unpack_from('<H', self.d, pp + 4 + 2 * k)[0]) for k in range(n))
        return '(' + ps + ')' + ret

    def method(self, i):
        c, p, n = struct.unpack_from('<HHI', self.d, self.mo + 8 * i)
        return self.tname(c), self.st(n), self.proto(p)

    def field(self, i):
        c, t, n = struct.unpack_from('<HHI', self.d, self.fo + 8 * i)
        return self.tname(c), self.st(n), self.tdesc(t)

    def classes(self):
        """yield (name, super, interfaces, methods{(name,proto)}, fields{(name,type)})"""
        for c in range(self.cs):
            b = self.co + 32 * c
            name = self.tname(self.u(b)); sup = self.u(b + 8)
            sup = None if sup == 0xFFFFFFFF else self.tname(sup)
            io = self.u(b + 12); ifs = []
            if io:
                n = self.u(io)
                ifs = [self.tname(struct.unpack_from('<H', self.d, io + 4 + 2 * k)[0]) for k in range(n)]
            ms, fs = set(), set()
            cd = self.u(b + 24)
            if cd:
                o = cd
                sf, o = self.uleb(o); inf, o = self.uleb(o); dm, o = self.uleb(o); vm, o = self.uleb(o)
                for n in (sf, inf):
                    idx = 0
                    for _ in range(n):
                        di, o = self.uleb(o); _, o = self.uleb(o); idx += di
                        _, fn, ft = self.field(idx); fs.add((fn, ft))
                for n in (dm, vm):
                    idx = 0
                    for _ in range(n):
                        di, o = self.uleb(o); _, o = self.uleb(o); _, o = self.uleb(o); idx += di
                        _, mn, mp = self.method(idx); ms.add((mn, mp))
            yield name, sup, ifs, ms, fs


def load_classes(dexes):
    out = {}
    for data in dexes:
        for name, sup, ifs, ms, fs in Dex(data).classes():
            if name not in out:
                out[name] = (sup, ifs, ms, fs)
    return out


def dexes_in(path):
    with zipfile.ZipFile(path) as z:
        return [z.read(n) for n in sorted(z.namelist()) if re.match(r'classes\d*\.dex$', n)]


def check(host, plugin_path):
    pdexes = dexes_in(plugin_path)
    plugin = load_classes(pdexes)

    def resolve(c):
        if c in host:
            return host[c], 'host'
        if c in plugin:
            return plugin[c], 'plugin'
        return None, None

    def is_platform(c):
        return c.startswith(PLATFORM) or not c or c[0] == '['

    def has_member(c, key, kind, seen=None):
        seen = seen or set()
        if c in seen:
            return False
        seen.add(c)
        if is_platform(c):
            return True  # cannot see into the platform; assume fine
        info, _ = resolve(c)
        if info is None:
            return False
        sup, ifs, ms, fs = info
        if key in (ms if kind == 'm' else fs):
            return True
        return any(has_member(s, key, kind, seen) for s in ([sup] if sup else []) + ifs)

    problems = []
    for data in pdexes:
        dx = Dex(data)
        for i in range(dx.u(64)):
            t = dx.tname(i).lstrip('[')
            if t.startswith('L'):
                t = t[1:-1]
            if len(t) == 1 or is_platform(t):
                continue
            if resolve(t)[0] is None:
                problems.append(f'MISSING    class {t}')
        for i in range(dx.ms):
            c, n, p = dx.method(i)
            c = c.lstrip('[')
            if c.startswith('L') or is_platform(c) or resolve(c)[0] is None:
                continue
            if not has_member(c, (n, p), 'm'):
                problems.append(f'NO-METHOD  {c}.{n}{p}  (resolved in {resolve(c)[1]})')
        for i in range(dx.fs):
            c, n, t = dx.field(i)
            if is_platform(c) or resolve(c)[0] is None:
                continue
            if not has_member(c, (n, t), 'f'):
                problems.append(f'NO-FIELD   {c}.{n}:{t}  (resolved in {resolve(c)[1]})')

    # override check: plugin classes that extend host classes
    for name, (sup, ifs, ms, fs) in plugin.items():
        if name in host:
            continue  # shadowed by the host copy, never used
        chain, s = [], sup
        while s and s in host and s not in chain:
            chain.append(s); s = host[s][0]
        if not chain:
            continue
        host_methods = set().union(*(host[c][2] for c in chain))
        host_names = {}
        for hn, hp in host_methods:
            host_names.setdefault((hn, hp.count(';') + hp.count('Z') * 0), []).append(hp)
        for mn, mp in ms:
            if mn not in OVERRIDE_NAMES or (mn, mp) in host_methods:
                continue
            same_name = [hp for hn, hp in host_methods if hn == mn and
                         len(re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', hp.split(')')[0])) ==
                         len(re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])', mp.split(')')[0]))]
            if same_name:
                problems.append(f'NO-OVERRIDE {name}.{mn}{mp}  host expects {same_name[0]}')

    uniq = sorted(set(problems))
    print(f'== {plugin_path}: {len(uniq)} problem(s)')
    for p in uniq[:80]:
        print('  ' + p)
    if len(uniq) > 80:
        print(f'  ... and {len(uniq) - 80} more')
    return len(uniq)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    host = load_classes(dexes_in(sys.argv[1]))
    total = sum(check(host, p) for p in sys.argv[2:])
    sys.exit(1 if total else 0)
