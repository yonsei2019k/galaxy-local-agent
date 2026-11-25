# 배포된 PC 작업기 스냅샷 (2026-11-16)

`D:\ZipHap\Compute\worker`의 공개 소스 스냅샷이다. 실제 설치 상태는 공유 변경 기록과 배포 해시로 확인한다. `recovery_engines.py`와 `image_progress.py`는 상위 `pc-worker/`의 최신 소스와 동기화했다. 다른 상위 파일은 미배포 후보일 수 있으므로 디렉터리 이름만으로 실행 버전을 추정하지 않는다.

- 제외: 모델·런타임(`runtime*`, `llama-runtime`, `models`), 설정·토큰(`config.json`, `pairing.json`), 인증서·키, 기록 파일, `*.before-*` 백업.
- 실행: `supervisor.py`가 로그인 시 시작되어 `server.py`를 `LOCALAPPDATA=D:\`로 띄우고 종료되면 다시 시작한다.
- `patch_log_chat.py`: 2026-11-14 적용한 기록 보완(날짜, 2MB 교체, 이미지 진행 성공 기록 생략, 대화 성공·실패 기록) 패치. 이미 반영된 `server.py`에는 다시 적용되지 않는다.
- 배포 방법: 작업기 유휴 확인 → 대상 파일 백업 → 교체 → `worker.pid` 프로세스만 종료(supervisor가 재기동) → `/v1/health` 응답 확인.
- Tailscale 주소는 `overlay_endpoint.py`로 탐지한다. 호스트 DNS에서 찾지 못하면 공식 설치 CLI의 자기 IPv4 조회만2초 한도로 실행하고 성공60초/실패5초 캐시한다. `server.py`와 이 모듈은 함께 배포한다. 실제 모델/이미지 작업을 보존하고 유휴·모델 정리 확인 뒤 재시작한다. 원래 페어링 토큰/인증서·VPN·방화벽 설정은 바꾸지 않는다.

1089 이미지 보완은 `server.py`, 복구·진행 모듈, `engines.py`, `image_profiles.py`, `native_memory_image.py`, `windows_budget.py`, `resident_budget.py`를 함께 쓴다. 명시적 일시정지 재개에는 앱1089와 새 `explicit` 표지가 필요하다. 레거시 `patch_worker.py`는 원래 기준선의 복구 설치만 수행하며 새 모델/프로필/빠른 디코딩을 설치하지 않는다. 빠른 모델 파일은 별도로 검증해 내려받고 기존 모델을 교체하지 않는다. 정확한 핀·실행 옵션·실패 및 실제 폰 결과는 [이미지 검증 기록](../../docs/image-workflow-repair-2026-11-16.ko.md)을 따른다.


### 네이버 지역·쇼핑 공개 검색

배포 입력은 같은 작업기 폴더의 `server.py`, `naver_search.py`, 기존 `browser_agent.py`와 안드로이드 앱이다. 서버 파일을 교체하기 전에 새 모듈을 설치한다. 실행 중인 작업기 프로세스가 읽을 수 있는 환경에 네이버 검색 API 앱의 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`을 설정한다. 키 값은 휴대폰·대화·Git에 복사하지 않는다. 작업기를 유휴 상태에서 재시작한 후 페어링된 휴대폰의 health 응답에서 `naverPublicSearch`를 확인한다. 이 값은 키 두 개의 설정 여부이며 실제 조회 성공 여부는 별도다.

Codex·Claude의 기존 휴대폰 MCP에서 `search_web(source="naver_places")`는 업체명·주소를 조회하고, 판매처를 지정하지 않은 `search_shopping`은 네이버 쇼핑 표시가와 기존 공개 가격 출처를 함께 읽는다. 판매처를 네이버로 지정하면 네이버 조회 결과를 사용한다. 실시간 재고·배송비·결제 총액은 판매처에서 따로 확인한다. 키 또는 연결이 없으면 기존 공개 검색 경로를 쓴다. [네이버 지역 검색 API](https://developers.naver.com/docs/serviceapi/search/local/local.md) · [쇼핑 검색 API](https://developers.naver.com/docs/serviceapi/search/shopping/shopping.md)


### 공연예술통합전산망(KOPIS) 공연목록

공연 조회 배포 시 `kopis_search.py`를 `server.py`와 함께 설치한다. 기존 서버에서 두 커넥터를 함께 import하므로 사용 여부와 관계없이 두 모듈 파일을 함께 배포한다. 운영자가 이미 발급받은 `KOPIS_SERVICE_KEY`가 작업기 환경에 있을 때 health의 `kopisPublicSearch`가 설정된다. 모델 프로세스의 환경에서 이 키를 제거한다.

`search_web(source="kopis", query="공연명")`으로 공연 목록을 읽으며 기본 조회 기간은 한국 날짜 기준 오늘부터 31일이다. 선택 인자 `startDate`, `endDate`로 양 끝을 지정하고 1~5페이지에서 한 번에 5건씩 읽는다. 공연 ID, 장소, 공연기간·상태를 표시한다. 예매 가능 좌석·가격·할인·실제 구매 결과는 판매처에서 확인한다. 연결 불가 시 원래 공연명과 지정 기간을 공개 웹 검색으로 이어간다. [KOPIS 공식 개발 가이드](https://kopis.or.kr/upload/openApi/공연예술통합전산망OpenAPI개발가이드.pdf)

### 국내 항공 운항과 서울 공공서비스예약

`travel_public_search.py`를 작업기의 `server.py`와 함께 설치한다. `search_web(source="tago_flights", query="GMP-CJU", startDate="2026-12-01")`는 작업기 환경의 `TAGO_SERVICE_KEY`(공공데이터포털의 디코딩된 키)로 해당 날짜의 편명, 공항, 예정 시각을 조회한다. 할인·잔여 좌석·발권은 항공사에서 확인한다. [국토교통부 TAGO](https://www.data.go.kr/data/15098526/openapi.do)

`search_web(source="seoul_reservations", query="테니스")`는 키 없이 서울시 공식 통합 검색의 첫 화면에서 최대 5개 항목을 읽는다. 결과의 상세 주소에서 시설별 조건과 예약 가능 여부를 확인한다. `open_url`의 `service=srt_booking`, `seoul_reservations`, `silson24`는 공식 사이트를 휴대폰에서 연다. 실손 청구는 사이트에서 가입자의 인증·계약 선택·서류 확인·전송이 필요하다. [서울 공공서비스예약](https://yeyak.seoul.go.kr/web/main.do) · [SR 승차권](https://eticket.srail.kr/hpg/hra/01/selectScheduleList.do?pageId=TK0100000000) · [금융위원회 실손24 안내](https://fsc.go.kr/po010103/85456)
