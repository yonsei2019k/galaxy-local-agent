"""Synthetic APK ZIP/ELF tests only; no build, real APK, key, model or device.

Run: python -B -m unittest discover -s scripts/tests -p test_rust_react_apk.py -v
"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib


SPEC = importlib.util.spec_from_file_location("verify_rust_react_apk", Path(__file__).resolve().parents[1] / "verify-rust-react-apk.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def elf(align=16384, vaddr=65536, offset=0, *, machine=183, phcount=1, ptype=1):
    ident = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    header = struct.pack("<16sHHIQQQIHHHHHH", ident, 3, machine, 1, 0, 64, 0, 0, 64, 56, phcount, 0, 0, 0)
    segment = struct.pack("<IIQQQQQQ", ptype, 5, offset, vaddr, 0, 64, 64, align)
    return (header + segment).ljust(256, b"\0")


def png():
    def chunk(kind, value):
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0\0\0\0\0")) + chunk(b"IEND", b"")


POLICY = "default-src &#39;none&#39;; script-src &#39;self&#39;; style-src &#39;self&#39;; img-src &#39;self&#39;; connect-src &#39;none&#39;"
HTML = ('<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="' + POLICY + '">'
        '<script type="module" src="./assets/main.js"></script><link rel="stylesheet" href="./assets/main.css">'
        '</head><body><div id="root"></div></body></html>').encode()


def members():
    return {audit.NATIVE[0]: elf(), audit.NATIVE[1]: elf(), audit.REACT + "index.html": HTML,
            audit.REACT + "assets/main.js": b'const mascot="./ziphap-mascot.png";',
            audit.REACT + "assets/main.css": b"body{color:#123}", audit.MASCOT: png(),
            "assets/skills/catalog.json": b'{"version":1}',
            # Intentionally public registration/public trust material is permitted.
            "assets/connections/google-registration.json": b'{"client_id":"synthetic-public-client"}',
            "assets/trust/public.pem": b"-----BEGIN PUBLIC KEY-----\nsynthetic\n-----END PUBLIC KEY-----"}


class ApkAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="synthetic-apk-audit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "synthetic.apk"

    def write(self, data=None, extras=(), compression=zipfile.ZIP_STORED):
        data = members() if data is None else data
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)  # Duplicate fixture is deliberate.
            with zipfile.ZipFile(self.path, "w", compression=compression) as archive:
                for name, body in list(data.items()) + list(extras):
                    archive.writestr(name, body)
        return self.path

    def result(self, data=None, **options):
        return audit.verify_apk(self.write(data, **options))

    def failure(self, report, code=None, stage=None):
        self.assertFalse(report["passed"], report)
        failures = [c for c in report["checks"] if c["status"] == "FAIL"]
        self.assertEqual(1, len(failures), report)
        if code:
            self.assertEqual(code, failures[0]["code"], report)
        if stage:
            self.assertEqual(stage, failures[0]["name"], report)
        return failures[0]

    def test_complete_synthetic_apk_passes_exact_pin_without_modification(self):
        self.write()
        before = self.path.read_bytes()
        expected = hashlib.sha256(before).hexdigest()
        report = audit.verify_apk(self.path, expected)
        self.assertTrue(report["passed"], report)
        self.assertEqual({"sha256": expected, "bytes": len(before)}, report["artifact"])
        self.assertTrue(all(c["status"] == "PASS" for c in report["checks"]))
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(["synthetic.apk"], [p.name for p in self.root.iterdir()])
        self.assertIn("signature", report["notAssessed"])
        self.assertIn("zip_16k_mmap_alignment", report["notAssessed"])
        self.assertIn("device_runtime", report["notAssessed"])

    def test_deflate_and_regular_zip_directory_entries_are_supported(self):
        self.assertTrue(self.result(extras=[("assets/", b""), ("assets/react/assets/", b"")], compression=zipfile.ZIP_DEFLATED)["passed"])

    def test_aapt2_case_sensitive_compiled_resource_names_are_distinct(self):
        self.assertTrue(self.result(extras=[("res/Bl.xml", b"compiled-one"),("res/bL.xml", b"compiled-two")])["passed"])

    def test_pin_mismatch_stops_before_zip_parsing(self):
        self.write()
        report = audit.verify_apk(self.path, "0" * 64)
        self.failure(report, "APK_SHA256_MISMATCH", "artifact_identity")
        self.assertEqual("NOT_RUN", report["checks"][1]["status"])
        self.assertIsNotNone(report["artifact"]["sha256"])
        self.failure(audit.verify_apk(self.path, "NOT_A_PIN"), "EXPECTED_SHA256_FORMAT")

    def test_invalid_missing_and_empty_files_fail_with_fixed_diagnostics(self):
        self.failure(audit.verify_apk(self.path), "APK_READ_OR_FORMAT_ERROR")
        self.path.write_bytes(b"")
        self.failure(audit.verify_apk(self.path), "APK_SIZE_BOUND")
        self.path.write_bytes(b"synthetic private body must not appear in diagnostics")
        report = audit.verify_apk(self.path)
        self.failure(report, "ZIP_EOCD")
        self.assertNotIn("private body", json.dumps(report))

    def test_eocd_entry_and_directory_bounds_apply_before_zipfile_allocation(self):
        self.write()
        raw = bytearray(self.path.read_bytes())
        pos = raw.rfind(b"PK\x05\x06")
        struct.pack_into("<HH", raw, pos + 8, audit.MAX_ENTRIES + 1, audit.MAX_ENTRIES + 1)
        self.path.write_bytes(raw)
        with patch.object(audit.zipfile, "ZipFile", side_effect=AssertionError("must not allocate")):
            self.failure(audit.verify_apk(self.path), "ZIP_DIRECTORY_BOUND")
        self.write()
        with patch.object(audit, "MAX_DIRECTORY", 1):
            self.failure(audit.verify_apk(self.path), "ZIP_DIRECTORY_BOUND")

    def test_zip64_multidisk_and_trailing_bytes_reject(self):
        self.write()
        original = self.path.read_bytes()
        pos = original.rfind(b"PK\x05\x06")
        for delta, value, code in ((4, 1, "ZIP_MULTIDISK"), (8, 65535, "ZIP64_UNSUPPORTED")):
            with self.subTest(code=code):
                raw = bytearray(original)
                struct.pack_into("<H", raw, pos + delta, value)
                if delta == 8:
                    struct.pack_into("<H", raw, pos + 10, value)
                self.path.write_bytes(raw)
                self.failure(audit.verify_apk(self.path), code)
        self.path.write_bytes(original + b"trailing")
        self.failure(audit.verify_apk(self.path), "ZIP_EOCD_BOUND")

    def test_path_traversal_absolute_and_ambiguous_names_reject(self):
        for name in ("../escape", "/absolute", "assets/../private", "assets/./a", "assets//a",
                     "C:/escape", "assets/control\n.txt"):
            with self.subTest(name=name):
                self.failure(self.result(extras=[(name, b"synthetic")]), "ZIP_PATH")
        # Windows ZipInfo normalizes backslashes while WRITING. Corrupt both
        # stored names deliberately to exercise an actual hostile archive name.
        self.write(extras=[("assets/a", b"synthetic")])
        raw = self.path.read_bytes().replace(b"assets/a", b"assets\\a")
        self.path.write_bytes(raw)
        self.failure(audit.verify_apk(self.path), "ZIP_PATH")

    def test_duplicate_case_unicode_and_file_directory_aliases_reject(self):
        for extras in ([(audit.MASCOT, png())], [(audit.MASCOT.upper(), png())],
                       [("assets/é.txt", b"a"), ("assets/e\u0301.txt", b"b")],
                       [("assets/react", b"file")]):
            with self.subTest(extras=[x[0] for x in extras]):
                self.failure(self.result(extras=extras))

    def test_symlink_and_encrypted_members_reject(self):
        link = zipfile.ZipInfo("assets/link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.failure(self.result(extras=[(link, b"../private")]), "ZIP_SPECIAL_FILE")
        self.write()
        raw = bytearray(self.path.read_bytes())
        local = raw.find(b"PK\x03\x04"); central = raw.find(b"PK\x01\x02")
        struct.pack_into("<H", raw, local + 6, 1)
        struct.pack_into("<H", raw, central + 8, 1)
        self.path.write_bytes(raw)
        self.failure(audit.verify_apk(self.path), "ZIP_ENCRYPTED")

    def test_expansion_ratio_entry_and_total_bomb_bounds(self):
        self.failure(self.result(extras=[("assets/flood.txt", b"0" * 500_000)], compression=zipfile.ZIP_DEFLATED), "ZIP_RATIO_BOUND")
        self.write()
        with patch.object(audit, "MAX_ENTRY", 100):
            self.failure(audit.verify_apk(self.path), "ZIP_EXPANSION_BOUND")
        with patch.object(audit, "MAX_TOTAL", 100):
            self.failure(audit.verify_apk(self.path), "ZIP_EXPANSION_BOUND")
        with patch.object(audit, "MAX_APK", 100):
            self.failure(audit.verify_apk(self.path), "APK_SIZE_BOUND")

    def test_local_header_disagreement_and_crc_corruption_fail(self):
        self.write()
        raw = bytearray(self.path.read_bytes())
        pos = raw.find(b"PK\x03\x04")
        raw[pos + 30] ^= 1
        self.path.write_bytes(raw)
        self.failure(audit.verify_apk(self.path), "ZIP_LOCAL_MISMATCH")
        self.write()
        with zipfile.ZipFile(self.path) as archive:
            info = archive.getinfo(audit.REACT + "assets/main.js")
            start = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
        raw = bytearray(self.path.read_bytes()); raw[start] ^= 1; self.path.write_bytes(raw)
        self.failure(audit.verify_apk(self.path), "APK_READ_OR_FORMAT_ERROR", "asset_hygiene")

    def test_required_libraries_must_be_at_exact_arm64_paths(self):
        for name in audit.NATIVE:
            data = members(); data["lib/x86_64/" + Path(name).name] = data.pop(name)
            self.failure(self.result(data), "ARM64_LIBRARIES_MISSING")

    def test_elf64_architecture_and_structural_header_validation(self):
        samples = [b"not an ELF", elf(machine=62), elf(phcount=256), elf(ptype=4)]
        for sample in samples:
            data = members(); data[audit.NATIVE[0]] = sample
            self.failure(self.result(data), stage="elf_load_alignment")
        for offset, value in ((4, 1), (5, 2), (6, 0)):
            sample = bytearray(elf()); sample[offset] = value
            data = members(); data[audit.NATIVE[1]] = sample
            self.failure(self.result(data), "ELF64_LITTLE_ENDIAN")

    def test_all_pt_loads_need_power_of_two_16k_or_greater_and_congruence(self):
        for alignment in (0, 1, 4096, 8192, 24576):
            data = members(); data[audit.NATIVE[1]] = elf(align=alignment)
            self.failure(self.result(data), "ELF_LOAD_ALIGNMENT")
        data = members(); data[audit.NATIVE[0]] = elf(vaddr=65537)
        self.failure(self.result(data), "ELF_LOAD_CONGRUENCE")
        data[audit.NATIVE[0]] = elf(align=65536)
        self.assertTrue(self.result(data)["passed"])
        # A valid first segment must not hide a 4-KiB later segment.
        sample = bytearray(elf(phcount=2))
        struct.pack_into("<IIQQQQQQ", sample, 120, 1, 6, 128, 65664, 0, 64, 64, 4096)
        data[audit.NATIVE[0]] = sample
        self.failure(self.result(data), "ELF_LOAD_ALIGNMENT")

    def test_segment_filesize_memsize_and_file_bounds(self):
        for field, value in ((64 + 32, 257), (64 + 40, 1), (64 + 8, 300)):
            sample = bytearray(elf()); struct.pack_into("<Q", sample, field, value)
            data = members(); data[audit.NATIVE[0]] = sample
            self.failure(self.result(data), "ELF_SEGMENT_RANGE")

    def test_missing_empty_or_invalid_react_assets_are_not_presence_only_passes(self):
        for name in (audit.REACT + "index.html", audit.REACT + "assets/main.js", audit.REACT + "assets/main.css", audit.MASCOT):
            data = members(); data.pop(name)
            self.failure(self.result(data), stage="react_assets")
            data = members(); data[name] = b""
            self.failure(self.result(data), stage="react_assets")
        data = members(); data[audit.MASCOT] = b"renamed text is not a mascot image" * 3
        self.failure(self.result(data), "MASCOT_PNG")

    def test_html_references_cannot_escape_use_remote_urls_or_reference_absent_code(self):
        for ref in ("https://invalid.example/main.js", "//invalid.example/main.js", "/assets/main.js",
                    "../other.js", "%2e%2e/other.js", ".%252e/other.js", "assets\\main.js",
                    "assets/main.js?query=1", "assets/missing.js", "data:text/javascript,code"):
            data = members(); data[audit.REACT + "index.html"] = HTML.replace(b"./assets/main.js", ref.encode())
            self.failure(self.result(data), stage="react_assets")

    def test_csp_must_be_single_early_meta_with_exact_connect_src_none(self):
        for policy in ("default-src 'none'", "connect-src *", "connect-src 'self'",
                       "connect-src 'none' https://invalid.example", "connect-src 'none';connect-src *"):
            data = members(); data[audit.REACT + "index.html"] = HTML.replace(POLICY.encode(), policy.encode())
            self.failure(self.result(data), stage="html_csp")
        data = members(); data[audit.REACT + "index.html"] = HTML.replace(b"<script", b'<meta http-equiv="Content-Security-Policy" content="connect-src &#39;none&#39;"><script')
        self.failure(self.result(data), "HTML_CSP_COUNT")
        data = members(); data[audit.REACT + "index.html"] = HTML.replace(b"<head>", b"<head><script src='./assets/main.js'></script>")
        self.failure(self.result(data), "HTML_CSP_POSITION")

    def test_inline_scripts_base_and_event_handlers_cannot_bypass_offline_index(self):
        for fragment in (b"<base href='https://invalid.example/'>", b"<script>alert(1)</script>", b"<img onerror='code()'>"):
            data = members(); data[audit.REACT + "index.html"] = HTML.replace(b"</body>", fragment + b"</body>")
            self.failure(self.result(data), stage="react_assets")

    def test_model_and_private_config_names_reject_without_scanning_values(self):
        for name in ("assets/models/model.gguf", "assets/model.tflite", "assets/model.safetensors",
                     "assets/weights/part.dat", "assets/key.p12", "assets/signing.keystore",
                     "assets/.env", "assets/.local/config.json", "assets/agent-provider.json", "assets/auth.json"):
            with self.subTest(name=name):
                self.failure(self.result(extras=[(name, b"synthetic fixture")]), stage="asset_hygiene")

    def test_renamed_model_magic_and_embedded_private_credentials_are_detected(self):
        for payload in (b"GGUFrenamed model", b"ggmlrenamed model", b"\x10\x00\x00\x00TFL3renamed model",
                        b"-----BEGIN PRIVATE KEY-----\nSYNTHETIC-NOT-A-KEY\n-----END PRIVATE KEY-----",
                        b'{"client_secret":"synthetic-private-value"}', b'{"phoneCapability":"synthetic-private-value"}'):
            report = self.result(extras=[("assets/renamed.dat", payload)])
            self.failure(report, stage="asset_hygiene")
            self.assertNotIn("synthetic-private-value", json.dumps(report))

    def test_private_key_detection_spans_scan_chunks(self):
        payload = b"x" * 65525 + b"-----BEGIN RSA PRIVATE KEY-----\nSYNTHETIC-ONLY"
        self.failure(self.result(extras=[("assets/renamed.dat", payload)]), "ASSET_CREDENTIAL_LITERAL")

    def test_hygiene_does_not_silently_skip_oversized_assets_or_index(self):
        self.write()
        with patch.object(audit, "MAX_ASSET", 1):
            self.failure(audit.verify_apk(self.path), "MEMBER_SIZE_BOUND")
        with patch.object(audit, "MAX_HTML", 10):
            self.failure(audit.verify_apk(self.path), "MEMBER_SIZE_BOUND")
        with patch.object(audit, "MAX_ASSETS_TOTAL", 1):
            self.failure(audit.verify_apk(self.path), "ASSETS_TOTAL_BOUND")
        report = self.result(extras=[("assets/large.dat", b"s" * 2048)])
        self.assertTrue(report["passed"])
        with patch.object(audit, "MAX_ASSET", 1024):
            self.failure(audit.verify_apk(self.path), "ASSET_SCAN_BOUND")

    def test_concurrent_artifact_change_is_not_success(self):
        self.write()
        real = audit.digest_file
        calls = 0
        def changed(source):
            nonlocal calls
            calls += 1
            digest, size = real(source)
            return (digest if calls == 1 else "0" * 64), size
        with patch.object(audit, "digest_file", side_effect=changed):
            self.failure(audit.verify_apk(self.path), "APK_CHANGED_DURING_AUDIT", "artifact_stable")

    def test_cli_json_and_exit_codes_do_not_claim_signing_or_device_success(self):
        self.write()
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(0, audit.main([str(self.path)]))
        report = json.loads(stdout.getvalue())
        self.assertTrue(report["passed"])
        self.assertIn("installation", report["notAssessed"])
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(1, audit.main([str(self.path), "--expected-sha256", "0" * 64]))
        self.failure(json.loads(stdout.getvalue()), "APK_SHA256_MISMATCH")


if __name__ == "__main__":
    unittest.main()
