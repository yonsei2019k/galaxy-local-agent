# PC 이미지 복구

사용자가 요청한 실패 이전 계산 재사용 기능이다. 현재 배포 worker는 사용자 전용 외부 경로에 있다. 이 디렉터리는 새 복구 계층과 기존 파일 두 개에 대한 검증된 변경 도구만 보관한다. 실행 중 worker나 모델을 덮어쓰지 않는다. 모델/런타임/개인 입력/사진/토큰/복구 파일은 Git에 넣지 않는다.

* `ram_only` 요청은 기존 동작이다. 새 앱이 `encrypted_checkpoint`로 요청하고 원래 compute owner를 제시한 경우만 PC 저장을 허용한다.
* SDXL/Qwen의 현재 Euler sampler에 한정한다. 첫 단계, 매 5단계, 마지막 단계의 FP32 latent와 원래 sigma schedule을 Windows 사용자 DPAPI로 암호화한다. 미리보기 이미지는 복구 근거가 아니다.
* 원래 request hash/model weights/runtime binary hashes/sigma/shape를 확인한다. 모델·참고 입력·해상도·단계·시드를 바꿔 이어붙이지 않는다. Euler 이외 sampler와 동영상 실행기는 지원하지 않는다.
* PC의 원래 입력·참고 사진·미수신 PNG도 DPAPI로 암호화한다. 최대 4개 복구 폴더이며 정상 기록은 생성 시각부터 24시간 후 만료·정리한다. 손상 기록은 복구를 거부하며 슬롯을 임의로 덮어쓰지 않는다.
* 네트워크 단절은 일시 정지 후 원래 ID로 이어받는다. 사용자의 명시 중지는 해당 복구 상태를 삭제한다. 서로 다른 owner의 조회·재개·삭제는 거부한다. 재연결/서버 재시작은 완료된 결과를 다시 생성하지 않는다.
* 휴대폰은 Keystore 암호화 ticket에 ID/owner/PC 권한 fingerprint/원래 UI 조건만 저장한다. PC 재등록/권한 변경은 예전 ticket 재사용을 거부한다. 참고 사진은 원래 요청의 정규화 픽셀 hash와 대조한다.
* PNG를 받은 시점에 ACK하지 않는다. 휴대폰 파일과 메타데이터를 저장·재확인한 뒤 ACK한다. ACK가 실패하면 원래 결과를 유지한다. 이미 휴대폰에 저장된 동일 파일이면 새 계산 없이 PC 정리를 재시도한다.

네이티브 기반: stable-diffusion.cpp `37bc88b5e97a213821949732ea66c58ae8ffc784`, GGML `7b2b435da087614be772ee6f5ea80e68eca81de6`. `native/prepare_native.py`는 이 소스의 Euler 함수만 수정한다. 새 shared runtime은 동일 GGML revision의 CUDA/Vulkan backend와 함께 별도 경로에 설치한다. GPU/RAM 부족 자체가 해결되는 것은 아니며, 저장 이전 실패를 저장 이후 재개라고 표시하지 않는다.

검증 경계: 네이티브 저장·복원/동일 연산/변조 거부 테스트와 제어 계층 테스트는 실제 모델 생성·의미 품질·휴대폰 설치 검증을 대체하지 않는다. 실제 상태는 공유 change log에 따로 기록한다.

### 이미지 진행 상태 계약 (2026-11-01 소스 준비, 미배포)
`phase`는 기존 값(queued/waiting_resources/loading/sampling/decoding/terminal)을 보존한다. 별도 `stage`는 실제 실행 지점에서만 기록한다: admitting, waiting_compute(연산 잠금 획득 실패), releasing_model, verifying_models(모델 SHA 검사), preparing_runtime(실행/복구 환경 확인), loading_model, waiting_memory, waiting_gpu. 샘플링/완료 등 현재 phase와 맞지 않는 오래된 stage는 응답에서 제외한다. 다른 사용자의 요청/내용/ID는 상태 응답에 추가하지 않는다.

`patch_image_progress.transformed(workerRoot)`는 기준선과 맞는 원본만 좁게 변환하며 코드/재시작을 적용하지 않는다. `image_progress.py`, 변환된 engines.py/owned_engines.py/recovery_engines.py를 함께 준비해야 한다. 실제 배포는 현재 이미지 생성·결과 수신·정리 및 다른 연산이 끝난 것을 확인하고 파일별 기준선을 재대조한 뒤 수행한다. 앱도 새 PcImageProgressPolicy가 포함된 릴리스가 필요하다. 구형 작업기의 queued에 대해서 새 앱은 'PC 실행 상태 확인 중 · 준비 또는 대기'로 표시하며 다른 작업 존재를 추측하지 않는다. 현재 실행 중 작업기에는 적용하지 않았다.
