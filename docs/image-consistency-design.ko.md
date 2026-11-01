# 이미지 시리즈: 기준 사진과 생성 조건 유지

2026-10-31. 휴대폰 연결이 끊긴 동안 구현하는 Image Studio 기능. 설치·실제 모델 품질은 별도 검증한다.

## 벤치마크와 적용

- [Krita AI control layers](https://docs.interstice.cloud/control-layers/): 참고 사진의 역할과 강도를 구분한다. 현재 설치되지 않은 Face/IP-Adapter/ControlNet 기능을 지원한다고 표시하지 않는다.
- [Firefly composition reference](https://helpx.adobe.com/firefly/web/work-with-images/generate-images/match-image-composition-to-reference-image.html): 참고 이미지 선택과 영향 조절을 생성 설명 가까이에 둔다. 구도 참고와 인물 동일성은 구분한다.
- [ComfyUI FLUX2 workflow](https://docs.comfy.org/tutorials/flux/flux-2-dev): 모델·시드·생성 조건 재사용을 모바일 양식으로 단순화한다. 노드 편집을 일반 생성의 전제로 삼지 않는다.
- [InvokeAI recall](https://invoke.ai/development/guides/recall-api/): 이전 조건을 명시적으로 불러온다. 결과를 기준 사진으로 자동 교체하지 않는다.

## 구현 계약

시리즈 이름, 유지할 특징, 변경할 장면, 제외 표현, 고정 시드, 강도, 단계 수와 PC 모델 선택을 암호화된 Studio 전용 프리셋에 저장한다. 저장/불러오기는 사용자가 명시적으로 실행한다. 채팅과 기존 대기 요청에 숨은 조건을 주입하지 않는다. 기준 사진은 크기·해상도를 검증하여 앱 내부에 복사하고 SHA-256으로 확인한다. 원래 문서 접근권이 만료되어도 복사본을 쓴다. 프리셋 삭제/교체 때문에 사용자 원본이나 별도 생성 산출물을 삭제하지 않는다.

유지할 특징과 변경할 장면은 별도 입력이고 실행 시 생성 설명으로 합친다. 유지 특성은 모델에 주는 요청이며 동일성 검증 결과가 아니다. 기준/결과 비교를 제공하고 실제 결과 모델을 표시한다. 기존 요청별 취소·PC 연결 승인·모델 핀·참고 이미지 전처리 계약을 보존한다.

## 남은 검증과 범위

기준 사진 입력은 기존 img2img 파이프라인에 연결된다. SDXL의 img2img와 프롬프트/시드 재사용만으로 얼굴·제품 동일성을 보장하지 않는다. PC 참고 편집은 서버의 실제 지원 여부 검사에서 거부될 수 있다. Qwen-Image-2512는 새 이미지 모델이며 별도 Edit-2511 모델 다운로드와 실제 편집 연결·실측은 별도 작업이다. 다중 참고 이미지·자동 얼굴 유사도 검사·마스크/영역 편집은 구현 완료로 표시하지 않는다.

휴대폰 재연결 후 프리셋 재시작 복원, 기준 사진 접근권 만료 후 사용, 기준/결과 비교, 생성 중 취소, PC 연결 해제, SDXL/Qwen 실제 결과 및 동일성 육안 비교를 수행한다.

### 2026-10-31 — 실기 후속: 실제 편집 모델과 진행 정보
실제1023에서는 기준 프리셋 복원·원본 제거 후 복사본 유지·변조 거부2개, 기존 UI5개, 설치된 휴대폰 SDXL 무결성1개가 모두 PASS7.766초였다. 실제 첫 화면에서 PC 상세 설정을 접고 시리즈를 바로 찾을 수 있음을 확인했다. 사적인 기존 생성 산출물은 재사용·공유하거나 Git에 넣지 않는다.

PC Qwen의 참고 사진 생성에는 전용 Qwen-Image-Edit-2511 모델을 명시적으로 선택하는 연결을 추가한다. 기본 선택은 SDXL/Qwen 두 개이고, Qwen의 새 이미지는2512/참고 편집은Edit2511로 구분해 preflight·요청·결과·저장 메타데이터에 실제 핀을 보존한다. 참조 편집 엔진은 설치된 weights의 SHA와 전용 vision projector/VAE/encoder를 확인한다. 모델 지시를 동일성 보증으로 표시하지 않는다. UI의 계산 위치 알림은 채팅 상태와 분리해 PC 선택 화면에 과거 채팅의 기기 실행 문구가 섞이지 않게 한다.

PC 실제 SDXL50단계 실험 중 단계0 현상을 확인했고, 설치 runtime37bc88b의 공식 API/source를 확인했다. OpenAI 동기 생성은 SDAPI 진행 영역을 채우지 않으므로 두 API를 섞어 읽으면 실제 샘플링 중에도0으로 보일 수 있다. 다음 PC worker는 native 비동기 작업 ID에 묶인 미리보기의 실제 pass/step/total만 읽는다. 종료·모델·취소/cleanup 소유권을 유지하고 숫자를 시간으로 만들어내지 않는다. 이 연결과 편집 프로필의 격리 계약/실제 자식 종료 검사25개는 통과했으나 아직 실행 중인 기존 SDXL 작업을 교체하지 않았고 새 편집 모델 품질을 실제로 검증한 것은 아니다. [설치 runtime API](https://github.com/leejet/stable-diffusion.cpp/blob/37bc88b/examples/server/api.md), [Qwen Edit 구성](https://github.com/leejet/stable-diffusion.cpp/blob/37bc88b/docs/qwen_image_edit.md).


### 1026: 이름별 시리즈 보관과 결과 접근
최대12개 이름별 조건을 암호화 원자 파일로 보관한다. 기존 단일 recipe는 읽기 호환하며 명시 저장 때 새 형식으로 변환한다. 같은 이름은 갱신하고, 한도가 차면 기존 항목을 자동 버리지 않는다. 선택 불러오기와 확인 후 조건 삭제를 제공하며 삭제는 최신 내용이 선택 snapshot과 같을 때만 허용한다. 사진 파일/생성물은 지우지 않는다. 여러 파일/이미지는 중복 없이 최대16개 표시하고 파일 열기·공유·위치 선택 저장을 분리한다. 저장 뒤 실제 바이트 해시 재조회는 유지한다.

1026 호스트 core614/app550=1164 PASS, lint0errors132warnings,601 동결 입력 목록·SHA 일치, 서명 APK를 보존했다. 실제 기기 migration/picker/multi-output4 신규 회귀는 설치 후 실행한다. 소스/JVM 통과를 모델의 사진 동일성 성공으로 표현하지 않는다. PC Vulkan 공개 참고 편집은 실제12/50 이후 GPU workspace 여유 부족으로 실패했고 cleanup은 확인했다. 실제 PNG/동일성/폰 전달 합격이 아니다. 임시 진단 실행기는 유휴 확인 후 표준 worker로 복원했으며 pairing은 유지했다.
