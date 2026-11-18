# 도구 우선 실행과 화면 복구로 개선하는 자동화

설계 과제 ORCH-20261118-ADAPTIVE · 2026-11-18 KST 기준 검토. 기준 HEAD a934de6b038c7b2ae6196e8cff0dd68c0c7f849f와 진행 중인 미커밋 소스. **제안 설계이며 구현·빌드·설치·실행 완료가 아니다.** 통합 채팅의 실제 사용자 지시인 “도구로 진행하고 막힌 단계부터 computer use로 완료한 뒤 재사용 도구 제작”, “APK 없는 도구 업그레이드”, 일반 자동화 사전 승인과 로그인·결제 별도 승인을 반영한다.

## 실행과 복구 계약

모델이 현재 요청과 관찰 결과로 등록 도구·검증된 자동화·범용 UI 중 다음 행동을 선택한다. 자연어 키워드로 경로를 고정하지 않는다. 같은 대화 안에서 진행·인계·실제 결과를 보여 주며 별도 작업 모드를 요구하지 않는다.

**도구 실행 → 막힌 단계 checkpoint → 실제 상태 재확인 → 허용된 UI 복구 → 효과 재조회 → 재사용 후보 → 검증 → 활성판**

1. **도구 우선:** 현재 앱·계정·범위·버전에 맞는 검증판이 있으면 고정 실행기로 수행한다. 요청의 기존 승인 범위 안에서 앱 열기·계산·읽기·일반 입력을 매번 다시 승인받지 않는다. 호스트가 새 요청에 캡처한 제한적 grant로 처리하고, 로그인·결제는 별도 사용자 승인/직접 입력으로 인계한다. 계정·금액·작업 범위가 달라지거나 Android 특별 권한이 필요하면 기존 grant를 확대하지 않는다. 오래된 큐·읽기 전용 요청의 제한도 그대로다.
2. **단조 checkpoint:** 효과 직전 run/단계/시도 ID, 대상 ID, 효과 종류, 인자 digest, 승인 grant, 실행기·패키지 hash, 입력 상태 버전을 로컬 암호화 원장에 저장한다. 단계 결과는 not_attempted / confirmed_no_effect / applied / verified / unknown / owner_handoff로 보존한다. 완료 frontier는 실제 검증 단계만 전진하고 과거 기록을 고쳐 성공으로 만들지 않는다. 앱 재시작·네트워크 유실 때 in-flight 쓰기는 unknown으로 복원한다.
3. **막힌 곳부터:** 실행 전 거절이 입증되면 그 단계의 UI 대안을 고를 수 있다. 전송·결제·게시·취소가 이미 시도됐거나 결과가 불명확하면 **먼저 같은 계정의 내역/거래 ID로 reconcile**한다. 조회 불가면 보류한다. 이미 발생한 효과를 UI로 다시 실행하거나 도구 전체를 처음부터 재생하지 않는다. applied이면 남은 조회만 수행한다. 취소·권한 철회 후에는 새 효과를 시작하지 않고 필요한 상태 조정만 기록한다.
4. **범용 UI 복구:** 현재 화면을 새로 읽고 ref를 선택한다. 패키지가 담은 옛 좌표·snapshotId를 재생하지 않는다. 매 동작 전 대상·버전·권한을 다시 검사하고, 제한 횟수/시간 안에 복구되지 않으면 인계한다. 잠금·업무 프로필·CAPTCHA·인증·보호 결제 화면을 우회하지 않는다. 서비스가 금지한 자동 게시를 UI fallback으로 대신하지 않는다.
5. **실효과 확인:** 클릭 성공/페이지 재조회/모델의 “완료”는 해당 서비스의 완료가 아니다. 같은 계정·주문/리뷰/문서 ID·새 버전의 본문/금액/상태를 독립 조회한 호스트 영수증만 채택한다. unknown, 접수, 준비, 본인 제출 대기는 각각 표시한다. 결제 재시도·중복 제출로 확인을 대신하지 않는다.

## APK와 분리되는 자동화 패키지

**고정 Rust 실행기를 한 번 배포한 뒤** selector·유한 절차·검증 조건을 data-only 패키지로 교체하는 구조다. 지금의 앱에 임의 새 패키지를 넣어 즉시 실행할 수 있다는 뜻은 아니다.

| APK 없이 갱신할 자료 | 실행기/APK 갱신이 필요한 변경 |
|---|---|
| 이미 지원하는 동작의 selector, 입력 schema, 유한 상태 전이, 재조회 조건, 서비스 버전 범위, 설명·템플릿 | 새 Android 권한·접근성 기능·네이티브/JNI/UniFFI ABI, 새 파일/네트워크/결제 실행 primitive, 보호 경계·저장 형식·신뢰키 정책 변경 |
| 기존 권한 부분집합의 검증판 교체·중단·검증된 이전판 복귀 | 현재 호스트가 모르는 tool을 곧바로 ToolRegistry에 추가하거나 모델 코드 실행 |

제안 패키지는 manifest + workflow.json + selector 데이터 + 검증 fixture/결과 digest + 서명으로 한정한다. manifest에는 id/version/hash/publisherKeyId, engine/schema 호환 범위, Android 패키지·앱 버전·서명자 또는 웹 origin, 기존 primitive 목록·효과·데이터 등급·예산을 둔다. 실행 시 별도로 실제 계정의 **로컬 불투명 ID**, run/project/scope·grant revision·도구 버전·내용 hash를 결속한다. 패키지에 계정·쿠키·비밀번호·실제 대화·기기 로그를 넣지 않는다.

DSL은 허용된 도구 호출, 로컬 값 추출, 고정 비교, 한도 있는 분기만 표현한다. JS/Python/Rust/WASM 바이너리, shell/eval, 무제한 루프, 임의 URL 요청·파일 경로·모듈 import는 허용하지 않는다. 새 의미 도구는 호스트가 검증한 고정 ‘자동화 실행’ 진입점의 부분집합으로 투영한다. 모델/페이지가 보낸 이름·코드·문서가 실행 권한이나 grant가 될 수 없다.

다운로드와 활성화는 별개다. 호스트는 별도 신뢰된 catalog의 hash·서명키로 서명/크기/경로/중복키/schema/호환성·capability 부분집합을 검사한다. 후보를 만든 모델이 자기 출판키를 신뢰시키거나 검증 결과를 서명하지 못한다. 검증 호스트의 결과와 승인된 publisher 서명을 갖춘 동일 권한 업데이트만 사전 허용 정책으로 활성화한다. 새 권한·새 publisher는 별도 검토하며, 서명은 행동 승인을 대체하지 않는다.

## 자동 생성·검증·활성화

한 번의 성공에서 자동 후보를 만들 수 있으나 한 번의 성공을 재사용 자격으로 삼지 않는다. 성공 경로에서 비밀값을 제거한 selector·사전/사후 조건·효과 경계만 추출한다. 사용자 경험·별점·보상이나 읽지 않은 결과를 생성하지 않는다.

후보 → 정적 검증 → 격리 반복 시험 → 변경 UI/오계정/오대상/취소·만료/중복·응답 유실/프로세스 재시작 시험 → 승인된 대상 버전 실검증 → 검증판 순서로 승격한다. 실제 결제·전송을 반복 시험하지 않고 합성 fixture로 중복 방지·영수증을 먼저 검증한다. 실행기 외부에서 관찰한 결과를 사용하며 실패와 미실행도 분모에 남긴다.

활성화는 hash 기준 원자 교체, 이전 검증판과 revocation 목록 보관, UI 변경/오대상/검증 실패 시 즉시 격리·fallback을 적용한다. 진행 중 run은 원래 패키지 hash에 고정하고 업데이트로 도중 교체하지 않는다. rollback도 지원 버전·현재 grant·보안 철회 여부를 검사하며 권한 revision을 되돌리지 않는다. 이전판으로 복귀해도 unknown 효과는 해소되지 않고 재실행할 수 없다.

## PC·폰 실행 창과 구현 차이

웹은 본인 PC의 전용 headless 프로필을 우선 사용하고 정상 PC 창/세션과 분리한다. 로그인이 필요하면 자동화를 멈추고 명시적 로컬 로그인 화면으로 인계한 뒤 같은 프로필·계정 재결속을 확인한다. 폰 앱만 가능한 단계는 사용자에게 알린 foreground 상호작용 창에서 실행하고, 사용자 조작·포커스 변경·잠금 시 중지한다. 백그라운드 유지나 Tailscale은 화면 사용권을 부여하지 않는다. 비밀번호·쿠키·OTP·카드/CVC·인증서·결제 토큰은 **클라우드 전송 0**이며 보호 입력은 모델 관찰·후보 생성에서 제외한다. 허용된 화면 자료도 최소 범위로만 전송한다.

현재 기반은 고정 Kotlin ToolRegistry, ScreenControl의 snapshot/ref·보호 화면 거절, WebAgentPolicy와 browser_agent.py의 페이지·요소 검증, Rust RunMachine/ActionGrant 및 Android checkpoint다. ExtensionSecurity.inspect는 신뢰 catalog의 hash/commit과 ZIP/UTF8/선언 capability를 검사하지만 Markdown·템플릿 **읽기 검사만** 하며 설치·서명 배포·자동화 DSL 실행기가 아니다. 기존 기능이 위 전체 폐루프를 이미 구현한 것은 아니다. 브라우저 signature는 URL/ref/tag/type/label 중심이라 금액·계정·입력값 효과 결속도 보완해야 한다.

후속 파일별 계획(이 문서는 생성하지 않음): ① rust/crates/ziphap-core/src/automation/{package,checkpoint,verification}.rs와 해당 시험—유한 DSL·결속·unknown 조정. ② rust/crates/ziphap-pc/src/automation_catalog.rs—서명 catalog·격리 후보·실검증 영수증·원자 활성/rollback. ③ Android 신규 automation/AutomationPackageStore.kt, AdaptiveExecutionBridge.kt—암호화 checkpoint와 기존 실행기/foreground 창 연결. ④ Root 소유 ToolRegistry, ChatRunCoordinator, ScreenControl, WebAgentPolicy, PC browser의 최소 후크와 실제 전환 시험. 먼저 읽기 전용 한 단계와 합성 저장/재조회에서 도구→UI→후보→검증판을 입증한 뒤 서비스별 쓰기로 확장한다. **본 문서 검수는 소스 읽기만이며 빌드·모델·계정·기기 실행 0회다.**
