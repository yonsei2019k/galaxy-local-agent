"""Read domestic flight schedules and the public Seoul reservation site. No booking actions."""
import datetime as dt
from html.parser import HTMLParser
import http.client
import json
import os
import re
from urllib.parse import urlencode

TAGO_INFO = "https://www.data.go.kr/data/15098526/openapi.do"
SEOUL_VIEW = "https://yeyak.seoul.go.kr/web/reservation/selectReservView.do?rsv_svc_id="
AIRPORTS = {"GMP": "NAARKSS", "CJU": "NAARKPC", "PUS": "NAARKPK", "CJJ": "NAARKTU",
            "TAE": "NAARKTN", "KWJ": "NAARKJJ", "USN": "NAARKPU", "RSU": "NAARKJY",
            "ICN": "NAARKSI", "MWX": "NAARKJB", "HIN": "NAARKPS", "KUV": "NAARKJK",
            "WJU": "NAARKNW", "KPO": "NAARKTH", "YNY": "NAARKNY"}


def configured():
    return bool(os.environ.get("TAGO_SERVICE_KEY", "").strip())


def fetch(host, method, path, form=None):
    conn = http.client.HTTPSConnection(host, timeout=5)
    try:
        headers = {"User-Agent": "ZipHap.Agent/1.0"}
        if form:
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        conn.request(method, path, body=form, headers=headers)
        response = conn.getresponse()
        if response.status != 200:
            raise ValueError("upstream status")
        data = response.read(512 * 1024 + 1)
        if len(data) > 512 * 1024:
            raise ValueError("response too large")
        return data.decode("utf-8-sig")
    finally:
        conn.close()


def result(rows, query, **attributes):
    return 200, dict(success=bool(rows), items=rows, query=query, untrustedSourceData=True,
                     availabilityVerified=False, purchaseExecuted=False, **attributes)


def flights(body, request=fetch):
    if not isinstance(body, dict) or set(body) - {"query", "startDate", "page"}:
        return 400, {"error": "INVALID_ARGUMENTS"}
    query, day, page = body.get("query"), body.get("startDate"), body.get("page", 1)
    if not isinstance(query, str) or not re.fullmatch(r"(?:[A-Z]{3}|NAARK[A-Z]{2})-(?:[A-Z]{3}|NAARK[A-Z]{2})", query):
        return 400, {"error": "INVALID_ARGUMENTS"}
    dep, arr = (AIRPORTS.get(code, code) for code in query.split("-"))
    try:
        if not isinstance(day, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
            raise ValueError()
        date = dt.date.fromisoformat(day)
        if dep == arr or any(not re.fullmatch(r"NAARK[A-Z]{2}", x) for x in (dep, arr)) or type(page) != int or not 1 <= page <= 5:
            raise ValueError()
    except ValueError:
        return 400, {"error": "INVALID_ARGUMENTS"}
    if not configured():
        return 409, {"error": "TAGO_NOT_CONFIGURED"}
    params = dict(serviceKey=os.environ["TAGO_SERVICE_KEY"], pageNo=page, numOfRows=5, _type="json",
                  depAirportId=dep, arrAirportId=arr, depPlandTime=date.strftime("%Y%m%d"))
    try:
        data = json.loads(request("apis.data.go.kr", "GET", "/1613000/DmstcFlightNvgInfo/GetFlightOpratInfoList?" + urlencode(params)))["response"]
        if data["header"]["resultCode"] != "00":
            raise ValueError()
        items = data["body"].get("items") or {}
        rows = items.get("item", [])
        if isinstance(rows, dict):
            rows = [rows]
        if not isinstance(rows, list):
            raise ValueError()
        fields = ("vihicleId", "airlineNm", "depAirportNm", "arrAirportNm", "depPlandTime", "arrPlandTime")
        out = []
        for row in rows[:5]:
            entry = {f: str(row[f])[:100] for f in fields if isinstance(row.get(f), (str, int))}
            if not entry.get("vihicleId") or not entry.get("depPlandTime", "").startswith(params["depPlandTime"]):
                continue
            out.append(dict(entry, title=entry["vihicleId"], url=TAGO_INFO, source="국토교통부 TAGO"))
        return result(out, query, departureDate=day, page=page)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, http.client.HTTPException):
        return 502, {"error": "TAGO_UNAVAILABLE"}


class SeoulListings(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = {}

    def handle_starttag(self, tag, attrs):
        if tag != "a" or len(self.rows) >= 5:
            return
        a = dict(attrs)
        target = re.fullmatch(r"\s*fnDetailPage\('([Ss][0-9]{12,24})'\);\s*return false;\s*", a.get("onclick") or "")
        title = " ".join((a.get("title") or "").split())[:180]
        if target and title:
            sid = target[1]
            self.rows[sid] = dict(title=title, serviceId=sid, url=SEOUL_VIEW+sid, source="서울특별시 공공서비스예약")


def seoul(body, request=fetch):
    query = body.get("query") if isinstance(body, dict) else None
    if not isinstance(query, str) or set(body) != {"query"} or not 1 <= len(query.strip()) <= 50 or any(ord(c) < 32 for c in query):
        return 400, {"error": "INVALID_ARGUMENTS"}
    try:
        page = request("yeyak.seoul.go.kr", "POST", "/web/search/selectPageListTotalSearch.do", urlencode({"h_search1": query.strip()}))
        if "selectPageListSvcMoreAjax.do" not in page:
            raise ValueError("not a Seoul search results page")
        parser = SeoulListings()
        parser.feed(page)
        return result(list(parser.rows.values()), query, partial=True)
    except (OSError, ValueError, http.client.HTTPException):
        return 502, {"error": "SEOUL_RESERVATIONS_UNAVAILABLE"}
