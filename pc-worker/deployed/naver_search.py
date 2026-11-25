"""Paired phone read-only adapter for Naver Search (local places and shopping)."""

import datetime
import html
import http.client
import json
import os
import re
import urllib.parse

from browser_agent import url_allowed


def configured():
    return all(os.environ.get(key, "").strip() for key in ("NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET"))


def _fetch(path, headers):
    conn = http.client.HTTPSConnection("openapi.naver.com", timeout=5)
    try:
        conn.request("GET", path, headers=headers)
        response = conn.getresponse()
        if response.status != 200:
            # Never replay authentication headers on redirects.
            raise ValueError("UPSTREAM_FAILED")
        raw = response.read(128 * 1024 + 1)
        if len(raw) > 128 * 1024:
            raise ValueError("UPSTREAM_TOO_LARGE")
        return json.loads(raw)
    finally:
        conn.close()


def _text(value, limit=300):
    if not isinstance(value, str):
        return ""
    return " ".join(html.unescape(re.sub(r"</?b>", "", value, flags=re.I)).split())[:limit]


def _url(value):
    if not isinstance(value, str) or len(value) > 2048 or any(ord(c) <= 32 for c in value):
        return None
    try:
        url = urllib.parse.urlsplit(value)
        host = (url.hostname or "").rstrip(".")
        if (url.scheme == "https" and url.port in (None, 443) and
                not any(ch in url.netloc for ch in ("@", "%", "\\")) and
                re.fullmatch(r"[A-Za-z0-9.-]+\.[A-Za-z]{2,63}", host) and
                host != "appassets.androidplatform.net" and url_allowed(value)):
            return value
    except ValueError:
        pass
    return None


def search(body, fetch=_fetch):
    if not isinstance(body, dict) or set(body) != {"kind", "query"}:
        return 400, {"error": "INVALID_ARGUMENTS"}
    kind, query = body["kind"], body["query"]
    if kind not in ("local", "shop") or not isinstance(query, str) or not 1 <= len(query.strip()) <= 500:
        return 400, {"error": "INVALID_ARGUMENTS"}
    if any(ord(c) < 32 for c in query):
        return 400, {"error": "INVALID_ARGUMENTS"}
    if not configured():
        return 409, {"error": "NAVER_SEARCH_NOT_CONFIGURED"}
    query = query.strip()
    params = {"query": query, "display": 5, "sort": "sim" if kind == "shop" else "random"}
    path = "/v1/search/" + kind + ".json?" + urllib.parse.urlencode(params)
    headers = {"X-Naver-Client-Id": os.environ["NAVER_CLIENT_ID"],
               "X-Naver-Client-Secret": os.environ["NAVER_CLIENT_SECRET"], "Accept": "application/json"}
    try:
        data = fetch(path, headers)
        if not isinstance(data, dict) or not isinstance(data.get("items"), list):
            raise ValueError("INVALID_RESPONSE")
    except (OSError, ValueError, http.client.HTTPException):
        return 502, {"error": "NAVER_SEARCH_UNAVAILABLE"}
    rows = []
    checked = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    source = "네이버 쇼핑 검색 API" if kind == "shop" else "네이버 지역 검색 API"
    for item in data["items"][:5]:
        if not isinstance(item, dict):
            continue
        title = _text(item.get("title"))
        link = _url(item.get("link"))
        if not title:
            continue
        if kind == "shop":
            if not link:
                continue
            seller = _text(item.get("mallName"), 100)
            low = item.get("lprice")
            quote = low if isinstance(low, str) and re.fullmatch(r"[1-9][0-9]{0,14}", low) else None
            note = "검색 목록 표시가 " + quote + "원" if quote else "표시가 미확인"
            row = {"merchant": seller, "listingPriceKrw": quote, "snippet": note, "price": None}
            image = _url(item.get("image"))
            if image:
                row["imageUrl"] = image
        else:
            address = _text(item.get("roadAddress")) or _text(item.get("address"))
            link = link or "https://map.naver.com/p/search/" + urllib.parse.quote(title + " " + address, safe="")
            row = {"address": address, "category": _text(item.get("category")), "snippet": address}
        rows.append(row | {"title": title, "url": link, "source": source, "fetchedAt": checked})
    return 200, {"success": bool(rows), "query": query, "items": rows, "fetchedAt": checked, "shopping": kind == "shop",
                 "untrustedSourceData": True, "privateSourcesRead": False, "purchaseExecuted": False}
