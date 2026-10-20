#!/usr/bin/env python3
"""Assemble CI outputs, checking native architecture and mandatory components.

usage: package-runtime.py [--wine wine|wine-madeira]
--wine picks the Wine component under .android-out/: "wine" (Proton Android
fork, the default) or "wine-madeira" (Madeira's Wine, experimental).
"""
from pathlib import Path
import hashlib,json,shutil,struct,sys,zipfile
root=Path(__file__).resolve().parents[2]
args=sys.argv[1:]
wine='wine'
if args[:1]==['--wine'] and len(args)==2: wine=args[1]
elif args: raise SystemExit('usage: package-runtime.py [--wine wine|wine-madeira]')
if wine not in ('wine','wine-madeira'): raise SystemExit('Unknown Wine component: '+wine)
out=root/'.android-out'; stage=out/'bundle'
if stage.exists(): shutil.rmtree(stage)
stage.mkdir(parents=True)
for name in (wine,'fex'):
    shutil.copytree(out/name,stage,dirs_exist_ok=True,symlinks=False)
shutil.copytree(out/'graphics',stage/'graphics',dirs_exist_ok=True)
shutil.copytree(out/'addons',stage,dirs_exist_ok=True)
required=['bin/wine','bin/wineserver','lib/wine/aarch64-unix/ntdll.so',
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
# Re-use the upstream UI matched to this Wine build; alter only startup/settings.
import subprocess
subprocess.run([sys.executable,str(root/'runtime/android/prepare-java.py'),
                str(stage/'integration/WineActivity.java'),
                str(root/'android/app/src/main/java/org/winehq/wine')],check=True)
files={p.relative_to(stage).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
       for p in sorted(stage.rglob('*')) if p.is_file() and p.name!='runtime.json'}
manifest={'schema':2,'abi':'arm64-v8a','kind':'wine-arm64ec-android','wine':wine,'files':files,
          'sources':json.loads((root/'runtime/android/sources.json').read_text()),
          'validation':'compiled-components-only; device execution not verified'}
(stage/'runtime.json').write_text(json.dumps(manifest,indent=2)+'\n')
assets=root/'android/app/src/main/assets'; assets.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(assets/'madeira-runtime.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(stage.rglob('*')):
        if p.is_file(): z.write(p,p.relative_to(stage))
(assets/'madeira-runtime.sha256').write_text(hashlib.sha256((assets/'madeira-runtime.zip').read_bytes()).hexdigest())
print('Packaged',len(files),'files; native ELF architecture checked. Device tests still required.')
