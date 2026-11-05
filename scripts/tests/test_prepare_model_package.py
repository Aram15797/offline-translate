import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from scripts.prepare_model_package import prepare


class PackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "weights.bin").write_bytes(b"model bytes")

    def package(self, output=None):
        return prepare(self.source, output or self.root / "model.zip", "asr-test", "1.0", "Test model",
                       "SpeechRecognition", ["hy", "ru", "en"], "https://example.org/model.zip")

    def test_package_contents_and_catalog_integrity_match(self):
        (self.source / "tokenizer").mkdir()
        (self.source / "tokenizer/vocab.json").write_bytes(b"{}")
        entry = self.package()
        archive = self.root / "model.zip"
        self.assertEqual(entry["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(entry["archiveBytes"], archive.stat().st_size)
        with zipfile.ZipFile(archive) as package:
            self.assertEqual(set(package.namelist()), {"weights.bin", "tokenizer/vocab.json"})
            self.assertEqual(package.read("weights.bin"), b"model bytes")
            self.assertEqual(sum(file.file_size for file in package.infolist()), entry["unpackedBytes"])

    def test_refuses_output_inside_source_and_overwrite(self):
        with self.assertRaises(ValueError):
            self.package(self.source / "model.zip")
        self.package()
        original = (self.root / "model.zip").read_bytes()
        with self.assertRaises(ValueError):
            self.package()
        self.assertEqual(original, (self.root / "model.zip").read_bytes())

    def test_refuses_symlink_and_installer_marker(self):
        link = self.source / "linked"
        link.symlink_to(self.source / "weights.bin")
        with self.assertRaises(ValueError):
            self.package()
        link.unlink()
        (self.source / ".verified").write_text("forged")
        with self.assertRaises(ValueError):
            self.package()
        self.assertFalse((self.root / "model.zip").exists())

    def test_refuses_invalid_or_insecure_package_metadata(self):
        for url in ("http://example.org/model.zip", "https://user:pass@example.org/model.zip",
                    "https://example.org:8443/model.zip", "https://example.org/model.zip\n"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                prepare(self.source, self.root / "invalid.zip", "asr-test", "1.0", "Test",
                        "SpeechRecognition", ["hy"], url)
        self.assertFalse((self.root / "invalid.zip").exists())


if __name__ == "__main__":
    unittest.main()
