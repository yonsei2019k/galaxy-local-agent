"""Sign an exact-byte update manifest; private material is read only from CI secrets."""
import argparse
import base64
import hashlib
import json
import os
import re
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


# This describes the capabilities shipped by this client. It is deliberately
# independent of update-trust.json's approvedCapabilities, which is the user's
# installed-client approval set. New entries therefore fail closed on older apps.
RELEASE_CAPABILITIES = sorted({
    "datetime.read",
    "calendar.read",
    "calendar.prepare",
    "reminder.write",
    "alarm.prepare",
    "note.read",
    "note.write",
    "memory.read",
    "memory.write",
    "contacts.read",
    "sms.prepare",
    "email.prepare",
    "map.open",
    "url.open",
    "usage.read",
    "notification.events",
    "background.orchestrate",
    "document.read",
    "document.write",
    "spreadsheet.read_csv",
    "spreadsheet.write_csv",
    "pdf.inspect",
    "pdf.create",
    "email.compose",
    "notification.message.read",
    "notification.reply.rules",
    "screen.inspect",
    "screen.user_action",
    "conversation.import",
    "sms.read",
    "calllog.read",
    "audio.read",
    "audio.transcribe_local",
    "inference.gguf_cpu",
})


def envelope(payload: dict, private_pem: bytes, key_id: str) -> dict:
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    key = serialization.load_pem_private_key(private_pem, password=None)
    return {"keyId": key_id, "payload": base64.b64encode(raw).decode(), "signature": base64.b64encode(key.sign(raw)).decode()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--trust", default="public/update-trust.json")
    args = parser.parse_args()
    apk = Path(args.apk)
    if not apk.is_file():
        raise ValueError("APK path must name an existing file")
    if not 0 < args.version_code <= 2**31 - 1:
        raise ValueError("Version code must fit a positive Android version code")
    if not args.commit or len(args.commit) != 40 or any(c not in "0123456789abcdefABCDEF" for c in args.commit):
        raise ValueError("Commit must be a full 40-character hexadecimal SHA")
    if re.fullmatch(r"\d+(?:\.\d+)*", args.version_name) is None:
        raise ValueError("Version name must be a dotted numeric version")
    if args.tag != f"v{args.version_name}" or "/" in args.tag or "\\" in args.tag:
        raise ValueError("Release tag must match v<version-name>")
    trust = json.loads(Path(args.trust).read_text(encoding="utf-8-sig"))
    key_id = os.environ.get("UPDATE_KEY_ID", "release-1")
    private = os.environ["UPDATE_PRIVATE_KEY"].encode()
    key = serialization.load_pem_private_key(private, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("Release manifest key must be Ed25519")
    certificate = trust["apkCertificateSha256"]
    if len(certificate) != 64 or any(c not in "0123456789abcdefABCDEF" for c in certificate):
        raise ValueError("Pinned APK certificate fingerprint must be SHA-256 hex")
    with apk.open("rb") as apk_file:
        apk_sha256 = hashlib.file_digest(apk_file, "sha256").hexdigest()
    payload = {
        "schemaVersion": 1,
        "packageName": "com.user.localagent",
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "commitSha": args.commit,
        "apkUrl": f"https://github.com/yonsei2019k/galaxy-local-agent/releases/download/{args.tag}/{apk.name}",
        "apkSha256": apk_sha256,
        "apkCertificateSha256": certificate,
        "minSdk": 31,
        "capabilityApiVersion": 1,
        "databaseSchemaVersion": 1,
        "capabilities": RELEASE_CAPABILITIES,
        "channel": "stable",
    }
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if base64.b64decode(trust["keys"][key_id], validate=True) != public:
        raise ValueError("Release key does not match the public key pinned in this APK")
    Path(args.output).write_text(json.dumps(envelope(payload, private, key_id), separators=(",", ":")), encoding="utf-8")
    print(f"Signed manifest for {args.version_name}; SHA-256 {payload['apkSha256']}")


if __name__ == "__main__":
    main()
