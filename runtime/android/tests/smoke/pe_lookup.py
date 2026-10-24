# gdb python: name the PE module (and RVA) of each address in $PE_ADDRS by
# scanning back for its MZ/PE header. Wine on Android reads PE images into
# anonymous memory, so the core's file mappings cannot name them.
import os, struct
import gdb
inf = gdb.selected_inferior()
def rd(a, n):
    try: return bytes(inf.read_memory(a, n))
    except Exception: return None
for tok in os.environ.get('PE_ADDRS', '').split():
    a = int(tok, 16)
    base = a & ~0xfff
    found = None
    for _ in range(8192):          # up to 32 MB back
        h = rd(base, 2)
        if h == b'MZ':
            e = rd(base + 0x3c, 4)
            if e:
                pe = struct.unpack('<I', e)[0]
                if pe < 0x1000 and rd(base + pe, 4) == b'PE\0\0':
                    found = base; break
        base -= 0x1000
        if base <= 0: break
    if not found:
        print(f'PE {a:#x}: no image'); continue
    pe = struct.unpack('<I', rd(found + 0x3c, 4))[0]
    exp_rva = struct.unpack('<I', rd(found + pe + 24 + 112, 4))[0]
    name = '?'
    if exp_rva:
        nrva = struct.unpack('<I', rd(found + exp_rva + 12, 4))[0]
        raw = rd(found + nrva, 64) or b''
        name = raw.split(b'\0')[0].decode(errors='replace')
    print(f'PE {a:#x}: {name}+{a - found:#x}')
