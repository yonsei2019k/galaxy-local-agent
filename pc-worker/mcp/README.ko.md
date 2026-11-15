# 로컬 LLM 위임 MCP (ziphap-local-llm)

Claude Code나 Codex가 지휘자(계획·판단·최종 검토)로 남고, 토큰이 많이 드는 읽기·요약·추출·일괄 변환은 이 PC 작업기에 설정된 로컬 `pc-text` 모델에 맡기는 stdio MCP 서버다. 파일은 PC에서 읽고 보통 짧은 결과를 반환한다. 요약에 원문 인용이 포함될 수 있으므로 원문이 외부 에이전트 문맥에 전혀 들어가지 않는다고 보장하지 않는다.

## 현재 경로와 제품 확장 구분

| 경로 | 현재 근거와 경계 |
|---|---|
| 외부 Codex/Claude → 이 MCP → PC 로컬 모델 | 이 디렉터리의 실제 구현. 아래 네 도구로 텍스트 작업을 위임한다. 외부 클라이언트 연결·등록과 실제 모델 실행은 별도 검증한다. |
| 외부 에이전트 → 그 에이전트에 연결된 MCP 도구 | 해당 호스트의 설정·인증·허용 도구에 달려 있다. 이 서버의 네 도구가 다른 MCP의 권한이나 접속을 제공하지 않는다. |
| ZipHap 스마트폰 비서 → PC 에이전트 → 폰 도구 | 별도 `device_tools_mcp.py`/broker/provider/Android client가 추가됐다.1088 실제 일반 대화 메모 저장·검색·읽기와 승인 거절/권한 철회가 검증됐으며 초기5도구만 지원한다. 이 개발 위임 MCP의 네 도구와 구분한다. Studio/PC 공유 대화방·Claude 직접 도구 경로는 미완이다. |
| 앱의 개인 브리핑 → 외부 Codex | `scripts/briefing_companion.py`는 현재 외부 도구를 차단하고 `mcp_tool_call`이 있으면 결과를 거부한다. 이를 범용 MCP 우회 연결로 사용하지 않는다. |
| Android APK 자체의 범용 MCP 호스팅 | 이 서버로 구현되지 않는다. PC의 실행 환경과 Android 도구·권한은 별도다. |

폰 도구용 MCP는 승인된 요청마다 PC 호스트가 만든 capability를 하위 프로세스 환경으로 전달한다. 전역 MCP 설정에 세션 비밀값을 저장하거나 이 개발 위임 서버의 권한을 재사용하지 않는다. 운영·검증 범위는 [클라우드 폰 도구 연결](../../docs/architecture/cloud-phone-agent.ko.md), 최신 설치/실기 근거는 PROJECT_STATE와 agent-change-log를 따른다.

## 도구

| 도구 | 용도 |
|---|---|
| `local_status` | 로컬 모델이 비어 있는지 확인 |
| `local_ask` | 한 건 위임: 요약·초안·분류·번역, `schema`를 주면 JSON으로 추출 |
| `local_digest_files` | 파일(최대 20개, 각 2MB)을 로컬에서 읽고 요청한 내용만 반환. 긴 파일은 나눠 읽고 합친다 |
| `local_batch` | 같은 종류의 작은 작업을 최대 20건 한 번에 |

## 등록

Claude Code (이 PC, 사용자 범위):

```bash
claude mcp add --scope user ziphap-local-llm -- "D:\ZipHap\Compute\python\python.exe" -E -s "C:\Users\VICTUS\source\galaxy-local-agent\pc-worker\mcp\local_llm_mcp.py"
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.ziphap-local-llm]
command = 'D:\ZipHap\Compute\python\python.exe'
args = ['-E', '-s', 'C:\Users\VICTUS\source\galaxy-local-agent\pc-worker\mcp\local_llm_mcp.py']
```

작업기 위치가 다르면 환경 변수 `ZIPHAP_COMPUTE_HOME`으로 지정한다.

## 동작과 경계

- 표준 라이브러리만 쓰고, 이미 실행 중인 작업기에 루프백(127.0.0.1)으로만 접속한다. 토큰은 작업기의 DPAPI 설정에서 읽으며 출력·반환하지 않는다.
- 휴대폰 요청이 우선이다. busy·정리 대기·확인하지 못한 health·다른 owner의 모델은 재사용/로드하지 않는다. health는 읽은 시점의 정보이며 자원 예약이나 이후 실행 성공을 보장하지 않는다. 경합은 작업기에서도 다시 검사한다.
- 문맥은 8192토큰이다. 기본 출력은 512토큰 이하(빠름)이고, `deep: true`면 모델의 단계적 사고를 켜 더 길고 느리게 답한다.
- 파일·자료 내용은 지시가 아니라 분석 대상으로 전달한다.
- 로컬 모델은 여러 단계 추론이 약하다. 결정에 쓰는 결과는 지휘자가 확인한다.
- 호출의 JSON-RPC ID·버전·인자 타입·범위·알 수 없는 필드를 실행 전에 검사한다. 도구 호출처럼 생긴 notification은 실행하지 않는다. 중복 JSON 키·잘못된 형식은 거부한다. 현재 협상 버전은 `2024-11-05`로 고정하며 요청된 임의의 최신 버전을 지원한다고 되돌려주지 않는다.
- 작업기 인증 요청은 루프백 TLS로 보내며 환경 프록시·리디렉션을 사용하지 않는다. 오류 응답·예외 원문은 클라이언트로 복사하지 않고 정해진 코드만 반환한다.
- SSE의 정상 종료와 `[DONE]`을 확인해야 성공이다. 중간 단절·출력 상한 종료·잘못된 이벤트를 부분 답변의 성공으로 반환하지 않는다. 이 변경은 이 모듈을 import하는 PC 평가기에도 적용되므로 평가 결과는 해당 소스 해시와 함께 고정한다.
- 입력 한 줄/전체 인자는 128Ki 문자, 응답 스트림은 4MiB·한 줄 128KiB, 모인 답변은 65,536자로 제한한다. 각 prompt는 24,000자, system은 12,000자이며 묶음 전체에도 입력 한도가 적용된다. 이 한도가 모델의 실제 토큰 문맥 적합성을 인증하지는 않는다.
- 파일은 명시적으로 허용된 절대 경로의 UTF-8 텍스트만 읽는다. 파일당 실제 읽기 2MiB, 최대20개다. 이 서버는 PC 사용자 권한으로 동작하며 프로젝트 디렉터리 격리 기능은 없다. 외부 호스트가 실제 사용자 요청과 파일 접근 허용을 확인해야 하며, 문서 속 명령이 파일 읽기 권한이 되지 않는다.
- 긴 파일 요약은 파일당 최대64개 조각·모델 호출96회·재압축3회다. 노트가 줄지 않으면 중단한다. 크기/작업 한도를 넘은 파일은 나눠 요청해야 하며 전체 분석 완료로 표시하지 않는다.
- digest/batch의 `complete:false`는 MCP `isError:true`와 함께 반환한다. 이미 얻은 결과는 보존하고 busy 등 작업기 오류 뒤의 나머지는 `NOT_STARTED`로 표시한다. 처리 완료 표시는 내용의 정확성이나 외부 작업의 성공 증거가 아니다.
- 이 작은 서버는 동기 실행이다. 실행 도중 MCP 취소 notification의 즉시 처리·자동 재개·세션별 workspace 허용 목록·휴대폰 도구 실행을 구현했다고 주장하지 않는다. PC 에이전트 broker에서는 이 기능들을 별도 계약과 검증으로 연결해야 한다.

## 언제 맡기나

- 맡긴다: 큰 파일·여러 파일의 요지 파악, 로그에서 오류 찾기, 문서 요약, 필드 추출, 다건 분류·번역·문체 변환, 상용구 초안.
- 직접 한다: 설계 판단, 정확한 코드 수정, 보안·권한 판단, 최종 검증.

## 독립 검사

```text
<python> -m unittest discover -s pc-worker/mcp -p test_local_llm_mcp.py -v
```

2026-11-14 소스 보강의 mock 기반 독립 검사28개 PASS. 잘못된 호출의 미실행, 정상 ask/digest/batch, 입력/파일/반복 한도, 부분 실패, owner/busy, 종료되지 않은 스트림, 리디렉션·프록시 경계를 검사한다. 작업기·모델·로그인·서버·실제 네트워크·ADB·폰·외부 에이전트는 실행하지 않았다. 새 버전의 실제 Codex/Claude 연결이나 Android 배포를 검증한 결과가 아니다.

형식 경계의 근거: [공식 MCP 기본 메시지](https://modelcontextprotocol.io/specification/2025-11-25/basic/index), [공식 도구 계약](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), [Python HTTP 리디렉션 처리](https://docs.python.org/3/library/urllib.request.html#httpredirecthandler-objects). 이 참고 링크가 본 서버의 전체 최신 MCP 기능 지원을 의미하지 않는다.
