#!/usr/bin/env python3
"""Source validation only. This does not claim an Android or Wine runtime pass."""
from pathlib import Path
import json, py_compile, shutil, subprocess, sys, tempfile
root=Path(__file__).resolve().parents[3]
for p in (root/'runtime/android').glob('*.py'): py_compile.compile(str(p),doraise=True)
for p in (root/'runtime/android').glob('*.sh'): subprocess.run(['bash','-n',str(p)],check=True)
lock=json.loads((root/'runtime/android/sources.json').read_text())
assert set(lock)=={'wine','fex','dxvk','vkd3d','ntsync'}
for value in lock.values():
    assert value['url'].startswith('https://github.com/')
    assert len(value['commit'])==40 and all(c in '0123456789abcdef' for c in value['commit'])
# Compile and exercise the Android POSIX-shm replacement independently of FEX.
with tempfile.TemporaryDirectory() as temp:
    td=Path(temp);source=td/'shm.c';exe=td/'shm'
    header=root/'runtime/android/patches/fex_android_shm.h'
    source.write_text('#include "'+str(header)+'"\n'+r'''
#include <sys/stat.h>
int main(int argc,char **argv) {
    (void)argc;
    setenv("TMPDIR",argv[1],1);
    int fd=madeira_shm_open("fex-test",O_CREAT|O_RDWR|O_TRUNC,0777);
    if(fd<0)return 1;
    if(ftruncate(fd,4096))return 2;
    struct stat st;if(fstat(fd,&st)||st.st_size!=4096||(st.st_mode&0777)!=0600)return 3;
    close(fd);
    fd=madeira_shm_open("fex-test",O_RDWR,0777);if(fd<0)return 4;close(fd);
    if(madeira_shm_open("../escape",O_CREAT|O_RDWR,0600)!=-1)return 5;
    if(madeira_shm_unlink("fex-test"))return 6;
    return 0;
}
''')
    subprocess.run(['gcc','-Wall','-Wextra',str(source),'-o',str(exe)],check=True)
    subprocess.run([str(exe),temp],check=True)
print('PASS: Python, shell, source-lock validation and compiled shared-memory adapter test')
