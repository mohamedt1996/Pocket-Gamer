#!/usr/bin/env python3
"""Check that an APK contains the exact packaged Linux Proton archives."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import zipfile


def sha256(stream):
    digest = hashlib.sha256()
    size = 0
    while block := stream.read(1024 * 1024):
        digest.update(block)
        size += len(block)
    return size, digest.hexdigest()


def verify_android_runtime(apk):
    expected = apk.read("assets/madeira-runtime.sha256").decode("ascii").strip()
    assert len(expected) == 64 and all(c in "0123456789abcdef" for c in expected)
    info = apk.getinfo("assets/madeira-runtime.zip")
    assert info.compress_type == zipfile.ZIP_STORED
    with tempfile.TemporaryFile() as staged, apk.open(info) as source:
        digest = hashlib.sha256()
        while block := source.read(1024 * 1024):
            digest.update(block)
            staged.write(block)
        assert digest.hexdigest() == expected, "Android runtime checksum mismatch"
        staged.seek(0)
        with zipfile.ZipFile(staged) as runtime:
            manifest = json.loads(runtime.read("runtime.json"))
            assert manifest["schema"] == 2 and manifest["abi"] == "arm64-v8a"
            required = (
                "bin/wine", "bin/wineserver", "lib/wine/aarch64-unix/ntdll.so",
                "lib/wine/aarch64-unix/wineandroid.so",
                "lib/wine/aarch64-windows/libarm64ecfex.dll",
                "lib/wine/aarch64-windows/libwow64fex.dll",
                "lib/wine/i386-windows/kernel32.dll",
                "graphics/dxvk/x86/d3d11.dll", "graphics/dxvk/x64/d3d11.dll",
                "graphics/vkd3d/x86/d3d12.dll", "graphics/vkd3d/x64/d3d12.dll",
            )
            for name in required:
                assert name in manifest["files"] and runtime.getinfo(name).file_size > 0, name
    print(f"verified Android Wine/FEX runtime: {info.file_size} bytes, SHA-256 {expected}")


def main(apk_path, require_android_runtime=False):
    with zipfile.ZipFile(apk_path) as apk:
        manifest = json.loads(apk.read("assets/linux-proton/manifest.json"))
        assert manifest["schema"] == 1
        components = manifest["components"]
        assert {"proton", "rootfs"} <= components.keys()
        assert apk.getinfo("assets/runner/box64-android-arm64-16k.zst").file_size > 100_000
        for kind, entry in components.items():
            assert kind in ("proton", "rootfs", "eac_runtime")
            assert entry["asset"] == f"{kind}.tgz"
            name = "assets/linux-proton/" + entry["asset"]
            info = apk.getinfo(name)
            # APK assets must keep their gzip header and original archive bytes.
            assert info.compress_type == zipfile.ZIP_STORED, name
            with apk.open(info) as source:
                assert source.read(2) == b"\x1f\x8b", name
            with apk.open(info) as source:
                size, digest = sha256(source)
            assert (size, digest) == (entry["bytes"], entry["sha256"]), name
            print(f"verified {kind}: {size} bytes, SHA-256 {digest}")
        if require_android_runtime:
            verify_android_runtime(apk)
    print(f"verified APK: {apk_path}")


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3) or len(sys.argv) == 3 and sys.argv[2] != "--require-android-runtime":
        raise SystemExit("usage: verify-linux-proton-apk.py APK [--require-android-runtime]")
    main(Path(sys.argv[1]), len(sys.argv) == 3)
