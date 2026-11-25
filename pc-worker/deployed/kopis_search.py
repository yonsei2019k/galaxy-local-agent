"""Read KOPIS's public performance catalogue with a worker-owned service key."""
import datetime as dt
import http.client
import os
import re
import urllib.parse
import xml.etree.ElementTree as ET


def configured():
    return bool(os.environ.get("KOPIS_SERVICE_KEY", "").strip())


def _fetch(params):
    # The older www URL redirects. Never put the query-string credential on a redirect.
    conn = http.client.HTTPSConnection("kopis.or.kr", timeout=5)
    try:
        conn.request("GET", "/openApi/restful/pblprfr?" + urllib.parse.urlencode(params),
                     headers={"Accept": "application/xml"})
        response = conn.getresponse()
        if response.status != 200:
            raise ValueError("UPSTREAM_FAILED")
        return response.read(128 * 1024 + 1)
    finally:
        conn.close()


def search(body, fetch=_fetch):
    if not isinstance(body, dict) or set(body) - {"query", "startDate", "endDate", "page"}:
        return 400, {"error": "INVALID_ARGUMENTS"}
    title = body.get("query")
    page = body.get("page", 1)
    if (not isinstance(title, str) or not 1 <= len(title.strip()) <= 100 or any(ord(c) < 32 for c in title)
            or type(page) is not int or not 1 <= page <= 5):
        return 400, {"error": "INVALID_ARGUMENTS"}
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).date()
    if "startDate" in body or "endDate" in body:
        try:
            dates = [body.get(name) for name in ("startDate", "endDate")]
            if not all(isinstance(day, str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", day) for day in dates):
                raise ValueError()
            start, end = map(dt.date.fromisoformat, dates)
            if not 0 <= (end - start).days < 31:
                raise ValueError()
        except ValueError:
            return 400, {"error": "INVALID_ARGUMENTS"}
    else:
        start, end = today, today + dt.timedelta(days=30)
    if not configured():
        return 409, {"error": "KOPIS_NOT_CONFIGURED"}
    try:
        data = fetch({"service": os.environ["KOPIS_SERVICE_KEY"], "stdate": start.strftime("%Y%m%d"),
                      "eddate": end.strftime("%Y%m%d"), "shprfnm": title.strip(), "cpage": page, "rows": 5})
        if len(data) > 128 * 1024:
            raise ValueError()
        xml = data.decode("utf-8-sig")
        if "\0" in xml or re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", xml, re.I):
            raise ValueError()
        root = ET.fromstring(xml)
        if root.tag != "dbs" or root.find(".//returncode") is not None or root.find(".//errmsg") is not None:
            raise ValueError()
    except (OSError, ValueError, ET.ParseError, http.client.HTTPException):
        return 502, {"error": "KOPIS_UNAVAILABLE"}
    checked = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    rows = []
    for item in root.findall("db")[:5]:
        def value(field):
            return " ".join((item.findtext(field) or "").split())[:180]
        perf_id, name = value("mt20id"), value("prfnm")
        if not re.fullmatch(r"PF[0-9]{6,10}", perf_id) or not name:
            continue
        rows.append({"performanceId": perf_id, "title": name, "venue": value("fcltynm"),
                     "start": value("prfpdfrom"), "end": value("prfpdto"), "status": value("prfstate"),
                     "source": "KOPIS 공연목록", "url": "https://kopis.or.kr/", "fetchedAt": checked})
    return 200, {"success": bool(rows), "items": rows, "fetchedAt": checked, "page": page, "query": title,
                 "period": [start.isoformat(), end.isoformat()], "untrustedSourceData": True,
                 "availabilityVerified": False, "purchaseExecuted": False}
