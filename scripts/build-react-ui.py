"""Build the pinned offline React UI into generated Android assets; no signing, model or device access."""
from __future__ import annotations

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from urllib.parse import urlsplit


ASSET_PATH = "android-runtime/build/generated/react-assets/react"
STAGING_PATH = "android-runtime/build/generated/react-assets/react.staging"
BACKUP_PATH = "android-runtime/build/generated/react-assets/react.previous"
REMOVABLE = frozenset((ASSET_PATH, STAGING_PATH, BACKUP_PATH))
EXTENSIONS = frozenset((".html", ".js", ".css", ".svg", ".png", ".webp", ".jpg", ".jpeg", ".woff", ".woff2", ".ttf", ".json", ".ico"))
MAX_FILES = 256
MAX_BYTES = 20 * 1024 * 1024


def owned_path(root: Path, relative: str) -> Path:
    """No symlink/junction component may redirect a generated-output mutation."""
    root = root.resolve(strict=True)
    parts = Path(relative).parts
    if not parts or Path(relative).is_absolute() or any(part in (".", "..") for part in parts):
        raise RuntimeError("Invalid workspace-relative build path")
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink() or (hasattr(current, "is_junction") and current.is_junction()):
            raise RuntimeError("Build paths cannot traverse links or junctions")
    if current.resolve(strict=False) != root.joinpath(*parts):
        raise RuntimeError("Resolved build target escaped the expected workspace path")
    return current


def remove_generated(root: Path, relative: str) -> None:
    if relative not in REMOVABLE:
        raise RuntimeError("Refusing deletion outside the three exact generated UI directories")
    target = owned_path(root, relative)
    if target.exists():
        if not target.is_dir():
            raise RuntimeError("Generated asset target must be a directory")
        # Resolve and validate again immediately before recursive removal.
        checked = owned_path(root, relative)
        if checked != target:
            raise RuntimeError("Generated build target changed")
        shutil.rmtree(checked)


class LocalAssetReferences(HTMLParser):
    def __init__(self, dist: Path):
        super().__init__()
        self.dist = dist.resolve(strict=True)
        self.local_scripts = 0
        self.policies = []

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == "meta" and attributes.get("http-equiv", "").lower() == "content-security-policy":
            self.policies.append(attributes.get("content", ""))
            return
        if tag == "script":
            reference = attributes.get("src")
            if not reference:
                raise RuntimeError("Generated UI cannot contain inline executable scripts")
            self.local_scripts += 1
        elif tag == "link" and attributes.get("rel") == "stylesheet":
            reference = attributes.get("href")
        else:
            return
        parsed = urlsplit(reference or "")
        if not reference or parsed.scheme or parsed.netloc or reference.startswith(("/", "\\")) or "\\" in reference:
            raise RuntimeError("Generated UI script/styles must use local relative assets")
        target = (self.dist / parsed.path).resolve(strict=False)
        if self.dist not in target.parents or not target.is_file():
            raise RuntimeError("Generated UI references an absent or out-of-scope asset")


def validated_files(dist: Path) -> list[Path]:
    if not dist.is_dir() or dist.is_symlink():
        raise RuntimeError("React build output is missing or linked")
    files = []
    total = 0
    for path in sorted(dist.rglob("*")):
        relative = path.relative_to(dist)
        if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
            raise RuntimeError("Generated UI must not contain links or junctions")
        if any(part.startswith(".") or part in ("node_modules", "__pycache__") for part in relative.parts):
            raise RuntimeError("Private/configuration files cannot be copied into UI assets")
        if path.is_dir():
            continue
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            raise RuntimeError("Unexpected generated UI artifact type")
        files.append(path)
        total += path.stat().st_size
        if len(files) > MAX_FILES or total > MAX_BYTES:
            raise RuntimeError("Generated UI exceeds its asset size/count bound")
    index = dist / "index.html"
    if index not in files:
        raise RuntimeError("Generated UI index.html is missing")
    html = index.read_text(encoding="utf-8")
    references = LocalAssetReferences(dist)
    references.feed(html)
    # HTMLParser decodes character references in attributes; Vite emits &#39; for CSP quotes.
    policies = [[part.strip().split() for part in value.split(";") if part.strip()] for value in references.policies]
    if len(policies) != 1 or [d for d in policies[0] if d[0] == "connect-src"] != [["connect-src", "'none'"]]:
        raise RuntimeError("Generated UI is missing its offline content policy")
    if references.local_scripts < 1:
        raise RuntimeError("Generated UI has no compiled local entry script")
    return files


def publish(root: Path, dist: Path) -> int:
    files = validated_files(dist)
    target = owned_path(root, ASSET_PATH)
    staging = owned_path(root, STAGING_PATH)
    backup = owned_path(root, BACKUP_PATH)
    if any(path.exists() and not path.is_dir() for path in (target, staging, backup)):
        raise RuntimeError("Generated UI publication targets must be directories")
    # A failed earlier transaction preserves its previous known assets for recovery.
    if backup.exists() and not target.exists():
        backup.rename(target)
    remove_generated(root, STAGING_PATH)
    remove_generated(root, BACKUP_PATH)
    staging.mkdir(parents=True)
    try:
        for source in files:
            destination = staging / source.relative_to(dist)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        validated_files(staging)
        if target.exists():
            owned_path(root, ASSET_PATH).rename(owned_path(root, BACKUP_PATH))
        try:
            owned_path(root, STAGING_PATH).rename(owned_path(root, ASSET_PATH))
        except BaseException:
            if backup.exists() and not target.exists():
                owned_path(root, BACKUP_PATH).rename(owned_path(root, ASSET_PATH))
            raise
        remove_generated(root, BACKUP_PATH)
        return len(files)
    finally:
        remove_generated(root, STAGING_PATH)


def node_and_npm() -> tuple[Path, list[str], dict[str, str], str]:
    candidate = os.environ.get("ZIPHAP_NODE") or shutil.which("node")
    if not candidate:
        raise RuntimeError("Node.js >=20.19 is required (or set ZIPHAP_NODE to node.exe)")
    if not Path(candidate).is_absolute():
        raise RuntimeError("ZIPHAP_NODE must name an installed absolute executable path")
    node = Path(candidate).resolve(strict=True)
    environment = dict(os.environ)
    environment["PATH"] = str(node.parent) + os.pathsep + environment.get("PATH", "")
    version = subprocess.check_output([str(node), "--version"], env=environment, text=True).strip()
    numbers = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", version)
    parsed = tuple(map(int, numbers.groups())) if numbers else (0, 0, 0)
    if not (parsed[0] == 20 and parsed >= (20, 19, 0) or parsed >= (22, 12, 0)):
        raise RuntimeError("The pinned React toolchain requires Node.js >=20.19")
    explicit = os.environ.get("ZIPHAP_NPM_CLI")
    if explicit and not Path(explicit).is_absolute():
        raise RuntimeError("ZIPHAP_NPM_CLI must name an installed absolute npm-cli.js path")
    options = [Path(explicit)] if explicit else [node.parent / "node_modules/npm/bin/npm-cli.js",
              node.parent.parent / "lib/node_modules/npm/bin/npm-cli.js", Path("/usr/share/nodejs/npm/bin/npm-cli.js")]
    for path in options:
        if path.is_file():
            return node, [str(node), str(path.resolve(strict=True))], environment, version
    # On POSIX an installed npm shebang executable is safe with shell=False. Windows
    # npm.cmd is intentionally never passed through cmd.exe or a composed shell string.
    npm = shutil.which("npm") if os.name != "nt" and not explicit else None
    if npm:
        return node, [str(Path(npm).resolve(strict=True))], environment, version
    raise RuntimeError("npm-cli.js is missing; set ZIPHAP_NPM_CLI to its installed absolute path")


def build(ci_tests: bool = False) -> None:
    root = Path(__file__).resolve().parent.parent
    if not (root / "app/src/main/AndroidManifest.xml").is_file():
        raise RuntimeError("This helper must run from the Android repository")
    source = owned_path(root, "web-ui")
    package = owned_path(root, "web-ui/package.json")
    lock = owned_path(root, "web-ui/package-lock.json")
    manifest = json.loads(package.read_text(encoding="utf-8"))
    locked = json.loads(lock.read_text(encoding="utf-8"))
    for section in ("dependencies", "devDependencies"):
        for name, version in manifest.get(section, {}).items():
            if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version) or locked["packages"][""][section].get(name) != version:
                raise RuntimeError("React dependencies must exactly match the committed package lock")
    _, npm, environment, version = node_and_npm()
    ci = environment.get("CI", "").lower() in ("true", "1")
    if ci_tests and not ci:
        raise RuntimeError("--ci-tests is restricted to an explicit CI=true environment")
    modules = owned_path(root, "web-ui/node_modules")
    marker = owned_path(root, "web-ui/node_modules/.ziphap-inputs.sha256")
    signature = hashlib.sha256(package.read_bytes() + b"\0" + lock.read_bytes() + version.encode("ascii")).hexdigest()
    if ci_tests or not modules.is_dir() or not marker.is_file() or marker.stat().st_size > 128 or marker.read_text(encoding="ascii").strip() != signature:
        subprocess.run(npm + ["ci", "--no-audit", "--no-fund"], cwd=source, env=environment, check=True)
        owned_path(root, "web-ui/node_modules")
        owned_path(root, "web-ui/node_modules/.ziphap-inputs.sha256").write_text(signature + "\n", encoding="ascii")
    if ci_tests:
        subprocess.run(npm + ["test"], cwd=source, env=environment, check=True)
    # Vite may empty dist, so check this exact workspace target before running it too.
    owned_path(root, "web-ui/dist")
    subprocess.run(npm + ["run", "build"], cwd=source, env=environment, check=True)
    count = publish(root, owned_path(root, "web-ui/dist"))
    print(f"Offline React assets built and copied: {count} files.")


class BuildBoundaryTests(unittest.TestCase):
    def test_only_exact_generated_directories_may_be_removed(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-react-build-test-") as folder:
            root = Path(folder).resolve()
            with self.assertRaises(RuntimeError):
                remove_generated(root, "app")
            with self.assertRaises(RuntimeError):
                owned_path(root, "../outside")
            target = root / ASSET_PATH
            target.mkdir(parents=True)
            (target / "old.js").write_text("synthetic")
            remove_generated(root, ASSET_PATH)
            self.assertFalse(target.exists())

    def test_publication_never_moves_or_deletes_an_unexpected_target_file(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-react-build-test-") as folder:
            root = Path(folder).resolve()
            target = root / ASSET_PATH
            target.parent.mkdir(parents=True)
            target.write_text("preserve unexpected target")
            dist = root / "web-ui/dist"
            (dist / "assets").mkdir(parents=True)
            (dist / "assets/app.js").write_text("synthetic")
            (dist / "index.html").write_text('<meta http-equiv="Content-Security-Policy" content="connect-src \'none\'"><script src="./assets/app.js"></script>')
            with self.assertRaises(RuntimeError):
                publish(root, dist)
            self.assertEqual("preserve unexpected target", target.read_text())

    def test_publish_preserves_previous_assets_when_new_output_is_invalid(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-react-build-test-") as folder:
            root = Path(folder).resolve()
            target = root / ASSET_PATH
            target.mkdir(parents=True)
            (target / "keep.txt").write_text("previous verified build")
            dist = root / "web-ui/dist"
            dist.mkdir(parents=True)
            (dist / "index.html").write_text("invalid")
            with self.assertRaises(RuntimeError):
                publish(root, dist)
            self.assertEqual("previous verified build", (target / "keep.txt").read_text())

    def test_valid_publish_uses_local_assets_and_never_copies_private_files(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-react-build-test-") as folder:
            root = Path(folder).resolve()
            dist = root / "web-ui/dist"
            (dist / "assets").mkdir(parents=True)
            (dist / "assets/app.js").write_text("console.log('synthetic');")
            (dist / "index.html").write_text('<meta http-equiv="Content-Security-Policy" content="connect-src &#39;none&#39;"><script type="module" src="./assets/app.js"></script>')
            self.assertEqual(2, publish(root, dist))
            self.assertTrue((root / ASSET_PATH / "assets/app.js").is_file())
            (dist / "auth.key").write_text("synthetic forbidden")
            with self.assertRaises(RuntimeError):
                publish(root, dist)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ci-tests", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(BuildBoundaryTests))
        if not result.wasSuccessful():
            raise SystemExit(1)
    else:
        build(arguments.ci_tests)


if __name__ == "__main__":
    main()
