# 배포된 PC 작업기 스냅샷 (2026-11-14)

`D:\ZipHap\Compute\worker`에서 실제로 실행 중인 파일을 그대로 복사한 것이다. 상위 `pc-worker/`의 파일은 아직 배포하지 않은 다음 버전 소스(예: 이미지 진행 `stage` 계층이 들어간 `recovery_engines.py`)이므로 서로 다를 수 있다.

- 제외: 모델·런타임(`runtime*`, `llama-runtime`, `models`), 설정·토큰(`config.json`, `pairing.json`), 인증서·키, 기록 파일, `*.before-*` 백업.
- 실행: `supervisor.py`가 로그인 시 시작되어 `server.py`를 `LOCALAPPDATA=D:\`로 띄우고 종료되면 다시 시작한다.
- `patch_log_chat.py`: 2026-11-14 적용한 기록 보완(날짜, 2MB 교체, 이미지 진행 성공 기록 생략, 대화 성공·실패 기록) 패치. 이미 반영된 `server.py`에는 다시 적용되지 않는다.
- 배포 방법: 작업기 유휴 확인 → 대상 파일 백업 → 교체 → `worker.pid` 프로세스만 종료(supervisor가 재기동) → `/v1/health` 응답 확인.
- Tailscale 주소는 `overlay_endpoint.py`로 탐지한다. 호스트 DNS에서 찾지 못하면 공식 설치 CLI의 자기 IPv4 조회만2초 한도로 실행하고 성공60초/실패5초 캐시한다. `server.py`와 이 모듈은 함께 배포한다. 실제 모델/이미지 작업을 보존하고 유휴·모델 정리 확인 뒤 재시작한다. 원래 페어링 토큰/인증서·VPN·방화벽 설정은 바꾸지 않는다.
