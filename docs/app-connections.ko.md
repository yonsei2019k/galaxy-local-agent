# 앱·계정·PC 분석 도구 연결

2026-10-31 사용자의 앱 연결·빌드 우선 요청에 따라 추가했다. 소스와 APK 포함 여부, 실제 로그인·폰 동작은 별도의 증거다.

## Outlook 메일·일정

집합은 Microsoft 공개 클라이언트의 OAuth 기기 코드 방식으로 휴대폰에서 계정을 연결하고 Graph API로 메일 제목·발신자와 일정 제목·시각을 조회한다. 연결은 메일과 일정 각각 승인한다. 두 기능 모두 쓰기 권한을 요청하지 않는다.

1. 배포자는 Microsoft Entra의 앱 등록에서 사용할 개인·조직 계정 유형을 선택하고 **Allow public client flows**를 활성화한다. 배포 환경 변수 또는 GitHub Actions의 Repository variables에 `ZIPHAP_OUTLOOK_CLIENT_ID`와 필요 시 `ZIPHAP_OUTLOOK_TENANT`를 지정해 빌드한다. tenant는 조직 디렉터리 UUID 또는 `common`(기본), `organizations`, `consumers` 중 하나다. 이 프로토콜에는 리디렉션 URI와 클라이언트 시크릿이 필요 없다. 앱 등록이 없는 빌드는 연결 메뉴에 이를 표시한다. [Microsoft 기기 코드 인증](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)
2. 집합의 앱 연결에서 Outlook 메일 또는 일정을 선택해 휴대폰에 표시된 단기 코드를 공식 Microsoft 로그인 화면에 입력한다. 비밀번호·MFA는 Microsoft가 처리한다. 등록과 조직 정책에 따라 관리자 동의가 필요할 수 있다. 로그인 계정은 Graph `/me`로 확인하고, 기기 내 암호화 저장소에서 해당 서비스의 토큰만 갱신한다. 로그인 취소·앱 연결 해제 후에는 그 연결 요청을 재사용할 수 없다.
3. 받은편지함은 위임 `Mail.ReadBasic`으로 최근 20개의 헤더를, 일정은 `Calendars.ReadBasic`으로 요청 기간의 이벤트 첫 20개를 읽는다. 응답에 다음 페이지가 있으면 추가 결과가 있음을 표시한다. 계정 확인에 `User.Read`, 지속 연결에 `offline_access`를 요청한다. Android 설치본으로 권한 철회, 재연결, 실제 계정 결과의 대조가 남아 있다. [메일 조회](https://learn.microsoft.com/en-us/graph/api/user-list-messages?view=graph-rest-1.0) · [일정 조회](https://learn.microsoft.com/en-us/graph/api/calendar-list-calendarview?view=graph-rest-1.0)

## 앱 진입점

도구 화면 상단의 **계정·앱 연결 · 사진 글자 읽기**를 누른다. 기존 문서·Word/Excel·이메일·음성·영상·사진 도구와 메시지 알림 설정은 유지한다.

- Google Drive, Docs, Sheets, Slides, Gmail, Calendar, Tasks, Contacts, Forms, YouTube, Photos: 서비스별 계정 연결과 실제 자료 조회 버튼을 제공한다. 사용자가 제공한 Android OAuth 등록과 기존 앱 서명을 확인한다. 계정 연결만으로 실제 자료 읽기 성공을 표시하지 않는다.
- **GitHub**는 지정한 저장소의 최근 열린 이슈 한 페이지를 조회한다. 저장소를 선택한 세분화 토큰에 Issues 읽기 권한을 주고 보안 입력란에 등록한다. 토큰 소유자를 먼저 확인하고, 실제 자료 조회에는 `소유자/저장소`를 입력한다. 응답의 PR은 제외하므로 최대 20개보다 적을 수 있다. [GitHub 저장소 이슈 조회](https://docs.github.com/en/rest/issues/issues#list-repository-issues)
- **Slack**은 선택한 채널 ID의 최근 메시지를 최대 15개 조회한다. 공개 채널은 `channels:history`, 비공개 채널은 `groups:history` 권한이 필요하다. 봇을 사용할 때는 해당 채널에 초대한 뒤 보안 입력란에 연결 토큰을 등록한다. 워크스페이스의 앱 설치 승인 정책이 적용된다. [Slack 채널 이력 API](https://docs.slack.dev/reference/methods/conversations.history/)
- **구글 전체 연결 · 서비스별 권한 확인**은 11개 서비스를 순서대로 승인한다. 각 요청에서 이전 프로젝트의 권한 자동 합산을 끄고 같은 계정에 연결한다. Drive와 YouTube 권한의 합산으로 발생했던 실제 `400 invalid_request`를 이 방식으로 해결했다.
- Telegram Bot, Notion, Todoist: 네이티브 설정 화면에서 연결 토큰을 입력한다. 토큰은 모델 대화에 보내지 않으며 Android Keystore로 암호화한다. Telegram은 Bot이 볼 수 있는 허용 대화의 업데이트만 조회하며 개인 계정 전체 기록에 접근하는 기능은 아니다.
- OCR: 사용자가 선택한 사진의 한글·영문 글자를 읽는다. Google Play의 인식 모듈 준비·다운로드가 필요할 수 있으며 OCR 모델을 APK에 넣지 않는다.
- 공유 자료: 다른 앱에서 ZipHap으로 공유한 텍스트를 현재 선택 대화에 명시적으로 가져온다. 공유 본문의 지시는 실행하지 않는다. 전체 KakaoTalk 자동 내보내기·익시오 전체 기록 접근이 검증된 기능이라는 뜻은 아니다.
- 티맵·넷플릭스·쿠팡·쿠팡이츠: 설치된 앱을 열고 티맵 목적지, 넷플릭스 콘텐츠, 쿠팡 검색·상품 링크로 연결한다. 쿠팡 검색은 실제 기기의 연결 상태에 따라 브라우저로 열린다. 실행 경로 확인과 각 앱의 로그인·재생·주문 완료는 구분한다.

Google Chat, Keep, AI Studio/Gemini API, Home, Blogger, Books, Classroom, Meet는 사용자 제외 요청을 유지한다. Gmail은 초안 작성까지만 지원하고 메일 발송 도구를 추가하지 않는다. Calendar 작성은 참석자 초대를 추가하지 않으며 원격 작성은 저장 후 실제 읽기 검증과 실행 ID 기반 중복 방지를 사용한다.

## PC 분석·NotebookLM

기존 PC 보조의 연결 파일을 재사용한다. 연결 화면에서 **등록한 PC의 분석·NotebookLM 도구 사용 허용**을 선택하고 **PC 도구 실제 연결 확인**으로 검증한다. PC 분석 작업기는 기존 추론·이미지 작업기를 중단하거나 교체하지 않으며 별도 인증 TLS 경로를 사용한다. PC와 스마트폰 사이에는 등록한 인증서 고정과 암호화된 연결 자격을 사용한다.

분석할 CSV·TSV·Excel·ODS·Parquet 파일을 **현재 대화의 분석 파일 선택**에서 선택한다. URI·파일 내용 SHA256·선택 대화를 함께 검사한다. 앱 전송은 최대6MiB, 분석 기본10만 행이다. 선택 후 내용이 바뀌면 다시 선택해야 한다. 다른 대화의 선택 파일을 사용하지 않으며 임의 PC 파일 경로나 코드 실행을 원격 API로 제공하지 않는다.

비서가 호출할 수 있는 새 PC 도구는 다음과 같다.

| 도구 | 실제 처리 |
| --- | --- |
| analyze_selected_data | 표 구조·결측치·숫자 통계·상관관계·선형 회귀·군집·PNG 차트 |
| query_selected_data | 입력 표 data에 대한 단일 읽기 전용 SQL, 최대1000행 |
| list_notebooklm_notebooks | PC의 별도 인증 프로필이 접근할 수 있는 노트북 조회 |
| query_notebooklm | 지정한 기존 노트북에 질문, 별도 새 대화 사용 |

NotebookLM은 PC 전용 프로필의 실제 로그인이 필요하다. 지원 도우미는 버전이 고정된 별도 프로그램이며 공식 NotebookLM 공개 API라고 주장하지 않는다. 계정 미인증·권한 부족은 연결 필요로 표시한다. 목록 조회·질의만 제공하고 노트북 삭제·자료 공유 API는 추가하지 않는다.

PC에서 설치한 pandas/NumPy/Polars/PyArrow, DuckDB, SciPy/statsmodels/scikit-learn, Matplotlib/seaborn/Plotly, openpyxl/XlsxWriter/python-calamine, JupyterLab/ipykernel, SQLGlot 환경을 유지한다. 앱은 고정된 분석 작업을 호출하며 Jupyter 서버나 임의 Python 코드를 원격으로 실행하는 기능을 제공하지 않는다.

## 동작과 권한 표시

새 cloud 도구26개와 PC 도구4개, 위 네 앱 연결 도구9개를 기존 schema·dispatcher·capability·semantic privacy 검증에 등록했다. 서비스별 권한과 실제 검증 기록을 분리하고 매 호출에서 현재 자격·대화·파일 선택을 확인한다. 외부 자료는 도구 권한이 아니다. PC 도구는 네트워크 연결이 필요한 기능으로 표시한다.

## 이 변경의 검증 범위

연결 담당이 검사한 배포본은 **1011-authfix**이며 SHA256은 `9e15cc03a50ee49fb4fcf758e9b44eb6ab9fd0cf0c81c8625c4351740bf93f58`이다. core412개와 app104개 JVM 검사가 실패·오류·스킵 없이 통과했다. 같은 서명으로 실제 폰에 업데이트하고 설치된 base.apk의 해시를 대조했다. 기존 대화·모델·앱 데이터를 초기화하지 않았다. 이 별도 연결 빌드가 다른 담당의 이후 모드·장문·이미지 변경까지 포함한 통합본이라는 뜻은 아니다.

실제 폰에서 Google11개 서비스의 계정 승인 검사가 통과했다. 이어 Drive·Gmail·Calendar·Tasks·Contacts·YouTube의 최소 메타데이터 조회가 HTTP200으로 응답했고 Photos의 빈 선택 세션 생성·조회·삭제까지 확인했다. 문서·메일 본문과 사진 내용은 읽지 않았다. Docs·Sheets·Slides·Forms의 계정 승인은 완료됐으며 실제 사용할 자료 ID를 선택한 뒤의 조회·작성 검증은 별도다. 네 소비자 앱의 설치·실행 handler와 넷플릭스 콘텐츠 링크, 쿠팡 검색의 브라우저 연결도 실제 기기에서 확인했다.

PC 작업기 TLS/인증/실제 분석8개 검사와 숨김 실행 중인 실제 작업기의 합성 SQL 집계·파일 해시 대조도 확인했다. 이후 사용자가 NotebookLM 전용 프로필에 직접 로그인했고, PC 작업기의 실제 인증 TLS 요청으로 노트북 목록 조회도 통과했다. 제목·노트북 ID·계정명·인증 토큰은 검증 로그에 남기지 않았다. **PC 도구의 휴대폰 LAN 연결·권한·실제 분석은 아직 별도 검증이 필요하다.** Telegram Bot·Notion·Todoist는 네이티브 설정 화면에서 별도 자격을 연결해야 한다.

빌드는 다른 작업의 기존 산출물을 보존하기 위해 별도 소스 스냅샷에서 수행한다. 신규 파일18개와 기존 연결 지점8개는 파일별 SHA256 기준선 확인 후 canonical에 최소 병합했다. 이번 변경에 native source 수정이 없음을13개 파일에서 확인하고, 해당 소스보다 새로 빌드된 기존 native 라이브러리3개를 SHA256 대조 후 APK에 재사용했다. 새 네이티브 런타임을 처음부터 다시 빌드했다고 주장하지 않는다. 기존 K2 vital-lint 분석기 문제 때문에 이 빌드의 해당 lint 작업은 제외했으며 lint 통과 증거로 표현하지 않는다.

서명 APK·소스 기준선·검사·캐시 재사용·서명/포함 검사 영수증은 저장소 밖 connections-kit/artifacts에 보존한다. 기기 설치·계정 로그인·실제 전화기 작업이 이루어지기 전에는 PC 검사와 APK 빌드만으로 사용 완료라고 표시하지 않는다. 인증 토큰·연결 주소·개인 자료·모델 가중치는 소스나 Git에 넣지 않는다.

### 연결된 Notion 페이지와 YouTube 재생목록 읽기

Notion에서 제목을 검색한 뒤 선택한 페이지의 본문을 읽을 수 있습니다. 하위 블록은 한 번에 8개씩 읽고, 접힌 항목은 해당 블록을 열어 확인합니다. 긴 글은 다음 위치부터 이어 읽습니다. 파일·영상 자체를 내려받지는 않습니다. [Notion 블록 조회](https://developers.notion.com/reference/get-block-children)

YouTube에서는 내 재생목록을 조회하고 선택한 목록의 동영상 제목·순서·ID를 한 번에 20개씩 확인합니다. 다음 페이지가 있으면 같은 목록에서 이어 읽습니다. 기존 계정의 읽기 권한을 사용합니다. [YouTube 재생목록 항목 조회](https://developers.google.com/youtube/v3/docs/playlistItems/list)
