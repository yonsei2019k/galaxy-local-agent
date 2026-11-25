import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployed"))
import travel_public_search as api
import model_orchestration


class TravelPublicSearchTests(unittest.TestCase):
    def test_public_form_links_only_official_observed_service_ids(self):
        page = """<script src="/web/search/selectPageListSvcMoreAjax.do"></script><a href="#" onclick="fnDetailPage('S261109101342484380'); return false;" title="Tennis &amp; sports">
        </a><a href="https://example.org" onclick="goToOtherSite()" title="Fake"></a>"""
        fetch = Mock(return_value=page)
        status, result = api.seoul({"query": "테니스"}, fetch)
        self.assertEqual(200, status)
        self.assertEqual(["테니스"], parse_qs(fetch.call_args.args[3])["h_search1"])
        self.assertEqual(["Tennis & sports"], [r["title"] for r in result["items"]])
        self.assertEqual(api.SEOUL_VIEW+"S261109101342484380", result["items"][0]["url"])
        self.assertFalse(result["availabilityVerified"])

    @patch.dict(os.environ, {"TAGO_SERVICE_KEY": "fixture-only"})
    def test_flight_query_and_session_key_separation(self):
        row = {"vihicleId": "ab123", "depPlandTime": 202611251000, "arrPlandTime": 202611251110}
        fetch = Mock(return_value=json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": row}}}}))
        status, result = api.flights({"query": "GMP-CJU", "startDate": "2026-11-25"}, fetch)
        self.assertEqual(200, status)
        self.assertEqual("ab123", result["items"][0]["title"])
        url = urlsplit(fetch.call_args.args[2])
        params = parse_qs(url.query)
        self.assertEqual(["NAARKSS"], params["depAirportId"])
        self.assertEqual(["NAARKPC"], params["arrAirportId"])
        self.assertEqual(["20261125"], params["depPlandTime"])
        self.assertNotIn("fixture-only", json.dumps(result))
        self.assertNotIn("TAGO_SERVICE_KEY", model_orchestration.subscription_environment())

    def test_rejects_bad_source_arguments_without_request(self):
        fetch = Mock()
        for q in ("GMP-GMP", "xxx", "AAA-CJU"):
            self.assertEqual(400, api.flights({"query": q, "startDate": "2026-11-25"}, fetch)[0])
        self.assertEqual(400, api.flights({"query": "GMP-CJU", "startDate": "2026-02-30"}, fetch)[0])
        with patch.dict(os.environ, {"TAGO_SERVICE_KEY": ""}):
            self.assertEqual(409, api.flights({"query": "GMP-CJU", "startDate": "2026-11-25"}, fetch)[0])
        self.assertEqual(400, api.seoul({"query": "x", "extra": True}, fetch)[0])
        fetch.assert_not_called()
