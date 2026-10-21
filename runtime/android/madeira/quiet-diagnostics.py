#!/usr/bin/env python3
"""Demote Madeira's iOS investigation logging from ERR to TRACE.

Madeira's Wine carries a few hundred diagnostic messages written while
debugging on iOS ("iOS-Madeira alloc_thread_tls ...", "[cs-life] ml672 ...",
"[ndr-param] ..."). They use ERR, which Wine always prints, and they are not
behind WINE_IOS, so on Android every thread start, critical-section delete and
RPC call writes a line: slower games and a wine.log that grows without end.

As TRACE they are still there for an investigation (WINEDEBUG=+module, +sync,
+rpc ...) but silent by default. Only messages that carry Madeira's own markers
are touched (an mlNNN revision tag, "iOS-Madeira"/"madeira", or a hyphenated
[topic-tag]); upstream Wine's ERR messages keep their level.

usage: quiet-diagnostics.py WINE_SRC
"""
from pathlib import Path
import re, sys

MARKER = re.compile(r'ERR(\(\s*"[^"\n]*(?:\bml[0-9]{2,4}\b|iOS-Madeira|[Mm]adeira|MADEIRA|\[[a-z0-9]+-[a-z0-9-]+\]|\[dinput\]))')

src = Path(sys.argv[1])
changed = files = 0
for path in sorted(p for d in ('dlls', 'server', 'loader') for p in (src / d).rglob('*.c')):
    text = path.read_text(errors='surrogateescape')
    new, n = MARKER.subn(r'TRACE\1', text)
    if n:
        path.write_text(new, errors='surrogateescape')
        changed += n; files += 1
print(f'Madeira diagnostics: {changed} ERR messages demoted to TRACE in {files} files')
if changed < 50:
    raise SystemExit('Expected Madeira\'s diagnostic messages; the pinned tree changed: review quiet-diagnostics.py')
