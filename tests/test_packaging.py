import io
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from repack_install_compat import align_descriptor_flags, payloads
from module_prune import source_path, MODULE_DROP, KEEP_GUARD


class PackagingTest(unittest.TestCase):
    def test_baidu_descriptor_mismatch_keeps_payload(self):
        class Stream(io.BytesIO):
            def seekable(self): return False
            def seek(self, *args): raise io.UnsupportedOperation()
        memory = Stream()
        with zipfile.ZipFile(memory, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/native.so", b"payload" * 100)
        data = bytearray(memory.getvalue())
        central = data.index(b"PK\x01\x02")
        flags = struct.unpack_from("<H", data, central + 8)[0]
        self.assertTrue(flags & 8)
        struct.pack_into("<H", data, central + 8, flags & ~8)
        with tempfile.TemporaryDirectory() as tmp:
            source, output = Path(tmp) / "source.apk", Path(tmp) / "fixed.apk"
            source.write_bytes(data)
            before = payloads(source, standard=False)
            self.assertEqual(align_descriptor_flags(source, output), 1)
            self.assertEqual(source.read_bytes(), data)
            self.assertEqual(before, payloads(output, standard=True))
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.infolist()[0].flag_bits & 8, 8)

    def test_standard_archive_is_not_rewritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, output = Path(tmp) / "source.apk", Path(tmp) / "unused.apk"
            with zipfile.ZipFile(source, "w") as archive: archive.writestr("a", b"ok")
            self.assertEqual(align_descriptor_flags(source, output), 0)
            self.assertFalse(output.exists())

    def test_kotlin_pruning_resolves_real_sources_and_keeps_shared_slider(self):
        root = Path(__file__).resolve().parents[1] / "app/src/io/github/ldxm666"
        for relative in KEEP_GUARD:
            self.assertTrue(Path(source_path(root, relative)).is_file(), relative)
        self.assertNotIn("mapclean/GlassSlider.java", MODULE_DROP)
        self.assertIn("tmapclean/TmapEntry.java", MODULE_DROP)


if __name__ == "__main__": unittest.main()
