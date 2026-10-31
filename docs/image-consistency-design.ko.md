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
