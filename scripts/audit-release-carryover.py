"""Read-only reconciliation of a release report snapshot and the live intake queue."""
import argparse
import hashlib
import json
import re
from pathlib import Path

REPORT = re.compile(r"[a-z0-9][a-z0-9._-]*\.md")
QUEUE_REF = re.compile(r"(?<![\w/])reports/([a-z0-9][a-z0-9._-]*\.md)(?![\w/-])")
SHA256 = re.compile(r"[0-9a-f]{64}")
DECISIONS = {
    "frozen_included",
    "ancestor_found_verify_prior_release_evidence",
    "carry_forward_check_replacement_or_conflict",
    "carry_forward_verify_candidate_identity",
}


def audit(base, current, queue):
    """Only test evidence present in the inventory. A queue reference is not a disposition."""
    frozen = {}
    for row in base["reports"]:
        name, digest, decision = row["report"], row["sha256"], row["decision"]
        if not REPORT.fullmatch(name) or not SHA256.fullmatch(digest) or decision not in DECISIONS or name in frozen:
            raise ValueError("invalid or duplicate snapshot report")
        frozen[name] = (digest, decision)
    listed = set(QUEUE_REF.findall(queue))
    new = sorted(current.keys() - frozen.keys())
    removed = sorted(frozen.keys() - current.keys())
    changed = sorted(name for name in current.keys() & frozen.keys() if current[name] != frozen[name][0])
    needs_review = set(new) | set(changed) | {
        name for name, (_, decision) in frozen.items() if decision.startswith("carry_forward_")
    }
    missing = sorted((needs_review & current.keys()) - listed)
    prior_release_review = sorted(
        name for name, (_, decision) in frozen.items()
        if decision == "ancestor_found_verify_prior_release_evidence"
        and name in current and name not in listed
    )
    return {
        "frozenSha": base["frozenSha"],
        "releaseCandidate": base["releaseCandidate"],
        "snapshotRecordedAtUtc": base["recordedAtUtc"],
        "newSinceSnapshot": new,
        "changedSinceSnapshot": changed,
        "missingFromReports": removed,
        "missingQueueReference": missing,
        "priorReleaseEvidenceToReview": prior_release_review,
        "passedCoverage": not (missing or removed),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("intake", type=Path, help="directory containing reports/ and queue.md")
    args = parser.parse_args(argv)
    try:
        base = json.loads(args.snapshot.read_text(encoding="utf-8-sig"))
        roots = args.intake / "reports"
        if not roots.is_dir():
            raise ValueError("reports directory missing")
        current = {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in roots.glob("*.md") if p.is_file() and REPORT.fullmatch(p.name)
        }
        result = audit(base, current, (args.intake / "queue.md").read_text(encoding="utf-8-sig"))
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        print(json.dumps({"passedCoverage": False, "error": "INVALID_INTAKE_INPUT"}))
        return 2
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result["passedCoverage"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
