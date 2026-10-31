# 통화 녹음 후속 작업용 한국어 ASR — 2026-10-31 확인

이 폴더는 조사와 PC에서 실행하는 오프라인 평가기다. 앱 소스·기기 설정·모델을 변경하지 않았다. 모델 파일을 다운로드하거나 Gradle·ADB를 실행하지 않았다. Node 표준 라이브러리 평가기 검사는15/15 통과했으며, 실제 음성을 인식한 결과가 아니다.

## 먼저 비교할 경로

**자동 통화 후속 처리의 백그라운드 기준선은 캐시된 Qwen3-ASR이며, ML Kit Advanced 한국어는 앱 화면을 연 비교 후보로 분리한다.** 공식 ML Kit 문서가 S26/Exynos2600과 ko-KR를 지원 목록에 포함한다. 다만 해당 폰의 `checkStatus()==AVAILABLE`, 서비스 준비, 실제 오프라인 처리·정확도는 별도 확인해야 한다. 사용자가 설치한 삼성 녹음 앱의 전사 기능을 외부 앱에서 그대로 호출한다는 의미가 아니다. [ML Kit 공식 API](https://developers.google.com/ml-kit/genai/speech-recognition/android)

ML Kit GenAI 추론은 앱이 최상위 foreground일 때만 허용되며 foreground service도 예외가 아니다. 백그라운드 호출은 `BACKGROUND_USE_BLOCKED` 대상이다. 준비된 모델은 백그라운드 실행 자격을 뜻하지 않는다. 자동 작업 때문에 전역 ASR 선택을 바꾸거나 화면을 강제로 띄우지 않으며, Qwen의 한국어 정확도·속도 우위도 이 제약만으로 주장하지 않는다. [공식 백그라운드 제한](https://developers.google.com/ml-kit/genai#background_usage)

그다음 필요할 때만 Whisper large-v3-turbo Q8 CPU 후보를 비교한다. Q5는 저장 공간·속도와 한국어 핵심어 손실을 함께 평가한 뒤 고른다. 지금 자료로 어느 엔진이 S26의 가장 정확한 기본값이라고 단정할 수 없다.

| 후보 | 현재 앱 통합 | 디스크 근거 | 정확도·시간·전력 판정 |
|---|---|---|---|
| ML Kit Advanced `ko-KR` | `SystemAsrEngine`, alpha2, fromFile/FinalText+Completed 검사. 최상위 foreground 전용 | 시스템 관리. 앱이 별도 가중치 크기를 안다고 표시하면 안 됨 | 공식 지원 목록은 확인, 이 폰의 사용 가능 상태·RTF·한국어 점수 미측정. 백그라운드 자동 분석 기준선에서 제외. 앱 PSS는 별도 AICore 메모리를 제외함 |
| Qwen3-ASR1.7B Q8+bf16 encoder | 현재 `AsrCatalog.DEFAULT_MODEL`, CPU2threads/context2048 | 고정2파일 합계2,806,808,928B | 기존 구현 기준선. 한국어 통화 정확도/RTF/배터리 우위 미측정 |
| Qwen3-ASR0.6B Q8+bf16 encoder | 선택 가능, CPU4threads | 고정2파일 합계1,183,324,768B | 가벼운 비교 후보. 크기만으로 빠르거나 충분히 정확하다고 단정하지 않음 |
| Whisper large-v3-turbo / whisper.cpp | 신규 JNI 통합 필요 | 공식 변환 Q8:874,188,075B / Q5:574,041,195B / F16:1,624,555,275B | 이 폰의 peak RAM·RTF·열·CER는 모두 미측정. 모델 크기와 실제 peak RAM은 다름 |
| SenseVoiceSmall / sherpa-onnx | 이 Agent에는 미통합. 별도 번역 앱의 통합과 혼동하지 않음 | 변환별 크기·SHA는 채택할 exact artifact에서 확인해야 함 | Android/Kotlin·한국어 경로는 존재. 새 런타임과 가중치 라이선스 확인 및 통화 평가 필요 |

Qwen의 공식 모델·코드는 Apache2.0이며 한국어를 지원한다. 현재 앱의 두 GGUF 변환본 커밋/해시는 `AsrCatalog.kt`에 이미 고정돼 있다. 공식 다국어 결과는 이 폰의 한국어 통화 검증을 대신하지 않는다. [Qwen 공식 저장소](https://github.com/QwenLM/Qwen3-ASR), [모델 카드](https://huggingface.co/Qwen/Qwen3-ASR-1.7B)

Whisper turbo의 모델 카드는 MIT이며 다국어 ASR 후보다. 모델 카드에도 무음에서 말하지 않은 문장, 반복, 언어별 성능 차이가 한계로 기재돼 있다. 이 제한 때문에 평균 CER 외에 무음·부정·정정의 별도 검사가 필요하다. [원본 모델 카드](https://huggingface.co/openai/whisper-large-v3-turbo)

whisper.cpp는 MIT, Android 예제와 CPU/ARM 경로, VAD 지원이 있다. 현재 source 조사 커밋은 `482279b8a18855ba741d7b88c30fcb3874691f59`다. 이미 앱에 여러 ggml JNI가 있으므로 신규 빌드는 기존 target/ABI와 심볼 충돌을 검증해야 한다. 예제의 tiny/base 권고는 통화 정확도 벤치마크가 아니다. PC GPU 수치를 Exynos 속도로 바꾸어 쓰지 않는다. [공식 구현](https://github.com/ggml-org/whisper.cpp), [Android 예제](https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/examples/whisper.android/README.md)

Whisper 변환 고정값은 `public-model-metadata.json`에 있다. 공식 저장소 `ggerganov/whisper.cpp`, commit `5359861c739e955e79d9a303bcbc70fb988958b1`; Q8 파일 SHA256 `317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1`, Q5 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`. 메타데이터만 확인했으며 실제 파일은 받지 않았다. [공식 변환 파일 목록](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1)

SenseVoice는 **코드 라이선스와 가중치 라이선스를 분리**해야 한다. 코드 저장소는 현재 MIT이지만 모델 카드는 `other/model-license`로 FunASR Model Open Source License Agreement1.1을 연결한다. 이를 Apache/MIT 가중치로 표시하지 않는다. [sherpa Android·한국어 지원](https://k2-fsa.github.io/sherpa/onnx/sense-voice/), [모델 카드](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [모델 라이선스 원문](https://raw.githubusercontent.com/modelscope/FunASR/main/MODEL_LICENSE), [코드 MIT](https://raw.githubusercontent.com/QwenAudio/SenseVoice/main/LICENSE)

일반 Distil-Whisper `distil-large-v3`는 영어용이라 한국어 기본 후보에서 제외한다. 한국어 fine-tune `ghost613/whisper-large-v3-turbo-korean`은 게시자가 Zeroth 테스트 CER2.06%를 보고했지만 한국어 전화/업무 용어 결과가 아니며, 현재 모델 카드의 라이선스 메타데이터도 없다. 기본 채택 근거가 부족하다. 저장소의 `training_args.bin` 같은 pickle 훈련 파일은 추론에 필요하지 않으며 실행하지 않는다. 어휘를 줄인 alphaedge 모델은 완전 새 ASR 개선이 아니고 tokenizer/변환 호환성을 별도로 확인해야 한다. [영어 모델 카드](https://huggingface.co/distil-whisper/distil-large-v3), [한국어 fine-tune 원저자 카드](https://huggingface.co/ghost613/whisper-large-v3-turbo-korean), [어휘 축소 모델 카드](https://huggingface.co/alphaedge-ai/whisper-large-v3-turbo-kor-32768)

MLX의 공식 플랫폼 설명은 Apple silicon과 Linux CPU/CUDA이며, 이 자료에서 Android/Exynos 런타임을 확인하지 못했다. Android 구현 대체재로 제안하지 않는다. [MLX 공식 저장소](https://github.com/ml-explore/mlx)

## 현재 앱의 실제 정확도 공백

- `LocalAudioTranscriber.transcribe`:15초 고정 조각, 순차 전사, note 저장·재조회. VAD, 경계 overlap, 단어별 시각·화자 분리, 실제 confidence가 없다.
- `AudioChunkDecoder`:16kHz mono, 선형 보간 resample, stereo 평균. 무음 비율·clipping·채널 상쇄·겹친 발화 진단이 없다. 입력100MiB/2시간 제한이다. 새 폴더 감시의 파일 제한도 이 조건에 맞춰야 한다.
- `QwenAsrEngine`/JNI:512출력 한도에서 EOG가 없으면 실패한다. 한도에 잘린 전사를 성공으로 저장하는 구현은 아니다. 엔진은 ASR 시간·토큰 수를 제공하지만 정확도 confidence는 제공하지 않는다.
- `SystemAsrEngine`:정적 fromFile, FinalText만 저장하고 Completed를 요구한다. 공식 API의 word timing은 streaming에만 지원하므로 이 정적 입력에 `enableWordTiming=true`를 붙이면 안 된다. 현재15초 범위를 단어 시각으로 표현해서도 안 된다.
- `ScopedRecordingTool`:저장/manifest 재조회를 VERIFIED로 표시하면서 정확도·실제 발화 완전성·화자·원음 SHA 미확인을 별도 false로 표시한다. 새 `StableCallRecording.sha256`와 연결해 원음 해시 근거를 더할 수 있다. 이 사실이 ASR 의미 정확도를 자동 검증하지는 않는다.
- `AsrDeviceValidation.installAndValidateQwen`은 다운로드를 수행한다. 새 cached-only 비교에 그대로 실행하면 안 된다. 준비되지 않은 모델은 download 대신 SKIPPED_NOT_INSTALLED로 보고한다.
- 한국어 TTS3문장은 smoke다. 실제 통화의 좁은 대역, 겹침, 억양, 날짜 정정, 간호 행정 용어의 일반 정확도 증거로 사용할 수 없다. 현재 확인한 docs에는 새 ASR의 S26 한국어 비교 측정이 완료됐다는 근거가 없다.

## 녹음 수집의 현실적인 경로

삼성 기본 전화 앱의 자동 녹음과 사용자가 선택한 녹음 폴더의 안정된 새 파일을 연결한다. 삼성 안내는 자동녹음 선택과 `Recordings/Call` 저장 위치를 설명하지만 모델·앱·소프트웨어에 따라 경로가 달라질 수 있다고 명시한다. 경로를 고정해 권한을 우회하지 말고 실제 선택한 SAF grant를 사용한다. [삼성 자동 녹음 안내](https://www.samsungsvc.co.kr/solution/26041), [삼성 파일 위치 안내](https://www.samsungsvc.co.kr/solution/38418)

Android 문서에서 통화 양측 오디오 capture는 privileged preinstalled 권한과 구분된다. 일반 앱에 `RECORD_AUDIO`를 허용했다고 통화 downlink가 생기지는 않는다. 새 전화 통화를 직접 가로채는 방식보다 기본 전화 앱이 만든 사용자 소유 녹음을 처리한다. [Android 입력 공유 규칙](https://developer.android.com/media/platform/sharing-audio-input)

`callEndedAtIso`의 출처도 중요하다. 새 통화 종료 host event의 시각과 오래된 파일의 modifiedAt은 같지 않다. 과거 녹음의 ‘내일’을 오늘 기준으로 확정하지 않는다. 정확한 통화 시각 근거가 없으면 상대 날짜를 UNKNOWN/review로 남긴다. 연락처·발화자도 파일명이나 전화번호 문자열만으로 추정하지 않는다.

## 실행 순서 제안

1. 현재 설치 상태만 조회하고 준비된 ML Kit/Qwen1.7B/Qwen0.6B 후보를 같은 비개인 녹음으로 각각1회 예열+3회 비교한다. ASR→해제→분석 LLM 순서를 유지한다. ML Kit는 client close 이후 시스템 서비스 메모리까지 즉시 해제된다고 보장하지 않는다.
2. 정확한 raw transcript와 audio/source/chunk 해시를 보존한다. CER/WER, 중요 항목 P/R, 부정·취소·정정/근거 누락을 함께 본다. 합성 음성만으로 기본 엔진을 고르지 않는다.
3. 개선은 먼저 segmentation/resample 진단의 비교 실험으로 한다. VAD는 말 가장자리를 없앨 수 있고 text-only overlap 삭제는 반복 강조·부정을 지울 수 있으므로 원문을 자동 수정하지 않는다.
4. 기존 후보가 요구 정확도/지연을 충족하지 못할 때만 별도 whisper.cpp 실험 빌드와 고정 가중치를 검토한다. 무료·로컬 경로는 가능하지만 휴대폰에서 빠르다는 뜻은 아니다.
5. 간호 행정 glossary는 사용자가 승인한 표기/약어만 버전별로 적용한다. 지금 ASR 엔진 API에 glossary 입력이 없으므로 설치만으로 인식률이 오른다고 주장하지 않는다. 분석 단계의 별도 제안·교정과 raw ASR을 구분한다.
