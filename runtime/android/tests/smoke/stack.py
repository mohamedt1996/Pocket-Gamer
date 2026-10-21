#!/usr/bin/env python3
"""Native stacks of a hung process under bionic, where gdb cannot see the
loaded libraries (Android's linker does not publish _r_debug the way gdb
expects). For each thread: pc and lr from gdb, then the AArch64 frame-pointer
chain read from /proc/PID/mem; every address is matched to its library in
/proc/PID/maps and to the nearest exported symbol. Run as root.

usage: stack.py PID
"""
import bisect, os, re, struct, subprocess, sys

pid = int(sys.argv[1])
maps = []
for line in open(f'/proc/{pid}/maps'):
    f = line.split()
    lo, hi = (int(x, 16) for x in f[0].split('-'))
    maps.append((lo, hi, int(f[2], 16), f[5] if len(f) > 5 else ''))
symcache = {}

SYMBOLS = os.environ.get('STACK_SYMBOLS')

def symbols(path):
    # Prefer an unstripped copy of the same library (static functions get names).
    if SYMBOLS:
        alt = os.path.join(SYMBOLS, os.path.basename(path))
        if os.path.isfile(alt):
            path = alt
    if path not in symcache:
        syms = []
        for opt in (['-D'], []):
            out = subprocess.run(['nm', '--defined-only', *opt, path], capture_output=True, text=True).stdout
            for l in out.splitlines():
                p = l.split()
                if len(p) == 3 and p[1] in 'tTwWiI':
                    syms.append((int(p[0], 16), p[2]))
        syms.sort()
        symcache[path] = ([a for a, _ in syms], [n for _, n in syms])
    return symcache[path]

def first_load(path):
    # file offset of the segment with offset 0 gives the library's base
    return min((lo - off for lo, hi, off, p in maps if p == path), default=None)

def describe(addr):
    for lo, hi, off, path in maps:
        if lo <= addr < hi:
            if not path.startswith('/'):
                return f'{addr:#x} [{path or "anon"}]'
            base = first_load(path)
            rel = addr - base
            addrs, names = symbols(path)
            i = bisect.bisect_right(addrs, rel) - 1
            name = f'{names[i]}+{rel - addrs[i]:#x}' if i >= 0 and rel - addrs[i] < 0x100000 else '?'
            return f'{addr:#x} {path.rsplit("/", 1)[-1]}+{rel:#x} {name}'
    return f'{addr:#x} [unmapped]'

regs = subprocess.run(['gdb', '-p', str(pid), '-batch', '-nx', '-ex', 'set pagination off',
                       '-ex', 'thread apply all info registers pc lr x29 sp'],
                      capture_output=True, text=True, timeout=120).stdout
mem = open(f'/proc/{pid}/mem', 'rb')
def read64(addr):
    try:
        mem.seek(addr); return struct.unpack('<Q', mem.read(8))[0]
    except (OSError, ValueError, struct.error):
        return None

for block in re.split(r'\nThread ', '\n' + regs)[1:]:
    head = block.splitlines()[0]
    r = dict(re.findall(r'^(pc|lr|x29|sp)\s+(0x[0-9a-f]+)', block, re.M))
    if 'pc' not in r: continue
    print(f'Thread {head}')
    print('  pc', describe(int(r['pc'], 16)))
    print('  lr', describe(int(r['lr'], 16)))
    fp = int(r.get('x29', '0x0'), 16)
    for depth in range(24):
        if not fp or fp & 7: break
        nxt, ret = read64(fp), read64(fp + 8)
        if ret is None: break
        print(f'  #{depth}', describe(ret))
        if not nxt or nxt <= fp: break
        fp = nxt
