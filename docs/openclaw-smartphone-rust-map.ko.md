# OpenClaw 구조와 집합의 스마트폰 Rust 전환 지도

비교 기준은 OpenClaw `5c15d796c6`입니다. 저장소의 최상위 디렉터리를 확인한 뒤 실행 경로별 핵심 소스와 설계 문서를 읽었습니다. GitHub의 재귀 파일 목록이 잘렸으므로 전체 파일 수와 줄 수는 이 스캔으로 집계할 수 없습니다. [비교 기준 커밋](https://github.com/openclaw/openclaw/commit/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc)

| 영역 | OpenClaw 경로 | 집합의 현재 진입점 | 스마트폰 설계 후보 |
| --- | --- | --- | --- |
| 실행 루프 | [agent-loop.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/packages/agent-core/src/agent-loop.ts), [agent-loop-tool-execution.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/packages/agent-core/src/agent-loop-tool-execution.ts), [tool-repeat-guard.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/packages/agent-core/src/tool-repeat-guard.ts) | Android `ChatRunCoordinator.kt`, Rust `ziphap-core/src/run.rs`, PC `agent_provider_supervisor.py` | 요청과 관측을 묶은 다음 행동 선택을 유지. 같은 조회 반복을 제한하고, 중단 시 이미 확인한 결과를 보존. |
| 도구 등록과 정책 | [openclaw-tools.registration.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/agents/openclaw-tools.registration.ts), [validation.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/packages/llm-core/src/validation.ts) | `ExternalAgentContract.kt`, `ToolExecutor.kt`, `ziphap-core/src/grant.rs` | 실행 직전에 접속·권한·대상 결합을 검사. 휴대폰에 실제 설치된 앱과 승인된 기능만 등록. |
| 세션과 기록 | [session-controller.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/sessions/session-controller.ts), [session-accessor.sqlite-transcript-store.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/config/sessions/session-accessor.sqlite-transcript-store.ts) | `ChatRecords.kt`, Rust `ziphap-core/src/journal.rs`, `ExternalAgentPhoneCompletion.kt` | 세션과 영수증을 분리하고 Android 백그라운드 종료를 견디는 기기 저장을 유지. 취소된 작업의 늦은 영수증을 원래 호출과 대조. |
| 메모리 | [memory-budget.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/extensions/memory-core/src/memory-budget.ts), [flush-plan.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/extensions/memory-core/src/flush-plan.ts), [메모리 구조](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/docs/concepts/memory.md) | `CloudConversationContext.kt`, `KnowledgeIngestion.kt`, 기기 저장소 | 요청 관련 기억과 최근 문맥에 예산을 주고 원출처와 보존 범위를 유지. 상시 재색인과 전체 대화 전송은 축소. |
| 스킬 | [skills-prompt.ts](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/agents/embedded-agent-runner/skills-prompt.ts), [skills 서버 메서드](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/gateway/server-methods/skills.ts) | `BundledSkillStore.kt`, `app/src/main/assets/skills/` | 필요한 스킬만 읽고 절차와 증거 기준을 참고. 도구·권한은 기기가 별도로 검증. |
| 서버·채널·화면 | [Gateway 구조](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/docs/concepts/architecture.md), [요청 메서드 레지스트리](https://github.com/openclaw/openclaw/blob/5c15d796c6b6a36705ee85cdacfcc1c4c1b0d8dc/src/gateway/methods/registry.ts) | PC 페어링 워커, Android `ScreenControl.kt`, `web-ui/` | 여러 채널용 게이트웨이를 축소. 화면 요소 선택, 앱 전환, 권한 요청, 로컬 자료 처리, 느린 연결 복구는 스마트폰용으로 설계. |

Codex가 경로 조사, 문제 재현, Rust 구현과 회귀를 맡습니다. Claude에는 이 표에서 판단이 필요한 행만 보내고 유지·축소·재설계를 검토받습니다. 구현 뒤에는 배터리, 앱 중단, 권한 변경, 화면 갱신, 오프라인, 느린 연결에서 기존 시험이 놓친 실패를 독립 검토받습니다.

## 전환 전에 닫아야 할 기존 항목

- C-058–062의 PC 종료와 늦은 영수증 대조는 Rust 실행기의 종료 식별과 보류 호출 참조까지 소스에서 검증했습니다. 실제 브로커와 연결하는 시험이 남아 있습니다.
- C-055·C-063의 일시정지 시 공개 출처 표시는 2039 후보의 시험·서명 검증을 마쳤습니다. 이미지 작업이 끝난 뒤 설치와 실제 화면 확인이 필요합니다. 이 후보는 작업 상태를 그대로 두고 확인된 출처를 화면에 남깁니다.
- C-051의 확인된 진행 기준 마감은 PC의 300초, Android Rust 기록의 330초, 기기·브로커 세션의 360초를 함께 검토해야 합니다. C-064가 확인한 브로커의 완료 영수증만 진행으로 세는 경로부터 적용 후보를 만들고, 권한 만료 이후 실행을 재개할 수 없는 시험을 추가합니다.
- C-053의 페이지 설명 근거 등급은 본문과 설명을 분리하는 Rust 구현이 남아 있습니다. 설명만 읽은 경우 운임·좌석이나 작업 완료의 근거가 되지 않음을 시험합니다.
