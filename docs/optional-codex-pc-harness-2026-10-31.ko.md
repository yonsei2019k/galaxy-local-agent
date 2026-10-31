# 선택 가능한 PC Codex 실행 기반 — 읽기 조사

2026-10-31. 현재 Android 통합과 별개의 후속 아키텍처 검토다. OpenAI Docs 스킬로 공식 문서를 열어 확인했고, 로컬 Codex 실행 파일의 위치·파일 정보와 앱의 PC 연결 소스만 읽었다. 설치·설정·인증·모델·프로세스·기기 상태는 변경하지 않았다.

**권고:** 휴대폰용 비서를 계속 쓰면서, 연결된 PC에서는 Codex 실행 기반을 재사용하는 별도 경로를 작은 실험으로 검증할 가치가 있다. 도구 반복·작업 중 지시·스킬·이벤트 처리를 Android에 모두 다시 구현하는 부담을 줄일 수 있다. 다만 공식 app-server 자체가 실험 단계이므로, 현재 Android 개선을 중단하고 즉시 전면 교체할 근거는 부족하다. 작은 로컬 모델의 판단력이 Codex 서비스 수준으로 바뀐다는 뜻도 아니다. [CLI 성숙도·app-server 설명](https://learn.chatgpt.com/docs/cli/reference)

## 현재 문서가 확인해 주는 기능

| 필요 기능 | app-server의 실제 표면 |
| --- | --- |
| 대화·재개·분기 | `thread/start`, `thread/resume`, `thread/fork`; 저장 이력은 페이지 조회 |
| 작업 시작·추가 지시·중지 | `turn/start`, `turn/steer`, `turn/interrupt`; steer는 `expectedTurnId` 일치 필요 |
| 진행 과정 | turn/item 시작·완료, 답변 증분, 도구 진행 이벤트 |
| 목표 | `thread/goal/set`, `get`, `clear`, `updated`; 목표·상태·사용량 관리 |
| 스킬 | `skills/list`, 변경 알림, 명시 skill 입력 |
| 승인 | 명령·파일 변경·권한 요청에 대한 클라이언트 응답 |
| 클라이언트 실행 도구 | `dynamicTools`와 `item/tool/call`; 실험 API opt-in 필요 |

이 표는 현재 공개 프로토콜의 기능이며 설치된 바이너리에서 직접 실행한 결과가 아니다. 버전별 JSON/TypeScript schema 생성 기능으로 실제 계약을 고정할 수 있다. [App Server 프로토콜](https://learn.chatgpt.com/docs/app-server)

스킬은 목록의 설명을 먼저 보고 선택한 본문을 읽는 방식이며, 문맥에 따른 자동 선택도 지원한다. 따라서 사용자에게 계획·목표·스킬 탭부터 고르게 할 필요는 없다. 스크립트·실행 환경까지 자동으로 제공되는 것은 아니므로, PC 프로그램을 요구하는 스킬은 해당 환경이 있어야 한다. [스킬 구조·점진적 로딩](https://learn.chatgpt.com/docs/build-skills)

MCP는 로컬 stdio와 Streamable HTTP, 인증, 도구 허용·제외 목록을 제공한다. Android의 기존 검증 도구를 좁은 MCP 어댑터로 제공하는 경로가 가능하다. 이것은 설계 제안이며 현재 앱에 MCP 서버가 구현됐다는 뜻은 아니다. [MCP 연결 계약](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)

## 무료·로컬 모델의 정확한 한계

Codex의 OSS 모드는 Ollama·LM Studio 로컬 공급자를 문서화한다. 그러나 공급자 통신 설정은 현재 `wire_api="responses"`만 지원한다. **OpenAI Chat Completions 형식만 지원한다는 설명으로는 연결 가능성을 확인할 수 없다.** 임의 llama.cpp 서버도 선택한 버전이 실제 Responses 요청·스트림·도구 호출·결과 재입력·취소 흐름을 처리하는지 별도로 확인해야 한다. 이번 공식 문서 조사로 모든 llama.cpp 버전의 직접 호환을 확인하지 못했다. [OSS 모드](https://learn.chatgpt.com/docs/config-file/config-advanced), [공급자 프로토콜 제한](https://learn.chatgpt.com/docs/config-file/config-reference)

따라서 유료 API 없이 실행할 후보는 있으나, 정확한 서버·모델 조합의 호환성과 품질은 아직 미검증이다. ChatGPT 로그인 경로는 별개의 계정·서비스 경로이므로 “로컬 무료 경로”와 같은 것으로 설명하면 안 된다. 이번에는 토큰·키·계정 설정을 읽거나 옮기지 않았다.

## 우리 앱과의 차이 및 권장 연결

현재 `PcInferenceEngine`은 `/v1/llm/load,tokens,chat,cancel,unload` 자체 계산 프로토콜이다. `AutomaticComputeEngine`도 추론 계산 장소를 바꾸며, 요청·권한·기록은 휴대폰에 둔다. 따라서 app-server 재사용은 단순한 추론 URL 교체가 아니라 **별도 agent-session 연결**이다.

권장 경로는 `휴대폰 요청/승인/자료 → 기존 페어링 gateway → PC stdio app-server → 로컬 Responses 호환 모델`이다. 필요한 phone tool 호출은 같은 연결의 좁은 브리지를 거쳐 기존 Android 검증기를 실행하고 결과를 반환한다. 다음 차이는 어댑터가 해결해야 한다.

| 차이 | 유지할 계약 |
| --- | --- |
| 도구 권한 | Codex의 요청도 실제 인자·방·프로젝트·자료·Android 권한·사용자 승인을 다시 검사한다. 스킬/목표가 권한이 되지 않는다. |
| 저장·재개 | 휴대폰의 암호화 기록을 기준으로 삼는다. PC의 영속 대화·목표를 무심코 두 번째 원본으로 만들지 않는다. |
| PC 임시 처리 | 선택한 자료만 전달한다. PC rollout·로그·캐시의 실제 보존을 측정하고 임시 처리 계약에 맞춘다. `history.persistence=none`만으로 모든 저장 금지를 입증할 수 없다. |
| 중지·연결 끊김 | 원래 run/job·승인 요청을 함께 묶고, 이미 실행한 변경은 휴대폰 fallback에서 재실행하지 않는다. |
| 결과 | 도구 이벤트와 실제 검증 근거를 구분하고, 부분 분석·파일 전달·인용 범위는 기존 증거 계약을 유지한다. |

Codex의 목표·상태·토큰 사용량은 우리 `GoalDefinition`의 완료 기준별 실제 실행 근거와 같은 계약이 아니다. `thread/goal`을 연결하더라도 휴대폰의 기준 검증을 대신해 자동 완료시키면 안 된다.

보관되는 thread/rollout과 임시 fork가 문서화돼 있다. 이는 PC 저장 정책을 별도로 설계해야 한다는 근거다. 네트워크 WebSocket 연결은 experimental/unsupported이고 기본 비루프백 인증에도 주의점이 있어, 직접 LAN 포트 노출보다 기존 인증 gateway 뒤 stdio를 후보로 권한다. 또한 `process/*`, `thread/shellCommand` 같은 원시 API는 sandbox 밖에서 실행될 수 있으므로 모바일 클라이언트에 전체 RPC 통과 권한을 주면 안 된다. [저장·transport·프로세스 경계](https://learn.chatgpt.com/docs/app-server), [history 설정 범위](https://learn.chatgpt.com/docs/config-file/config-reference)

## 작은 후속 검증으로 결정할 것

1. 설치된 정확한 Codex 버전과 schema를 임시 위치에서 고정한다. 로컬 실행 파일은 발견했지만 파일 버전 필드가 비어 있었으며, 이번에는 CLI·서버를 실행하지 않았으므로 최신 문서와 동일한 기능이라고 단정하지 않았다.
2. 공개 합성 자료와 임시 작업 폴더만 사용해 로컬 공급자의 실제 도구 왕복·추가 지시·승인 거절·중지·재연결·저장 잔류를 검증한다. 클라우드 추론이 발생하지 않았는지도 확인한다.
3. 같은 요청·자료·모델로 현재 Android 루프와 목표 달성률·조건 보존·지연·반복·출처 정확성을 비교한다. 그 뒤에만 PC 경로를 추가하고, PC가 없을 때의 기존 휴대폰 경로는 보존한다.

PC에서 Codex의 실행 기반을 빌리는 선택은 유망하다. 스마트폰 자료 접근과 업무별 증거 검증까지 자동으로 해결하는 완제품은 아니므로, 기존 Android 작업은 유지하면서 별도 검증 가능한 backend로 추가하는 편이 현실적이다.
