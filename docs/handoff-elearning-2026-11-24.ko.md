# 이러닝 완주 에이전트 인계서 (Claude → Codex, 2026-11-24)

소유자 지시(2026-11-24): "실험 안 해도 돼. 코덱스에 인계하도록 인계서류 만들어줘." 이 문서는 남은 구현과 연결을 Codex가 이어받기 위한 자료다. 소유자 승인 없이 푸시·배포하지 않았다.

## 1. 무엇을 만드는가

교육 사이트 하나를 대화로 맡기면 집합이 여러 날에 걸쳐 다음 순서를 끝낸다. 결과로 사이트 수료 근거와 이수증 PDF를 대화에 돌려준다.

1. 이수 조건 찾기
2. 강의 재생
3. 시험
4. 만족도 조사
5. 수료 확인
6. 이수증 저장
7. 제출처 메일 초안

- 설계(승인됨): `docs/superpowers/specs/2026-11-24-elearning-completion-agent-design.md`
- 구현 계획(작업 0~9): `docs/superpowers/plans/2026-11-24-elearning-completion-agent.md`
- 선례: 소유자 휴대폰의 피클 앱 대화. 소유자 허락을 받고 열람했으며, 내용은 Git·문서에 넣지 않았다. 피클은 한림 사이버 연수원 12과목 시험·설문·수료증을 처리했다. KAIRB는 해외 서버 회선 때문에 0.24배속으로 정체됐다. hallymedu는 하루 8차시 한도와 mOTP 때문에 매일 이어 들었다.

## 2. 소유자 결정 (그대로 유지)

- **시험·설문:** 시험 응시와 만족도 조사 제출도 에이전트가 직접 한다. 소유자는 필수교육이 아니라고 밝혔다. 요청에 명시된 제출은 그 요청 자체가 승인이다.
- **별도 승인:** 되돌리기 힘든 일에만 받는다. 결제, 메일·메시지 발송, 전화, 탈퇴가 여기에 해당한다. 피클에서 소유자가 고른 "되돌리기 힘든 일만 허락 받기"와 같은 기준이다.
- **유지하는 원칙**
  - 비밀번호·mOTP는 소유자가 직접 입력한다.
  - CAPTCHA·매크로 탐지 우회, 진도 조작, 배속 우회를 하지 않는다.
- **재생 조건:** 휴대폰 앱 브라우저에서 재생한다. 이후 소유자가 Wi-Fi·충전 제한을 해제했다.
- **방식:** 작업 장부 + 호스트 대기 후 자동 재개(접근법 1).
- **화면 꺼짐 재생 실험(계획 Task 0):** 소유자가 생략을 지시했다. 백그라운드 재생과 전경 재생 중 무엇을 택할지는 Codex가 실기에서 판단한다(아래 5장 위험 1).

## 3. 브랜치와 커밋

- 작업 트리: `C:\Users\VICTUS\source\ziphap-claude-elearning`
- 브랜치: `claude/elearning-agent`
- 기준: `codex/chrome-action-continuation` eed351a
- 푸시하지 않았다.

| 커밋 | 내용 | 검증 |
|---|---|---|
| d63b9bb | 설계·구현 계획 문서 | — |
| d710b66 | Task 1 작업 장부 코어 `GoalProgressLedger` + 도구 정의 `read_goal_progress`/`update_goal_progress` | GoalProgressLedgerTest 8/8, core 전체 통과. 과제 검토 통과 |
| ef7ec40, b71cb29 | Task 2 요청 위임 제출 판정 `DelegatedSubmission` (동작 범위 강제, 라벨 정규화 보강 포함) | DelegatedSubmissionTest 9/9(Claude가 직접 재실행), core 전체 통과. 검토와 재검토 통과 |
| (Task 3) | 대기 조건·재개 판정 코어 `GoalWait`, `GoalWaitPolicy`, 도구 `wait_for_goal_condition` | 아래 3.1 |

### 3.1 Task 3 상태

커밋 6d112e2가 이 작업이다.

- **시험 결과:** GoalWaitTest 9/9(Claude가 직접 재실행), core 전체 1,135건이 통과했다. 과제 검토도 통과했다.
- **주의 (Task 4 연결 계약):** `onResumeFailedImmediately`의 `Rearm`은 `resumedOnceFor`를 지우지 않는다.
  - 같은 대기 ID로 다시 대기를 걸면 다음 `onWake`가 `wait_duplicate_wake`로 멈춘다.
  - 그래서 Task 4에서 다시 대기를 걸 때는 새 대기 ID를 발급하거나 `resumedOnceFor`를 비워야 한다.
  - 정책 코드는 계획 규격 그대로 두었다.

**검증 범위:** core JVM 시험뿐이다. APK 조립·서명·설치·실기는 하지 않았다. 앱 쪽 연결이 없어서 현재 앱 동작은 바뀌지 않는다. 새 core 클래스가 추가됐을 뿐이다.

## 4. 남은 작업 (Codex 인수)

계획 문서의 작업 번호를 따른다.

| 작업 | 내용 | 비고 |
|---|---|---|
| Task 4 | `app/.../scheduling/GoalWaitWork.kt`, `GoalWaitStore.kt` | 기존 `ScheduledReadWork` 패턴. 고유 작업 `KEEP`. 앱 시작 때 복원. 시험 실행은 `:android-runtime:testDebugUnitTest` |
| Task 5 | `app/.../integrations/MediaEndWatcher.kt` | 30초 간격으로 감시. 진행 정지 10분, 상한 6시간. 포그라운드 서비스와 웨이크락을 어디에 둘지는 실기 판단 |
| Task 6 | `app/.../integrations/CertificatePdfCapture.kt`, `app/src/main/java/android/print/WebViewPdfWriter.kt` | 인쇄형·blob 이수증. `%PDF-`, 10MB 이하, `PdfRenderer` 1페이지 이상, 원자적 저장. 기존 `PublicPdfArtifact` 저장 루틴은 Codex 판단으로 공용화 |
| Task 7 | 번들 스킬 `elearning-completion` | `SKILL.md`, `catalog.json`, sha256. 들어갈 문구는 계획 Task 7 참고 |
| Task 8 | 기존 핵심 파일 연결 | 아래 4.1 |
| Task 9 | 전체 빌드와 실기 | KAIRB 3강 → 퀴즈·설문·이수증 → 행정간사 메일 초안(발송 전까지) → hallymedu 하루 한도·mOTP 재개 |

### 4.1 연결 지점 (Codex 소유 파일)

1. **`ChatRunCoordinator`**
   - `GoalProgressToolCatalog`·`GoalWaitToolCatalog` 도구를 등록하고 실행한다.
   - `update_goal_progress`에서는 실행기가 `goalId`·`expectedUpdatedAt` CAS와 최상위 인자 키를 검사해야 한다. `parseOps`는 `ops`만 검사한다.
   - `GoalProgressLedger.apply`의 `observedAuditIds`에는 이번 목표에서 실제로 관찰한 도구 실행의 감사 ID만 넣는다. 읽기 도구, 장부 도구, 대기 도구의 ID는 빼야 한다. 빠뜨리면 장부 도구 자신의 ID로 `done`을 만들 수 있다.
   - `wait_for_goal_condition`을 실행하면 목표를 `WAITING`(일시정지 사유 `WAITING_CONDITION`)으로 바꾸고 `GoalWaitWork.arm`을 부른다.
   - 깨움 결과가 `Resume`이면 `queueNextGoalSegment`를 같은 목표 ID, 장부, 원래 권한 revision으로 호출한다. 권한이 바뀌었으면 승격하지 않고 보존한다.
2. **`GoalRunLaunchGuard`와 Rust `NativeGoalBudgetStop`**
   - 지금 예산은 누적 30분·144회·8구간이다. 이것을 '깨어난 활동 1회당 예산'으로 바꾸고, 대기 시간은 예산에서 뺀다.
   - 목표 전체 상한은 `GoalWaitPolicy`를 따른다. 14일 또는 사이트 마감 중 이른 날, 하루 12회, 전체 120회다.
3. **`AppWebAgent`, `public/app-web-agent.js`**
   - 앱 에이전트 WebView에만 `setMediaPlaybackRequiresUserGesture(false)`를 적용한다.
   - 실기 판단에 따라 `onPause`를 막는다.
   - `setSupportMultipleWindows(true)`와 `onCreateWindow`로 두 번째 오프스크린 창을 연다. 최대 2개다.
   - `DownloadListener`와 같은 출처 blob `fetch`를 연결하고 `CertificatePdfCapture`를 호출한다.
   - `MediaEndWatcher.sample`을 기존 미디어 읽기에 연결한다.
4. **`WebAgentPolicy` 승인 지점**
   - `riskyControl`에 걸린 클릭이라도 다음 두 조건을 모두 만족하면 클릭 단위 승인을 생략한다.
     - `DelegatedSubmission.permits(scope, url, label)`가 true다.
     - 같은 스냅샷 재검증을 통과한다.
   - `scope`는 시작된 목표의 `constraints`에서 `DelegatedSubmission.fromConstraints`로 읽는다. 위임 범위를 기록하는 줄의 형식은 `delegate:v1 {json}`이다.
5. **포그라운드 서비스**
   - 재생 중에는 `mediaPlayback` 유형과 `PARTIAL_WAKE_LOCK`을 쓴다.
   - 사람 없이 시작하려면 소유자가 Android 시스템 화면에서 배터리 최적화 예외를 직접 허용해야 한다.
   - 예외가 없으면 알림을 띄우고, 소유자가 누르면 시작한다.
6. **대화 작업 카드:** 장부 `summary()`, 대기 이유, 다음 재개 시각, 중지 버튼을 보인다. 실제로 멈췄으면 "멈춤"으로 표시한다.

## 5. 위험과 미검증

1. **화면 꺼짐 재생:** 오프스크린 WebView가 화면 꺼짐이나 백그라운드 상태에서 영상을 계속 재생하는지 검증하지 않았다(실험 생략).
   - 실패하면 전경 재생으로 바꾼다. 집합 화면에 플레이어를 띄우고, 기존 화면 유지 배지 방식을 확장한다.
2. **제스처 없는 재생:** 앱 WebView는 기본값(제스처 필요) 그대로다. 사이트가 제스처 없는 재생이나 백그라운드 재생을 막으면 우회하지 않고 소유자에게 안내한다.
3. **데이터:** 조사한 KAIRB 사례에 47분짜리 1.49GB 영상이 있었다. 모바일 재생 성능은 실기에서 확인한다.
4. **위임 판정의 한계**
   - 라벨은 페이지가 정하는 글자라 경험적 판정이다. 실제 경계는 출처 범위, 동작 범위, 스냅샷 재검증이다.
   - 영문 금지어가 부분 문자열로도 걸린다(예: repay). 막는 쪽으로 틀리는 것이라 그대로 둔다.
5. **인쇄형 이수증:** 원본이 아니라 화면에서 만든 PDF로 표시해야 한다.

## 6. 설계자 판단 기록 (R1~R11)

| 번호 | 판단 |
|---|---|
| R1 | Task 0 실기는 소유자 로그인이 필요해 뒤로 미룬다. 이후 소유자가 생략을 지시했다. |
| R2 | PC 재시도 한도(15분 × 8회) 뒤 다시 대기를 걸 때 기준 시각 |
| R3 | 앱 단위 시험은 `:android-runtime:testDebugUnitTest`로 실행한다(app/src/test를 그 모듈이 컴파일). |
| R4 | 인쇄 캡처 계측 시험은 기기에서만 실행한다. |
| R5 | 사무실 채팅방 게시와 접수 보고서는 로컬 조율 파일로 본다. |
| R6 | 출처 정규화를 core에 둔다. https, 포트 -1·443, `"https://"+host.lowercase()`. |
| R7 | 라벨을 두 계열로 나눠 동작 범위에 연결한다. |
| R8 | 라벨 정규화: `\p{Cf}` 제거, NFKC, 120자 초과는 거부. |
| R9~R11 | 깨움 계산 규칙, 판정 순서, 기록 필드(`anchorMinuteOfDay`, `lastFailureKey`) |

**R2 세부:** 같은 종류의 조건으로 다시 건다.
- `AtTime`은 다음 날 같은 시각으로 건다.
- `MediaEnd`·`OwnerAuth`는 1시간 뒤 `AtTime`으로 바꾼다.

**R7 세부:**
- 제출 계열(`제출|응시|확인|submit|confirm`)은 `QUIZ_SUBMIT` 또는 `SURVEY_SUBMIT`이 범위에 있어야 한다.
- 진행 계열(`완료|다음|finish|next`)은 세 동작 중 하나만 있으면 된다.
- 금지 계열이 먼저 적용된다.

**R9~R11 세부:**
- 깨움 계산: `Resume`이면 깨움 횟수를 늘린다. 단 `OWNER_AUTH_DONE`은 세지 않는다. 재시도는 세지 않는다.
- 판정 순서: 날짜 넘김 → 마감 → 중복 깨움 → 전체 한도 → PC 재시도 → 인증 시간 초과 → 하루 한도 → 재개
- 기록 필드: `anchorMinuteOfDay`, `lastFailureKey`를 추가했다.

진행 장부 원본은 작업 트리의 무시 경로 `.superpowers/sdd/2026-11-24-elearning-completion-agent/progress.md`에 있다. 과제 보고서와 검토 묶음도 같은 폴더에 있다.

## 7. 미룬 사소한 지적 (최종 검토에서 판단)

- **장부**
  - `done`이 아닌 항목의 근거 문자열은 관찰 여부를 검사하지 않는다.
  - `done` 항목의 메모만 고칠 때도 근거를 다시 내야 한다.
  - `setMeta`로 deadline을 지울 수 없다.
  - 도구 설명의 "ops1..20" 표기
  - 경계 시험이 부족하다.
  - 길이를 UTF-16 단위로 센다.
- **위임:** 영문 부분 문자열 과차단이 있다. 동형 문자는 막는 쪽으로 틀린다.
- **대기**
  - DST 시간대 시험과, 앵커가 없을 때 `createdAt` 시각으로 대체하는 경로의 시험이 없다.
  - `Rearm`에서 `record.condition`과 `condition`이 같은지 확인하는 단언이 없다. `OwnerAuth` 재대기 시험도 없다.
  - `expectedUpdatedAt`의 숫자 형식은 사용하는 쪽에서 검사해야 한다.
  - 파서 경계 시험이 부족하다.

## 8. 피클·오픈소스 참고 (코드 복사 없음)

- **Browser Use (MIT)**
  - 작업 공간 파일 기반 진행 기억 → 작업 장부
  - 실행 후 판정 → 수료 근거 없이는 완료가 아님
  - 비밀값 분리
- **Stagehand (MIT):** 한 번 찾은 동작을 다시 결정적으로 실행한다.
- **Skyvern (AGPL):** 인증번호 흐름의 아이디어만 쓴다.
- 영상 끝까지 대기, 여러 날 재개, 인쇄형 PDF를 지원하는 후보는 없었다. 자체 실기로 검증해야 한다.
