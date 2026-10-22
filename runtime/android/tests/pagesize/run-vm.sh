#!/usr/bin/env bash
# The Android runtime test on a 16 KB page kernel, the page size Android 15+
# devices are moving to. The runner's own filesystem (Android userspace from
# smoke/setup.sh, the APK, the test programs) is shared into a QEMU VM booted
# with Debian's arm64-16k kernel; inside, bionic is swapped for Android 15's
# page-size-agnostic linker and libc, then smoke/run.sh runs unchanged.
#
# usage: run-vm.sh APK TESTS_DIR LOG_DIR
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
APK=$(realpath "$1"); TESTS=$(realpath "$2"); LOGS=$(realpath -m "$3")
WORK=$(mktemp -d); OUT=/vmout
mkdir -p "$LOGS"
note() { echo "::$1 title=$2::$(sed 's/%/%25/g' | head -c 3900 | sed ':a;N;$!ba;s/\n/%0A/g')"; }

sudo mkdir -p "$OUT" /data/data/org.pocketarcade.madeira && sudo chown "$USER" "$OUT"
python3 "$HERE/get-kernel.py" "$WORK/kernel" > "$LOGS/kernel.log" 2>&1 || { note error "16K VM: kernel" < "$LOGS/kernel.log"; exit 1; }
# BIONIC=termux keeps the userspace's own Android 9 bionic (a control; 4 KB kernels only).
if [ "${BIONIC:-}" = termux ]; then mkdir -p "$OUT/bionic/lib64"; echo "termux (Android 9)" > "$OUT/bionic/VERSION"; cp /system/bin/linker64 "$OUT/bionic/"; echo "keeping the userspace's bionic" > "$LOGS/bionic.log"
else python3 "$HERE/get-bionic.py" "$OUT/bionic" > "$LOGS/bionic.log" 2>&1 || { note error "16K VM: Android 15 bionic" < "$LOGS/bionic.log"; exit 1; }; fi
cat "$LOGS/kernel.log" "$LOGS/bionic.log"

# initramfs: busybox, the modules, init.
mkdir -p "$WORK/initrd/bin" "$WORK/initrd/modules" "$WORK/initrd"/{proc,sys,dev}
cp /bin/busybox "$WORK/initrd/bin/busybox"
cp "$WORK/kernel/modules/"*.ko "$WORK/initrd/modules/" 2>/dev/null
cp "$WORK/kernel/modules.order" "$WORK/initrd/"
install -m755 "$HERE/init" "$WORK/initrd/init"
( cd "$WORK/initrd" && find . | cpio -o -H newc --quiet | gzip -1 ) > "$WORK/initrd.gz"

cat > "$OUT/inner.sh" <<INNER
#!/bin/bash
echo "::notice title=16K VM::kernel \$(uname -r), page size \$(getconf PAGESIZE) bytes, SVE vector length \$(cat /proc/sys/abi/sve_default_vector_length 2>/dev/null || echo none) bytes, bionic \$(cat $OUT/bionic/VERSION)"
[ -e /proc/self/exe ] || echo "::error title=16K VM::/proc is not available inside the VM"
# Android 15's bionic in place of Android 9's (/system is a writable copy of the Termux tree).
cp -f $OUT/bionic/linker64 /system/bin/linker64
cp -f $OUT/bionic/lib64/*.so /system/lib64/ 2>/dev/null
/system/bin/linker64 --help >/dev/null 2>&1; echo "linker64 runs: \$?"
{ echo "readlink /proc/self/exe: \$(readlink /proc/self/exe)"; exec 3< /etc/hostname; echo "fd3: \$(readlink /proc/self/fd/3)"; exec 3<&-
  grep -E ' / | /proc | /data' /proc/mounts; ls -la /proc/self/ | head -5
  W=\$(ls -d /data/data/org.pocketarcade.madeira 2>/dev/null); echo "app dir: \$W"; } > $OUT/env.txt 2>&1
echo "::notice title=16K VM environment::\$(sed ':a;N;\$!ba;s/\n/%0A/g' $OUT/env.txt)"
export PATH=/usr/sbin:/usr/bin:/sbin:/bin STACK_SYMBOLS="${STACK_SYMBOLS:-}" SMOKE_TIME_SCALE="${SMOKE_TIME_SCALE:-1}"
cd "$REPO"
# First a bare wineboot with core capture: when Wine cannot start on 16 KB pages, say where.
RT=/data/data/org.pocketarcade.madeira/files/madeira-runtime-v2; P=/tmp/probe-prefix
mkdir -p "\$RT" /tmp/cores && unzip -q -o "$APK" assets/madeira-runtime.zip -d /tmp/apk && unzip -q -o /tmp/apk/assets/madeira-runtime.zip -d "\$RT" && chmod -R u+x "\$RT"
echo '/tmp/cores/core.%p' > /proc/sys/kernel/core_pattern; ulimit -c unlimited
bash runtime/android/tests/smoke/prefix.sh "\$P"   # as the app writes it before the first start
L="\$RT/lib/wine/aarch64-unix/wine"
timeout 1200 env -i HOME=/tmp WINEPREFIX=\$P WINELOADER=\$L WINESERVER=\$RT/bin/wineserver WINEDLLPATH=\$RT/lib/wine PATH=\$RT/bin:/system/bin \
  WINEDEBUG=+timestamp,+pid,warn+seh,warn+module,err+all "\$L" wineboot --init > $OUT/probe.out 2> $OUT/probe.log
rc=\$?
{ echo "wineboot exit \$rc"; grep -v '^linker\|WARNING: linker\|^D \|^I \|iOS-x' $OUT/probe.log | grep -E 'err:|warn:seh|wine:|exception' | tail -30; echo "--- end of log"; grep -v '^linker\|WARNING: linker' $OUT/probe.log | tail -8; } > $OUT/probe.txt
for c in /tmp/cores/core.*; do [ -f "\$c" ] || continue
  python3 runtime/android/tests/smoke/core.py "\$(readlink -f /proc/1/root\$L 2>/dev/null || echo \$L)" "\$c" > $OUT/probe-core.txt 2>&1; break; done
echo "::\$([ \$rc = 0 ] && echo notice || echo error) title=16K VM wineboot::\$(head -c 3500 $OUT/probe.txt | sed ':a;N;\$!ba;s/\n/%0A/g')"
[ -s $OUT/probe-core.txt ] && echo "::error title=16K VM wineboot core::\$(head -c 3800 $OUT/probe-core.txt | sed ':a;N;\$!ba;s/\n/%0A/g')"
if [ \$rc != 0 ]; then
  # Where the first DLL load fails: module and mapping traces of a fresh start.
  rm -rf "\$P"; bash runtime/android/tests/smoke/prefix.sh "\$P"
  timeout 900 env -i HOME=/tmp WINEPREFIX=\$P WINELOADER=\$L WINESERVER=\$RT/bin/wineserver WINEDLLPATH=\$RT/lib/wine PATH=\$RT/bin:/system/bin \
    WINEDEBUG=+module,+loaddll,+file "\$L" wineboot --init > /dev/null 2> $OUT/probe2.log
  { ls -la "\$RT/lib/wine/" "\$RT"/lib/wine/*-windows/kernel32.dll 2>&1 | head -20; grep -n 'kernel32' $OUT/probe2.log | head -40; } > $OUT/probe2.txt
  l=\$(grep -n 'kernel32' $OUT/probe2.log | head -1 | cut -d: -f1)
  [ -n "\$l" ] && sed -n "\$((l > 15 ? l - 15 : 1)),\$((l + 5))p" $OUT/probe2.log | grep -v '^linker\|WARNING: linker' >> $OUT/probe2.txt
  echo "::error title=16K VM first failing load::\$(head -c 3800 $OUT/probe2.txt | sed ':a;N;\$!ba;s/\n/%0A/g')"
fi
rm -rf "\$RT" /tmp/apk "\$P"
[ \$rc = 0 ] || exit 1
bash runtime/android/tests/smoke/run.sh "$APK" "$TESTS" $OUT/logs
INNER

# The CPU: VM_CPU names a QEMU model of a real core (the CI uses Neoverse-N1). x86
# programs under FEX fail on TCG's ARMv9 models (Cortex-A710, and "max" even with
# 128-bit SVE) on 4 KB and 16 KB kernels and with either bionic alike, while the same
# build passes natively on the ARMv9 runner (Neoverse-N2): an emulation problem, not
# one of the runtime. Without VM_CPU: "max" with phone-sized (128-bit) SVE.
TCG_CPU=max,pauth=off,sve-max-vq=1,sme=off
if [ -n "${VM_CPU:-}" ] && qemu-system-aarch64 -cpu help | grep -qw "${VM_CPU%%,*}"; then TCG_CPU=$VM_CPU; fi
echo "::notice title=VM CPU::$TCG_CPU"
if [ -w /dev/kvm ] || sudo test -c /dev/kvm; then ACCEL=(-accel kvm -cpu host); echo "using KVM"; else ACCEL=(-accel tcg,thread=multi -cpu "$TCG_CPU"); echo "using TCG (no KVM)"; fi
sudo timeout "${VM_TIMEOUT:-9000}" qemu-system-aarch64 -M virt "${ACCEL[@]}" -smp 4 -m 12G -nographic -no-reboot \
  -kernel "$WORK/kernel/Image" -initrd "$WORK/initrd.gz" -append "console=ttyAMA0 rdinit=/init quiet" \
  -virtfs local,path=/,mount_tag=hostroot,security_model=passthrough,readonly=on \
  -virtfs local,path=$OUT,mount_tag=vmout,security_model=passthrough
echo "qemu exited: $?"
sudo cp -r "$OUT/logs/." "$LOGS/" 2>/dev/null; sudo chown -R "$USER" "$LOGS"
rc=$(cat "$OUT/rc" 2>/dev/null || echo 99)
echo "test exit code in VM: $rc"
exit "$rc"
