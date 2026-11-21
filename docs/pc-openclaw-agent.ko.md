# PC 집합 에이전트

스마트폰 ZipHap.Agent의 **연결 → PC 집합 에이전트 제어**에서 Codex 또는 Claude 구독을 선택한다. PC 작업 시작, 상태 확인, 결과 읽기, 중지를 기존 페어링으로 제어한다. Windows 작업기가 지정 문서를 읽고 헤드리스 OpenClaw 프로세스로 작업을 실행한다. PC의 요청과 지정 문서 내용은 이번 작업에 동의한 경우에만 선택한 모델 서비스로 전송한다.

- **Codex 구독**은 기존 ChatGPT 계정으로 OpenClaw의 전용 Codex OAuth 프로필에 로그인한다. OpenClaw가 네이티브 Codex 런타임을 사용한다. PC 전용 프로필 연결에 기존 계정을 쓰며, 서비스 실행 중에는 다른 계정이나 API 키로 자동 전환하지 않는다. [OpenClaw Codex 설정](https://docs.openclaw.ai/providers/openai/setup)
- **Claude 구독**은 구독 계정에 로그인한 Claude Code를 OpenClaw의 내장 `claude-cli` 런타임으로 호출한다. 런타임이 사용할 Claude Code의 네이티브 로그인 상태를 검사한다. [OpenClaw Claude CLI 설정](https://docs.openclaw.ai/gateway/cli-backends)
- 첫 버전은 PC 관리자가 지정한 UTF-8 텍스트 파일 최대 8개, 총 48,000바이트를 읽어서 분석 답변을 돌려준다. 파일 쓰기, 데스크톱 조작과 장기 작업 재개는 후속 범위다. 한 번에 한 건을 실행하고, 전화 화면을 나갔다 들어오면 작업 ID로 이어서 조회한다. 작업기 메모리의 종료 결과는 15분 동안 조회할 수 있다. OpenClaw 전용 디렉터리의 세션과 인증 상태는 별도로 보관되므로 해당 디렉터리 접근 권한을 작업 계정에 한정한다.
- PC가 실행 중이고 인터넷 연결과 구독 사용량이 남아 있어야 한다. 설치된 서비스의 인증이나 런타임 변경은 PC 쪽에서 관리한다. 휴대폰에는 모델 로그인 토큰을 보관하지 않는다.

## Windows 배치

1. OpenClaw `2026.11.6`과 이 릴리스가 지원하는 Node.js를 작업기 계정 전용 경로에 배치한다. 이 릴리스의 Node 조건은 `>=24.16.0 <25.0.0` 또는 `>=26.1.0`이다. OpenClaw의 진입점은 npm 실행 셸 대신 실제 JS 파일을 선택한다. [OpenClaw 릴리스](https://github.com/openclaw/openclaw/releases/tag/v2026.11.6)
2. 선택할 제공자마다 별도의 전용 OpenClaw 상태 디렉터리를 만든다. 이 디렉터리를 다른 Gateway나 OpenClaw 에이전트와 공유하면 상태 잠금이 겹칠 수 있다. 상태 디렉터리에는 해당 로그인만 설정하고 `.env`를 두지 않는다. [OpenClaw 단건 실행 옵션](https://docs.openclaw.ai/cli/agent)
3. Codex는 작업기 계정의 `OPENCLAW_STATE_DIR`를 Codex 전용 디렉터리로 지정한다. 같은 경로에서 헤드리스로 `openclaw plugins install npm:@openclaw/codex@2026.11.6`을 실행하고 `openclaw models auth login --provider openai --device-code`의 브라우저 인증을 기존 구독 계정으로 완료한다. 기기 인증 코드는 소유자에게만 표시한다. `openclaw models auth list --provider openai`에서 확인한 프로필 ID를 `oauthProfile`에, 구독에 노출되는 모델 ID를 `model`에 입력한다. [OpenClaw Codex 로그인](https://docs.openclaw.ai/providers/openai/setup)
4. Claude는 작업기 사용자 권한으로 `claude.exe`를 설치한다. 기존 구독 계정으로 로그인된 Claude Code의 전용 프로필 폴더를 `cliHome`에 지정한다. 사용하려는 모델의 정확한 ID를 `model`에 입력한다. `state`는 Claude용 OpenClaw 상태 폴더다.
5. PC 작업기의 데이터 폴더에 `pc-openclaw.json`을 둔다. 아래 구성은 경로와 프로필 ID 예시다. `*Sha256`은 각각 해당 파일의 SHA-256을 소문자 64자리로 넣는다. 아직 연결하지 않은 제공자는 `subscriptions`에서 뺄 수 있다. 문서에는 소유자가 서비스 전송을 허용한 자료만 지정한다. 원본 파일과 인증 상태는 로컬 배치에만 둔다.

```json
{
  "node": "C:/ZipHapPc/node/node.exe",
  "nodeSha256": "<SHA-256>",
  "entry": "C:/ZipHapPc/openclaw/openclaw.mjs",
  "entrySha256": "<SHA-256>",
  "workspace": "C:/ZipHapPc/approved-documents",
  "sources": ["회의자료.md"],
  "subscriptions": {
    "codex": {
      "model": "gpt-6-astra",
      "state": "C:/ZipHapPc/state/codex",
      "oauthProfile": "openai:example@example.invalid"
    },
    "claude": {
      "model": "claude-sonnet-5",
      "state": "C:/ZipHapPc/state/claude",
      "cli": "C:/ZipHapPc/claude/claude.exe",
      "cliSha256": "<SHA-256>",
      "cliHome": "C:/ZipHapPc/claude-profile"
    }
  }
}
```

6. 배포본에 PC 런타임 모듈과 경로를 반영한 뒤 PC 작업기를 유휴 상태에서 재시작한다. 앱에서 구독별로 개인정보가 없는 테스트 문서를 한 건 처리하고, 상태 조회와 중지를 확인한다. 연결 화면의 구독 표시는 PC 설정의 존재를 기준으로 한다. 실제 구독 로그인과 모델 응답은 구독별 실기 작업으로 확인한다.

## 장단점

| 장점 | 운영상 감안할 점 |
| --- | --- |
| 기존 Codex와 Claude 구독으로 모델을 선택할 수 있어 새 API 키를 발급할 필요가 없다. | PC 작업도 해당 계정의 사용량을 쓴다. 제한이나 로그인 만료가 발생하면 해당 작업이 멈춘다. 계정에 추가 사용량 결제를 켜 둔 경우 서비스 설정을 따른다. |
| 휴대폰은 페어링으로 제어하고, 지정 문서 처리와 프로세스 생명주기는 PC에서 관리한다. | PC 전원과 네트워크가 필요하고 Windows 런타임과 구독별 로그인 상태를 관리해야 한다. |
| 두 런타임과 모델을 분리해 어느 구독으로 요청이 갔는지 확인할 수 있다. | 두 제공자의 모델, CLI, 인증이 따로 바뀔 수 있어 업데이트마다 구독별 검증이 필요하다. |
| 소유자가 매 요청의 문서 전송을 확인하고 지정 파일의 분석 결과를 휴대폰에서 받는다. | 지정 문서와 요청은 모델 서비스로 전송된다. PC 작업기 재시작 후에는 전화 화면에서 이전 작업을 이어서 조회할 수 없다. |
