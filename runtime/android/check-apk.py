#!/usr/bin/env python3
"""Fail when an APK's own native libraries would not load on 16 KB page devices.

Android 15+ devices with 16 KB pages refuse ELF segments aligned to less than
16 KB. usage: check-apk.py APK...
"""
import struct, sys, zipfile

bad = []
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as z:
        libs = [n for n in z.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')]
        if not libs: raise SystemExit(f'{apk}: no arm64-v8a libraries')
        for name in libs:
            data = z.read(name)
            phoff = struct.unpack_from('<Q', data, 32)[0]
            phsize, phcount = struct.unpack_from('<HH', data, 54)
            aligns = [struct.unpack_from('<Q', data, phoff + i * phsize + 48)[0]
                      for i in range(phcount) if struct.unpack_from('<I', data, phoff + i * phsize)[0] == 1]
            ok = aligns and min(aligns) >= 0x4000
            print(f'{"ok " if ok else "BAD"} {apk}: {name} LOAD align {", ".join(hex(a) for a in sorted(set(aligns)))}')
            if not ok: bad.append(name)
if bad: raise SystemExit('Not 16 KB page aligned: ' + ', '.join(sorted(set(bad))))
