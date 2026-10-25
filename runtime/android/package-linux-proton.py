#!/usr/bin/env python3
"""Verify existing x86-64 Linux packages and stage an optional APK asset set."""
import argparse
import hashlib
import json
from pathlib import Path
import posixpath
import shutil
import struct
import tarfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "android/app/src/main/assets/linux-proton"
PROTON_SHA512 = "45721a7b625b2404d8896bb5e2a8b87fc9623a394873a6dd6b900c839681d5c01ec694ca00d003fc9c733c5f6c5648af8171985656f9fa47e20999fbebdbe235"


def digest(path, name):
    h = hashlib.new(name)
    with path.open("rb") as src:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify(path, kind):
    with tarfile.open(path, "r:gz") as tar:
        members = {m.name.removeprefix("./"): m for m in tar}
        if not members:
            raise ValueError(f"{kind}: empty package")
        prefix = ""
        marker = {"rootfs": "usr/bin/python3", "proton": "proton",
                  "eac_runtime": "v2/lib/x86_64-linux-gnu/easyanticheat.so"}[kind]
        if kind == "eac_runtime":
            bridges = [m for n, m in members.items() if
                       n.endswith("v2/lib/x86_64-linux-gnu/easyanticheat.so") or
                       n.endswith("v2/lib/x86_64-linux-gnu/easyanticheat_x64.so")]
            if not bridges:
                raise ValueError("EAC package has no x86-64 Proton bridge")
            for bridge in bridges:
                stream = tar.extractfile(bridge)
                header = stream.read(20) if stream else b""
                if header[:6] != b"\x7fELF\x02\x01" or len(header) < 20 or struct.unpack_from("<H", header, 18)[0] != 62:
                    raise ValueError("EAC bridge is not an x86-64 Linux ELF")
        if kind != "eac_runtime":
            candidates = [n for n in members if n == marker or n.endswith("/" + marker)]
            if len(candidates) != 1:
                raise ValueError(f"{kind}: expected one {marker}")
            prefix = candidates[0][:-len(marker)]
        required = {"rootfs": ["usr/bin/python3", "usr/lib/x86_64-linux-gnu/libc.so.6",
                              "usr/lib/i386-linux-gnu/libc.so.6",
                              "usr/lib/ld-linux.so.2",
                              "usr/lib/x86_64-linux-gnu/libvulkan_lvp.so",
                              "usr/lib/i386-linux-gnu/libvulkan_lvp.so",
                              "usr/share/vulkan/icd.d/lvp_icd.x86_64.json",
                              "usr/share/vulkan/icd.d/lvp_icd.i686.json"],
                    "proton": ["proton", "files/bin/wine"], "eac_runtime": []}[kind]
        for relative in required:
            if prefix + relative not in members and not (kind == "rootfs" and relative.startswith("usr/lib/") and
                prefix + relative.removeprefix("usr/") in members):
                raise ValueError(f"{kind}: missing {relative}")
        if kind == "rootfs":
            for arch in ("x86_64", "i686"):
                name = prefix + f"usr/share/vulkan/icd.d/lvp_icd.{arch}.json"
                stream = tar.extractfile(members[name])
                icd = json.load(stream) if stream else {}
                if icd.get("ICD", {}).get("library_path") != "libvulkan_lvp.so":
                    raise ValueError(f"rootfs: {arch} Vulkan ICD is not relocatable")
            for relative, elf_class, machine in (
                ("usr/lib/x86_64-linux-gnu/libvulkan_lvp.so", 2, 62),
                ("usr/lib/i386-linux-gnu/libc.so.6", 1, 3),
                ("usr/lib/i386-linux-gnu/libvulkan_lvp.so", 1, 3),
            ):
                item = members[prefix + relative]
                stream = tar.extractfile(item)
                header = stream.read(20) if stream else b""
                if header[:4] != b"\x7fELF" or header[4] != elf_class or \
                        struct.unpack_from("<H", header, 18)[0] != machine:
                    raise ValueError(f"rootfs: wrong ELF architecture in {relative}")
        if kind in ("rootfs", "proton"):
            elf_name = prefix + ("usr/bin/python3" if kind == "rootfs" else "files/bin/wine")
            for _ in range(16):
                item = members.get(elf_name)
                if item is None:
                    raise ValueError(f"{kind}: broken executable link {elf_name}")
                if not (item.issym() or item.islnk()):
                    break
                elf_name = (prefix + item.linkname.removeprefix("/")) if item.linkname.startswith("/") else (
                    posixpath.normpath(posixpath.join(posixpath.dirname(elf_name), item.linkname)) if item.issym()
                    else item.linkname)
            else:
                raise ValueError(f"{kind}: executable link loop")
            stream = tar.extractfile(item)
            if stream is None:
                raise ValueError(f"{kind}: missing ELF")
            header = stream.read(20)
            if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 62:
                raise ValueError(f"{kind}: expected x86-64 Linux ELF")
        for name, member in members.items():
            if name.startswith("/") or ".." in name.split("/") or not (
                member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError(f"{kind}: unsafe or unsupported TAR entry {name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--proton", type=Path, required=True)
    parser.add_argument("--rootfs", type=Path, required=True)
    parser.add_argument("--eac", type=Path, help="Official Proton EasyAntiCheat Runtime supplied through Steam/Heroic")
    args = parser.parse_args()
    if digest(args.proton, "sha512") != PROTON_SHA512:
        raise SystemExit("GE-Proton11-11 x86-64 archive does not match its published SHA-512")
    files = {"proton": args.proton, "rootfs": args.rootfs}
    if args.eac:
        files["eac_runtime"] = args.eac
    for kind, path in files.items():
        verify(path, kind)
    OUT.mkdir(parents=True, exist_ok=True)
    if not args.eac:
        (OUT / "eac_runtime.tgz").unlink(missing_ok=True)
    manifest = {"schema": 1, "protonRelease": "GE-Proton11-11-x86_64", "components": {}}
    for kind, path in files.items():
        # Android's asset packager transparently expands and renames .gz files.
        name = f"{kind}.tgz"
        shutil.copyfile(path, OUT / name)
        manifest["components"][kind] = {"asset": name, "bytes": path.stat().st_size,
                                        "sha256": digest(path, "sha256")}
    (OUT / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
