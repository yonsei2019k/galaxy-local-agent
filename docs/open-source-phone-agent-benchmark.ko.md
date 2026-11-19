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
