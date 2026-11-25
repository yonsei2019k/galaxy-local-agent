import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployed"))
import naver_search


class NaverSearchTests(unittest.TestCase):
    def test_missing_credentials_defers_to_existing_public_read(self):
        with patch.dict(os.environ, {"NAVER_CLIENT_ID": "", "NAVER_CLIENT_SECRET": ""}):
            self.assertEqual(409, naver_search.search({"kind": "shop", "query": "sample"})[0])

    @patch.dict(os.environ, {"NAVER_CLIENT_ID": "test-id", "NAVER_CLIENT_SECRET": "test-secret"})
    def test_listing_is_observed_price_only(self):
        def fetch(path, headers):
            self.assertTrue(path.startswith("/v1/search/shop.json?"))
            self.assertNotIn("test-secret", path)
            self.assertEqual("test-secret", headers["X-Naver-Client-Secret"])
            return {"items": [{"title": "<b>상품</b>", "lprice": "9000", "mallName": "판매처",
                               "link": "https://shop.example.org/item"}]}
        status, result = naver_search.search({"kind": "shop", "query": "샘플 &"}, fetch)
        self.assertEqual(200, status)
        row = result["items"][0]
        self.assertEqual("상품", row["title"])
        self.assertEqual("9000", row["listingPriceKrw"])
        self.assertIsNone(row["price"])
        self.assertFalse(result["purchaseExecuted"])

    @patch.dict(os.environ, {"NAVER_CLIENT_ID": "test-id", "NAVER_CLIENT_SECRET": "test-secret"})
    def test_place_with_no_website_has_an_identifiable_map_search(self):
        status, result = naver_search.search({"kind": "local", "query": "테스트 숙소"},
            lambda path, headers: {"items": [{"title": "샘플 펜션", "roadAddress": "샘플로 1", "link": ""}]})
        self.assertEqual(200, status)
        self.assertEqual("샘플로 1", result["items"][0]["address"])
        self.assertTrue(result["items"][0]["url"].startswith("https://map.naver.com/p/search/"))

    @patch.dict(os.environ, {"NAVER_CLIENT_ID": "test-id", "NAVER_CLIENT_SECRET": "test-secret"})
    def test_unavailable_api_does_not_expose_transport_details(self):
        def fetch(path, headers):
            raise ValueError("test-secret")
        self.assertEqual((502, {"error": "NAVER_SEARCH_UNAVAILABLE"}),
                         naver_search.search({"kind": "local", "query": "sample"}, fetch))

    def test_listing_links_reject_internal_hosts_and_userinfo(self):
        for url in ("https://127.0.0.1/a", "https://agent.internal/a", "https://@site.example.org/a",
                    "https://127.1/a", "https://example.org:91/p", "https://appassets.androidplatform.net/"):
            with self.subTest(url=url):
                self.assertIsNone(naver_search._url(url))

    def test_unexpected_destination_rejected_before_credentials(self):
        self.assertEqual(400, naver_search.search({"kind": "shop", "query": "sample", "url": "other"})[0])
