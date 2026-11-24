# 채팅 영역 재구성 0·1단계 구현 계획 (웹 UI)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **목표:** 집순이 채팅 화면을 피클 수준으로 가볍게 만든다.
  - 평문 답
  - 한 줄 작업 카드와 상세 시트
  - 헤더 작업 줄
  - 정리된 입력창
  - 고를 수 있는 캐릭터
  - 토큰 원본 하나
  - 화면 측정 하네스
- **구조:**
  - React 웹 UI(`web-ui/src`)만 바꾼다. 네이티브(`MainActivity`, 브리지 정책)는 바꾸지 않는다.
  - 이번 단계에서는 Snapshot 계약에 필드를 추가하지 않는다. 기존 필드로 표현한다.
  - 음성 대화, 대화 안 승인 카드, 네이티브 연동은 다음 계획(1b·2단계)에서 다룬다.
- **기술:** React 19, TypeScript 5.9, Vite 7, vitest 3(jsdom), Playwright 1.65.1(`scripts/browser-validation`), Python 3.12(토큰 생성)
- **설계 문서:** `docs/superpowers/specs/2026-11-24-chat-ux-redesign-plan.md` (소유자 승인 사항 포함)
- **작업 트리:** `C:\Users\VICTUS\source\ziphap-claude-uxplan`
- **브랜치:** `claude/chat-ux-plan` (기준 e637973)
- Codex가 다른 브랜치에서 같은 파일을 고치고 있으므로 통합은 Codex가 한다.

## 전체 제약

- **화면 규칙**
  - 밝은 단일 테마만 쓴다. 검은 화면은 금지다(소유자 2026-11-19).
  - 역할 색: 코발트 #3355F0(행동), 코랄 #C9401F(확인 필요), 민트 #0B7F5D(완료), 선샤인 #FFB020(대기, 글자 #8A5A00), 라일락 #7C4DEB(협업)
  - 바탕 #F7F8FC, 카드 #FFFFFF, 글자 #171B33
  - 대비는 WCAG AA(본문 4.5:1)를 지킨다.
- **제품 규칙(AGENTS.md):** 대화는 하나다. 작업 분류 탭과 모드 선택은 추가하지 않는다. 키워드로 의도를 분류하지 않는다.
- **브리지 경계:** UI는 입력 경계일 뿐 권한을 만들지 않는다. 새 `Intent` action을 추가하지 않는다(네이티브 허용 목록 `ReactBridgePolicy.kt`가 거부한다). 기존 action만 쓴다.
- **캐릭터**
  - 시안 3종: `bear` 곰돌이, `cat` 고양이, `bunny` 토끼
  - 색 8가지: `butter`, `peach`, `strawberry`, `sky`, `matcha`, `grey`, `cocoa`, `snow`
  - 표정 6가지: `basic`, `smile`, `focus`, `surprise`, `sleepy`, `wink`
  - 상태 소품: `none`, `work`, `wait`, `done`, `alert`
  - 기본값은 `bear`·`butter`·`basic`이다.
  - 상태별 자동 표정: `work`→`focus`, `wait`→`sleepy`, `done`→`smile`, `alert`→`surprise`
  - 원본: 시안 캔버스의 `Character` 부품(viewBox 200×200, 층 구조)
  - 선택값은 이번 단계에서 WebView `localStorage` 키 `ziphap.character.v1`에 저장한다. 실패해도 기본값으로 그린다.
- **개인 자료:** 시험·하네스의 고정 자료는 합성 자료만 쓴다. 스크린샷은 Git에 넣지 않는다(`.local/` 또는 무시 경로).
- **커밋:** 메시지는 한국어로 쓴다. 마지막 줄은 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`이다.
- **명령**
  - 웹 UI 시험: `cd web-ui && npx vitest run --environment jsdom`
  - 타입 검사와 빌드: `cd web-ui && npm run build`
  - 하네스: `cd scripts/browser-validation && node --test chat-visual.test.cjs`

## 리뷰 중점

1. **긴 한국어·영문 혼합 답과 표·코드 블록:** 평문 답으로 바꿔도 360px 폭에서 가로 넘침이 없어야 한다. 시험은 Task 2 하네스와 Task 4가 맡는다.
2. **메시지에 붙지 못한 작업:** `messageId`가 없거나 보이지 않는 경우에도 카드가 사라지지 않고 대화 끝에 한 줄 카드로 보여야 한다. 시험은 Task 5가 맡는다.
3. **카드·시트의 오래된 버튼:** 작업이 끝나 시트의 "예약 중지"가 더 이상 유효하지 않으면 버튼이 사라져야 한다(`canCancel` false). 시험은 Task 5가 맡는다.
4. **`localStorage`를 쓸 수 없는 WebView:** 접근이 막혀도 캐릭터가 기본값으로 그려지고 예외가 나지 않아야 한다. 시험은 Task 3이 맡는다.
5. **작업 2개 이상이 동시에 진행·대기 중일 때:** 헤더 작업 줄이 "작업 2개"로 표시되고, 누르면 첫 활성 작업으로 이동해야 한다. 시험은 Task 6이 맡는다.

---

### Task 1: 디자인 토큰 원본과 생성

**Files:**
- Create: `design/tokens.json`
- Create: `scripts/gen-design-tokens.py`
- Create: `web-ui/src/tokens.css` (생성물, 커밋함)
- Create: `app/src/main/java/com/user/localagent/ui/ZipHapTokens.kt` (생성물, 커밋함)
- Modify: `web-ui/src/styles.css`(상단 토큰 정의를 `@import './tokens.css'`와 변수 참조로 바꿈), `web-ui/src/main.tsx` 또는 `App.tsx`(필요하면 import)
- Modify: `web-ui/src/App.tsx`(효과 없는 `data-theme` 계산 제거: `systemDark`와 `theme` 변수, `data-theme` 속성)
- Modify: `docs/design-system.md` (현재 테마와 토큰 원본 위치로 갱신)
- Test: `scripts/tests/test_gen_design_tokens.py`

**Interfaces**
- 생산
  - CSS 변수 이름:
    - 색: `--color-bg`, `--color-surface`, `--color-text`, `--color-muted`, `--color-line`, `--color-cobalt`, `--color-cobalt-text`, `--color-cobalt-soft`, `--color-coral`, `--color-coral-text`, `--color-coral-soft`, `--color-mint`, `--color-mint-text`, `--color-mint-soft`, `--color-sun`, `--color-sun-text`, `--color-sun-soft`, `--color-lilac`, `--color-lilac-text`, `--color-lilac-soft`
    - 간격: `--space-1`..`--space-8` (4, 8, 12, 16, 20, 24, 32, 40px)
    - 글자: `--fs-caption`(12), `--fs-small`(13), `--fs-body`(15), `--fs-subtitle`(17), `--fs-title`(20), `--fs-screen`(24)
    - 모서리: `--radius-card`(16), `--radius-control`(12), `--radius-sheet`(24), `--radius-pill`(999)
  - Kotlin: `object ZipHapTokens { val Bg: Color; … }` 같은 이름의 `Color`와 `Dp`, `TextUnit` 값
- 소비: `styles.css`의 기존 별칭(`--muted` 등)은 새 변수를 가리키게 해서 화면을 바꾸지 않는다.

- [ ] **Step 1: 실패하는 시험을 쓴다.** `scripts/tests/test_gen_design_tokens.py`
  - `test_generates_css_and_kotlin_from_json`: 임시 JSON 한 벌로 생성한다. CSS에 `--color-cobalt: #3355F0;`, Kotlin에 `val Cobalt = Color(0xFF3355F0)`가 있어야 한다.
  - `test_rejects_bad_hex`: `"#33F"`처럼 6자리가 아닌 hex는 `ValueError`
  - `test_output_is_stable`: 두 번 생성한 결과가 바이트 단위로 같아야 한다.
- [ ] **Step 2:** 실행해 실패를 확인한다.
  - 명령: `"$ZIPHAP_PYTHON" -m pytest scripts/tests/test_gen_design_tokens.py -q`. pytest가 없으면 `-m unittest`를 쓴다.
- [ ] **Step 3:** `gen-design-tokens.py`를 구현한다.
  - 사용법: `python scripts/gen-design-tokens.py [--check]`. `--check`는 생성물이 원본과 다르면 종료 코드 1을 낸다.
  - `tokens.json`에는 위 값과 설계 문서 3.3·5.7 값을 그대로 넣는다.
- [ ] **Step 4:** 생성물을 만들고 `styles.css`·`App.tsx`·문서를 고친다. 웹 UI 시험·빌드·생성 시험을 통과시킨다.
  - 화면이 바뀌지 않아야 한다: 기존 변수 값과 같은지 diff로 확인한다.
- [ ] **Step 5:** 커밋한다. 메시지: `디자인 토큰 원본과 CSS·Compose 생성 추가`

### Task 2: 화면 측정 하네스

**Files:**
- Create: `scripts/browser-validation/chat-visual.test.cjs`
- Create: `scripts/browser-validation/chat-fixtures.cjs` (합성 Snapshot 12종)
- Modify: `scripts/browser-validation/package.json` (`test` 스크립트에 새 파일 추가)
- Modify: `scripts/browser-validation/.gitignore` (`out/`)

**Interfaces**
- 생산: `node --test chat-visual.test.cjs`
  - 동작
    1. `web-ui/dist`를 Node `http`로 띄운다(빌드는 먼저 `npm run build`).
    2. `addInitScript`로 `window.ziphapNative={postMessage(){}}`를 심는다.
    3. 각 고정 자료를 `SNAPSHOT_EVENT`(bridge.ts의 상수 문자열을 그대로)로 보낸다.
    4. 360×800, 393×852, 412×915에서 측정한다.
  - 결과: `out/<fixture>-<w>.png`, `out/metrics.json`에 `{fixture, width, visibleItems, overflowX, minContrast}`
- 고정 자료(합성): `empty`, `chatShort`, `chatLong`(평문 20개와 표·코드), `taskRunning`, `taskPaused`, `taskWaitingGoal`, `taskFailed`, `taskCompleted`, `unanchoredTasks`, `twoLiveTasks`, `websiteCard`, `files`

- [ ] **Step 1:** 시험을 쓴다. 항목마다 단언한다.
  - `overflowX === false`: `document.documentElement.scrollWidth <= innerWidth`
  - 보이는 모든 글자 요소의 `minContrast >= 4.5`. 굵은 18.66px 이상 글자는 3.0
  - `visibleItems`(뷰포트 안의 `.message`, `.work-card`, `.task-card` 수)를 기록만 한다.
- [ ] **Step 2:** `cd web-ui && npm run build`를 한 뒤 하네스를 실행한다. 지금 화면 기준값을 `out/metrics.baseline.json`으로 복사한다.
  - 이 파일은 Git 밖에 둔다. 컨트롤러가 장부에 숫자를 옮겨 적는다.
- [ ] **Step 3:** 커밋한다. 메시지: `채팅 화면 측정 하네스와 합성 고정 자료 추가`

### Task 3: 캐릭터 부품과 선택 시트

**Files:**
- Create: `web-ui/src/character/Character.tsx`
- Create: `web-ui/src/character/characterData.ts`
- Create: `web-ui/src/character/CharacterPicker.tsx`
- Create: `web-ui/src/character/useCharacter.ts`
- Create: `web-ui/src/character/character.css`
- Modify: `web-ui/src/Conversation.tsx`
  - `BrandMark` 사용처를 캐릭터로 교체한다.
  - Welcome의 `ziphap-mascot.png`를 큰 캐릭터로 교체한다.
- Modify: `web-ui/src/Workspace.tsx`: 헤더의 `topbar-brand`를 캐릭터 버튼으로 바꾼다. 누르면 선택 시트가 열린다.
- Test: `web-ui/src/character/Character.test.tsx`

**Interfaces**
- 생산
  - `type CharacterVariant = 'bear'|'cat'|'bunny'`
  - `type CharacterColor = 'butter'|'peach'|'strawberry'|'sky'|'matcha'|'grey'|'cocoa'|'snow'`
  - `type CharacterExpr = 'basic'|'smile'|'focus'|'surprise'|'sleepy'|'wink'`
  - `type CharacterState = 'none'|'work'|'wait'|'done'|'alert'`
  - `interface CharacterChoice { variant: CharacterVariant; color: CharacterColor; expr: CharacterExpr }`
  - `export const DEFAULT_CHARACTER: CharacterChoice = { variant: 'bear', color: 'butter', expr: 'basic' }`
  - `export function Character(props: { choice: CharacterChoice; state?: CharacterState; size: number; decorative?: boolean }): JSX.Element`
    - `decorative`면 `aria-hidden`이다. 아니면 `role="img"`와 `aria-label="집순이"`를 붙인다.
    - 상태가 있으면 자동 표정을 쓴다.
  - `export function useCharacter(): [CharacterChoice, (next: CharacterChoice) => void]`
    - `localStorage` 키 `ziphap.character.v1`을 쓴다. JSON을 검증하고, 실패하면 기본값을 쓴다. 모든 접근은 try/catch로 감싼다.
  - `export function CharacterPicker(props: { open: boolean; value: CharacterChoice; onChange(next): void; onClose(): void })`
    - `<dialog>` 하단 시트다. 시안 3, 색 8, 표정 6 버튼(`aria-pressed`)과 큰 미리보기가 있다.
- 그림 값: `characterData.ts`에 시안 캔버스 `Character` 부품의 팔레트, 표정 경로, 층 좌표를 그대로 옮긴다(설계 문서 5.7).
  - 이 작업의 요구사항 원본은 `C:\Users\VICTUS\AppData\Local\Temp\claude\ef6229d8dcd23d42\41889c65-1dab-40f0-af42-7de7bcccb56d\scratchpad\zipchar\project\Character.dc.html`이다.

- [ ] **Step 1:** 실패하는 시험을 쓴다. `Character.test.tsx`
  - `rendersEachVariantEarsAndDefaults`: 3종마다 귀 층이 하나씩만 그려진다(`[data-layer="ears-bear"]` 등). 기본값은 곰돌이다.
  - `stateOverridesExpression`: `state="wait"`이면 `[data-expr="sleepy"]`와 `[data-layer="prop-wait"]`가 있다.
  - `storageFailureFallsBackToDefault` (리뷰 중점 4): `localStorage.getItem`이 throw해도 `useCharacter`가 기본값을 주고, 저장 실패도 삼킨다.
  - `rejectsUnknownStoredValues`: `{"variant":"dog"}`가 저장돼 있으면 기본값을 쓴다.
  - `pickerChangesAndPersists`: 시트에서 "고양이"와 "하늘"을 누르면 `onChange`가 해당 값으로 호출되고 저장된다.
- [ ] **Step 2:** 실패를 확인한다. 명령: `cd web-ui && npx vitest run src/character --environment jsdom`
- [ ] **Step 3:** 구현한다.
  - SVG는 JSX로 직접 그린다. `innerHTML`은 쓰지 않는다.
  - 층마다 `data-layer`를, 표정 그룹에 `data-expr`를 붙인다.
- [ ] **Step 4:** 교체 지점을 고치고, 기존 `App.test.tsx`에서 마스코트·브랜드마크를 단언하던 부분을 새 구조에 맞게 고친다. 전체 웹 UI 시험과 빌드를 통과시킨다.
- [ ] **Step 5:** 커밋한다. 메시지: `고를 수 있는 집순이 캐릭터 부품과 선택 시트 추가`

### Task 4: 메시지 표현 (평문 답)

**Files:**
- Modify: `web-ui/src/Conversation.tsx` (`Message`)
- Modify: `web-ui/src/styles.css`, `web-ui/src/answer.css`
- Test: `web-ui/src/App.test.tsx` (기존 시험 갱신과 추가)

**규칙**
- 에이전트 답: 상자와 테두리 없이 평문으로 쓴다. 바탕 투명, 글자는 `--color-text`, 줄 간격 1.6.
- 연속된 에이전트 답 묶음: 첫 메시지에만 캐릭터(24px)와 작성자 이름을 붙인다. 작성자가 바뀌면 새 묶음이다.
- 사용자 말풍선: 오른쪽 정렬, `--color-cobalt-soft` 바탕, 모서리 `--radius-card`, 최대 폭 82%
- 동작 버튼: 복사 아이콘을 없앤다. "···" 메뉴 하나만 남긴다(기존 `messageMenu` intent). 복사는 네이티브 메뉴에 있다.
- `role==='tool'|'system'`: 작은 회색 한 줄 기록(`--fs-small`, `--color-muted`)

- [ ] **Step 1:** 시험을 쓴다.
  - `groupsConsecutiveAssistantAnswersUnderOneLabel`: 연속 2개는 라벨 1개, 사이에 사용자 메시지가 끼면 라벨 2개
  - `assistantAnswerHasNoBubbleBox`: `.assistant-bubble`가 없고 `.assistant-text`가 있다.
  - `keepsSingleMenuAndRemovesCopyIcon`
  - 기존 복사 시험("copies the selected answer")은 메뉴 경로로 바꾼다.
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** 웹 UI 시험·빌드·하네스를 통과시킨다. 하네스 `chatLong`의 가로 넘침이 없어야 한다(리뷰 중점 1).
- [ ] **Step 5:** 커밋한다. 메시지: `에이전트 답을 평문 묶음으로 바꾸고 메시지 동작 정리`

### Task 5: 작업 카드 하나 (`WorkCard`)와 상세 시트

**Files:**
- Create: `web-ui/src/work/WorkCard.tsx`
- Create: `web-ui/src/work/WorkSheet.tsx`
- Create: `web-ui/src/work/workPresentation.ts`
- Create: `web-ui/src/work/work.css`
- Modify: `web-ui/src/Conversation.tsx`
  - `TaskCard`, floating 영역 3곳, `phasePresentation`을 대체한다.
  - `taskPhase`는 `workPresentation`으로 옮기고 re-export한다.
- Test: `web-ui/src/work/WorkCard.test.tsx`, `App.test.tsx` 갱신

**Interfaces**
- 생산
  - `type WorkTone = 'active'|'waiting'|'review'|'complete'|'failed'|'neutral'`
  - `function workPresentation(task: Task): { tone: WorkTone; title: string; icon: IconName; characterState: CharacterState }`
    - running/queued/waiting_resource → `active`(`work`)
    - waiting_goal → `waiting`(`wait`)
    - approval/waiting_approval/paused/waiting_input → `review`(`alert`)
    - completed → `complete`(`done`)
    - failed → `failed`
    - cancelled/interrupted/clarification_record·알 수 없음 → `neutral`
    - 제목 문구는 기존 `phasePresentation` 값을 따른다.
  - `function friendlyStep(label: string): string`: 내부 문구를 사람 말로 바꾼다. 아래 표에 없는 값은 그대로 둔다.

| 내부 문구 패턴 | 바꾼 문구 |
|---|---|
| `^사이트 화면 확인` | `사이트 화면을 읽었어요` |
| `^공개 웹 검색` | `웹에서 찾아봤어요` |
| `^웹페이지 읽기` | `웹페이지를 읽었어요` |
| `· 실행 결과 기록$` (꼬리) | 꼬리를 제거 |
| `^(모델 추론\|후속 조건 검증 기록\|답변 구간 저장 확인)` | 숨김(빈 문자열 → 표시 안 함) |

  - `function WorkCard(props: { task: Task; onOpen(taskId: string): void })`
    - 한 줄 카드: 아이콘 + 제목(`task.label`) + `"{presentation.title}"` + `task.detail` 첫 줄(40자 자름) + `›`
    - 진행 중이면 가는 진행 막대를 넣는다. 높이는 56~72px, `id="work-{task.id}"`, 전체가 `<button>`이다.
  - `function WorkSheet(props: { task: Task|null; enabled: boolean; onClose(): void; onStopGoalWait(runId: string): void })`
    - `<dialog>` 하단 시트. 상태, 상세, `friendlyStep`으로 바꾼 단계 목록, 근거(`evidence` label·detail)를 보인다.
    - `waiting_goal && canCancel && enabled`일 때만 "예약 중지" 버튼을 보인다.
- 배치 규칙
  - 카드는 원래 사용자 메시지 아래에 붙인다(기존 `cardsByMessage` 유지).
  - 붙지 못한 작업은 대화 끝에 한 줄 카드로 모은다. "이전 작업 기록" 접기, 일시정지 섹션, 현재 작업 섹션은 없앤다.
  - `waiting_input`은 기존 `reply-needed` 줄을 유지한다.
  - `websiteCard`
    - `inputHeight`가 있으면 기존 빈 자리를 그대로 둔다(네이티브 겹침).
    - 그 외에는 `WorkCard` 모양으로 그린다. 상세는 `WorkSheet`에 `website.steps`를 단계로 보인다.

- [ ] **Step 1:** 시험을 쓴다.
  - `mapsEveryKnownPhaseToTone`: 표의 모든 phase
  - `friendlyStepRewritesKnownLabelsAndHidesInternal`
  - `unanchoredTasksAppearAtEndAsCards` (리뷰 중점 2): `messageId`가 없는 작업 2개 → 대화 끝 `.work-card` 2개, `details.task-evidence`는 0개
  - `sheetShowsStopOnlyWhenCancellable` (리뷰 중점 3): `canCancel` false로 다시 렌더하면 버튼이 사라진다.
  - `cardOpensSheetWithSteps`
  - 기존 `App.test.tsx`의 TaskCard·floating 단언을 새 구조로 고친다. 고친 시험 이름을 보고서에 적는다.
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** 웹 UI 시험·빌드·하네스를 통과시킨다.
- [ ] **Step 5:** 커밋한다. 메시지: `작업 카드를 한 줄 카드와 상세 시트로 통합`

### Task 6: 헤더 작업 줄과 입력창 정리

**Files:**
- Modify: `web-ui/src/Workspace.tsx` (`Header`, `Composer`)
- Modify: `web-ui/src/Conversation.tsx` (`current-work` 단추 제거)
- Create: `web-ui/src/work/HeaderWorkLine.tsx`
- Create: `web-ui/src/ComposerSheet.tsx`
- Test: `App.test.tsx` 갱신과 추가

**규칙**
- **헤더**
  - 구성: [메뉴] [캐릭터 버튼(32px, Task 3 시트 열기)] [이름 "집순이"와 그 아래 `HeaderWorkLine`]
  - 오른쪽의 "이 대화"(`manageThreads`) 아이콘은 유지한다.
  - 모델 `<select>`와 실행 중 모델 표시는 헤더에서 뺀다.
- **`HeaderWorkLine`**
  - 활성·대기 작업(`isLiveTaskPhase` + `queued` + `waiting_goal`)이 1개면 `"{제목} · {presentation.title}"`, 2개 이상이면 `"작업 N개"`를 보인다.
  - 누르면 첫 활성 작업 카드로 스크롤한다(리뷰 중점 5). 없으면 렌더하지 않는다.
  - `websiteCard.working`이면 그 제목을 우선한다.
- **입력창**
  - [+] → `ComposerSheet`(하단 시트)를 연다. 내용:
    - "파일 첨부"(기존 `attach` intent)
    - "답변 방식"(기존 모델 목록 라디오, `selectModel` intent, 현재 선택 표시)
    - 실행 중 모델 이름(읽기 전용)
  - `composer-status`는 사용자에게 의미 있는 안내만 남긴다(음성 상태, 길이 초과, 연결 오류). 일반 `snapshot.status` 문구는 헤더 작업 줄이 대신하므로 입력창 위에 보이지 않는다.
  - 받아쓰기, 중단, 추가 지시, 보내기는 그대로 둔다.

- [ ] **Step 1:** 시험을 쓴다.
  - `headerShowsSingleTaskLineAndScrolls`
  - `headerShowsTaskCountForTwoLiveTasks` (리뷰 중점 5)
  - `modelChoiceMovedIntoComposerSheetAndSendsSelectModel`
  - `composerHidesGenericStatusButKeepsVoiceAndLengthNotes`
  - 기존 모델 선택 시험("다음 답변 방식" select)을 시트 경로로 바꾼다.
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** 웹 UI 시험·빌드·하네스를 통과시킨다.
- [ ] **Step 5:** 커밋한다. 메시지: `헤더 작업 줄과 입력창 시트로 상태·모델 선택 정리`

### Task 7: 측정 비교·전체 빌드·인계 기록

**Files:**
- Modify: `docs/agent-change-log.md` (맨 위 항목)
- Create (무시 경로): 접수 보고서 `C:\Users\VICTUS\source\galaxy-local-agent\.local\coordination\release-intake\reports\claude-chat-ux-<sha>.md`

- [ ] **Step 1:** 하네스를 다시 실행해 기준값과 비교한다.
  - 완료 기준: `chatLong`과 `taskCompleted`의 393px `visibleItems`가 기준값의 1.5배 이상이다.
  - 모든 고정 자료에서 `overflowX` false, 대비 AA를 만족한다.
  - 숫자는 보고서에 적는다.
- [ ] **Step 2:** 전체 빌드를 한다.
  - 명령: `./gradlew :core:test :android-runtime:testDebugUnitTest :android-runtime:lintRelease :app:lintRelease :app:assembleRelease --max-workers=2 --offline`
  - React 자산은 `scripts/build-react-ui.py`가 같이 묶는다.
  - 기대: BUILD SUCCESSFUL
- [ ] **Step 3:** 변경 기록과 접수 보고서를 쓴다.
  - 바뀐 화면, 측정 수치, 미검증 범위(서명·설치·실기, 네이티브 연동, 음성 대화)를 적는다.
- [ ] **Step 4:** 커밋한다. 메시지: `채팅 화면 0·1단계 변경 기록`
