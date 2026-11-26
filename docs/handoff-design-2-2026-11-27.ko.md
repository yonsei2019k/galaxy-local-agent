# 디자인 언어 2.0 인계서 (Claude → Codex, 2026-11-27)

소유자 지시(2026-11-25~26):
- AI가 만든 듯한 느낌을 없앤다. 대상은 기본 글꼴, 진한 선, 기본 아이콘과 이모티콘이다.
- 피클처럼 깔끔하고 트렌디하게, 움직이는 캐릭터를 포함해 렌더링 요소를 늘린다.
- 채팅뿐 아니라 설정과 모든 화면에 적용한다.

이 문서는 그 작업을 Codex가 통합할 수 있도록 정리한 것이다. 푸시, 서명, 설치는 하지 않았다.

## 1. 위치

- 작업 트리: `C:\Users\VICTUS\source\ziphap-claude-design2`
- 브랜치: `claude/design-2`. 기준은 **a505dac** (`codex/work-history`, 2026-11-26 최신)이다. 커밋 15개.
- 이전 채팅 개편 인계서: `docs/handoff-chat-ux-2026-11-24.ko.md`(다른 작업 트리 `ziphap-claude-uxplan`). 그 1~6단계는 Codex가 이미 통합했다.
- 계획: `ziphap-claude-uxplan/docs/superpowers/plans/2026-11-25-design-language-2.md`
- 진행 장부와 작업별 보고서: `.superpowers/sdd/2026-11-26-design2-rebase/` (무시 경로)
- 배포 접수 보고서: `galaxy-local-agent/.local/coordination/release-intake/reports/claude-design-2-532b2dd.md`
- 변경 기록: `docs/agent-change-log.md` 맨 위 항목

## 2. 소유자 결정

1. **글꼴:** 본문은 SUIT Variable, 제목·캐릭터 이름·빈 화면 제목은 주아. 둘 다 OFL 1.1이고, 2026-11-26 소유자 허락 뒤 공식 저장소에서 내려받았다.
2. **레이아웃:** 토스·당근·피클 계열로 간다.
   - 옅은 회색 바탕에 흰 둥근 카드를 놓고, 묶음 사이는 간격으로 나눈다.
   - 진한 선은 쓰지 않는다.
   - 버튼과 입력칸은 채운 형태로 하고, 목록 줄에는 아이콘 타일을 붙인다.
3. **아이콘·그림:**
   - 기본 아이콘을 집순이 그림체 듀오톤 아이콘 50종으로 바꾸고, 그림 12종을 새로 만든다.
   - 답변 속 이모티콘은 화면에 그릴 때 집순이 스티커 21종으로 바꾼다.
   - 시스템 이모티콘은 화면 어디에도 나오지 않는다.
4. **캐릭터:** 시안 3종 × 색 8 × 표정 6. 고르는 방식은 기존 그대로다.
5. **움직임:**
   - 숨쉬기, 깜빡임, 새 답변 때 깡충, 작업 중 생각하는 모습, 완료 축하
   - 빈 화면 그림의 떠다님, 시트, 카드
   - "애니메이션 줄이기" 설정을 따르고, 화면 밖에서는 멈춘다.

## 3. 커밋

| 커밋 | 내용 |
|---|---|
| 2ab304f | 토큰 2.0: 면, 그림자, 모서리, 글자 단계, 생성기 `--check` |
| 1f5fe0d | 듀오톤 아이콘·그림 원본과 생성기(`scripts/gen-icons.py`). Codex의 speaker 아이콘도 새 그림체로 포함 |
| 88b081f | 자리표시자 글자색 AA |
| 404b169 | Compose 테마 2.0과 공통 부품(`ui/ZipComponents.kt`) |
| e217bb8, 150c584 | 최신 웹 화면 전면 정리, 스티커, 모션, 움직임 줄이기 |
| e71de25, 04b1425 | 글꼴 적용(Android, 웹). 사이드바 로고를 집순이 캐릭터로 |
| c0c7ad3, 0cd5987, 3fc82c2, 7cc51cc | Compose 전 화면: 설정·연결 / 둘러보기·연락처·기록·보관함 / 기능 화면 / 대화상자·승인·글자 기호. 공통 대체 부품 `ui/ZipMaterial.kt` |
| d3553a0 | 웹 글꼴 라이선스를 빌드 대상 밖으로 옮김(React 빌드가 `.txt`를 거부), coral-text 토큰 #B83C1D |
| 532b2dd | 뜻이 다른 이모티콘(비행기·금지·카드 등)을 스티커 묶음에서 제외, 승인 취소 버튼 무게 맞춤, 뒤로 버튼 화면 낭독 이름 |
| (이 문서) | 변경 기록, 인계서 |

## 4. 검증

- **전체 Gradle:** `:core:test :android-runtime:testDebugUnitTest :android-runtime:lintRelease :app:lintRelease :app:assembleRelease`가 BUILD SUCCESSFUL이다(단위 시험 1,226건, lint 오류 0). 그 뒤 마지막 수정은 `compileReleaseKotlin`으로 확인했다.
- **웹:** vitest 156건, 화면 하네스 51건(`scripts/browser-validation/chat-visual.test.cjs`, 일반과 `CHAT_VISUAL_REDUCED_MOTION=1`), 이모티콘 검사 0건.
- **화면 측정:** 393px 혼합 대화에서 한 화면에 보이는 항목은 7개다(개편 전 4개). 대비는 최소 5.16이다.
- **최종 검토(opus):** 동작, 권한, 보안 경계, testTag, semantics의 숨은 변경은 없다.
  - 승인·결제·금고 화면은 같은 사실을 보여 주고, 같은 명시 확인을 요구한다.
  - 하단 시트의 닫기 제스처는 닫기만 한다.
- **화면 확인:** 스크린샷은 `scripts/browser-validation/out/`에 있다(Git 밖). `ui-tour.cjs`가 사이드바, 음성, 작업 기록, 숙소 화면을 찍는다.

## 5. 통합할 때 주의

1. **충돌 가능 파일:** Codex가 a505dac 이후 같은 화면 파일을 바꿨다면 충돌한다. 화면 변경은 모양 줄에 한정했다.
   - `MainActivity.kt`, 각 Panel, `web-ui/src` 컴포넌트, `styles.css`
2. **생성물:** `design/*.json`이 원본이다. `tokens.css`, `ZipHapTokens.kt`, `icons/generated.ts`, `res/drawable/zi_*.xml`은 생성물이다.
   - 수정은 원본에서 하고, `python scripts/gen-design-tokens.py`와 `python scripts/gen-icons.py`로 다시 만든다(`--check`로 확인).
   - `zi_` 접두어 drawable은 생성기가 관리하고, 원본에 없는 것은 지운다.
3. **CSP:** `web-ui/vite.config.ts`에 `font-src 'self'`가 있어야 글꼴이 보인다.
4. **글꼴 라이선스:** `web-ui/public`에 `.txt`를 두면 `build-react-ui.py`가 빌드를 거부한다. 라이선스는 `web-ui/licenses`와 `app/src/main/assets/licenses`에 둔다.
5. **Android 아이콘:** `zi_ill_*`는 고정 색 그림이라 `Image`나 `ZipIllustration`으로만 쓴다. `Icon(tint)`로 쓰면 색이 덮인다.

## 6. 남은 일

- **Codex 소유:**
  - `JIPSOONI_VOICE`(ChatRunCoordinator)에 "이모티콘을 쓰지 않는다"를 넣는다. 화면 치환은 이미 되어 있으니 보조 장치다.
  - View 기반 Activity 5개를 테마 2.0에 맞춘다: 연결 설정, 쿠팡 리뷰, PC OpenClaw, 공유 자료, X 로그인 결과.
- **실기 확인:** 서명, 설치, 실제 휴대폰에서 다음을 본다.
  - 그림자 세기, 듀오톤 아이콘 색(fillAlpha 0.24)
  - WebView 글꼴 로딩
  - 길게 눌러 복사했을 때 원문이 나오는지
  - TalkBack
- **작은 정리(최종 검토 Minor):**
  - 쓰지 않는 부품과 중복을 정리한다.
    - 쓰지 않는 것: ZipScreen, ZipSwitchRow, ZipTextField, ZipTag, ZipBlockGap
    - ZipComponents와 ZipMaterial 사이의 버튼 중복
  - 토큰 밖 색 몇 곳을 토큰으로 옮긴다. 웹은 `motion.css`·`sheet.css`·`styles.css`의 hex, Kotlin은 `ZipHapDesign`의 Elevated·Outline·Error다.
  - 빈 화면 문장이 제목 스타일로 보이는 곳을 고친다(`ZipEmptyState`).
  - 대화 고정 표시를 하트 아이콘 대신 다른 것으로 바꾼다.
  - Android용 more, camera, pin 아이콘을 추가한다.
  - 이름 변경 폼 버튼을 44px로 맞춘다.
  - 메시지 수정 중 시트를 끌어 닫으면 초안이 사라지지 않게 막는다.
  - `ImageStudioPanel`의 남은 import를 정리한다.
