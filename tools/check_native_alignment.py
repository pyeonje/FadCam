"""Validate ELF PT_LOAD and ZIP alignment of native libraries in an APK.

Usage: python tools/check_native_alignment.py path/to/app.apk
The script reads headers directly; no NDK or third party packages are required.
"""
import json
import struct
import sys
import zipfile
from pathlib import Path


def inspect_apk(path):
    report = []
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for entry in apk.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            data = apk.read(entry)
            if data[:4] != b"\x7fELF":
                raise ValueError(f"Not ELF: {entry.filename}")
            endian = "<" if data[5] == 1 else ">"
            is64 = data[4] == 2
            phoff = struct.unpack_from(endian + ("Q" if is64 else "I"), data, 32 if is64 else 28)[0]
            phsize, phnum = struct.unpack_from(endian + "HH", data, 54 if is64 else 42)
            loads = []
            for index in range(phnum):
                fields = struct.unpack_from(endian + ("IIQQQQQQ" if is64 else "IIIIIIII"), data, phoff + index * phsize)
                if fields[0] != 1:
                    continue
                offset, vaddr, alignment = (fields[2], fields[3], fields[7]) if is64 else (fields[1], fields[2], fields[7])
                loads.append({"alignment": alignment, "offset": offset, "vaddr": vaddr})
            raw.seek(entry.header_offset)
            header = raw.read(30)
            namelen, extralen = struct.unpack_from("<HH", header, 26)
            zip_offset = entry.header_offset + 30 + namelen + extralen
            elf_ok = bool(loads) and all(segment["alignment"] >= 16384 and (segment["vaddr"] - segment["offset"]) % 16384 == 0 for segment in loads)
            zip_ok = entry.compress_type != zipfile.ZIP_STORED or zip_offset % 16384 == 0
            report.append({"library": entry.filename, "load_alignments": [segment["alignment"] for segment in loads], "elf_16kb": elf_ok, "zip_16kb": zip_ok})
    return report


if __name__ == "__main__":
    results = inspect_apk(Path(sys.argv[1]))
    print(json.dumps(results, indent=2))
    print(f"Checked {len(results)} native libraries; incompatible: {sum(not (r['elf_16kb'] and r['zip_16kb']) for r in results)}")
    sys.exit(0 if all(r["elf_16kb"] and r["zip_16kb"] for r in results) else 1)
