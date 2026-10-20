#!/usr/bin/env python3
"""Stage Android library closure, including optional runtime dlopen roots."""
from pathlib import Path
import re, shutil, subprocess, sys
source,out=map(Path,sys.argv[1:])
system={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libvulkan.so',
        'libEGL.so','libGLESv2.so','libGLESv1_CM.so','libOpenSLES.so','libaaudio.so',
        'libz.so','libnativewindow.so'}
index={}
for p in sorted((source/'lib').rglob('*')):
    if p.is_file() and '.so' in p.name: index.setdefault(p.name,p)
# Audio and media backends use dlopen, hence they need explicit roots.
roots=['libfreetype.so','libfontconfig.so','libgnutls.so','libpulse.so',
       'libasound.so','libSDL2.so','libavcodec.so','libavformat.so','libavutil.so',
       'libgstreamer-1.0.so','libgstapp-1.0.so','libgstaudio-1.0.so','libgstvideo-1.0.so']
queue=[p for p in out.rglob('*') if p.is_file()]
queue += [index[n] for n in roots if n in index]
seen=set()
while queue:
    p=queue.pop()
    if p in seen: continue
    seen.add(p)
    with p.open('rb') as f: magic=f.read(4)
    if magic!=b'\x7fELF': continue
    if p.is_relative_to(source):
        dest=out/'lib'/p.name
        dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(p,dest)
    info=subprocess.check_output(['readelf','-d',str(p)],text=True)
    for name in re.findall(r'\(NEEDED\).*\[(.*?)\]',info):
        if name in system: continue
        if (out/'lib'/name).is_file(): queue.append(out/'lib'/name)
        elif name in index: queue.append(index[name])
        elif list((out/'lib/wine').rglob(name)): queue.extend((out/'lib/wine').rglob(name))
        else: raise SystemExit(f'Unresolved native dependency {name} required by {p.name}')

licenses=source/'share/licenses'
if licenses.exists():shutil.copytree(licenses,out/'share/licenses/termux-dependencies',dirs_exist_ok=True,symlinks=False)
