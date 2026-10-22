#!/usr/bin/env python3
"""Where a Wine process that died by a signal was: every thread's pc and lr
(crashing thread first) as library+offset from the core's file mappings, and
the instructions around the faulting pc. Anonymous mappings (JIT code, PE
images read into memory on Android) are reported by their base address.

usage: core.py EXECUTABLE CORE
"""
import bisect, os, re, subprocess, sys

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

SYMBOLS = os.environ.get('STACK_SYMBOLS')
symcache = {}
def symbol(path, rel):
    # Nearest function symbol, preferring an unstripped copy of the library.
    alt = os.path.join(SYMBOLS, os.path.basename(path)) if SYMBOLS else ''
    src = alt if alt and os.path.isfile(alt) else path
    if src not in symcache:
        syms = []
        for opt in (['-D'], []):
            out = subprocess.run(['nm', '--defined-only', *opt, src], capture_output=True, text=True).stdout
            for l in out.splitlines():
                f = l.split()
                if len(f) == 3 and f[1] in 'tTwW':
                    syms.append((int(f[0], 16), f[2]))
        syms.sort()
        symcache[src] = ([a for a, _ in syms], [n for _, n in syms])
    addrs, names = symcache[src]
    i = bisect.bisect_right(addrs, rel) - 1
    return f' {names[i]}+{rel - addrs[i]:#x}' if i >= 0 and rel - addrs[i] < 0x40000 else ''

def describe(a):
    for lo, hi, off, path in maps:
        if lo <= a < hi:
            if path:
                base = min(l - o for l, h, o, p in maps if p == path)
                return f'{a:#x} {path.rsplit("/", 1)[-1]}+{a - base:#x}{symbol(path, a - base)}'
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
bt = gdb('p/x $x29')
fp = re.search(r'= (0x[0-9a-f]+)', bt)
chain, fp = [], int(fp.group(1), 16) if fp else 0
for depth in range(12):
    if not fp or fp & 7: break
    out = gdb(f'x/2gx {fp:#x}')
    vals = re.findall(r'0x[0-9a-f]{16}', out.split(':', 1)[-1]) if ':' in out else []
    if len(vals) < 2: break
    nxt, ret = int(vals[0], 16), int(vals[1], 16)
    chain.append(f'  #{depth} ' + describe(ret))
    if nxt <= fp: break
    fp = nxt
print('frames of the crashing thread:'); print('\n'.join(chain))
print(gdb('x/10i $pc-20'))

# Died inside Wine's own exception setup (a fault it could not deliver): the
# CONTEXT it was building holds the original fault. Find it among the
# callee-saved registers by its ContextFlags (CONTEXT_ARM64 = 0x400000).
regs = gdb('info registers ' + ' '.join(f'x{i}' for i in range(19, 29)))
tried = []
for name, val in re.findall(r'^(x\d+)\s+(0x[0-9a-f]+)', regs, re.M):
    out = gdb(f'x/34gx {val}')
    words = [int(w, 16) for w in re.findall(r'0x[0-9a-f]+', '\n'.join(l.split(':', 1)[-1] for l in out.splitlines() if ':' in l))]
    tried.append(f'{name}={val}:{words[0]:#x}' if words else f'{name}={val}:-')
    if len(words) < 34 or (words[0] & 0xffffffff) & 0xfff00000 != 0x00400000: continue
    x = words[1:30]   # X0..X28
    fp_, lr, sp, pc = words[30], words[31], words[32], words[33]
    print(f'original fault (CONTEXT at {name}={val}):')
    print('  pc', describe(pc), '| lr', describe(lr))
    print(f'  sp={sp:#x} fp={fp_:#x} x0={x[0]:#x} x1={x[1]:#x} x2={x[2]:#x} x3={x[3]:#x} x8={x[8]:#x}'
          f' x16={x[16]:#x} x17={x[17]:#x} x18={x[18]:#x} x28={x[28]:#x}')
    print(gdb(f'x/10i {pc - 20:#x}'))
    break
else:
    print('no CONTEXT among', ' '.join(tried))
