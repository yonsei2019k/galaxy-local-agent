# 이러닝 완주 에이전트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **목표:** 교육 사이트 하나를 대화로 맡기면, 집합이 여러 날에 걸쳐 끝까지 처리하고 수료 근거와 이수증 PDF를 대화에 돌려준다.
  - 처리 범위: 이수 조건 확인 → 강의 → 시험 → 설문 → 수료 확인 → 이수증
- **구조:** 기존 앱 브라우저·목표 자동 이어가기·PDF 저장 위에 네 가지를 더한다.
  - 모델이 쓰는 작업 장부
  - 호스트의 대기와 자동 재개
  - 요청 위임 제출 판정
  - 인쇄형 이수증 캡처
- **역할 분담:** 새 기능은 이번 작업이 새 파일로 만든다. 기존 핵심 파일에 연결하는 일은 Codex가 맡는다.
- **기술:** Kotlin(core JVM, Android app), WorkManager, Android WebView·`PrintDocumentAdapter`·`PdfRenderer`, 번들 스킬(`SKILL.md` + `catalog.json`)
- **설계 문서:** `docs/superpowers/specs/2026-11-24-elearning-completion-agent-design.md` (승인 2026-11-24)
- **기준:** `codex/chrome-action-continuation` eed351a
- **작업 트리:** `C:\Users\VICTUS\source\ziphap-claude-elearning`
- **브랜치:** `claude/elearning-agent`

## 전체 제약

- **제출 위임**
  - 요청에 명시된 시험·설문 제출은 요청 자체가 승인이다.
  - 결제·구매·송금·이체·보내기·전송·삭제·탈퇴·해지·가입 계열은 위임 범위와 상관없이 기존 승인을 받는다.
- **로그인·인증·탐지**
  - 비밀번호·mOTP는 소유자가 직접 입력한다. 모델과 로그에 값이 남지 않는다.
  - CAPTCHA·매크로 탐지 우회, 진도 조작, 배속 우회를 하지 않는다.
- **재생 조건:** 휴대폰 앱 브라우저에서 재생한다. 소유자의 후속 지시에 따라 모바일 데이터와 배터리에서의 재생도 지원한다.
- **장부**
  - 항목은 300개, 전체 크기는 64KB, `note`는 200자, `requirements`는 2,000자까지다.
  - `done`은 이번 목표에서 관찰한 도구 실행의 감사 ID가 있을 때만 받는다.
- **대기 수치**
  - `media_end` 대기: 감시 간격 30초, 진행 정지 10분, 상한 6시간
  - 목표 전체: 14일 또는 사이트 마감 중 이른 날, 하루 깨움 12회, 전체 120회
  - PC 미연결: 15분 간격으로 8회까지 다시 시도하고, 이 재시도는 깨움 횟수에 세지 않는다.
  - 재개는 1회만 한다. 같은 조건으로 즉시 다시 실패하면 멈추고 보고한다.
- **매일 인증:** 인증 대기 전환은 같은 깨움 1회로 센다. 21:00까지 응답이 없으면 다음 날 같은 시각에 다시 깨운다.
- **이수증**
  - `filesDir/exports/artifact-<uuid>.pdf`에 원자적으로 저장한다.
  - 파일은 `%PDF-`로 시작해야 하고, 10MB 이하여야 하며, `PdfRenderer`로 1페이지 이상 열려야 한다.
  - 인쇄형은 "화면에서 만든 PDF"로 표시한다.
- **프로젝트 규칙(AGENTS.md)**
  - 키워드·문구로 의도를 분류하지 않는다.
  - 알 수 없거나 형식이 틀린 도구 호출은 실행하지 않는다.
  - 개인 자료·기기 식별자·스크린샷은 Git에 넣지 않는다.
  - 커밋 메시지는 한국어로 쓴다.
- **빌드 환경:** 도구는 Bash에서 다음 환경으로 실행한다.
  ```
  T=C:/Users/Public/Documents/ESTsoft/CreatorTemp/galaxy-local-agent-tools
  JAVA_HOME, ANDROID_HOME, ANDROID_SDK_ROOT, GRADLE_USER_HOME를 $T 아래 경로로 지정
  ```
  - 공통 실행: `./gradlew <task> --max-workers=2 --offline`

## 리뷰 중점 (테스트가 놓치기 쉬운 실패)

1. 모델이 근거 없이 장부 항목을 `done`으로 바꾼다. 거부해야 한다. 시험은 Task 1이 맡는다.
2. 위임된 사이트에서 "결제하고 제출"처럼 섞인 라벨이 나오거나, 다른 출처에서 "제출"이 나온다. 둘 다 승인으로 돌려야 한다. 시험은 Task 2가 맡는다.
3. PC가 꺼진 동안 다시 시도한 횟수가 하루 깨움 한도를 소모한다. 소모하지 않아야 한다. 시험은 Task 3이 맡는다.
4. 재부팅 뒤 이미 지난 대기를 복원하면서 깨움이 두 번 일어난다. 한 번만 깨워야 한다. 시험은 Task 3 복원 판정과 Task 4의 `KEEP`이 맡는다.
5. 로그인 만료 화면의 HTML이 `.pdf` 이름으로 저장된다. `%PDF-`·크기·페이지 검사로 거부해야 한다. 시험은 Task 6이 맡는다.

---

### Task 0: 화면 꺼짐 재생 실험 (실기, 다른 대기 구현보다 먼저)

**Files:**
- Modify: `docs/agent-change-log.md` (결과 기록만)

이 실험의 결과에 따라 Task 5의 방식(백그라운드 재생 또는 전경 재생)이 정해진다. 장부, 위임, 대기 코어, 이수증, 스킬(Task 1~4, 6, 7)은 이 결과와 상관없어서 병렬로 진행한다.

- [ ] **Step 1:** 휴대폰에 설치된 빌드가 eed351a 계열(0.1.71-workflow-result-test, 2071)인지 `dumpsys package com.user.localagent | grep versionName`으로 확인한다.
- [ ] **Step 2:** 소유자에게 KAIRB 로그인 상태를 확인받는다. 로그인은 소유자가 직접 한다.
- [ ] **Step 3:** 집순이 대화로 KAIRB 1번 강의를 열고 재생 버튼을 누르게 한다.
  - 시작 진도율과 `read_app_browser_page`의 `playbackRule="playing && advancing"` 결과를 기록한다.
  - 재생이 시작되지 않으면 그 사실 자체를 결과로 기록한다. 제스처 요구 기본값 때문일 수 있다.
- [ ] **Step 4:** 화면을 끄고 10분 뒤 화면을 켠다. 사이트의 진도율 표시와 미디어 `positionMs`를 다시 읽는다.
- [ ] **Step 5:** 결과를 변경 기록에 남긴다.
  - 남길 내용: 진도율 증가량, 위치 증가량, 실패 이유
  - 판정
    - 10분 동안 위치가 8분 이상 늘었으면 백그라운드 재생으로 간다.
    - 그렇지 않으면 전경 재생으로 간다.
  - 남기지 않을 것: 개인 정보나 화면 캡처
- [ ] **Step 6:** 기록을 커밋한다. 메시지: `화면 꺼짐 상태 앱 브라우저 재생 실험 결과 기록`

### Task 1: 작업 장부 코어 (`GoalProgressLedger`)

**Files:**
- Create: `core/src/main/kotlin/com/user/localagent/core/GoalProgressLedger.kt`
- Create: `core/src/main/kotlin/com/user/localagent/core/GoalProgressToolCatalog.kt`
- Test: `core/src/test/kotlin/com/user/localagent/core/GoalProgressLedgerTest.kt`

**Interfaces:**
- **Produces (데이터):**
  - `@Serializable enum class LedgerKind { LECTURE, QUIZ, SURVEY, CERTIFICATE, CONDITION, OTHER }`
    - `@SerialName`은 소문자로 쓴다.
  - `@Serializable enum class LedgerStatus { TODO, ACTIVE, WAITING, DONE, BLOCKED }`
  - `@Serializable data class LedgerItem(val id:String, val course:String, val unit:String, val kind:LedgerKind, val status:LedgerStatus=TODO, val note:String="", val evidence:List<String> = emptyList())`
  - `@Serializable data class GoalProgressLedger(val goalId:String, val requirements:String="", val dailyLimit:Int?=null, val deadline:String?=null, val items:List<LedgerItem> = emptyList(), val updatedAt:Long=0)`
- **Produces (함수):**
  - `fun GoalProgressLedger.validate(): GoalProgressLedger`
  - `fun GoalProgressLedger.apply(ops:List<LedgerOp>, observedAuditIds:Set<String>, now:Long): GoalProgressLedger`
  - `fun GoalProgressLedger.summary(): String`
    - 대화 카드용 한 줄이다. 형식: `"<course> <done>/<total>"`를 `" · "`로 잇는다.
  - `sealed interface LedgerOp`
    - `Add(item)`
    - `Set(id, status, note?, evidence)`
    - `SetMeta(requirements?, dailyLimit?, deadline?)`
- **Produces (도구):** `object GoalProgressToolCatalog`
  - `READ = "read_goal_progress"`
  - `UPDATE = "update_goal_progress"`
  - 둘 다 `Risk.LOW`이고, `definitions`와 `names`를 둔다.
  - `fun parseOps(call:ToolCall): List<LedgerOp>`
    - 알 수 없는 키·형식이면 `IllegalArgumentException`을 던진다.
  - UPDATE 인자: `goalId`, `expectedUpdatedAt`, `ops`(array, 1..20)

- [ ] **Step 1: 실패하는 시험을 쓴다.** `GoalProgressLedgerTest`
  - `doneWithoutObservedEvidenceIsRejected`
    - `Set(id, DONE, evidence=listOf("audit-x"))`에 `observedAuditIds=emptySet()`을 주면 `IllegalArgumentException`이 나야 한다.
  - `doneWithObservedEvidenceIsAccepted`
    - `observedAuditIds=setOf("audit-1")`일 때 결과 항목의 status가 `DONE`이고 evidence가 `["audit-1"]`이어야 한다.
  - `limitsAreEnforced`
    - 항목 301개, note 201자, requirements 2001자, 직렬화 64KB 초과는 각각 거부되어야 한다.
  - `duplicateIdAndUnknownIdAreRejected`
    - 같은 id로 `Add` 두 번, 없는 id에 `Set`은 각각 거부되어야 한다.
  - `summaryCountsDonePerCourse`
    - KAIRB 항목 3개 중 1개가 `DONE`이면 `"KAIRB 1/3"`이 나와야 한다.
  - `parseOpsRejectsUnknownKeys`
    - op에 `"extra":1`이 들어 있으면 거부되어야 한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :core:test --tests "com.user.localagent.core.GoalProgressLedgerTest" --offline`
  - 기대: 컴파일 실패(미정의)
- [ ] **Step 3:** 구현한다.
  - `apply`는 op를 순서대로 적용한 뒤 `validate()`를 호출한다.
  - `DONE`이면 evidence가 비어 있지 않아야 하고, 모든 항목이 `observedAuditIds` 안에 있어야 한다.
  - `updatedAt`에는 `now`를 넣는다.
  - 직렬화는 `Json{encodeDefaults=true; ignoreUnknownKeys=false}`로 한다.
- [ ] **Step 4:** 같은 명령으로 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `작업 장부 코어와 장부 도구 정의 추가`

### Task 2: 요청 위임 제출 판정 (`DelegatedSubmission`)

**Files:**
- Create: `core/src/main/kotlin/com/user/localagent/core/DelegatedSubmission.kt`
- Test: `core/src/test/kotlin/com/user/localagent/core/DelegatedSubmissionTest.kt`

**Interfaces:**
- **Produces (데이터):**
  - `@Serializable enum class DelegatedAction { LESSON_COMPLETE, QUIZ_SUBMIT, SURVEY_SUBMIT }`
  - `@Serializable data class DelegationScope(val origins:Set<String>, val actions:Set<DelegatedAction>)`
    - `origins`는 `https://host` 형식이고 최대 4개다.
- **Produces (함수):** `object DelegatedSubmission`
  - `const val CONSTRAINT_PREFIX = "delegate:v1 "`
  - `fun fromConstraints(constraints:List<String>): DelegationScope?`
    - `CONSTRAINT_PREFIX` 뒤의 JSON 한 줄을 읽는다. 둘 이상이거나 형식이 틀리면 null이다.
  - `fun toConstraint(scope:DelegationScope): String`
  - `fun permits(scope:DelegationScope?, pageUrl:String, controlLabel:String): Boolean`
    - true면 클릭 단위 승인을 생략한다.
- **Consumes:** `WebAgentPolicy.urlAllowed(url)`
  - 출처 정규화는 `BrowserAgentTools.origin`과 같은 규칙(https, 포트 기본값)을 따른다.

- [ ] **Step 1: 실패하는 시험을 쓴다.** `DelegatedSubmissionTest`
  - 기준 scope는 `origins={"https://kairb.org"}`, `actions={QUIZ_SUBMIT, SURVEY_SUBMIT}`이다.
  - `permits(scope,"https://kairb.org/quiz/1","제출")`은 true
  - `permits(scope,"https://kairb.org/survey","설문 제출하기")`는 true
  - `permits(scope,"https://kairb.org/q","확인")`은 true
  - `permits(scope,"https://kairb.org/pay","결제하고 제출")`은 false (리뷰 중점 2)
  - `permits(scope,"https://evil.example/quiz","제출")`은 false (리뷰 중점 2)
  - `permits(scope,"https://kairb.org/q","전송")`과 `"탈퇴"`, `"삭제"`는 모두 false
  - `permits(null, …)`은 false
  - `permits(scope,"http://kairb.org/q","제출")`은 false (http)
  - `fromConstraints(listOf(toConstraint(scope)))`는 `scope`와 같아야 한다.
  - 접두어 줄이 두 개면 `fromConstraints`는 null이어야 한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :core:test --tests "com.user.localagent.core.DelegatedSubmissionTest" --offline`
  - 기대: FAIL
- [ ] **Step 3:** 구현한다.
  - **허용 라벨 정규식:** `제출|확인|완료|다음|응시|submit|confirm|finish|next` (대소문자 무시)
  - **금지 라벨 정규식:** `결제|구매|주문|송금|이체|보내기|전송|삭제|탈퇴|해지|가입|pay|buy|purchase|send|transfer|delete|unsubscribe`
  - 금지가 하나라도 맞으면 false다.
  - 라벨은 공백을 정리한 뒤 120자까지만 본다.
- [ ] **Step 4:** 같은 명령으로 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `요청 위임 제출 판정 추가`

### Task 3: 대기 조건·재개 판정 코어 (`GoalWait`)

**Files:**
- Create: `core/src/main/kotlin/com/user/localagent/core/GoalWait.kt`
- Create: `core/src/main/kotlin/com/user/localagent/core/GoalWaitToolCatalog.kt`
- Test: `core/src/test/kotlin/com/user/localagent/core/GoalWaitTest.kt`

**Interfaces:**
- **Produces (대기 조건):** `@Serializable sealed interface WaitCondition`
  - `MediaEnd(val pageUrl:String, val playerSignature:String, val ledgerItemId:String)`
  - `AtTime(val atEpochMillis:Long, val needsPlayback:Boolean)`
  - `OwnerAuth(val handoffId:String)`
- **Produces (대기 기록):** `@Serializable data class GoalWaitRecord(...)`
  - 필드: `id`, `goalId`, `threadId`, `branchId`, `parentRunId`, `authorityRevision:Long?`, `condition`, `createdAt`, `startedAt`, `deadlineAt`, `wakesToday:Int=0`, `wakesTotal:Int=0`, `dayKey:String`, `pcRetries:Int=0`, `resumedOnceFor:String?=null`
- **Produces (깨움 사건):** `enum class WakeEvent`
  - `MEDIA_ENDED`, `MEDIA_STALLED`, `PLAYER_GONE`, `LOGIN_EXPIRED`, `MEDIA_LIMIT`, `CONDITIONS_UNMET`, `TIME_REACHED`, `OWNER_AUTH_DONE`, `OWNER_AUTH_TIMEOUT`, `RESTORED_PAST_DUE`
- **Produces (판정 결과):** `sealed interface WaitDecision`
  - `Resume(record)`
  - `RetryLater(atEpochMillis, record)`
  - `Rearm(condition, record)`
  - `Stop(code:String)`
- **Produces (판정 함수):** `object GoalWaitPolicy`
  - 상수: `MAX_DAYS=14`, `MAX_WAKES_PER_DAY=12`, `MAX_WAKES_TOTAL=120`, `PC_RETRY_INTERVAL_MS=15*60_000L`, `PC_RETRY_LIMIT=8`, `MEDIA_POLL_MS=30_000L`, `MEDIA_STALL_MS=10*60_000L`, `MEDIA_MAX_MS=6*3_600_000L`, `AUTH_CUTOFF_HOUR=21`
  - `fun onWake(record, event, now, zone:ZoneId, pcAvailable:Boolean): WaitDecision`
  - `fun onResumeFailedImmediately(record, conditionKey:String): WaitDecision`
    - 같은 조건이면 `Stop("wait_repeat_failure")`이다.
  - `fun deadline(startedAt:Long, siteDeadline:Long?): Long`
- **Produces (도구):** `object GoalWaitToolCatalog`
  - `WAIT = "wait_for_goal_condition"`, `Risk.LOW`
  - 인자: `goalId`, `expectedUpdatedAt`, `condition`(object: `kind`=`media_end|at_time|owner_auth` 외)
  - `fun parse(call:ToolCall): WaitCondition`

- [ ] **Step 1: 실패하는 시험을 쓴다.** `GoalWaitTest`
  - `pcOfflineRetriesDoNotCountAsWakes` (리뷰 중점 3)
    - `pcAvailable=false`이면 `RetryLater(now+15분)`이고 `wakesToday`가 그대로여야 한다.
    - 8번째 뒤에는 `Rearm`(다음 깨움 대기)이고 `pcRetries`가 0으로 돌아가야 한다.
  - `dailyAndTotalLimits`
    - `wakesToday=12`이면 다음 날 같은 시각으로 `Rearm`이어야 한다.
    - `wakesTotal=120`이면 `Stop("wait_total_limit")`이어야 한다.
    - `now>deadlineAt`이면 `Stop("wait_deadline")`이어야 한다.
  - `authTimeoutRearmsNextDaySameTime`
    - 09:00에 깨워 `OwnerAuth`로 전환했다.
    - 21:00 뒤 `OWNER_AUTH_TIMEOUT`이면 다음 날 09:00 `AtTime`으로 `Rearm`이어야 한다.
    - `wakesToday`는 이 전환으로 늘지 않아야 한다.
  - `restoredPastDueResumesOnce` (리뷰 중점 4)
    - `RESTORED_PAST_DUE` 첫 번째는 `Resume`이어야 한다.
    - 같은 `record.id`의 두 번째는 `resumedOnceFor`로 인해 `Stop("wait_duplicate_wake")`이어야 한다.
  - `repeatFailureStops`
    - `onResumeFailedImmediately`에 같은 조건 키를 두 번 주면 `Stop("wait_repeat_failure")`이어야 한다.
  - `deadlineIsEarlierOf14DaysAndSiteDeadline`
  - `parseRejectsUnknownKind`
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :core:test --tests "com.user.localagent.core.GoalWaitTest" --offline`
  - 기대: FAIL
- [ ] **Step 3:** 구현한다.
  - 상태가 없는 순수 함수로 만든다. 기록 갱신은 반환값으로만 한다.
  - 날짜 키는 `zone` 기준 `yyyy-MM-dd`로 하고, 날짜가 바뀌면 `wakesToday`를 0으로 돌린다.
- [ ] **Step 4:** 같은 명령으로 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `목표 대기 조건과 자동 재개 판정 코어 추가`

### Task 4: 대기 예약 작업자 (`GoalWaitWork`)

**Files:**
- Create: `app/src/main/java/com/user/localagent/scheduling/GoalWaitWork.kt`
- Create: `app/src/main/java/com/user/localagent/scheduling/GoalWaitStore.kt`
- Test: `app/src/test/java/com/user/localagent/scheduling/GoalWaitStoreTest.kt`

**Interfaces:**
- **Consumes:** Task 3의 `GoalWaitRecord`, `GoalWaitPolicy`, `WakeEvent`
- **Produces (저장소):** `class GoalWaitStore(private val load:()->String?, private val save:(String)->Unit)`
  - 앱에서는 기존 암호화 `ConnectionStore`의 키 `goal.wait.v1`로 연결한다.
  - 함수: `put(record)`, `get(goalId):GoalWaitRecord?`, `remove(goalId)`, `all():List<GoalWaitRecord>`
  - 목표당 대기는 하나만 둔다. 같은 `goalId`로 `put`하면 교체한다.
- **Produces (작업자):** `class GoalWaitWork(context, onWake: suspend (GoalWaitRecord, WakeEvent)->Unit)`
  - `suspend fun arm(record:GoalWaitRecord)`
    - `AtTime`이면 `OneTimeWorkRequest`에 `setInitialDelay`를 건다.
    - `needsPlayback`은 재개 목적을 기록한다. 접속 방식 및 전원 조건과 관계없이 정해진 시각에 재개한다.
    - 등록은 `enqueueUniqueWork("goal-wait-"+record.id, KEEP)`로 한다.
  - `suspend fun cancel(goalId:String)`
  - `suspend fun restore(now:Long)`
    - 앱 시작 때 부른다. 시각이 지난 대기는 `RESTORED_PAST_DUE`로 1회 깨운다.
  - `onWake`는 Codex가 연결한다. 다음 목표 구간 큐잉은 Task 8에서 한다.

- [ ] **Step 1: 실패하는 시험을 쓴다.** `GoalWaitStoreTest` (JVM, 메모리 문자열 저장소)
  - 넣고 다시 읽은 값이 같아야 한다.
  - 같은 `goalId`로 두 번째 `put`을 하면 하나만 남아야 한다.
  - 손상된 JSON을 불러오면 빈 목록이 되어야 하고, 예외가 나지 않아야 한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :app:testReleaseUnitTest --tests "com.user.localagent.scheduling.GoalWaitStoreTest" --offline`
  - 기대: FAIL
- [ ] **Step 3:** 구현한다. 작업자는 기존 `ScheduledReadWork`의 등록·복원 패턴을 따른다. 재시도는 5회까지다.
- [ ] **Step 4:** 같은 명령으로 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `목표 대기 저장소와 WorkManager 예약 작업자 추가`

### Task 5: 영상 종료 감시기 (`MediaEndWatcher`) — Task 0 결과 뒤에 시작

**Files:**
- Create: `app/src/main/java/com/user/localagent/integrations/MediaEndWatcher.kt`
- Test: `app/src/test/java/com/user/localagent/integrations/MediaEndWatcherTest.kt`

**Interfaces:**
- **Consumes:**
  - Task 3: `GoalWaitPolicy.MEDIA_POLL_MS`, `MEDIA_STALL_MS`, `MEDIA_MAX_MS`, `WakeEvent`
  - core: `BrowserMediaProgress`
- **Produces:** `class MediaEndWatcher`
  - 생성자 인자:
    - `sample: suspend ()->MediaSample?`
    - `conditionsMet: ()->Boolean`
    - `clock: ()->Long`
    - `sleep: suspend (Long)->Unit`
  - `data class MediaSample(val positionMs:Long, val durationMs:Long?, val ended:Boolean, val loginRequired:Boolean)`
  - `suspend fun watch(startedAt:Long): WakeEvent`
- **실행 위치:** 포그라운드 서비스와 `PARTIAL_WAKE_LOCK`, 그리고 `sample`을 앱 브라우저 미디어 읽기에 잇는 연결은 Codex가 한다(Task 8).
  - Task 0이 "전경 재생"으로 판정되면 감시기는 그대로 쓰고, 실행 위치만 전경 화면으로 바뀐다.

- [ ] **Step 1: 실패하는 시험을 쓴다.** 가짜 시계를 쓴다.
  - ended 샘플이면 `MEDIA_ENDED`
  - 위치가 10분 동안 그대로면 `MEDIA_STALLED`
  - 샘플이 null이면 `PLAYER_GONE`
  - `loginRequired`면 `LOGIN_EXPIRED`
  - 6시간이 지나면 `MEDIA_LIMIT`
  - `conditionsMet()`이 false가 되면 `CONDITIONS_UNMET`
  - 샘플 간격은 30초여야 한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :app:testReleaseUnitTest --tests "com.user.localagent.integrations.MediaEndWatcherTest" --offline`
  - 기대: FAIL
- [ ] **Step 3:** 구현한다.
  - "진행"은 직전 샘플보다 `positionMs`가 늘어난 것을 말한다.
  - 정지 시간은 마지막 진행 시각부터 잰다.
- [ ] **Step 4:** 같은 명령으로 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `영상 종료 감시기 추가`

### Task 6: 이수증 캡처 (`CertificatePdfCapture`)

**Files:**
- Create: `app/src/main/java/com/user/localagent/integrations/CertificatePdfCapture.kt`
- Create: `app/src/main/java/android/print/WebViewPdfWriter.kt`
  - `PrintDocumentAdapter`의 package-private 콜백을 쓰기 위해 이 패키지에 둔다.
- Test: `app/src/test/java/com/user/localagent/integrations/CertificatePdfCaptureTest.kt`
- Test: `app/src/androidTest/java/com/user/localagent/CertificatePrintCaptureCanary.kt`

**Interfaces:**
- **Produces:** `object CertificatePdfCapture`
  - `const val MAX_BYTES = 10*1024*1024`
  - `fun verifyBytes(bytes:ByteArray): Boolean`
    - `%PDF-`로 시작하고 `MAX_BYTES` 이하여야 한다.
  - `suspend fun fromPrint(webView:WebView, exportsDir:File): StoredCertificate`
    - `createPrintDocumentAdapter`로 A4 PDF를 만든다.
  - `fun fromBlobBase64(base64:String, exportsDir:File): StoredCertificate`
    - 같은 출처 blob을 페이지 안 `fetch`로 받은 base64 문자열을 넣는다. JS 쪽은 Codex가 맡는다.
  - `data class StoredCertificate(val fileName:String, val sha256:String, val pages:Int, val source:String)`
    - `fileName`은 `artifact-<uuid>.pdf`이다.
    - `source`는 `"print"` 또는 `"blob"`이다.
  - 저장 위치는 `exportsDir`(= `filesDir/exports`)이다. 저장은 임시 파일 → fsync → `PdfRenderer` 페이지 확인 → `ATOMIC_MOVE` 순서로 한다.
  - 저장·검증 루틴이 `PublicPdfArtifact`에 이미 있으면 재사용한다. 공용 함수로 꺼내는 수정은 Codex와 합의한 뒤에만 한다.

- [ ] **Step 1: 실패하는 JVM 시험을 쓴다.** (리뷰 중점 5)
  - `verifyBytes("<html>login</html>".toByteArray())`는 false
  - 10MB+1 크기는 false
  - `"%PDF-1.7..."`은 true
  - `fromBlobBase64`에 PDF가 아닌 내용을 넣으면 `IllegalArgumentException`이 나고 파일이 생기지 않아야 한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
  - 실행: `./gradlew :app:testReleaseUnitTest --tests "com.user.localagent.integrations.CertificatePdfCaptureTest" --offline`
  - 기대: FAIL
- [ ] **Step 3:** 구현한다. 인쇄 경로는 `WebViewPdfWriter.write(adapter, attributes, file, onDone)`로 레이아웃 → 쓰기 콜백을 잇는다.
- [ ] **Step 4:** JVM 시험을 다시 실행해 PASS를 확인한다.
- [ ] **Step 5:** 계측 시험 `CertificatePrintCaptureCanary`를 만든다.
  - 합성 HTML("수료증 · 테스트")을 WebView에 띄우고 `fromPrint`를 부른다.
  - 기대: `pages>=1`이고 파일이 `%PDF-`로 시작한다.
  - 기기에서 실행할 때만 돌리고, 결과를 변경 기록에 적는다.
- [ ] **Step 6:** 커밋한다. 메시지: `인쇄형·blob 이수증 PDF 캡처 추가`

### Task 7: 번들 스킬 `elearning-completion`

**Files:**
- Create: `app/src/main/assets/skills/elearning-completion/SKILL.md`
- Modify: `app/src/main/assets/skills/catalog.json` (항목 추가, sha256)
- Test: 기존 번들 무결성 회귀 (`BundledSkillStore` 시험)

**SKILL.md 형식:**
- 프런트매터: `name: elearning-completion`, `description`, `version: 1`, `runtime: ziphap-android-local`
- 본문(한국어): 설계 6.5의 9단계
- 반드시 넣을 문장
  - 장부의 `done`은 관찰 근거가 있을 때만 한다.
  - 비밀번호·인증은 소유자가 직접 입력한다.
  - 진도 조작·배속 우회·CAPTCHA 우회를 하지 않는다.
  - 결제·메일 발송은 승인 뒤에 한다.
  - 대기 중에는 "백그라운드에서 계속 중"이라고 말하지 않고, 실제 대기 상태와 재개 조건을 말한다.
  - 설문 의견 문항은 요청에 적힌 소유자의 평가로 채운다. 요청에 없으면 시작할 때 한 번 묻는다.
  - 시험은 제출 뒤 점수를 읽는다. 목표 점수에 못 미치고 사이트가 재응시를 허용하면, 사이트 한도와 1회 추가 중 작은 횟수만큼 다시 푼다.

**catalog.json 항목:**
- `tools`: `open_app_browser_page`, `read_app_browser_page`, `app_browser_action`, `save_app_browser_pdf`, `read_goal_progress`, `update_goal_progress`, `wait_for_goal_condition`, `prepare_email`
  - `save_app_browser_pdf`의 정확한 이름은 eed351a의 PDF 저장 도구명을 확인해 그대로 쓴다.
- `limitations`: 화면 꺼짐 재생은 Task 0 결과를 따른다. 인쇄형 이수증은 원본이 아니라 화면에서 만든 PDF다.

- [ ] **Step 1:** SKILL.md와 카탈로그 항목을 쓰고 sha256을 계산해 넣는다.
- [ ] **Step 2:** 스킬 무결성 시험을 돌려 PASS를 확인한다.
  - 실행: `./gradlew :app:testReleaseUnitTest --tests "*BundledSkill*" --offline`
- [ ] **Step 3:** 커밋한다. 메시지: `이러닝 완주 번들 스킬 추가`

### Task 8: Codex 연결 인계 (기존 핵심 파일)

**Files:**
- Create: `.local/coordination/release-intake/reports/claude-elearning-<sha>.md`
  - 무시되는 경로다. 커밋하지 않는다.
- Modify: `docs/agent-change-log.md`

**연결 항목:** 사무실 채팅방으로 Codex에 넘긴다. 각 항목은 제안 diff나 정확한 호출 지점으로 넘긴다.

1. **`ChatRunCoordinator`**
   - Task 1과 Task 3의 도구를 등록하고 실행한다.
   - `observedAuditIds`는 이번 목표의 실제 관찰 기록에서 준다.
   - `wait_for_goal_condition`을 실행하면 목표를 `WAITING`으로 바꾸고, 일시정지 사유 `WAITING_CONDITION`을 기록한 뒤 `GoalWaitWork.arm`을 부른다.
   - `onWake` 결과가 `Resume`이면 `queueNextGoalSegment`를 같은 목표 ID, 장부, 원래 권한 revision으로 부른다. 권한이 바뀌었으면 보존한다.
2. **`GoalRunLaunchGuard`와 Rust `NativeGoalBudgetStop`**
   - 누적 30분·144회·8구간을 '깨어난 활동 1회당 예산'으로 바꾼다.
   - 대기 시간은 예산에서 뺀다.
   - 목표 전체 상한은 Task 3의 `GoalWaitPolicy`를 쓴다.
3. **`AppWebAgent`와 `app-web-agent.js`**
   - 제스처 요구를 끈다. 앱 에이전트 WebView에만 적용한다.
   - Task 0 판정에 따라 `onPause`를 막는다.
   - `onCreateWindow`로 두 번째 오프스크린 창을 연다. 최대 2개다.
   - `DownloadListener`와 blob `fetch`를 연결하고 `CertificatePdfCapture`를 호출한다.
   - `MediaEndWatcher.sample`을 기존 미디어 읽기에 연결한다.
4. **`WebAgentPolicy` 승인 지점**
   - `riskyControl`에 걸려도 `DelegatedSubmission.permits(scope, url, label)`가 true이고 같은 스냅샷 재검증을 통과하면 클릭 단위 승인을 생략한다.
   - `scope`는 시작된 목표의 `constraints`에서 `fromConstraints`로 읽는다.
5. **포그라운드 서비스**
   - 재생 중 `mediaPlayback` 유형과 `PARTIAL_WAKE_LOCK`을 쓴다.
   - 배터리 최적화 예외가 없으면 알림을 띄우고, 소유자가 누르면 시작한다.
6. **대화 작업 카드:** 장부 `summary()`, 대기 이유, 다음 재개 시각, 중지 버튼을 보인다.

- [ ] **Step 1:** 위 6항목을 사무실 채팅방 `codex-main`에 요청으로 게시한다. Task 1~7의 커밋 SHA와 Interfaces를 붙인다.
- [ ] **Step 2:** 배포 접수 보고서를 쓴다(`docs/automatic-updates.ko.md` 절차).
- [ ] **Step 3:** 변경 기록에 실제 검증 범위를 적는다.
  - 소스와 JVM 시험만 했는지
  - 설치·실기를 했는지 안 했는지
- [ ] **Step 4:** 커밋한다. 메시지: `이러닝 완주 에이전트 코어 변경 기록과 연결 인계`

### Task 9: 전체 검증 (통합 뒤)

- [ ] **Step 1:** 코어·안드로이드 전체 시험, lint, 조립을 돌린다.
  - 실행: `./gradlew :core:test :android-runtime:testDebugUnitTest :android-runtime:lintRelease :app:lintRelease :app:assembleRelease --max-workers=2 --offline`
  - 기대: BUILD SUCCESSFUL
- [ ] **Step 2:** Codex 통합 빌드를 설치한 뒤 실기를 순서대로 한다.
  1. KAIRB 3개 강의 완주
  2. 퀴즈·설문·이수증
  3. 행정간사 메일 초안(발송 전까지)
  4. hallymedu 하루 한도·mOTP 재개
  - 각 결과를 변경 기록에 실제 증거로만 적는다.

## 오픈소스 참고 (증류 대상, 코드 복사 없음)

- **Browser Use** (MIT)
  - 작업 공간 파일 기반 진행 기억 → Task 1 장부
  - 실행 후 판정(judge)을 완료 검증에 씀 → 설계 4장 "수료 근거 없으면 완료 아님"
  - 비밀값 분리(`sensitive_data`) → 기존 보안 로그인 카드와 비교
- **Stagehand** (MIT): 한 번 찾은 동작을 기억했다가 결정적으로 다시 실행하는 방식이다. 강의 다음 버튼 반복에 참고한다.
- **Skyvern** (AGPL-3.0): TOTP 인계 흐름만 참고한다. 라이선스 때문에 코드는 쓰지 않는다.
- 어떤 후보도 "영상 끝까지 대기 + 여러 날 재개 + 인쇄형 PDF"를 공식 지원하지 않는다. 이 세 가지는 자체 벤치마크(Task 9 실기)로 검증한다.
