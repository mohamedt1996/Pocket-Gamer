#!/usr/bin/env python3
"""Check that an APK contains the exact packaged Linux Proton archives."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def sha256(stream):
    digest = hashlib.sha256()
    size = 0
    while block := stream.read(1024 * 1024):
        digest.update(block)
        size += len(block)
    return size, digest.hexdigest()


def main(apk_path):
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
    print(f"verified APK: {apk_path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-linux-proton-apk.py APK")
    main(Path(sys.argv[1]))
