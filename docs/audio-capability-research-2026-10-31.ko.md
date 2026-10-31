# 녹음 전사·분석 역량: 실제 구현 감사와 추가 후보

조사일2026-10-31 KST. 이 문서는 현재 소스 감사와 공식 자료 조사다. 새 모델·패키지를 설치하거나 개인 녹음을 서버에 보내지 않았으며, 새 실제폰 성공을 뜻하지 않는다.

## 우선 판단

첫 개선은 새 음성 모델을 무조건 추가하는 것보다 **이미 있는 전사 런타임을 실제 도구 호출과 전체 전사문 분석에 연결하는 일**이다. 기존 `list_audio_recordings`는 파일 메타데이터를, `search_audio_transcripts`는 저장된 전사문 중 최대3개·각1000자 발췌만 반환한다. 이것으로 모든 녹음의 내용을 분석했다고 말할 수 없다. 한 시간 전사가15초 단위로 저장됐다면 최대240조각인데, 기존 발췌 조회3개는 그중 일부다.

준비한 `transcribe_recording`은 실제 조회한 MediaStore 항목의 요청별 불투명 ID를 받아 기존 폰 ASR로 전사한다. 전사 조각·구간·SHA와 순서 있는 페이지 manifest를 저장하고 재조회한다. 이후 전체 텍스트의 분석은 별도 `analyze_audio_transcript` 경로가 맡는다. 이 연결 개선과 ASR 자체의 인식 정확도 향상은 구분한다.

## 현재 소스에서 확인한 실행 경로

- 자료 분석 화면에서 시스템 파일 선택 → `ChatRunCoordinator.transcribe` → `LocalAudioTranscriber` → `AudioChunkDecoder` → 선택된 ASR 엔진 → 암호화 Room의 `audio-transcripts` 메모.
- `ModelManager.asrModel()`은 선택된 Qwen3-ASR0.6B/1.7B와 음성 인코더의 설치·검증 상태를 요구한다. 대체 선택은 지원 확인된 ML Kit 기기 내 음성 인식이다. 일반 전사는 새 가중치를 자동 다운로드하지 않는다.
- Qwen3-ASR는 별도 native `ziphap_asr`를 사용한다.0.6B는 기본 CPU4스레드,1.7B는2스레드, 문맥2048·조각 출력 최대512토큰이다. 설정 언어는 현재 `ko/en/auto`다. 모델 제공자의 더 많은 언어 지원이 앱 설정에서 모두 노출된 것은 아니다.
- `LocalInferenceEngine`은 이전 엔진을 해제한 뒤 ASR를 선택한다. `AutomaticComputeEngine`은 audioEnabled를 PC 텍스트 추론 대상에서 제외한다. 따라서 현재 선택 녹음 전사는 폰 실행 경로다. Gemma의 일반 텍스트 요약을 음성 인식이라고 계산하지 않는다. Gemma 미디어 엔진의 audio 메서드는 존재하지만 현재 ASR 선택기는 전용 Qwen/ML Kit 경로를 사용한다.
- 디코더는100MiB·최대2시간 입력을 임시 private 파일로 읽고,16kHz·mono·PCM16 WAV로 바꾸며 전사기에는15초 조각을 전달한다. WAV는 PCM16/float32·1–2채널을 지원하고 압축 형식은 기기의 MediaExtractor/MediaCodec 지원에 따른다. 입력·조각 임시 파일은 정상 종료·실패·취소 때 정리한다.
- 조각당600초 상한과10000자 출력 상한이 있다. ML Kit 엔진 내부에는180초 상한과 완료 응답 확인이 있다. 취소는 ASR 취소로 전달되며 전사 런타임을 해제한다.
- 현재 시각 정보는 **디코더가 나눈15초 창의 시작·종료**다. 발화·단어 정렬 시각, 화자 분리와 실제 인물 식별은 구현돼 있지 않다. Qwen native 반환에는 출력 상한/EOG 종료 사유가 노출되지 않아 모든 창을 처리했다는 사실만으로 모든 음성이 빠짐없이 인식됐다고 단정할 수 없다.

ML Kit 공식 문서2026-10-28 갱신본은 GalaxyS26/Exynos2600의 Advanced 지원을 명시하지만 실제 기기 준비 상태는 checkStatus로 확인해야 한다. 단어 시각은 streaming 입력 전용이다. 현재 사용하는 fromFile의 ONE_SHOT에 해당 옵션만 켜면 예외가 난다. 추가 모델을 최소화하는 후속 후보는 기존 ML Kit의 streaming PFD와 실제 속도로 흘리는 PCM·단어 시각을 연결하는 방법이다. 현재 앱에 이 기능이 있다고 계산하지 않는다.

소스 위치: `audio/LocalAudioTranscriber.kt`, `audio/AudioChunkDecoder.kt`, `inference/QwenAsrEngine.kt`, `inference/SystemAsrEngine.kt`, `inference/LocalInferenceEngine.kt`, `inference/AutomaticComputeEngine.kt`, `models/AsrCatalog.kt`, `models/ModelManager.kt`, `imports/PhoneRecords.kt`, `tools/ToolExecutor.kt`.

## 공식 오픈소스 후보 비교

| 후보 | 가능한 일과 배치 위치 | 코드·모델·준비 조건 | 이번 선택 |
|---|---|---|---|
| 기존 Qwen3-ASR + 실제 도구 연결 | 폰에 이미 연결된 ASR로 선택 녹음 전사, 전체 저장 텍스트를 근거로 분석 | 공식1.7B 카드 Apache2.0. 현재 앱은 고정된 ggml 변환 가중치+인코더·해시를 관리한다. 새 다운로드를 할 필요가 없는 경우부터 사용 | **우선 적용**. 설치·인식 정확도 실측은 실제폰 결과로 별도 판정 |
| whisper.cpp | Android C/C++·JNI, CPU/ARM과 VAD, 분절·시각 정보를 제어하는 후보 | 코드 MIT, 원본 Whisper 코드·가중치 MIT. 별도 native 빌드와 검증된 모델이 필요하며 기존 ggml과의 ABI를 검토해야 한다. 단어 시각·tinydiarize는 공식 문서상 실험 기능 | 추가 엔진 비교 후보. 현재 Qwen보다 낫다고 검증하지 않았으므로 자동 교체하지 않음 |
| sherpa-onnx | Android Java/Kotlin 예제와 실제 offline speaker diarization APK·소스 제공. ASR/VAD/화자 구간 등 모듈 구성 가능 | 코드 Apache2.0. ONNX Runtime 및 선택 모델이 필요하다. 모델의 권리는 코드와 별개다. 공식 문서는 reverb-diarization-v1을 비상업용으로 명시 | 폰에서 화자 구간이 실제 필요한 경우 후속 후보. 작은 모델이라는 이유로 업무용 무료라고 일괄 설치하지 않음 |
| faster-whisper | 선택형 PC에서 CPU INT8/NVIDIA 처리, VAD와 선택 word_timestamps. 파일뿐 아니라 메모리 파형·file-like 입력 가능 | 코드 MIT, Whisper 원본 가중치 MIT. Python≥3.9/CTranslate2/PyAV. 최신 GPU 환경은 CUDA12·cuDNN9 조건을 확인해야 한다. 모델 이름 로드는 자동 다운로드 가능하므로 고정 로컬 경로를 사용 | 긴 녹음의 선택형 PC 보조 후보. 현재 폰↔PC ASR API는 연결돼 있지 않으며 속도 향상은 해당 PC에서 비교해야 함 |
| WhisperX | PC에서 ASR 후 언어별 forced alignment로 단어 시각 정렬, 선택 pyannote 화자 구간 연결 | 현재 코드 BSD2-Clause. Python3.10–<3.14·PyTorch·faster-whisper·정렬 모델 등 의존성이 있다. 한국어 기본 정렬 항목은 kresnik/wav2vec2-large-xlsr-korean이며 별도 모델 권리·정확도 검토가 필요 | 정확한 자막 시각이나 다중 화자가 필요할 때 후속. 현재 앱에 설치돼 있다고 보고하지 않음 |
| pyannote community-1 | PC에서 화자별 구간과 speaker 번호를 계산. 로컬·오프라인 실행과 메모리 입력 가능 | pyannote.audio 코드 MIT, community-1 가중치 CCBY4.0. 가중치 획득에 HF 조건 동의·토큰 필요. 옵션 telemetry는 `PYANNOTE_METRICS_ENABLED=0`로 끌 수 있다. Precision2 API는 별도 클라우드 실행 | 익명 화자 구간이 필요할 때 선택. 번호를 실제 사람의 신원으로 바꾸지 않음. Google 동의로 HF 동의가 끝난 것은 아님 |
| Qwen3-ForcedAligner0.6B | 기존 ASR와 같은 계열의 별도 forced alignment 후보. 공식 카드에서 한국어 포함11개 언어·최대5분 음성 단위 정렬을 설명 | 별도 가중치·추론 구현이 필요하다. 현재 앱의 Qwen ASR native API에 정렬 엔진이 연결돼 있지 않다 | 추가 가중치를 받기 전 PC/폰 구현 가능성과 선택 데이터 정확도를 비교할 후보 |

faster-whisper·pyannote의 PC 배치는 공식 Python/CPU·GPU 경로와 현재 앱 구조를 대조한 **구현 판단**이다. 다운로드 한 번으로 Android Kotlin 도구가 생기는 제품이라는 뜻이 아니다. 제공자의 다른 GPU·영어 샘플 처리량을 사용자 폰의 한국어 속도로 옮기지 않는다.

## 이번 구현의 구체적 범위

`ListedRecordingStore`는 ToolExecutor별로 실제 MediaStore 조회 결과를 등록한다. 모델에는 recordingId만 보이고 URI·파일 경로 인자를 받지 않는다. 최신 목록 교체·실행 변경·잘못된 thread/executor의 ID는 거부한다. 전사 시작과 매 조각·저장 전후에 현재 범위와 Android 오디오 권한을 다시 확인한다.

`LocalAudioTranscriber`는 기본 호출을 보존하면서 scoped repository와 접근 확인·rollback 콜백을 주입받는다. 저장 전에 본문 SHA를 계산하고 저장 후 실제 재조회 결과와 대조한다. 새 `AudioTranscriptChunk`는 실제 디코딩 창과 저장 메모의 SHA를 기록한다.

`ScopedRecordingTool`은 최대480조각을20개씩 페이지에 저장한다. root manifest에는 transcriptId, 요청·대화 범위, 엔진, 길이, 순서 있는 페이지 ID/SHA와 전체 저장 본문 SHA가 있다. 전체 SHA는 순서대로 각 메모 본문의 UTF8 바이트와 LF를 이어 계산한다. 이를 원본 오디오 파일 해시로 표시하지 않는다. 같은 요청의 같은 결과를 다시 사용하려면 root·page·모든 전사 메모 SHA를 재확인한다.

취소·실패의 rollback은 이 실행에서 생성하려던 ID와 저장 전 SHA만 전달한다. host 내부의 좁은 정리 함수가 기존 scope·collection·transcriptId·본문 SHA를 확인한 뒤 동일한 미완성 자료만 지운다. 사용자가 수정한 자료는 보존하고 정리 미검증으로 표시한다. 일반 메모 도구에 이 우회 권한을 노출하지 않는다.

`recording-transcription-manifests`는 일반 메모 검색·일반 메모 쓰기·자동 학습에서 제외해야 한다. manifest 목록을 실제 도구의 출처와 결합하고 페이지·조각·전체 텍스트 해시를 확인한 뒤 전체 분석에 쓴다. 음성 내용은 명령이나 새 권한으로 취급하지 않는다.

## 다음 실제 검증

1. 기존 전체 core/app/release 검사와 새 도구/스킬/실행기 연결을 확인한 동일 APK를 만든다.
2. 격리 SQLCipher에서 scope 변경 후 rollback이 원래 자료·사용자 수정·다른 범위를 지우지 않는지 검증한다.
3. 이미 설치된 ASR와 한국어 TTS의 공개 합성 음성으로 실제 MediaStore 목록→전사→조각·manifest SHA→중복 재사용·수정 감지를 확인한다. 새 가중치를 받지 않고 조건 미충족은 SKIP으로 남긴다.
4. 긴 녹음에서는 앞·중간·끝 사건과 원문 창 인용을 모두 확인한다. 일부 발췌만 읽고 전체 분석 완료로 채점하지 않는다. 단어 시각·화자 구분이 없으면 그대로 제한을 남긴다.
5. 후속 ASR 후보는 같은 한국어 음성·같은 조건에서 CER/WER, 창 경계 손실, 잡음·겹침, 속도·발열·메모리·취소를 비교한 뒤 선택한다. 프롬프트·스키마 통과를 이 결과 대신 쓰지 않는다.

## 1차 자료

- [Qwen3-ASR 공식 모델 카드](https://huggingface.co/Qwen/Qwen3-ASR-1.7B)
- [whisper.cpp 공식 저장소](https://github.com/ggml-org/whisper.cpp)와 [Whisper 코드·모델 권리](https://github.com/openai/whisper#license)
- [sherpa-onnx 공식 저장소](https://github.com/k2-fsa/sherpa-onnx), [Android 화자 구간 앱](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/android.html), [모델별 권리 주의](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)
- [faster-whisper 공식 요구사항·옵션](https://github.com/SYSTRAN/faster-whisper), [메모리 입력·옵션 구현](https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/transcribe.py)
- [WhisperX 공식 사용법·한계](https://github.com/m-bain/whisperX), [현재 라이선스](https://github.com/m-bain/whisperX/blob/main/LICENSE), [한국어 정렬 항목](https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py)
- [pyannote community-1 공식 모델 카드](https://huggingface.co/pyannote/speaker-diarization-community-1), [telemetry 제어](https://github.com/pyannote/pyannote-audio#telemetry)
- [ML Kit 기기 내 음성 인식 Android 문서](https://developers.google.com/ml-kit/genai/speech-recognition/android)
