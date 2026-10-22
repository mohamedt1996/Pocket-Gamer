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
python3 "$HERE/get-bionic.py" "$OUT/bionic" > "$LOGS/bionic.log" 2>&1 || { note error "16K VM: Android 15 bionic" < "$LOGS/bionic.log"; exit 1; }
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
echo "::notice title=16K VM::kernel \$(uname -r), page size \$(getconf PAGESIZE) bytes, bionic \$(cat $OUT/bionic/VERSION)"
# Android 15's bionic in place of Android 9's: /system points into the (read-only) Termux
# tree, so a writable copy is mounted over it.
S=\$(readlink -f /system); mkdir -p /tmp/system && cp -a "\$S/." /tmp/system/ && mount --bind /tmp/system "\$S"
cp -f $OUT/bionic/linker64 /system/bin/linker64
cp -f $OUT/bionic/lib64/*.so /system/lib64/
/system/bin/linker64 --help >/dev/null 2>&1; echo "linker64 runs: \$?"
{ echo "readlink /proc/self/exe: \$(readlink /proc/self/exe)"; exec 3< /etc/hostname; echo "fd3: \$(readlink /proc/self/fd/3)"; exec 3<&-
  grep -E ' / | /proc | /data' /proc/mounts; ls -la /proc/self/ | head -5
  W=\$(ls -d /data/data/org.pocketarcade.madeira 2>/dev/null); echo "app dir: \$W"; } > $OUT/env.txt 2>&1
echo "::notice title=16K VM environment::\$(sed ':a;N;\$!ba;s/\n/%0A/g' $OUT/env.txt)"
export PATH=/usr/sbin:/usr/bin:/sbin:/bin STACK_SYMBOLS="${STACK_SYMBOLS:-}" SMOKE_TIME_SCALE="${SMOKE_TIME_SCALE:-1}"
cd "$REPO" && bash runtime/android/tests/smoke/run.sh "$APK" "$TESTS" $OUT/logs
INNER

if [ -w /dev/kvm ] || sudo test -c /dev/kvm; then ACCEL=(-accel kvm -cpu host); echo "using KVM"; else ACCEL=(-accel tcg,thread=multi -cpu max,pauth=off); echo "using TCG (no KVM)"; fi
sudo timeout "${VM_TIMEOUT:-9000}" qemu-system-aarch64 -M virt "${ACCEL[@]}" -smp 4 -m 12G -nographic -no-reboot \
  -kernel "$WORK/kernel/Image" -initrd "$WORK/initrd.gz" -append "console=ttyAMA0 rdinit=/init quiet" \
  -virtfs local,path=/,mount_tag=hostroot,security_model=passthrough,readonly=on \
  -virtfs local,path=$OUT,mount_tag=vmout,security_model=passthrough
echo "qemu exited: $?"
sudo cp -r "$OUT/logs/." "$LOGS/" 2>/dev/null; sudo chown -R "$USER" "$LOGS"
rc=$(cat "$OUT/rc" 2>/dev/null || echo 99)
echo "test exit code in VM: $rc"
exit "$rc"
