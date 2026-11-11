import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_native_alignment import inspect_apk, personal_apk_passes


def elf(alignment):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(data)


class NativeAlignmentTest(unittest.TestCase):
    def test_personal_apk_requires_nonempty_arm64_uvc_and_alignment(self):
        uvc = "lib/arm64-v8a/libdroiduvc.so"
        for name, alignment, passes in (
            (None, 16384, False),
            ("lib/arm64-v8a/libother.so", 16384, False),
            ("lib/x86_64/libdroiduvc.so", 16384, False),
            (uvc, 4096, False),
            (uvc, 16384, True),
        ):
            with self.subTest(name=name, alignment=alignment), tempfile.TemporaryDirectory() as tmp:
                apk = Path(tmp) / "fixture.apk"
                with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as z:
                    if name:
                        z.writestr(name, elf(alignment))
                self.assertEqual(passes, personal_apk_passes(inspect_apk(apk)))

    def test_unaligned_stored_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("lib/arm64-v8a/libdroiduvc.so", elf(16384))
            result = inspect_apk(apk)
            self.assertTrue(result[0]["elf_16kb"])
            self.assertFalse(result[0]["zip_16kb"])
            self.assertFalse(personal_apk_passes(result))


if __name__ == "__main__":
    unittest.main()
