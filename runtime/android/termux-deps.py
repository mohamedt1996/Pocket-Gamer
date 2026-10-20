#!/usr/bin/env python3
"""Stage Wine's bionic build/runtime libraries from the official Termux repos.

Replaces the prebuilt GameNative/termux-on-gha tarball. Resolves the
dependency closure of ROOTS from the official Termux apt indexes, downloads
each .deb, verifies its SHA256 against the index and extracts it with
dpkg-deb. The result has the same layout Wine's build scripts expect:
DEST/data/data/com.termux/files/usr/...

Termux is a rolling repository without snapshots, so the exact package set is
recorded in share/licenses/termux-packages.txt inside the staged prefix and is
shipped with the runtime's licence bundle.

usage: termux-deps.py DEST [--index-dir DIR] [--resolve-only]
"""
from pathlib import Path
import argparse, gzip, hashlib, lzma, re, shutil, subprocess, sys, tempfile

# Official Termux repositories (aarch64). termux-main and termux-x11 are the
# only sources needed: every root below resolves inside these two. Each lists
# Termux's own mirrors in order; packages are verified against the index's
# SHA256, so any mirror that answers is equally trustworthy.
REPOS = [
    (['https://packages-cf.termux.dev/apt/termux-main',
      'https://packages.termux.dev/apt/termux-main',
      'https://mirror.grimler.se/termux/termux-main'], 'stable', 'main'),
    (['https://packages-cf.termux.dev/apt/termux-x11',
      'https://packages.termux.dev/apt/termux-x11',
      'https://mirror.grimler.se/termux/termux-x11'], 'x11', 'main'),
]
ARCH = 'aarch64'

# Libraries Wine's Android build links or dlopens (headers + .so). Matches
# the library set GameNative's termux-on-gha installed, without its build
# tools (clang, python, git, ninja, nodejs).
ROOTS = '''
alsa-lib dbus ffmpeg fontconfig freetype glib gnutls gst-plugins-base
gst-plugins-good gstreamer krb5 libandroid-shmem libandroid-spawn libc++
libgmp libgnutls libltdl libsndfile libsoxr libx11 libxcb libxcomposite
libxcursor libxext libxfixes libxi libxrandr libxrender mesa opengl
pulseaudio sdl2 speexdsp vulkan-loader xorg-xrandr xorgproto
'''.split()

# Termux base/shell packages that some libraries list as dependencies but
# that contribute nothing Wine builds against or loads.
SKIP = {'bash', 'coreutils', 'dash', 'termux-tools', 'termux-exec', 'termux-am',
        'termux-am-socket', 'termux-keyring', 'termux-licenses', 'apt', 'dpkg',
        'sed', 'grep', 'findutils', 'procps', 'psmisc', 'less', 'gawk', 'which',
        'debianutils', 'termux-core'}


def parse_index(text, base):
    entries = []
    for block in text.split('\n\n'):
        fields, key = {}, None
        for line in block.splitlines():
            if line[:1] in (' ', '\t') and key:
                fields[key] += '\n' + line.strip()
            elif ':' in line:
                key, value = line.split(':', 1)
                fields[key] = value.strip()
        if 'Package' in fields:
            fields['_base'] = base
            entries.append(fields)
    return entries


def curl(url, dest):
    """Download with curl, the client setup-tools.sh already uses on CI.
    Python's urllib could not fetch the Termux index from GitHub runners,
    while apt-based builds there can. Raises RuntimeError with curl's own
    message on failure."""
    result = subprocess.run(['curl', '-fsSL', '--retry', '3', '--retry-delay', '2',
                             '--connect-timeout', '30', '-o', str(dest), url],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or f'curl exit {result.returncode}')


def fetch_index(mirrors, dist, comp, index_dir):
    """Return (index text, mirror base that served it)."""
    if index_dir:
        host = mirrors[0].split('://', 1)[1].replace('/', '_')
        return (Path(index_dir) / f'{host}_dists_{dist}_{comp}_binary-{ARCH}_Packages').read_text(), mirrors[0]
    errors = []
    with tempfile.TemporaryDirectory() as tmp:
        for base in mirrors:
            url = f'{base}/dists/{dist}/{comp}/binary-{ARCH}/Packages'
            for suffix, decode in (('.xz', lzma.decompress), ('.gz', gzip.decompress), ('', lambda b: b)):
                dest = Path(tmp) / 'Packages'
                try:
                    curl(url + suffix, dest)
                    return decode(dest.read_bytes()).decode(), base
                except (RuntimeError, lzma.LZMAError, OSError, EOFError, UnicodeDecodeError) as e:
                    errors.append(f'  {url}{suffix}: {e}')
    raise SystemExit('Cannot download the Termux index from any mirror:\n' + '\n'.join(errors))


def version_key(v):
    # Good enough for picking the newest of duplicate entries in one index.
    return [int(x) if x.isdigit() else x for x in re.split(r'(\d+)', v)]


def resolve(entries):
    by_name, provides = {}, {}
    for e in entries:
        name = e['Package']
        if name not in by_name or version_key(e['Version']) > version_key(by_name[name]['Version']):
            by_name[name] = e
        for p in e.get('Provides', '').split(','):
            p = p.strip().split(' ')[0]
            if p:
                provides.setdefault(p, name)
    chosen, queue = {}, list(ROOTS)
    while queue:
        want = queue.pop()
        name = want if want in by_name else provides.get(want)
        if name is None:
            raise SystemExit(f'Termux package not found: {want}')
        if name in chosen or name in SKIP:
            continue
        chosen[name] = by_name[name]
        deps = ','.join(chosen[name].get(k, '') for k in ('Pre-Depends', 'Depends'))
        for clause in deps.split(','):
            options = [o.strip().split(' ')[0].split(':')[0] for o in clause.split('|') if o.strip()]
            if not options:
                continue
            pick = next((o for o in options if o in by_name or o in provides), None)
            if pick is None:
                raise SystemExit(f'Unresolvable dependency {clause.strip()!r} of {name}')
            queue.append(pick)
    return chosen


def download(entry, cache):
    """Fetch one .deb, trying the serving mirror first, then the others."""
    dest = cache / Path(entry['Filename']).name
    errors = []
    for base in [entry['_base']] + [m for m in entry['_mirrors'] if m != entry['_base']]:
        url = f"{base}/{entry['Filename']}"
        try:
            curl(url, dest)
        except RuntimeError as e:
            errors.append(f'  {url}: {e}')
            continue
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest == entry['SHA256']:
            return dest
        errors.append(f"  {url}: SHA256 {digest} != {entry['SHA256']}")
        dest.unlink()
    raise SystemExit(f"Cannot download {entry['Package']}:\n" + '\n'.join(errors))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dest')
    ap.add_argument('--index-dir', help='read apt Packages files from here (tests)')
    ap.add_argument('--resolve-only', action='store_true')
    args = ap.parse_args()
    entries = []
    for mirrors, dist, comp in REPOS:
        text, base = fetch_index(mirrors, dist, comp, args.index_dir)
        repo_entries = parse_index(text, base)
        for e in repo_entries:
            e['_mirrors'] = mirrors
        entries += repo_entries
    chosen = resolve(entries)
    manifest = ''.join(f"{n} {e['Version']} {e['SHA256']} {e['_base']}/{e['Filename']}\n"
                       for n, e in sorted(chosen.items()))
    if args.resolve_only:
        sys.stdout.write(manifest)
        return
    dest = Path(args.dest).resolve()
    dest.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, entry in sorted(chosen.items()):
            deb = download(entry, Path(tmp))
            subprocess.run(['dpkg-deb', '-x', str(deb), str(dest)], check=True)
            deb.unlink()
    usr = dest / 'data/data/com.termux/files/usr'
    if not (usr / 'lib').is_dir():
        raise SystemExit(f'Termux prefix missing after extraction: {usr}')
    licenses = usr / 'share/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    (licenses / 'termux-packages.txt').write_text(manifest)
    # Termux ships each package's licence as share/doc/<pkg>/copyright;
    # collect-libs.py bundles share/licenses, so gather them there.
    for name in chosen:
        copyright = usr / 'share/doc' / name / 'copyright'
        if copyright.is_file():
            (licenses / name).mkdir(exist_ok=True)
            shutil.copyfile(copyright, licenses / name / 'copyright')
    print(f'Staged {len(chosen)} official Termux packages into {usr}')


if __name__ == '__main__':
    main()
