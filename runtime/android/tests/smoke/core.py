#!/usr/bin/env python3
"""Where a Wine process that died by a signal was: every thread's pc and lr
(crashing thread first) as library+offset from the core's file mappings, and
the instructions around the faulting pc. Anonymous mappings (JIT code, PE
images read into memory on Android) are reported by their base address.

usage: core.py EXECUTABLE CORE
"""
import re, subprocess, sys

exe, core = sys.argv[1], sys.argv[2]
def gdb(*cmds):
    args = ['gdb', '-batch', '-nx', exe, core]
    for c in cmds: args += ['-ex', c]
    return subprocess.run(args, capture_output=True, text=True, timeout=120).stdout

maps = []
for line in gdb('info proc mappings').splitlines():
    f = line.split()
    if len(f) >= 4 and f[0].startswith('0x'):
        try:
            lo, hi, off = int(f[0], 16), int(f[1], 16), int(f[3], 16)
        except ValueError:
            continue
        maps.append((lo, hi, off, f[4] if len(f) > 4 else ''))

def describe(a):
    for lo, hi, off, path in maps:
        if lo <= a < hi:
            if path:
                base = min(l - o for l, h, o, p in maps if p == path)
                return f'{a:#x} {path.rsplit("/", 1)[-1]}+{a - base:#x}'
            return f'{a:#x} [anon {lo:#x}-{hi:#x}]'
    return f'{a:#x} [not in a file mapping]'

head = gdb('p $_siginfo.si_signo', 'p/x $_siginfo._sifields._sigfault.si_addr')
print('signal / fault address:', ' '.join(l.split('=', 1)[-1].strip() for l in head.splitlines() if '=' in l))
regs = gdb('thread apply all info registers pc lr sp x18 x0 x1 x16 x17')
for block in re.split(r'\nThread ', '\n' + regs)[1:6]:
    r = dict(re.findall(r'^(pc|lr|sp|x18|x0|x1|x16|x17)\s+(0x[0-9a-f]+)', block, re.M))
    if 'pc' not in r: continue
    print('Thread', block.splitlines()[0][:60])
    print('  pc', describe(int(r['pc'], 16)), '| lr', describe(int(r['lr'], 16)))
    print('  ' + ' '.join(f'{k}={r[k]}' for k in ('sp', 'x18', 'x0', 'x1', 'x16', 'x17') if k in r))
print(gdb('x/10i $pc-20'))
