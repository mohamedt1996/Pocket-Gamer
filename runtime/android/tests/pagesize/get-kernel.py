#!/usr/bin/env python3
"""Fetch Debian's arm64 16 KB-page kernel and the modules a 9p/overlay root needs.

Writes OUT/Image (as packaged: QEMU unpacks gzip and EFI zboot images),
OUT/modules/*.ko (decompressed) and OUT/modules.order (load order).

usage: get-kernel.py OUT_DIR
"""
import lzma, re, shutil, subprocess, sys, tempfile, urllib.request
from pathlib import Path

out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
MIRROR = 'https://deb.debian.org/debian/'
WANT = ['virtio_pci', 'virtio_mmio', '9pnet_virtio', '9p', 'overlay', 'virtio_blk']

def log(*a): print('[kernel]', *a, flush=True)

pkgs = None
for dist in ('trixie', 'trixie-backports', 'sid'):
    try:
        text = lzma.decompress(urllib.request.urlopen(f'{MIRROR}dists/{dist}/main/binary-arm64/Packages.xz', timeout=120).read()).decode()
    except Exception as e:
        log(dist, 'index failed:', e); continue
    cands = []
    for block in text.split('\n\n'):
        m = re.search(r'^Package: (linux-image-(\d+\.\d+)[^\s]*-arm64-16k(?:-unsigned)?)$', block, re.M)
        if not m: continue
        fn = re.search(r'^Filename: (\S+)$', block, re.M).group(1)
        ver = tuple(int(x) for x in re.findall(r'\d+', m.group(1))[:4])
        cands.append((ver, m.group(1), fn))
    if cands:
        pkgs = max(cands); log(dist, 'chose', pkgs[1]); break
if not pkgs: raise SystemExit('no arm64-16k kernel package found')

work = Path(tempfile.mkdtemp())
deb = work / 'k.deb'
with urllib.request.urlopen(MIRROR + pkgs[2], timeout=600) as r, open(deb, 'wb') as f:
    shutil.copyfileobj(r, f)
root = work / 'root'
subprocess.run(['dpkg-deb', '-x', str(deb), str(root)], check=True)
vmlinuz = next((root / 'boot').glob('vmlinuz-*'))
shutil.copyfile(vmlinuz, out / 'Image')
kver = vmlinuz.name[len('vmlinuz-'):]
moddir = next(root.glob(f'*lib/modules/{kver}'))
log('kernel', kver, 'config page size:', subprocess.run(['grep', '-h', 'CONFIG_ARM64_.*K_PAGES=y', *root.glob('boot/config-*')], capture_output=True, text=True).stdout.strip())

files = {}
for p in moddir.rglob('*.ko*'):
    files[re.sub(r'\.ko(\.xz|\.zst|\.gz)?$', '', p.name).replace('-', '_')] = p
mods = out / 'modules'; mods.mkdir(exist_ok=True)
order, seen = [], set()
def decompressed(name):
    src = files[name]; dst = mods / f'{name}.ko'
    if not dst.exists():
        data = src.read_bytes()
        if src.suffix == '.xz': data = lzma.decompress(data)
        elif src.suffix == '.zst': data = subprocess.run(['zstd', '-dc', str(src)], capture_output=True, check=True).stdout
        dst.write_bytes(data)
    return dst
def add(name):
    if name in seen or name not in files: return
    seen.add(name)
    deps = subprocess.run(['modinfo', '-F', 'depends', str(decompressed(name))], capture_output=True, text=True).stdout.strip()
    for d in filter(None, deps.split(',')): add(d.replace('-', '_'))
    order.append(name)
for w in WANT: add(w)
(out / 'modules.order').write_text(''.join(f'{n}.ko\n' for n in order))
log('modules (built-in when absent):', order)
