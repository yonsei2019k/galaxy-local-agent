"""Bounded, read-only APK packaging audit (Python stdlib only).

Usage: python scripts/verify-rust-react-apk.py FINAL.apk --expected-sha256 HEX
JSON goes to stdout; exit 0 means these static checks passed, 1 means rejected.
Never extracts, loads native code, signs, installs, builds, or changes the APK.
Only an explicitly designated final artifact is release evidence; fixtures and
intermediate build outputs are not. This is not a general secret/malware scanner.

ELF reference: https://gabi.xinuos.com/elf/07-pheader.html (p_align/congruence).
Android: https://developer.android.com/guide/practices/page-sizes
ZIP mmap alignment, signatures and actual 16-KiB device loading are separate.
"""
from __future__ import annotations

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import stat
import struct
import unicodedata
from urllib.parse import unquote, urlsplit
import zipfile
import zlib


MAX_APK = 1024 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_DIRECTORY = 16 * 1024 * 1024
MAX_ENTRY = 256 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
MAX_RATIO = 200
MAX_NATIVE = 128 * 1024 * 1024
MAX_ASSET = 16 * 1024 * 1024
MAX_ASSETS_TOTAL = 64 * 1024 * 1024
MAX_HTML = 512 * 1024
PAGE = 16_384
NATIVE = ("lib/arm64-v8a/libziphap_android.so", "lib/arm64-v8a/libjnidispatch.so")
REACT = "assets/react/"
MASCOT = REACT + "ziphap-mascot.png"
CHECKS = ("artifact_identity", "zip_safety", "native_libraries", "elf_load_alignment",
          "react_assets", "html_csp", "asset_hygiene", "artifact_stable")
MODEL_EXTENSIONS = {".gguf", ".ggml", ".litertlm", ".tflite", ".onnx", ".safetensors",
                    ".pt", ".pth", ".ckpt", ".bin", ".h5", ".hdf5", ".weights"}
PRIVATE_EXTENSIONS = {".key", ".p12", ".pfx", ".jks", ".keystore", ".dpapi"}
PRIVATE_NAMES = {"agent-provider.json", "agent-workspaces.json", "credentials.json",
                 "secrets.json", "pairing.json", "auth.json", "id_rsa", "id_ed25519"}
PRIVATE_DIRS = {".git", ".local", ".ssh", ".aws", ".codex", "node_modules", "__pycache__"}
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
    re.compile(rb"\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b"),
    # Deliberately flags literal assignments, not bare API names/tool schemas.
    re.compile(rb'''["'](?:api[_-]?key|access_token|refresh_token|client_secret|private_key|password|pairing_token|token|agentCapability|phoneCapability)["']\s*[:=]\s*["'][^"'\r\n]{1,4096}["']''', re.I),
)


class Rejected(Exception):
    def __init__(self, code, member=None):
        super().__init__(code)
        self.code, self.member = code, member


def require(condition, code, member=None):
    if not condition:
        raise Rejected(code, member)


def digest_file(source):
    source.seek(0)
    digest, total = hashlib.sha256(), 0
    while True:
        block = source.read(1024 * 1024)
        if not block:
            return digest.hexdigest(), total
        total += len(block)
        require(total <= MAX_APK, "APK_SIZE_BOUND")
        digest.update(block)


def bounded_directory(source, size):
    """Bound central-directory allocation BEFORE ZipFile parses attacker metadata.

    ZIP64/multidisk archives are outside this <1-GiB audit profile, not silently
    partially inspected. APK signing blocks in the pre-directory gap are allowed.
    """
    source.seek(max(0, size - 65_557))
    tail = source.read(65_557)
    offset = tail.rfind(b"PK\x05\x06")
    require(offset >= 0 and len(tail) - offset >= 22, "ZIP_EOCD")
    _, disk, cd_disk, disk_count, count, cd_size, cd_offset, comment = struct.unpack_from("<4s4H2LH", tail, offset)
    require(offset + 22 + comment == len(tail), "ZIP_EOCD_BOUND")
    require(disk == cd_disk == 0 and disk_count == count, "ZIP_MULTIDISK")
    require(count != 65535 and cd_size != 0xFFFFFFFF and cd_offset != 0xFFFFFFFF, "ZIP64_UNSUPPORTED")
    require(0 < count <= MAX_ENTRIES and 0 < cd_size <= MAX_DIRECTORY, "ZIP_DIRECTORY_BOUND")
    eocd = size - len(tail) + offset
    require(cd_offset + cd_size == eocd, "ZIP_DIRECTORY_RANGE")
    return count, cd_offset


def safe_name(name):
    require(type(name) is str and 0 < len(name.encode("utf-8")) <= 1024, "ZIP_NAME_BOUND")
    require(not any(ord(c) < 32 or ord(c) == 127 for c in name), "ZIP_PATH")
    require(not name.startswith("/") and "\\" not in name and ":" not in name, "ZIP_PATH")
    parts = name.rstrip("/").split("/")
    require(all(p and p not in (".", "..") for p in parts), "ZIP_PATH")
    canonical=unicodedata.normalize("NFC", name.rstrip("/"))
    # AAPT2 shortens compiled resource names using a case-sensitive alphabet.
    # Android ZIP lookup keeps res/Bl.xml distinct from res/bL.xml; no extraction occurs here.
    return canonical if name.startswith("res/") and name.isascii() else canonical.casefold()


def zip_inventory(archive, source, count, cd_offset):
    infos = archive.infolist()
    require(len(infos) == count, "ZIP_ENTRY_COUNT")
    entries, aliases, ranges, total = {}, set(), [], 0
    for info in infos:
        # ZipInfo truncates filename at NUL; inspect the original spelling too.
        name = info.orig_filename
        alias = safe_name(name)
        require(name not in entries and alias not in aliases, "ZIP_DUPLICATE_NAME")
        require(info.filename == name, "ZIP_PATH")
        entries[name], total = info, total + info.file_size
        aliases.add(alias)
        mode = (info.external_attr >> 16) & 0xFFFF
        require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), "ZIP_SPECIAL_FILE", name)
        require(not info.flag_bits & 1, "ZIP_ENCRYPTED", name)
        require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "ZIP_COMPRESSION", name)
        require(0 <= info.file_size <= MAX_ENTRY and total <= MAX_TOTAL, "ZIP_EXPANSION_BOUND", name)
        require(info.file_size <= MAX_RATIO * max(1, info.compress_size), "ZIP_RATIO_BOUND", name)
        require(info.header_offset >= 0 and info.header_offset + 30 <= cd_offset, "ZIP_LOCAL_RANGE", name)
        source.seek(info.header_offset)
        header = source.read(30)
        require(len(header) == 30 and header[:4] == b"PK\x03\x04", "ZIP_LOCAL_HEADER", name)
        local = struct.unpack("<4s5H3L2H", header)
        require(local[2] == info.flag_bits and local[3] == info.compress_type, "ZIP_LOCAL_MISMATCH", name)
        raw_name = source.read(local[-2])
        encoding = "utf-8" if info.flag_bits & 0x800 else "cp437"
        require(raw_name.decode(encoding, "strict") == name, "ZIP_LOCAL_MISMATCH", name)
        start = info.header_offset + 30 + local[-2] + local[-1]
        end = start + info.compress_size
        require(end <= cd_offset, "ZIP_LOCAL_RANGE", name)
        ranges.append((info.header_offset, end))
    ranges.sort()
    require(all(left[1] <= right[0] for left, right in zip(ranges, ranges[1:])), "ZIP_OVERLAPPING_MEMBERS")
    for name in entries:
        require(not any(name[:i] in entries for i, c in enumerate(name) if c == "/"), "ZIP_FILE_DIRECTORY_ALIAS")
    return entries


def read_member(archive, entries, name, limit):
    require(name in entries and not entries[name].is_dir(), "MEMBER_MISSING", name)
    info = entries[name]
    require(0 < info.file_size <= limit, "MEMBER_SIZE_BOUND", name)
    with archive.open(info) as stream:
        data = stream.read(limit + 1)
    require(len(data) == info.file_size and len(data) <= limit, "MEMBER_SIZE_BOUND", name)
    return data


def elf_segments(data, member):
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "ELF64_LITTLE_ENDIAN", member)
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    _, kind, machine, version, _, phoff, _, _, ehsize, phsize, phcount, _, _, _ = header
    require(kind == 3 and machine == 183 and version == 1 and ehsize == 64, "ELF_ARM64_SHARED_OBJECT", member)
    require(phsize == 56 and 0 < phcount <= 256 and phoff >= 64 and phoff + phsize * phcount <= len(data), "ELF_PROGRAM_HEADERS", member)
    segments = []
    for index in range(phcount):
        ptype, _, offset, vaddr, _, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
        if ptype != 1:
            continue
        require(filesz <= memsz and offset + filesz <= len(data) and vaddr + memsz < 2**64, "ELF_SEGMENT_RANGE", member)
        require(align >= PAGE and align & (align - 1) == 0, "ELF_LOAD_ALIGNMENT", member)
        require(offset % align == vaddr % align, "ELF_LOAD_CONGRUENCE", member)
        segments.append({"index": index, "alignment": align, "offset": offset, "virtualAddress": vaddr})
    require(bool(segments), "ELF_NO_LOAD_SEGMENTS", member)
    return {"member": member, "loadSegments": segments}


def relative_asset(reference, entries, extension):
    require(type(reference) is str and reference and not any(c.isspace() or ord(c) < 32 for c in reference), "REACT_REFERENCE")
    parsed = urlsplit(reference)
    require(not parsed.scheme and not parsed.netloc and not parsed.query and not parsed.fragment, "REACT_REFERENCE")
    decoded = unquote(parsed.path, encoding="utf-8", errors="strict")
    require("%" not in decoded and "\\" not in decoded and not decoded.startswith("/"), "REACT_REFERENCE")
    while decoded.startswith("./"):
        decoded = decoded[2:]
    safe_name(decoded)
    require(Path(decoded).suffix.lower() == extension, "REACT_REFERENCE_TYPE")
    name = REACT + decoded
    require(name in entries and not entries[name].is_dir() and entries[name].file_size > 0, "REACT_REFERENCE_MISSING", name)
    return name


class ReactIndex(HTMLParser):
    def __init__(self, entries):
        super().__init__(convert_charrefs=True)
        self.entries, self.scripts, self.styles, self.policies = entries, [], [], []
        self.in_head, self.saw_resource = False, False

    def handle_starttag(self, tag, attrs):
        require(len(attrs) == len({key for key, _ in attrs}), "HTML_DUPLICATE_ATTRIBUTE")
        attributes = dict(attrs)
        require(tag != "base" and not any(key.startswith("on") for key in attributes), "HTML_EXECUTABLE_INLINE")
        if tag == "head":
            self.in_head = True
        if tag == "meta" and attributes.get("http-equiv", "").lower() == "content-security-policy":
            require(self.in_head and not self.saw_resource, "HTML_CSP_POSITION")
            self.policies.append(attributes.get("content", ""))
        if tag == "script":
            self.saw_resource = True
            self.scripts.append(relative_asset(attributes.get("src"), self.entries, ".js"))
        if tag == "link" and "stylesheet" in attributes.get("rel", "").lower().split():
            self.saw_resource = True
            self.styles.append(relative_asset(attributes.get("href"), self.entries, ".css"))

    def handle_endtag(self, tag):
        if tag == "head":
            self.in_head = False

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def check_csp(self):
        require(len(self.policies) == 1, "HTML_CSP_COUNT")
        directives = [part.strip().split() for part in self.policies[0].split(";") if part.strip()]
        require([d for d in directives if d[0].lower() == "connect-src"] == [["connect-src", "'none'"]], "HTML_CSP_CONNECT_SRC")
        return {"connectSrc": "'none'", "policyCount": 1}


def react_assets(archive, entries):
    html = read_member(archive, entries, REACT + "index.html", MAX_HTML).decode("utf-8", "strict")
    parser = ReactIndex(entries)
    parser.feed(html)
    parser.close()
    require(bool(parser.scripts) and bool(parser.styles), "REACT_ENTRY_ASSETS")
    mascot = read_member(archive, entries, MASCOT, MAX_ASSET)
    require(len(mascot) >= 33 and mascot[:8] == b"\x89PNG\r\n\x1a\n" and mascot[12:16] == b"IHDR", "MASCOT_PNG", MASCOT)
    width, height = struct.unpack_from(">II", mascot, 16)
    require(0 < width <= 8192 and 0 < height <= 8192, "MASCOT_DIMENSIONS", MASCOT)
    return parser, {"scripts": parser.scripts, "stylesheets": parser.styles, "mascot": MASCOT}


def scan_assets(archive, entries):
    assets = [i for n, i in entries.items() if n.startswith("assets/") and not i.is_dir()]
    require(sum(i.file_size for i in assets) <= MAX_ASSETS_TOTAL, "ASSETS_TOTAL_BOUND")
    scanned = 0
    for info in assets:
        name = info.filename
        parts = name.lower().split("/")
        suffix = Path(parts[-1]).suffix
        require(suffix not in MODEL_EXTENSIONS and not any(p in {"models", "weights"} for p in parts), "ASSET_MODEL_FILE", name)
        require(suffix not in PRIVATE_EXTENSIONS and parts[-1] not in PRIVATE_NAMES and not parts[-1].startswith(".env")
                and not any(p in PRIVATE_DIRS for p in parts), "ASSET_PRIVATE_CONFIG", name)
        require(info.file_size <= MAX_ASSET, "ASSET_SCAN_BOUND", name)
        # Full bounded scan, including binary assets. Overlap exceeds max pattern length.
        with archive.open(info) as stream:
            tail, total = b"", 0
            while True:
                block = stream.read(65_536)
                if not block:
                    break
                if total == 0:
                    require(block[:4] not in (b"GGUF", b"ggml", b"ggmf", b"ggjt") and block[4:8] != b"TFL3", "ASSET_MODEL_MAGIC", name)
                total += len(block)
                require(total <= MAX_ASSET, "ASSET_SCAN_BOUND", name)
                sample = tail + block
                require(not any(pattern.search(sample) for pattern in SECRET_PATTERNS), "ASSET_CREDENTIAL_LITERAL", name)
                tail = sample[-8192:]
        require(total == info.file_size, "ASSET_SIZE_MISMATCH", name)
        scanned += total
    return {"filesScanned": len(assets), "bytesScanned": scanned, "method": "full_bounded_names_magic_and_known_literal_patterns"}


def verify_apk(path, expected_sha256=None):
    """Return only artifact metadata/fixed failure codes, never asset/secret text."""
    report = {"schemaVersion": 1, "kind": "rust_react_apk_static_audit", "passed": False,
              "artifact": {"sha256": None, "bytes": None},
              "checks": [{"name": name, "status": "NOT_RUN"} for name in CHECKS],
              "notAssessed": ["signature", "zip_16k_mmap_alignment", "installation", "device_runtime",
                              "arbitrary_obfuscated_or_encrypted_secrets", "non_asset_payload_hygiene"]}
    stage = 0
    def passed(details=None):
        nonlocal stage
        report["checks"][stage].update(status="PASS", details=details or {})
        stage += 1
    try:
        require(expected_sha256 is None or re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is not None, "EXPECTED_SHA256_FORMAT")
        with open(path, "rb") as source:
            initial = os.fstat(source.fileno())
            require(stat.S_ISREG(initial.st_mode) and 0 < initial.st_size <= MAX_APK, "APK_SIZE_BOUND")
            sha, size = digest_file(source)
            report["artifact"].update(sha256=sha, bytes=size)
            require(expected_sha256 is None or sha == expected_sha256, "APK_SHA256_MISMATCH")
            passed()
            count, cd_offset = bounded_directory(source, size)
            with zipfile.ZipFile(source, "r") as archive:
                entries = zip_inventory(archive, source, count, cd_offset)
                passed({"entries": count, "metadataBoundsChecked": True, "allEntryCrcsChecked": False})
                require(all(n in entries and not entries[n].is_dir() for n in NATIVE), "ARM64_LIBRARIES_MISSING")
                passed({"members": list(NATIVE)})
                passed({"libraries": [elf_segments(read_member(archive, entries, n, MAX_NATIVE), n) for n in NATIVE]})
                index, details = react_assets(archive, entries)
                passed(details)
                passed(index.check_csp())
                passed(scan_assets(archive, entries))
            final_sha, final_size = digest_file(source)
            final = os.fstat(source.fileno())
            current = os.stat(path)
            require((final_sha, final_size) == (sha, size) and
                    (initial.st_dev, initial.st_ino, initial.st_mtime_ns) == (final.st_dev, final.st_ino, final.st_mtime_ns) and
                    (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) ==
                    (final.st_dev, final.st_ino, final.st_size, final.st_mtime_ns), "APK_CHANGED_DURING_AUDIT")
            passed({"sha256ReadbackMatched": True})
        report["passed"] = True
    except (Rejected, OSError, ValueError, UnicodeError, zipfile.BadZipFile, NotImplementedError, RuntimeError, struct.error, zlib.error) as error:
        row = report["checks"][min(stage, len(CHECKS) - 1)]
        row.update(status="FAIL", code=error.code if isinstance(error, Rejected) else "APK_READ_OR_FORMAT_ERROR")
        if isinstance(error, Rejected) and error.member is not None:
            row["member"] = error.member
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Root-designated final APK, or an explicitly synthetic fixture")
    parser.add_argument("--expected-sha256", help="Optional exact lowercase artifact pin")
    args = parser.parse_args(argv)
    report = verify_apk(args.apk, args.expected_sha256)
    print(json.dumps(report, ensure_ascii=True, sort_keys=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
