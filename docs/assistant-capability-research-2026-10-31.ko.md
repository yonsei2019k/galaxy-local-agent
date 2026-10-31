# 스마트폰 비서 업무역량 확장 조사

조사일: 2026-10-31 KST. 대상: ZipHap.Agent Android 앱. 이 문서는 새 기능 선정과 통합·검증 기준을 기록한다. 격리 준비물의 존재를 서명 APK 포함, 휴대폰 설치 또는 실제 모델의 업무 성공으로 해석하지 않는다.

## 이번에 먼저 채울 빈자리

가장 효과가 큰 추가는 **로컬 계산 3종 + 근거 조회 API 2종 + 업무 흐름 3종**으로 판단했다. 기존 Google·문서·PC 분석 기능을 중복 설치하지 않고, 작은 모델이 계산을 추측하거나 출처를 만들어 내기 쉬운 작업을 실제 결과로 확인하게 한다. 필요 없는 공통 규칙과 문장별 예외를 더하는 방식은 피한다.

| 추가 | 해결할 문제 | 동작 범위 |
| --- | --- | --- |
| `calculate` | 견적·합계·비율·시간 비용을 모델이 암산하다 틀림 | 제한된 수식의 십진 계산. 네트워크·계정·임의 코드 실행 없음 |
| `date_calculate` | 기간·마감일·날짜 차이 계산 오류 | 명시적인 날짜와 연산. 자연어에서 날짜를 추측하는 도구 아님 |
| `find_free_time` | 일정이 비었다는 이유만으로 탑승·회의 가능 판정 | 명시한 바쁜 구간·허용 구간·여유 시간으로 빈 시간 계산. 계정 조회는 기존 일정 도구가 담당 |
| `search_publications` | 일반 검색 요약만으로 건강·과학 근거를 단정 | Europe PMC 공개 문헌 검색. 실제 검색어·초록·문헌 식별자·라이선스·조회 시점 반환 |
| `verify_reference` | 없는 논문·잘못된 DOI를 인용 | Crossref 등록 서지 조회. DOI 정확 조회와 제목 기반 후보를 구별 |
| `work-briefing` | 메일·할 일·일정을 각각 요약하고 후속 업무를 놓침 | 실제 자료에서 우선순위·마감·다음 행동을 정리하고 요청된 초안·할 일을 준비 |
| `availability-planning` | 업무 패턴·야간 근무·이동 여유를 누락 | 일정 조회 결과와 확인된 생활·업무 조건을 로컬 계산에 연결 |
| `evidence-research` | 검색 성공을 취재 완료로 취급 | 분야에 맞는 출처 선택, 문헌·서지 확인, 불일치 검토, 요청된 문서·표 생성 |

세 스킬은 자체 작성한 Android 지침이다. 특정 어휘·문장 형태의 분기표가 아니며 모든 요청에 같은 순서를 강제하지 않는다. 도구 결과에 따라 다음 작업을 고르게 한다. 스킬은 모델 가중치 학습, 새 계정 권한 또는 없는 실행 도구를 추가하지 않는다.

## 기존 기능과 중복을 피한 이유

현재 소스에는 기본·연결·PC·앱 실행·스킬 조회를 합한 도구 77개와 카탈로그 스킬 18개가 있다. Google Drive/Docs/Sheets/Slides/Gmail/Calendar/Tasks/Contacts/Forms/YouTube/Photos, 로컬 문서 생성, 선택 자료 PC 분석·NotebookLM 경로가 있으므로 같은 이름의 데스크톱 플러그인을 설치하는 것이 우선 과제가 아니다. **카탈로그에 등록됨·인증됨·실제 자료 처리 성공은 다른 상태**다. 최신 계정 연결·실기 결과는 `docs/app-connections.ko.md`와 `docs/agent-change-log.md`를 따른다.

Google 일정 API는 지정한 캘린더의 이벤트를 기간·페이지로 조회한다. 업무 정리에서 페이지 제한과 다른 캘린더 누락을 고려해야 한다. Gmail의 초안 생성과 발송도 별도 동작이다. 현재 앱은 실제 등록된 초안 도구까지만 연결한다. Google Tasks의 기본 목록 조회는 계정의 모든 프로젝트·모든 배정 업무를 읽는다는 의미가 아니다. [Calendar 이벤트 조회](https://developers.google.com/workspace/calendar/api/v3/reference/events/list), [Gmail 초안 생성](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.drafts/create), [Tasks 조회](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list).

## 선정한 무료 공개 API

### Europe PMC: 근거를 읽을 수 있는 문헌 검색

생명과학·의학 문헌의 제목, 식별자, 초록, 문헌 유형, 공개 열람·라이선스 등 구조화된 자료를 반환한다. 첫 연결은 별도 MCP/Python 실행기 없이 Android에서 공식 REST를 호출한다. 이번 조사에서 공개 합성 검색어 `TITLE_ABS:(shift work sleep)`를 키 없이 실제 조회해 HTTP200, 결과3969개, 첫 결과의 초록1747자를 확인했다. 이것은 한 공개 조회의 연결 증거이며 연구 결론이나 앱의 전체 취재 성능 검증이 아니다. [공식 REST 안내](https://dev.europepmc.org/RestfulWebService), [공식 API 공개 조회](https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=TITLE_ABS%3A%28shift%20work%20sleep%29&format=json&resultType=core&pageSize=1).

공개 열람 표시와 재사용 라이선스는 구분한다. 반환된 라이선스가 없으면 자유 재배포를 추측하지 않는다. 초록을 읽었으면 초록 수준의 근거로 보고한다. 다른 분야의 출장·가격·법·연결 방법 조사에 이 API를 강제로 사용하지 않는다. 현재 조사 환경에서 안내 웹페이지는 시간 초과/접근 차단이 있었으나 API JSON 조회는 성공했다. [권리 안내](https://dev.europepmc.org/Copyright).

### Crossref: 인용 정보 확인

가입 없이 DOI와 서지 정보를 조회할 수 있다. 대부분의 메타데이터는 자유롭게 사용할 수 있지만 일부 초록의 권리는 별도다. 등록 정보에는 출판 후 변경·철회 관련 정보가 있을 수 있다. 정확한 DOI 조회, 서지 후보 일치, 찾지 못함, 일시 실패를 서로 다르게 반환해야 한다. Crossref에 없다는 사실로 가짜 논문이라고 판단해서는 안 된다. [공식 REST 문서](https://www.crossref.org/documentation/retrieve-metadata/rest-api/).

2025-12-01부터 public pool은 단일 레코드5요청/초, 목록·검색1요청/초이며 동시 요청은1개다. 첫 구현은 보수적인 직렬 호출·간격 제한과 HTTP 상태 처리를 사용한다. 타인의 이메일을 임의 `mailto`로 넣어 제한을 우회하지 않는다. [공식 한도 변경 공지](https://www.crossref.org/blog/announcing-changes-to-rest-api-rate-limits/).

## 유용하지만 지금 자동으로 넣지 않은 후보

| 후보 | 공식 자료에서 확인한 사항 | 이번 판단 |
| --- | --- | --- |
| OpenAlex REST | 현재 무키 일일$0.10, 무료 계정 키$1.00 상당; 일반 검색 호출$0.001. 데이터와 API 제공 비용은 별개 | 여러 학문 분야로 넓힐 다음 후보. 최신 예산·키 관리·소진 처리를 함께 설계한 뒤 추가 |
| OpenAlex 공식 MCP | 공식 저장소가 있으나 MCP 클라이언트·인증·도구 연결은 REST 호출과 별개 | 이름만 설치해 휴대폰 기능으로 보고하지 않음 |
| PubMed E-utilities | 생명과학 문헌 조회용 공식 API | 첫 묶음의 Europe PMC와 중복이 커 후순위. 전문 검색이 필요할 때 보완 검토 |
| KMA 단기예보 | 공공데이터포털 무료, 개발10,000, 자동 승인. 서비스키와 좌표·발표 시각 처리 필요 | 국내 날씨의 다음 우선 후보. 실제 키 연결 후 추가하고 키 없이 완료했다고 하지 않음 |
| KASI 특일 정보 | 국경일·공휴일·기념일·절기 조회. 무료, 개발10,000, 자동 승인. 서비스키 필요 | 국내 업무일 계산의 다음 후보. 법정 공휴일과 실제 개인 근무일은 구분 |
| MET Norway Locationforecast | 날씨 데이터 CC BY4/NLOD2. 출처 표시·식별 User-Agent·캐시·조회 규칙 필요 | 상업 사용도 가능한 날씨 대안. 실제 연락 가능한 앱 식별자와 캐시 설계 후 추가 |
| Open-Meteo | 무료 서비스는 비상업용.10,000/일·5,000/시간·600/분 | 사업용까지 무조건 무료인 기본 제공자로 묶지 않음. 사용 조건에 맞는 별도 선택지 |
| Nager.Holidays | 개인·비영리 WebAPI, 상업은 active sponsorship. NuGet/Docker는 별도 라이선스 | 업무 앱에 조건 없이 무료 기본 탑재하지 않음. 국내는 KASI 우선 검토 |
| Amadeus Self-Service | 공식 개발 문서가 deprecated, 저장소2026-07-17 archive; 공식 발표는 Enterprise 제공 지속 명시 | 과거 무료 할당량을 근거로 새 항공료 연결을 만들지 않음 |
| Skyscanner Travel API | 상업 계약·승인 필요. 개인 비상업 프로젝트는 제공 대상 제외, 파트너 심사 기준 존재 | 누구나 바로 쓰는 무료 항공료 API로 추천하지 않음 |

근거: [OpenAlex 최신 사용 비용](https://help.openalex.org/access/example-costs/), [가격·CC0 정책](https://help.openalex.org/access/pricing/), [공식 MCP](https://github.com/ourresearch/openalex-mcp-server), [PubMed 공식 E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25497/), [KMA 단기예보](https://www.data.go.kr/data/15084084/openapi.do), [KASI 특일](https://www.data.go.kr/data/15012690/openapi.do), [MET Norway 이용 조건](https://api.met.no/doc/TermsOfService), [데이터 라이선스](https://api.met.no/doc/License), [Open-Meteo 약관](https://open-meteo.com/en/terms), [Nager 약관](https://nagerholidays.com/legal/termsofservice), [패키지 라이선스](https://nagerholidays.com/integration/getstarted), [Amadeus 공식 deprecated 문서](https://github.com/amadeus4dev/developer-guides), [Amadeus 공식 발표](https://amadeus.com/en/industry-messaging/statement-regarding-amadeus-for-developers-portal), [Skyscanner 신청 조건](https://www.partners.skyscanner.net/contact/travel-api), [API 인증](https://developers.skyscanner.net/docs/getting-started/authentication).

KMA/KASI는 조사 도구의 페이지 변환이 실패해 공식 페이지를 직접 HTTPS 조회하여 무료·자동 승인·개발 할당량·인증키 안내를 확인했다. 키를 발급하거나 실제 날씨·특일 API를 실행한 것은 아니다. Amadeus 홈페이지는 동적 페이지여서 배너를 직접 읽지 못했다. 공식 GitHub의 폐기 안내와 archive 날짜까지만 이번 조사에서 직접 확인했으며 정확한 서비스 종료 문구를 읽었다고 기록하지 않는다.

## MCP·플러그인 설치의 실제 효과

**Playwright MCP**는 동적 항공·여행 사이트처럼 일반 텍스트 조회로 부족한 페이지를 조사하는 PC 확장 후보다. 공식 구현은 접근성 스냅샷을 사용하며 Node20+와 MCP 클라이언트를 요구한다. 폰에 설치되는 Kotlin 실행 도구가 아니므로, PC 프로세스 설치 외에도 현재의 인증된 PC 연결, 요청 범위, 취소, 결과·가격 시점 반환을 앱에 연결해야 한다. 첫 묶음은 이 통합을 완료하지 않으므로 브라우저 조사 성공을 주장하지 않는다. [Microsoft 공식 Playwright MCP 안내](https://github.com/microsoft/playwright/blob/main/docs/src/getting-started-mcp.md).

**MarkItDown MCP**는 PDF·Office 등 자료를 읽기용 Markdown으로 바꾸는 PC 후보다. 공식 도구의 `convert_to_markdown(uri)`는 파일·HTTP·data URI를 받으며 서버는 로컬 신뢰 에이전트 용도다. 현재 선택 파일 분석과 문서 기능이 있으므로 중복부터 확인한다. 도입한다면 선택한 파일만 전달하는 기존 PC 경계에 연결하고 원본 서식 편집이나 OCR 정확도 향상을 자동으로 주장하지 않는다. [Microsoft 공식 README](https://github.com/microsoft/markitdown/blob/main/packages/markitdown-mcp/README.md).

**MCP Registry**는 후보를 찾는 공개 목록이다. 카탈로그에 존재한다는 이유로 해당 코드의 호환성·신뢰성·폰 실행을 확인한 것은 아니다. 업무에 필요한 원 제공자의 API가 단순 REST 두 개라면 범용 MCP 런타임보다 작게 직접 연결하는 편이 이 앱의 설치·메모리·오류 구분에 적합하다는 구현 판단이다. [공식 Registry](https://github.com/modelcontextprotocol/registry).

기존 Gmail·Drive·Calendar·Contacts 데스크톱 플러그인은 개발 환경에서 유용하지만 Android 앱의 계정 연결 코드와 독립적이다. 데스크톱에 설치한 수를 폰 도구 수에 더하지 않는다. NotebookLM·선택 자료 PC 분석도 페어링·실행 결과가 있어야 실제 폰 업무로 검증된다.

## 항공편 사례에서 이번 확장의 효과와 남은 부분

“일주일치 제주 항공편 중 내 업무 패턴으로 탈 수 있는 가장 싼 왕복편”은 다음 결과가 서로 연결되어야 한다. 문장을 특정 패턴으로 고정할 이유는 없다.

1. 실제 날짜·출발지·요청 기간과 이미 알려진 업무 패턴을 보존한다.
2. 관련 일정·근무 제약을 읽고 이동·탑승 여유를 포함해 가능한 시간 구간을 계산한다.
3. 그 구간의 실제 항공편과 동일 조건의 요금·수하물·세금·조회 시점을 확인한다.
4. 갈 때와 올 때를 묶어 조건을 충족한 관찰 후보 중 총액을 비교한다.

이번의 날짜·빈 시간·수치 계산과 workflow는 1·2·4의 오류를 줄일 실행 기반이다. **3의 현재 항공요금 연결을 새로 설치한 것은 아니다.** 동적 웹 조회 또는 별도 승인된 공급자 연결과 실제 결과가 필요하다. 그 상태에서 Danawa 일반 상품 검색으로 문제를 바꾸거나 “전 노선 최저가”라고 쓰는 것은 완료 기준에 맞지 않는다.

## 통합·검증 인계

1012 normal/test/offline APK의 불변 서명·설치 확인 뒤 공식1013 소스 창에서 새 기능을 canonical에 병합했다. 기존 파일은 적용 직전 SHA를 확인하고 파일별 기준선을 보존했으며, 카탈로그의 기존18항목은 유지하고 새3항목만 추가했다. 현재 등록82도구·카탈로그21스킬이며 legacy research를 포함한 본문 파일은22개다. 이 변경은 이미 설치된1012에 들어 있지 않다.

실제 연결은 ToolRegistry 인자·중첩배열 schema, 모드·네트워크 능력 표시, 읽기/계산 효과 구분, ToolExecutor의 실행 handler까지 포함한다. 공개 취재는 로컬 모델이 선택한 서지 도구가 있는 경우 EvidenceResearchWorkflow로 들어가며 공개 API/웹/관찰한 원문 중 다음 행동 또는 종료를 선택한다. 최대6제공자 요청·4도구 실행을 지킨다. 외부 자료가 계정 읽기 권한이나 새 도구 권한을 만들지 않는다. 신규 evidence-research는 조사 결정과 답변 단계에서 실제 SHA 검증 본문을 읽는다.

격리 Kotlin 컴파일 검사52개(로컬 계산18·공개 API17·등록/모드/실행계획5·조사 루프9·writer 근거 문맥3)가 통과했다. 실제 새 API production transport의 PC 공개 조회2개도 성공했다. 휴대폰·계정·실제 모델 추론은 이 검사에서 사용하지 않았다. **전체 정식 보안/앱 검사,1013 릴리스 빌드·서명·설치 및 폰 실제handler 검증은 통합 담당의 단일 빌드/기기 창에서 후속 수행한다.** 이전1012의 검사597개를 신규 기능 통과로 사용하지 않는다.

독립 최종 검토에서 generic JSON 압축이 모든 문자열을300자로 잘라 초록·서지 근거를 잃는 문제를 찾아 보완했다. 새 API 결과는 타입 있는 필드로 보존하고4500자 이내 전용 writer 문맥을 사용한다. DOI·초록·라이선스·변경/철회 메타데이터와 실제로 읽은 수준을 남기며 문맥을 줄이면 표시한다. 공개 API와 원문 열기·상품 검색이 함께 계획되어도 지원 조회부터 진행하고 이 루프 밖의 미수행 도구를 partial에 남긴다. UI 열기나 상품 조회를 수행한 척하지 않는다.

- 스킬은 본문별6KiB 이하, UTF-8·SHA·등록 도구 참조·전체 목록4000자 예산을 확인한다. 기존18개와 합쳐21개가 되는 예상은 최종 병합 기준으로 재검사한다.
- 계산 검사는 소수 정밀도·괄호·0나눗셈·길이/깊이, 날짜 경계·윤년·잘못된 날짜, 빈 시간의 겹침·경계·야간·서머타임·버퍼를 다룬다.
- 공개 API는 합성 공개 질의의 실제 HTTP 결과, 응답 파서, 초록·라이선스 누락,429·서버 오류·취소, DOI 정확 조회와 후보·미조회 구분을 확인한다. 조회하지 못한 상태를 문헌 부재로 바꾸지 않는다.
- 최종 릴리스 빌드·서명·설치 해시를 남긴 뒤 실제 로컬 모델이 조건이 변형된 요청에서도 필요한 도구를 고르고 관찰 결과를 반영하는지 별도로 검사한다.
- 업무 정리는 우선순위·기한·후속 작업이 원문과 맞는지, 빈 시간은 제약을 누락하지 않는지, 연구는 읽은 수준·출처·불확실성을 지키는지 최종 결과로 평가한다. 첫 도구 이름이나 JSON 형식 통과만을 업무 성공으로 채점하지 않는다.

검증 결과와 실제 설치 상태는 병합 담당이 `docs/agent-change-log.md`에 덧붙인다. 이 조사와 스킬 준비만으로 작은 모델의 문맥 이해가 해결됐다고 보고하지 않는다.
