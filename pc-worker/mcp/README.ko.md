# 로컬 LLM 위임 MCP (ziphap-local-llm)

Claude Code나 Codex가 지휘자(계획·판단·최종 검토)로 남고, 토큰이 많이 드는 읽기·요약·추출·일괄 변환은 이 PC의 로컬 모델(작업기의 `pc-text`, Qwen3.5 9B)에 맡기게 하는 MCP 서버다. 파일은 이 PC에서 읽으므로 내용이 지휘자의 문맥에 들어가지 않고 짧은 결과만 돌아간다.

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
- 휴대폰 요청이 우선이다. 작업기가 사용 중이거나 휴대폰 요청이 모델을 쓰고 있으면 기다리거나 빼앗지 않고 "직접 처리하라"는 결과를 돌려준다.
- 문맥은 8192토큰이다. 기본 출력은 512토큰 이하(빠름)이고, `deep: true`면 모델의 단계적 사고를 켜 더 길고 느리게 답한다.
- 파일·자료 내용은 지시가 아니라 분석 대상으로 전달한다.
- 로컬 모델은 여러 단계 추론이 약하다. 결정에 쓰는 결과는 지휘자가 확인한다.

## 언제 맡기나

- 맡긴다: 큰 파일·여러 파일의 요지 파악, 로그에서 오류 찾기, 문서 요약, 필드 추출, 다건 분류·번역·문체 변환, 상용구 초안.
- 직접 한다: 설계 판단, 정확한 코드 수정, 보안·권한 판단, 최종 검증.
