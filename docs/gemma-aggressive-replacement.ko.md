# Gemma 4 HauhauCS Aggressive 교체 — 2026-10-30

Galaxy의 대화용 Gemma E2B/E4B를 아래 GGUF로 교체 설치했다. 기본 대화 선택은 E2B다.

| 모델 | 파일 | 바이트 | 배포 커밋 |
| --- | --- | ---: | --- |
| [E2B](https://huggingface.co/HauhauCS/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive) | Gemma-4-E2B-Uncensored-HauhauCS-Aggressive-IQ3_M.gguf | 3,134,964,672 | da8593c3e407afcd3e7da94ff2d69d77e2a28a48 |
| [E4B](https://huggingface.co/HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive) | Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf | 5,335,285,728 | 45b6a334b4bcd1d7f37179df58b3b1d66a184e5d |

SHA-256:

- E2B: `0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b`
- E4B: `d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96`

PC와 휴대폰 양쪽에서 파일 크기와 전체 SHA-256을 확인했다. 모델은 APK/Git에 포함하지 않는다.

기존 고정 버전 llama.cpp CPU 엔진에 `gemma4` 아키텍처를 허용했다. GGUF에 내장된 Jinja 대화 형식, 실제 토크나이저, system/user/assistant 역할과 생성 중지 경로를 사용한다. 다른 아키텍처와 음성·사진 GGUF 입력은 계속 거부한다. 규칙/도구 권한 검증은 그대로 서버 없이 기기에서 실행한다.

기존 LiteRT E2B는 `gemma-e2b-media`로 보존해 음성·사진 기능에 사용한다. 자동 대화 라우팅에서는 이 전용 모델을 제외한다. 모델 교체 시 이전 E4B 가중치도 자동 삭제하지 않는다. 교체 전 모델 목록은 앱 외부 파일의 `model-replacement-backup/`에 보관한다. 한 번에 모델 하나만 메모리에 적재한다.

## 검증

- Core 273/273, 앱 단위 테스트 44/44, release lint, release/test APK 빌드와 기존 키 서명 검증 통과.
- 기존 application ID와 서명으로 개발 APK version1004를 업데이트 설치했다. 새 Android 권한은 추가하지 않았고 기존 scoped grant 4개를 새 APK 해시로 이관했다.
- APK SHA-256: `f7630916bccd107f201e7f66d2888c79a0b6fc0c2bf0d08b9ffb6c6adac525f7`.
- 각 모델에서 실제 로딩/생성, system+대화 이력 입력, 실제 토큰 수와 prefill 수 일치, 생성 중지 및 호출 코루틴 유지 검증 통과. 가상 독서 일정 질문에 둘 다 오후 3시라고 한국어로 답했다.
- 마지막 별도 기기 테스트에서 두 새 해시, GGUF 실행 형식, 기본 E2B 선택을 확인한 뒤 앱을 열었다.

짧은 CPU/4K/생각 끄기/출력 상한48토큰 측정:

| 모델 | 로딩 ms | 첫 표시 ms | decode tokens/s | 최대 PSS KiB |
| --- | ---: | ---: | ---: | ---: |
| E2B IQ3_M | 3,038 | 4,821 | 4.38 | 3,592,724 |
| E4B Q4_K_M | 13,005 | 2,312 | 6.69 | 6,330,543 |

각각 한 번의 짧은 측정이다. 종전 LiteRT 모델의 속도/10분 내구성 결과는 새 가중치의 검증 근거로 재사용하지 않는다. 새 모델의 10분 내구성, 장문 성능, 종합 답변 품질은 아직 검증하지 않았다. E4B 자동 품질 라우팅은 새 파일에 일치하는 내구성 증거를 계속 요구하며, 직접 선택은 가능하다. 비공개 기기 보고서와 원본 백업은 저장소 밖 개발 도구 폴더에 둔다.

## 후속 음성·사진 작업 상태

위 검증 수치와 LiteRT 보존 설명은 최초 대화 모델 교체 시점의 기록이다. 후속 요청으로 새 두 모델의 mmproj를 설치·검증했고 두 모델 모두 합성 빨간 원 사진 인식에 성공했다. Gemma 직접 한국어 전사는 의미 일치 검사에 실패했다. 현재 소스는 전용 Qwen3-ASR 및 선택형 기기 내 음성 AI로 전사하고 선택한 Gemma로 내용을 분석하도록 수정·검사 중이며, **새 ASR의 휴대폰 설치·검증과 구형 Gemma 전부 삭제는 아직 완료되지 않았다.** [정확한 범위와 검증 기준](local-asr-and-galaxy-ai.ko.md)을 참조한다. 최초1004 APK의 결과를 이후 복합 APK의 검증으로 재사용하지 않는다.
