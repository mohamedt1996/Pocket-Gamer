#!/usr/bin/env python3
"""Assemble CI outputs, checking native architecture and mandatory components.

usage: package-runtime.py [--wine wine|wine-madeira]
--wine picks the Wine component under .android-out/: "wine" (Proton Android
fork, the default) or "wine-madeira" (Madeira's Wine, experimental).
"""
from pathlib import Path
import hashlib,json,re,shutil,struct,sys,zipfile
root=Path(__file__).resolve().parents[2]
args=sys.argv[1:]
opts=dict(zip(args[::2],args[1::2]))
if len(args)%2 or set(opts)-{'--wine','--fex'}: raise SystemExit('usage: package-runtime.py [--wine wine|wine-madeira] [--fex fex|fex-madeira]')
wine=opts.get('--wine','wine'); fex=opts.get('--fex','fex')
if wine not in ('wine','wine-madeira'): raise SystemExit('Unknown Wine component: '+wine)
if fex not in ('fex','fex-madeira'): raise SystemExit('Unknown FEX component: '+fex)
out=root/'.android-out'; stage=out/'bundle'
if stage.exists(): shutil.rmtree(stage)
stage.mkdir(parents=True)
for name in (wine,fex):
    # debug/: unstripped copies for the runtime test's stack traces, not for the APK
    shutil.copytree(out/name,stage,dirs_exist_ok=True,symlinks=False,ignore=shutil.ignore_patterns('debug'))
shutil.copytree(out/'graphics',stage/'graphics',dirs_exist_ok=True)
shutil.copytree(out/'addons',stage,dirs_exist_ok=True)
required=['bin/wine','bin/wineserver','lib/wine/aarch64-unix/wine','lib/wine/aarch64-unix/ntdll.so',
'lib/wine/aarch64-unix/wineandroid.so','lib/wine/aarch64-unix/winevulkan.so',
'lib/wine/aarch64-unix/libarm64ecfex.so','lib/wine/aarch64-unix/libwow64fex.so',
'lib/wine/aarch64-windows/libarm64ecfex.dll','lib/wine/aarch64-windows/libwow64fex.dll',
'lib/wine/aarch64-windows/kernel32.dll','lib/wine/i386-windows/kernel32.dll']
required += ['addons/dock/dockhost.exe','addons/xinput/x64/xinput_pocket.dll','addons/xinput/x86/xinput_pocket.dll',
             'share/licenses/madeira-dock/LICENSE']
for arch in ('x64','x86'):
    required += [f'graphics/dxvk/{arch}/{n}.dll' for n in ('d3d9','d3d10core','d3d11','dxgi')]
    required += [f'graphics/vkd3d/{arch}/{n}.dll' for n in ('d3d12','d3d12core')]
    required += [f'graphics/smoke/{arch}/{n}.exe' for n in ('hello','d3d11','d3d12-device')]
for name in required:
    if not (stage/name).is_file(): raise SystemExit('Missing required component: '+name)
for p in stage.rglob('*'):
    if not p.is_file(): continue
    with p.open('rb') as f:
        h=f.read(64)
        if h[:4]==b'\x7fELF':
            if h[4:6]!=b'\x02\x01' or struct.unpack_from('<H',h,18)[0]!=183:
                raise SystemExit('Non-AArch64 ELF in runtime: '+str(p))
            offset=struct.unpack_from('<Q',h,32)[0]
            size,count=struct.unpack_from('<HH',h,54)
            for i in range(count):
                f.seek(offset+i*size);ph=f.read(size)
                if struct.unpack_from('<I',ph)[0]==3:
                    start=struct.unpack_from('<Q',ph,8)[0];length=struct.unpack_from('<Q',ph,32)[0]
                    f.seek(start);interp=f.read(length).rstrip(b'\0')
                    if interp!=b'/system/bin/linker64':raise SystemExit('Non-bionic interpreter: '+str(p))
        elif p.suffix.lower() in ('.dll','.exe'):
            if h[:2]!=b'MZ' or len(h)<64:raise SystemExit('Invalid PE: '+str(p))
            f.seek(struct.unpack_from('<I',h,60)[0]);pe=f.read(6)
            if pe[:4]!=b'PE\0\0':raise SystemExit('Invalid PE signature: '+str(p))
            machine=struct.unpack_from('<H',pe,4)[0]
            rel=p.relative_to(stage).as_posix()
            expected={0x14c} if '/x86/' in rel or '/i386-windows/' in rel else {0x8664} if '/x64/' in rel else {0xa641,0xa64e,0xaa64,0x8664}
            if machine not in expected:raise SystemExit('Wrong PE architecture: '+rel)

# Android 9 loadability for GStreamer's GL library, and drop libraries Wine never reaches.
import subprocess as _sp
_sp.run([sys.executable, str(root/'runtime/android/slim-runtime.py'), str(stage)], check=True)

# --- Android loader fixes and checks -----------------------------------------
# Termux libraries (and the Proton fork's Wine) carry DT_RUNPATH
# /data/data/com.termux/files/usr/lib, which does not exist on a device. The
# first Wine process is the app's own :wine process, where LD_LIBRARY_PATH is
# not consulted for System.load()ed code, so Wine's dlopen of e.g.
# libfreetype.so needs a RUNPATH that points into this bundle. The new path is
# shorter than the old one, so it is written in place in .dynstr.
def elf_dynamic(data):
    phoff=struct.unpack_from('<Q',data,32)[0]; phsize,phcount=struct.unpack_from('<HH',data,54)
    loads=[];dynamic=None;aligns=[]
    for i in range(phcount):
        o=phoff+i*phsize
        ptype,_,offset,vaddr,_,filesz,_,align=struct.unpack_from('<IIQQQQQQ',data,o)
        if ptype==1: loads.append((vaddr,offset,filesz)); aligns.append(align)
        elif ptype==2: dynamic=(offset,filesz)
    return loads,dynamic,aligns

def vaddr_to_offset(loads,addr):
    for vaddr,offset,filesz in loads:
        if vaddr<=addr<vaddr+filesz: return offset+addr-vaddr
    raise ValueError('address outside PT_LOAD')

def rewrite_runpath(path,new):
    data=bytearray(path.read_bytes())
    loads,dynamic,_=elf_dynamic(data)
    if not dynamic: return False
    entries=[struct.unpack_from('<qQ',data,dynamic[0]+i) for i in range(0,dynamic[1],16)]
    strtab=next((v for t,v in entries if t==5),None)
    changed=False
    for tag,value in entries:
        if tag in (15,29) and strtab is not None:   # DT_RPATH, DT_RUNPATH
            start=vaddr_to_offset(loads,strtab)+value
            end=data.index(b'\0',start)
            old=bytes(data[start:end])
            if b'com.termux' not in old: continue
            if len(new)>len(old): raise SystemExit(f'RUNPATH {old!r} too short to rewrite in {path}')
            data[start:end]=new.encode()+b'\0'*(len(old)-len(new))
            changed=True
    if changed: path.write_bytes(bytes(data))
    return changed

rewritten=0; unaligned=[]
for p in sorted(stage.rglob('*')):
    if not p.is_file() or p.read_bytes()[:4]!=b'\x7fELF': continue
    rel=p.relative_to(stage)
    parts=rel.parts
    if parts[:1]==('lib',) and len(parts)==2: runpath='$ORIGIN'                      # lib/*.so
    elif parts[:3]==('lib','wine','aarch64-unix'): runpath='$ORIGIN/../..'           # lib/wine/aarch64-unix/*
    elif parts[:1]==('bin',): runpath='$ORIGIN/../lib'
    else: runpath='$ORIGIN'
    if rewrite_runpath(p,runpath): rewritten+=1
    _,_,aligns=elf_dynamic(p.read_bytes())
    if aligns and min(aligns)<0x4000: unaligned.append(rel.as_posix())
print(f'Rewrote Termux RUNPATH to bundle-relative paths in {rewritten} ELF files')

# Libraries without any RUNPATH (Mesa's libEGL/libGLESv2/libgallium, pulled in by
# winegstreamer through libgstgl) cannot find their own bundled dependencies:
# bionic does not search a dependent's RUNPATH for a library's NEEDED entries.
# Adding a RUNPATH needs new room in the file, so this uses patchelf, keeping new
# segments 16 KB aligned.
import shutil as _shutil, subprocess as _subprocess
bundled={p.name for p in (stage/'lib').iterdir() if p.is_file()}
added=[]
for p in sorted(stage.rglob('*')):
    if not p.is_file() or p.read_bytes()[:4]!=b'\x7fELF': continue
    info=_subprocess.run(['readelf','-dW',str(p)],capture_output=True,text=True).stdout
    if 'RUNPATH' in info or 'RPATH' in info: continue
    if '(NEEDED)' not in info: continue   # static (wine-preloader): nothing to look up
    needed=re.findall(r'\(NEEDED\).*\[(.*?)\]',info)
    rel=p.relative_to(stage).parts
    # A few Wine unix modules also dlopen() bundled libraries by name (win32u ->
    # libfreetype.so/libfontconfig.so, dwrite -> libfreetype.so, bcrypt/secur32/crypt32 -> libgnutls.so,
    # winebus -> libSDL2.so), so they need a RUNPATH even when none of their
    # DT_NEEDED entries is bundled. Only those: rewriting the layout of the Wine
    # loader or ntdll.so (which sit at addresses Wine reserves) is not worth the risk.
    dlopeners={'win32u.so','dwrite.so','bcrypt.so','secur32.so','crypt32.so','winebus.so'}
    if not any(n in bundled for n in needed) and not (rel[:3]==('lib','wine','aarch64-unix') and p.name in dlopeners): continue
    patchelf=_shutil.which('patchelf')
    if not patchelf: raise SystemExit(f'{p.relative_to(stage)} needs a RUNPATH: install patchelf')
    runpath='$ORIGIN' if rel[:1]==('lib',) and len(rel)==2 else '$ORIGIN/../..' if rel[:3]==('lib','wine','aarch64-unix') else '$ORIGIN/../lib'
    _subprocess.run([patchelf,'--page-size','16384','--set-rpath',runpath,str(p)],check=True)
    added.append(p.relative_to(stage).as_posix())
print('Added RUNPATH to',len(added),'libraries:',', '.join(added))
# 16 KB page devices (Android 15+) cannot map 4 KB-aligned segments of these files.
unaligned=[p.relative_to(stage).as_posix() for p in sorted(stage.rglob('*'))
           if p.is_file() and p.read_bytes()[:4]==b'\x7fELF' and (lambda a: a and min(a)<0x4000)(elf_dynamic(p.read_bytes())[2])]
for rel in unaligned:
    print(f'::warning::{rel} is not 16 KB page aligned; it will not load on 16 KB page devices')

# Wine dlopen()s these by the name configure recorded (android-sonames.py).
# Android has only the unversioned system libraries.
def has_cstring(data,s): return (b'\0'+s+b'\0') in data
win32u=(stage/'lib/wine/aarch64-unix/win32u.so').read_bytes()
for good,bad in ((b'libvulkan.so',b'libvulkan.so.1'),(b'libEGL.so',b'libEGL.so.1')):
    if has_cstring(win32u,bad) or not has_cstring(win32u,good):
        raise SystemExit(f'win32u.so loads {bad.decode()} instead of Android\'s {good.decode()}: rebuild Wine with android-sonames.py')

# Every library must resolve its symbols against what Android 9 (API 28) provides.
_sp.run([sys.executable, str(root/'runtime/android/check-symbols.py'), str(stage)], check=True)

# Fontconfig (used by win32u for system fonts) has Termux's config path compiled in.
# WineLaunch points FONTCONFIG_FILE here instead: Android's fonts, cache in XDG_CACHE_HOME.
(stage/'etc/fonts').mkdir(parents=True,exist_ok=True)
(stage/'etc/fonts/fonts.conf').write_text('<?xml version="1.0"?>\n<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">\n<fontconfig>\n  <dir>/system/fonts</dir>\n  <dir>/product/fonts</dir>\n  <cachedir prefix="xdg">fontconfig</cachedir>\n</fontconfig>\n')

# Re-use the upstream UI matched to this Wine build; alter only startup/settings.
import subprocess
subprocess.run([sys.executable,str(root/'runtime/android/prepare-java.py'),
                str(stage/'integration/WineActivity.java'),
                str(root/'android/app/src/main/java/org/winehq/wine')],check=True)
files={p.relative_to(stage).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
       for p in sorted(stage.rglob('*')) if p.is_file() and p.name!='runtime.json'}
manifest={'schema':2,'abi':'arm64-v8a','kind':'wine-arm64ec-android','wine':wine,'fex':fex,'files':files,
          'sources':json.loads((root/'runtime/android/sources.json').read_text()),
          'validation':'compiled-components-only; device execution not verified'}
(stage/'runtime.json').write_text(json.dumps(manifest,indent=2)+'\n')
assets=root/'android/app/src/main/assets'; assets.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(assets/'madeira-runtime.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(stage.rglob('*')):
        if p.is_file(): z.write(p,p.relative_to(stage))
(assets/'madeira-runtime.sha256').write_text(hashlib.sha256((assets/'madeira-runtime.zip').read_bytes()).hexdigest())
print('Packaged',len(files),'files; native ELF architecture checked. Device tests still required.')
