#!/usr/bin/env python3
"""Fetch a page-size-agnostic bionic (Android 15+) for the 16 KB page test.

Android 9's linker (Termux aosp-libs, used by the 4 KB runtime test) rounds
segments to 4 KB and cannot load anything on a 16 KB page kernel; real 16 KB
devices run Android 15 or later, whose linker reads the page size at run time.
This takes linker64 and bionic from Google's arm64 Android emulator image: the
image's system partition, then the runtime APEX inside it.

usage: get-bionic.py OUT_DIR [API]
"""
import io, os, re, shutil, struct, subprocess, sys, tempfile, urllib.request, xml.etree.ElementTree as ET, zipfile
from pathlib import Path

out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
want_api = int(sys.argv[2]) if len(sys.argv) > 2 else 35
REPO = 'https://dl.google.com/android/repository/sys-img/google_apis/'

def log(*a): print('[bionic]', *a, flush=True)

def find_archive():
    xml = urllib.request.urlopen(REPO + 'sys-img2-3.xml', timeout=60).read()
    root = ET.fromstring(xml)
    best = None
    for pkg in root.iter():
        if not pkg.tag.endswith('remotePackage'): continue
        path = pkg.get('path', '')
        m = re.fullmatch(r'system-images;android-(\d+);google_apis;arm64-v8a', path)
        if not m or int(m.group(1)) < want_api: continue
        api = int(m.group(1))
        for url in pkg.iter():
            if url.tag.endswith('url') and url.text and url.text.endswith('.zip'):
                if best is None or api < best[0]: best = (api, url.text.strip())
    if not best: raise SystemExit(f'no arm64 google_apis image for API >= {want_api}')
    return best

def magic(path):
    with open(path, 'rb') as f:
        head = f.read(8192)
    if head[:4] == b'\x3a\xff\x26\xed': return 'sparse'
    if head[512:520] == b'EFI PART': return 'gpt'
    if len(head) >= 1082 and head[1080:1082] == b'\x53\xef': return 'ext4'
    if len(head) >= 1028 and struct.unpack('<I', head[1024:1028])[0] == 0xE0F5E1E2: return 'erofs'
    if head[:4] == b'PK\x03\x04': return 'zip'
    if head[0x1000:0x1004] == b'\x67\x44\x6c\x61': return 'super'
    return 'unknown:' + head[:16].hex()

def gpt_partitions(path):
    with open(path, 'rb') as f:
        f.seek(512); hdr = f.read(92)
        lba, n, size = struct.unpack('<QII', hdr[72:88])
        f.seek(lba * 512); table = f.read(n * size)
    parts = []
    for i in range(n):
        e = table[i * size:(i + 1) * size]
        first, last = struct.unpack('<QQ', e[32:48])
        if not first: continue
        name = e[56:128].decode('utf-16-le').rstrip('\0')
        parts.append((name, first * 512, (last - first + 1) * 512))
    return parts

def super_partitions(path):
    # Android dynamic partitions (liblp): geometry at 4096 (backup 8192), metadata at 12288.
    with open(path, 'rb') as f:
        f.seek(12288); h = f.read(256)  # reserved 4K + two geometry copies
        assert struct.unpack('<I', h[:4])[0] == 0x414C5030, 'not liblp metadata'
        header_size = struct.unpack('<I', h[8:12])[0]
        tables_size = struct.unpack('<I', h[44:48])[0]
        def desc(off): return struct.unpack('<III', h[off:off + 12])
        p_off, p_num, p_sz = desc(80); e_off, e_num, e_sz = desc(92)
        f.seek(12288 + header_size); tables = f.read(tables_size)
    exts = [struct.unpack('<QIQI', tables[e_off + i * e_sz:e_off + i * e_sz + 24]) for i in range(e_num)]
    parts = []
    for i in range(p_num):
        p = tables[p_off + i * p_sz:p_off + (i + 1) * p_sz]
        name = p[:36].split(b'\0')[0].decode()
        first, count = struct.unpack('<II', p[40:48])
        pieces = [(exts[j][2] * 512, exts[j][0] * 512) for j in range(first, first + count) if exts[j][1] == 0]
        parts.append((name, pieces))
    return parts

def cut(src, dst, pieces):
    with open(src, 'rb') as f, open(dst, 'wb') as o:
        for off, length in pieces:
            f.seek(off); left = length
            while left:
                b = f.read(min(left, 1 << 24)); o.write(b); left -= len(b)

def extract_fs(img, files, dest):
    """Copy FILES (paths inside the filesystem) to DEST; returns what was found."""
    kind = magic(img)
    found = {}
    if kind == 'ext4':
        for p in files:
            target = dest / p.replace('/', '_')
            r = subprocess.run(['debugfs', '-R', f'dump -p {p} {target}', str(img)], capture_output=True, text=True)
            if target.exists() and target.stat().st_size: found[p] = target
    elif kind == 'erofs':
        tmp = Path(tempfile.mkdtemp(dir=dest))
        subprocess.run(['fsck.erofs', f'--extract={tmp}', str(img)], check=True, capture_output=True)
        for p in files:
            src = tmp / p.lstrip('/')
            if src.is_file():
                target = dest / p.replace('/', '_'); shutil.copyfile(src, target); found[p] = target
    else:
        raise SystemExit(f'{img}: unsupported filesystem {kind}')
    return found

def system_partition(img, work):
    kind = magic(img); log(img.name, kind)
    if kind == 'sparse':
        raw = work / (img.name + '.raw'); subprocess.run(['simg2img', str(img), str(raw)], check=True); img.unlink()
        return system_partition(raw, work)
    if kind == 'gpt':
        parts = gpt_partitions(img); log('gpt', [(n, s) for n, o, s in parts])
        name, off, size = next((p for p in parts if p[0].startswith('system')), None) or max(parts, key=lambda p: p[2])
        part = work / f'{name}.img'; cut(img, part, [(off, size)]); img.unlink()
        return system_partition(part, work)
    if kind == 'super':
        parts = super_partitions(img); log('super', [n for n, _ in parts])
        name, pieces = next(p for p in parts if re.fullmatch(r'system(_a)?', p[0]))
        part = work / f'{name}.img'; cut(img, part, pieces); img.unlink()
        return system_partition(part, work)
    return img

api, url = find_archive()
log('API', api, url)
work = Path(tempfile.mkdtemp())
zpath = work / 'image.zip'
with urllib.request.urlopen(REPO + url, timeout=600) as r, open(zpath, 'wb') as f:
    shutil.copyfileobj(r, f, 1 << 24)
with zipfile.ZipFile(zpath) as z:
    names = z.namelist(); log('zip', [n for n in names if n.endswith('.img')])
    sysname = next(n for n in names if n.endswith('/system.img') or n == 'system.img')
    z.extract(sysname, work)
zpath.unlink()
system = system_partition(work / sysname, work)

lib64 = ['libc++.so', 'liblog.so', 'ld-android.so', 'libdl_android.so']
want = [f'/system/apex/com.android.runtime.apex', f'/system/apex/com.android.runtime.capex'] + [f'/system/lib64/{l}' for l in lib64]
found = extract_fs(system, want + [w.replace('/system/', '/', 1) for w in want], work)
log('system files', sorted(found))
apex = next((v for k, v in found.items() if 'runtime' in k), None)
if not apex: raise SystemExit('runtime APEX not found')
with zipfile.ZipFile(apex) as z:
    if 'original_apex' in z.namelist():
        inner = work / 'original.apex'; inner.write_bytes(z.read('original_apex')); z2 = zipfile.ZipFile(inner)
    else:
        z2 = z
    payload = work / 'apex_payload.img'; payload.write_bytes(z2.read('apex_payload.img'))
bionic = [f'/lib64/bionic/{n}' for n in ('libc.so', 'libm.so', 'libdl.so', 'libdl_android.so')] + ['/bin/linker64']
got = extract_fs(payload, bionic, work)
log('apex files', sorted(got))
(out / 'lib64').mkdir(exist_ok=True)
for k, v in got.items():
    shutil.copyfile(v, out / ('linker64' if k.endswith('linker64') else 'lib64/' + Path(k).name))
for k, v in found.items():
    if k.endswith('.so'): shutil.copyfile(v, out / 'lib64' / Path(k).name)
os.chmod(out / 'linker64', 0o755)
(out / 'VERSION').write_text(f'android-{api} {url}\n')
log('done:', sorted(str(p.relative_to(out)) for p in out.rglob('*') if p.is_file()))
