#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,sys
base=Path(__file__).resolve().parent;component=sys.argv[1]
lock=json.loads((base/'sources.json').read_text())
source_names={'wine':['wine'],'wine-madeira':['madeira-wine','madeira','directaudio'],'fex':['fex'],'graphics':['dxvk','vkd3d']}[component]
paths=['fetch.py','setup-tools.sh',f'build-{component}.sh']
paths += {'wine':['patch-wine.py','collect-libs.py','termux-deps.py','patches/wineandroid_vulkan.c'],
          'wine-madeira':['patch-wine.py','collect-libs.py','termux-deps.py','patches/wineandroid_vulkan.c']
                         +sorted('madeira/patches/'+p.name for p in (base/'madeira/patches').glob('*.patch')),
          'fex':['patches/fex_android_shm.h'],
          'graphics':['tests/hello.c','tests/d3d11.c','tests/d3d12-device.c']}[component]
h=hashlib.sha256(json.dumps({n:lock[n] for n in source_names},sort_keys=True).encode())
for name in sorted(paths):h.update(name.encode());h.update((base/name).read_bytes())
print(h.hexdigest())
