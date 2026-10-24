#!/usr/bin/env python3
"""Check that an APK contains the exact packaged Linux Proton archives."""
import hashlib
import json
from pathlib import Path
import subprocess
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


def verify_box32(apk):
    manifest = json.loads(apk.read("assets/runner/box64-box32-android-arm64-16k.json"))
    assert manifest["schema"] == 1 and manifest["box32"] is True
    assert manifest["sourceCommit"] == "420eda69ff51ab5d5a7cbc3dee2e70433be3411d"
    name = "assets/runner/" + manifest["asset"]
    assert name == "assets/runner/box64-box32-android-arm64-16k.zst"
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory, "box32.zst")
        binary = Path(directory, "box32")
        with apk.open(name) as source, archive.open("wb") as target:
            while block := source.read(1024 * 1024):
                target.write(block)
        with archive.open("rb") as source:
            size, digest = sha256(source)
        assert size > 100_000 and digest == manifest["archiveSha256"]
        with binary.open("wb") as output:
            subprocess.run(["zstd", "-dc", str(archive)], stdout=output, check=True)
        with binary.open("rb") as source:
            _, digest = sha256(source)
        assert digest == manifest["binarySha256"]
        with binary.open("rb") as source:
            header = source.read(20)
        assert header[:6] == b"\x7fELF\x02\x01" and header[18:20] == b"\xb7\x00"
    print(f"verified Android Box64/Box32: {size} compressed bytes, SHA-256 {manifest['archiveSha256']}")


def main(apk_path, require_android_runtime=False, native_only=False):
    with zipfile.ZipFile(apk_path) as apk:
        if native_only:
            assert "assets/linux-proton/manifest.json" not in apk.namelist()
            verify_android_runtime(apk)
            verify_box32(apk)
            print(f"verified native-only APK: {apk_path}")
            return
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
            verify_box32(apk)
    print(f"verified APK: {apk_path}")


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3) or len(sys.argv) == 3 and sys.argv[2] not in \
            ("--require-android-runtime", "--native-only"):
        raise SystemExit("usage: verify-linux-proton-apk.py APK [--require-android-runtime|--native-only]")
    main(Path(sys.argv[1]), len(sys.argv) == 3 and sys.argv[2] == "--require-android-runtime",
         len(sys.argv) == 3 and sys.argv[2] == "--native-only")
