# 배포된 PC 작업기 스냅샷 (2026-11-16)

`D:\ZipHap\Compute\worker`의 공개 소스 스냅샷이다. 실제 설치 상태는 공유 변경 기록과 배포 해시로 확인한다. `recovery_engines.py`와 `image_progress.py`는 상위 `pc-worker/`의 최신 소스와 동기화했다. 다른 상위 파일은 미배포 후보일 수 있으므로 디렉터리 이름만으로 실행 버전을 추정하지 않는다.

- 제외: 모델·런타임(`runtime*`, `llama-runtime`, `models`), 설정·토큰(`config.json`, `pairing.json`), 인증서·키, 기록 파일, `*.before-*` 백업.
- 실행: `supervisor.py`가 로그인 시 시작되어 `server.py`를 `LOCALAPPDATA=D:\`로 띄우고 종료되면 다시 시작한다.
- `patch_log_chat.py`: 2026-11-14 적용한 기록 보완(날짜, 2MB 교체, 이미지 진행 성공 기록 생략, 대화 성공·실패 기록) 패치. 이미 반영된 `server.py`에는 다시 적용되지 않는다.
- 배포 방법: 작업기 유휴 확인 → 대상 파일 백업 → 교체 → `worker.pid` 프로세스만 종료(supervisor가 재기동) → `/v1/health` 응답 확인.
- Tailscale 주소는 `overlay_endpoint.py`로 탐지한다. 호스트 DNS에서 찾지 못하면 공식 설치 CLI의 자기 IPv4 조회만2초 한도로 실행하고 성공60초/실패5초 캐시한다. `server.py`와 이 모듈은 함께 배포한다. 실제 모델/이미지 작업을 보존하고 유휴·모델 정리 확인 뒤 재시작한다. 원래 페어링 토큰/인증서·VPN·방화벽 설정은 바꾸지 않는다.

1089 이미지 보완은 `server.py`, 복구·진행 모듈, `engines.py`, `image_profiles.py`, `native_memory_image.py`, `windows_budget.py`, `resident_budget.py`를 함께 쓴다. 명시적 일시정지 재개에는 앱1089와 새 `explicit` 표지가 필요하다. 레거시 `patch_worker.py`는 원래 기준선의 복구 설치만 수행하며 새 모델/프로필/빠른 디코딩을 설치하지 않는다. 빠른 모델 파일은 별도로 검증해 내려받고 기존 모델을 교체하지 않는다. 정확한 핀·실행 옵션·실패 및 실제 폰 결과는 [이미지 검증 기록](../../docs/image-workflow-repair-2026-11-16.ko.md)을 따른다.
