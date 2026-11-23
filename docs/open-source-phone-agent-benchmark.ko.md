# 오픈소스 스마트폰 에이전트 벤치마크

2026-11-19 기준으로 공식 저장소와 아래 코드 경로를 검토했다. 코드를 가져오거나 외부 앱을 설치하기 전, 집합에서 얻을 수 있는 기능과 시험 기준을 정리한 결과다.

| 우선순위 | 검토한 구현 | 집합에서 비교할 기능 |
| --- | --- | --- |
| 1 | **AndroidWorld** (Apache-2.0). 연락처 검증기가 이름과 정규화한 번호를 실제 기기 상태에서 읽어 비교한다. [연락처 검증기](https://github.com/google-research/android_world/blob/main/android_world/task_evals/common_validators/contacts_validators.py) | 전용 에뮬레이터에 기준 데이터와 동명이인·유사 레코드를 넣고, 집합의 완료 메시지와 독립 조회를 별도로 평가한다. 정확한 레코드 변경과 비대상 레코드 보존이 모두 필요하다. 연락처 초기화 코드는 격리 환경에서만 사용한다. |
| 2 | **PhoneUse** (MIT). 관찰 전체에 기한을 두며 현재 앱과 요소 조건을 평가한다. 화면 수집이 불완전하거나 대상이 여러 개면 각각 미확인·모호함을 반환하고 읽기만 반복한다. [ConditionalObservation.kt](https://github.com/nidhoggfgg/PhoneUse/blob/main/app/src/main/java/ai/nidhoggfgg/phoneuse/device/ConditionalObservation.kt) | `ScreenBridge`·`ScreenControl`의 후속 관찰에 적용할 설계다. 앱·화면 버전과 대상 일치를 확인하고 변경 결과가 불명확하면 화면 조회만 제한적으로 반복한다. 승인이나 로그인·결제 경계는 기존 Android 호스트가 판단한다. |
| 3 | **Mobilerun** (MIT). 화면의 앱 패키지에 대응하는 Markdown 지침을 불러온다. [App Cards 구현 안내](https://github.com/droidrun/mobilerun/blob/main/mobilerun/config/app_cards/README.md) | 집합의 기존 스킬 경로에서, 모델이 실제로 선택한 앱의 패키지·지원 버전에 해당하는 짧은 탐색 지침을 불러오는 방식을 비교한다. 티맵 경로 조회와 쿠팡 상품 탐색처럼 읽기 시나리오부터 측정하고, 카드나 외부 화면 텍스트가 도구 권한을 부여하는 경로는 두지 않는다. |
| 4 | **Mobile-Agent v3** (저장소 MIT, 포함 모듈은 파일별 확인). 화면 전후를 비교해 기대 결과, 잘못 열린 화면, 변화 없음으로 나누고 최근 행동 결과를 다음 판단에 제공한다. [실행과 결과 비교 코드](https://github.com/X-PLUG/MobileAgent/blob/main/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3.py#L383-L438) | 실패 직후 관찰을 도구·대상·기대 상태·실제 상태·실패 코드로 축약해 다음 추론에 전달한다. 모델의 재판단은 구조화된 상태로 판정이 어려울 때 비교하고, 실제 완료는 독립 조회로 판정한다. 스크린샷과 추론 원문을 저장하는 실행 기록 방식은 운영 폰에 적용하지 않는다. |
| 5 | **Open-AutoGLM** (Apache-2.0). 화면 조작 중 사람이 넘겨받는 콜백과 로그인 상황의 중단 구조가 있다. 민감 클릭 확인은 모델이 메시지 필드를 보내는지에 의존하는 경로가 있다. [ActionHandler](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/actions/handler.py#L22-L81) · [클릭 확인 분기](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/actions/handler.py#L120-L137) | 중단·사용자 조작·현재 화면 재확인 뒤 이어지는 UX를 비교한다. 집합의 로그인·결제 조건은 실제 화면·도구·작업 범위를 호스트가 검사한다. ADB 화면 조작은 개발 시험 환경에서만 비교한다. |

## 집합에 적용할 비교 순서

1. **행동의 실제 결과 검증** — 집합 기본 경로와 변경 후보를 같은 에뮬레이터 상태에서 반복한다. 연락처 동명이인, 잘못 열린 목록, 저장 후 지연, 화면 이동 중 관찰, 이미 적용됐는지 모르는 쓰기를 넣는다. 정확한 대상 조회, 한 번만 적용, 비대상 보존, 보류 상태의 정확성을 각각 채점한다.
2. **작업 흐름 개선** — 앱별 지침이나 직전 실패 상태가 있을 때 다음 시도의 성공률, 잘못된 대상 선택 횟수, 재시도 횟수, 수행 시간, 추론 호출 수와 토큰을 비교한다. 화면 오인식·연결 중단·권한 만료를 분리한다. 교훈 자동 반영은 같은 경계 시험에서 개선을 재현한 뒤 연결할 후속 작업이다.
3. **정확한 완료 보고** — 한국어 실제 휴대폰 시나리오에는 짧은 질문과 추가 답변, 결제 전 중단, 정확한 결과 검증을 포함한다. AndroidWorld 점수, 합성 결과, 집합의 실제 휴대폰 결과는 각각 별도 기록한다. 평가용 접근성과 개발 권한을 개인 휴대폰 운영에 추가하지 않는다.

현재 단계는 소스 비교다. 외부 에이전트를 실행하거나 해당 코드를 집합에 반영하지 않았다.

## 2026-11-23 — 로그인 인계와 화면이 달라졌을 때의 복구 비교

- **OpenPhoneUse (MIT, `54a3ef0`)**는 Android 접근성 트리와 이미지를 이용해 다음 행동을 결정하고, 클릭 뒤 실제 화면을 다시 읽는다. `ChatUserGate`는 현재 요청의 ID로만 응답을 받고 요청이 끝나거나 취소되면 확인창을 정리한다. `ScreenWatch`는 모델이 판단하는 중 화면이 달라졌을 때 기존 판단을 중단하고 최신 상태를 넘긴다. 재생 시간처럼 숫자만 달라진 변화는 별도로 처리한다. [ChatUserGate](https://github.com/joungminsung/OpenPhoneUse/blob/54a3ef025dabc8524028506b2cf7204096a8de24/app/src/main/java/com/phoneuse/app/ChatUserGate.kt) · [ScreenWatch](https://github.com/joungminsung/OpenPhoneUse/blob/54a3ef025dabc8524028506b2cf7204096a8de24/app/src/main/java/com/phoneuse/agent/ScreenWatch.kt)
- **OpenDroid**는 별도의 PC를 전제로 하지 않는 Android 앱이며 화면 읽기와 터치, 재사용 가능한 스킬을 제공한다. **Mobilerun**은 여러 실행 환경에서 Android 관찰·조작을 통합하는 개발용 구성이다. **Open-AutoGLM**의 수동 인계 콜백은 로그인·인증의 전환을 비교하기 좋다. 두 개발용 경로의 ADB나 외부 접근성 서버는 집합 운영 구성을 변경하지 않는다. [OpenDroid](https://github.com/Silung/OpenDroid) · [Mobilerun](https://github.com/droidrun/mobilerun) · [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)
- 실제 참조 앱의 교육 대화는 HTTP 브라우저 인계, HTTPS 로그인 카드, 페이지 전환과 학습시간의 별도 확인을 보여 줬다. 채팅의 보고만으로 이수 여부나 자격 증명의 실제 저장 방식을 확인하지 않았다. 실제 시험에서는 페이지 증거와 사이트의 진도·이수 상태를 별개로 확인한다.
- 집합의 앱 브라우저는 같은 창에서 사용자가 직접 입력하고, 현재 인계 ID의 ‘로그인했어요’만 받는 짧은 대기를 추가한다. 대기 중 모델 화면 조회를 멈추고, 확인한 뒤 이전 탐색을 반복하지 않은 채 HTTPS의 동일 작업 출처에서 화면을 새로 읽는다. 원본 비밀번호를 저장하는 기능은 이 변경에 포함되지 않는다.

## 내장 브라우저와 PC 브라우저 제어 비교 (2026-11-24)

| 후보 | GitHub 별 수 | 집합 적용 |
| --- | ---: | --- |
| [Playwright](https://github.com/microsoft/playwright) | 100,895 | Apache-2.0. 클릭이 실제 대상에 도달할 수 있는지 검사하는 방식을 내장 WebView에 적용했다. 의존성 1.65.1은 실제 Chromium 회귀 시험에 사용한다. |
| [Puppeteer](https://github.com/puppeteer/puppeteer) | 95,894 | Apache-2.0. PC Chromium 제어 대안이다. 현재 집합 PC는 이미 격리 프로필과 헤드리스 CDP를 사용한다. |
| [Browser Use](https://github.com/browser-use/browser-use) | 121,835 | MIT. 웹 작업 계획과 도구 실행을 묶는 Python 에이전트다. 도입 시 집합의 기존 모델 연결·승인·증거 처리와 겹친다. |
| [chromiumoxide](https://github.com/mattsse/chromiumoxide) | — | MIT/Apache-2.0. 향후 PC CDP 계층을 Rust로 옮길 경우 비교할 후보이다. |

별 수는 조회 시점의 관심도이며 처리시간 비교 자료가 아니다. [Playwright Android 지원](https://playwright.dev/docs/api/class-android)은 인증된 ADB가 필요한 실험 기능이다. 제품의 폰 실행은 시스템 WebView 위에서 이뤄지고, Playwright는 테스트 단계에서 실행된다. [클릭 가능 여부 검사](https://playwright.dev/docs/actionability) 중 가림과 실제 대상 확인을 적용했다. 신규 가림 상태는 클릭이 전달되기 전에 반환되므로 새 페이지 관측을 요구한다. 기존 스냅샷·승인 확인과 휴대폰의 로그인 인계 경로는 유지된다.

Windows에서 재현하려면 `scripts/browser-validation`에서 `npm ci --ignore-scripts`, `npx playwright install chromium --only-shell`, `npm test` 순으로 실행한다. 브라우저 시험은 모바일 뷰포트에서 가림, 가림 해제, 같은 모양의 요소 교체, 비활성 영역을 검증한다. 실제 사이트 로그인·이수와 전체 자율 업무의 처리 속도는 이 시험 범위 밖이다.
