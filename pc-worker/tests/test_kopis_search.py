import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployed"))
import kopis_search


class KopisTests(unittest.TestCase):
    def test_unconfigured_skips_network(self):
        with patch.dict(os.environ, {"KOPIS_SERVICE_KEY": ""}):
            status, _ = kopis_search.search({"query": "show"}, lambda _: self.fail("network"))
        self.assertEqual(409, status)

    @patch.dict(os.environ, {"KOPIS_SERVICE_KEY": "worker-secret"})
    def test_31_days_and_paging_collect_only_public_show_facts(self):
        def fetch(params):
            self.assertEqual(("20261101", "20261201", 2, 5, "공연"), tuple(
                params[key] for key in ("stdate", "eddate", "cpage", "rows", "shprfnm")))
            self.assertEqual("worker-secret", params["service"])
            return "<dbs><db><mt20id>PF123456</mt20id><prfnm>공연</prfnm><fcltynm>극장</fcltynm></db></dbs>".encode()
        status, result = kopis_search.search({"query":"공연", "startDate":"2026-11-01", "endDate":"2026-12-01", "page":2}, fetch)
        self.assertEqual(200, status)
        self.assertEqual("극장", result["items"][0]["venue"])
        self.assertFalse(result["availabilityVerified"])
        self.assertNotIn("worker-secret", str(result))

    def test_invalid_dates_pages_extra_params_are_rejected_before_network(self):
        for extra in ({"startDate":"2026-11-01"}, {"startDate":"2026-11-01","endDate":"2026-12-02"},
                      {"startDate":"2026-11-30","endDate":"2026-11-29"},
                      {"page":True}, {"page":6}, {"service":"override"}, {"venueId":"anything"}):
            with self.subTest(extra=extra):
                self.assertEqual(400, kopis_search.search({"query":"show", **extra}, lambda _: self.fail())[0])

    @patch.dict(os.environ, {"KOPIS_SERVICE_KEY": "worker-secret"})
    def test_error_xml_declarations_and_overlong_responses_fail_closed(self):
        for xml in (b"<dbs><db><returncode>02</returncode></db></dbs>", b"<html/>",
                    b"<!DOCTYPE dbs [<!ENTITY a 'data'>]><dbs/>",
                    "<!DOCTYPE dbs><dbs/>".encode("utf-16"), b"x" * (128 * 1024 + 1)):
            with self.subTest(sample=xml[:12]):
                self.assertEqual((502, {"error":"KOPIS_UNAVAILABLE"}),
                                 kopis_search.search({"query":"show"}, lambda _: xml))
