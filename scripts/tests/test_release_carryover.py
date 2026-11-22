"""Release-intake omissions on synthetic inventories."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("carryover", Path(__file__).parents[1] / "audit-release-carryover.py")
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class CarryoverAudit(unittest.TestCase):
    def setUp(self):
        self.base = {
            "frozenSha": "a" * 40, "releaseCandidate": 1, "recordedAtUtc": "2026-11-22T00:00:00Z",
            "reports": [
                {"report": "included.md", "sha256": "a" * 64, "decision": "frozen_included"},
                {"report": "deferred.md", "sha256": "b" * 64, "decision": "carry_forward_verify_candidate_identity"},
            ],
        }
        self.current = {"included.md": "a" * 64, "deferred.md": "b" * 64}

    def test_late_report_needs_its_own_queue_reference(self):
        self.current["late.md"] = "c" * 64
        result = subject.audit(self.base, self.current, "reports/deferred.md reports/not-late.md")
        self.assertEqual(["late.md"], result["missingQueueReference"])
        self.assertFalse(result["passedCoverage"])
        self.assertTrue(subject.audit(self.base, self.current, "reports/deferred.md reports/late.md")["passedCoverage"])

    def test_already_deferred_report_cannot_disappear_from_queue(self):
        result = subject.audit(self.base, self.current, "")
        self.assertEqual(["deferred.md"], result["missingQueueReference"])

    def test_updated_included_report_is_reconsidered(self):
        self.current["included.md"] = "d" * 64
        result = subject.audit(self.base, self.current, "reports/deferred.md")
        self.assertEqual(["included.md"], result["changedSinceSnapshot"])
        self.assertEqual(["included.md"], result["missingQueueReference"])

    def test_deleted_report_fails_even_with_old_queue_reference(self):
        del self.current["deferred.md"]
        result = subject.audit(self.base, self.current, "reports/deferred.md")
        self.assertEqual(["deferred.md"], result["missingFromReports"])
        self.assertFalse(result["passedCoverage"])

    def test_duplicate_snapshot_rows_reject(self):
        self.base["reports"].append(self.base["reports"][0])
        with self.assertRaises(ValueError):
            subject.audit(self.base, self.current, "reports/deferred.md")

    def test_ancestry_alone_needs_evidence_review_but_is_not_a_missing_carryover(self):
        row = self.base["reports"][0]
        row["decision"] = "ancestor_found_verify_prior_release_evidence"
        result = subject.audit(self.base, self.current, "reports/deferred.md")
        self.assertTrue(result["passedCoverage"])
        self.assertEqual(["included.md"], result["priorReleaseEvidenceToReview"])
