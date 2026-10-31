# CachedCallAsrValidation 실행 계약

새 Android 계측 소스2개는 `CachedCallAsrValidation.kt`와 `CachedCallAudioFixtures.kt`다. 이 문서를 작성한 담당은 Gradle·ADB·기기 검사를 실행하지 않았다. `score.test.mjs`15개 통과는 오프라인 점수 계산기의 결과이며, 아래 기기 검사 통과를 뜻하지 않는다.

## 개별 gate

| Method | 필수 인자 | 동작 |
|---|---|---|
| readinessOnlyDoesNotDownloadOrChangeSelectedAsr | cachedCallAsrReadinessApproved=true | 로컬 모델 목록/검증 상태와 ML Kit ko 지원 상태만 확인. 모델 다운로드·추론 없음 |
| sameCachedKoreanTelephoneSamplesAcrossAvailableEngines | cachedCallAsrBenchmarkApproved=true | 이미 준비된 엔진만 동일 합성 PCM으로 비교 |
| decoderChecksActualSourceBytesBeforeAnyChunk | cachedCallAudioHashValidationApproved=true | 새 합성100ms WAV만 사용. 잘못된 SHA/크기는 callback0, 맞는 SHA/크기는 정확한 WAV callback1 확인. LLM·ASR 추론 없음 |

선택 인자 `cachedAsrModels=asr-mlkit,asr-qwen-17b,asr-qwen-06b`로 실행 대상을 제한할 수 있다. 기본은 세 후보 모두다. 준비되지 않은 후보는 SKIPPED_UNAVAILABLE이며 모델을 받지 않는다. ML Kit는 RuntimeGate.foreground=false이면 SKIPPED_FOREGROUND_REQUIRED로 구분한다. 준비 상태 AVAILABLE은 백그라운드 실행 가능을 뜻하지 않는다. 모두 unavailable/foreground-required skip이면 실제 JUnit assumption skip이며 측정 성공이 아니다. 시도한 엔진 실패는 그대로 실패다. `cachedAsrPerSampleTimeoutSeconds` 기본180, 허용30–600초이고 전체 benchmark는25분 제한이다.

ML Kit GenAI 공식 제한은 **최상위 foreground 앱만 추론 허용**이며 foreground service도 `BACKGROUND_USE_BLOCKED` 대상이다. ML Kit 비교를 원하면 사용자가 앱을 열 수 있을 때 root가 실제 MainActivity를 먼저 표시한다. 검사는 화면을 강제로 띄우거나 전역 ASR 선택을 바꾸지 않는다. 백그라운드 자동 통화 후속 처리 기준선은 캐시된 Qwen으로 분리한다. 보고서에 foreground 필요/당시 상태와 skip 이유를 남기며 원문은 남기지 않는다. [공식 제한](https://developers.google.com/ml-kit/genai#background_usage)

## 실제 측정 내용과 한계

설치된 네트워크 불필요 한국어 TTS 음성으로 하나의 공개 합성 문장을 만든다. 이름·명시 월일·15:00→15:30 정정·발송 부정·초안 한정이 포함돼 있다. 원음을16kHz mono로 decode하고8kHz downsample/16kHz upsample을 거친 동일한 clean/noise15dB 두 파일을 만든다. 이는 8kHz 재샘플링 모의 자료이며 실제 전화망 codec·실제 통화 녹음이 아니다. 음성을 조용히 잘라 길이를 맞추지 않으며30초를 넘으면 실패한다.

엔진별로 모델을 한 번 준비하고 clean→noise 순서로 추론한다. 따라서 두 번째 조건은 warm 상태일 수 있다. 각 파일 SHA가 엔진 간 같아야 같은 입력 비교다. 보고서의 load_and_verify_ms는 모델 SHA 검증을 포함하므로 순수 cold-load 시간으로 표시하지 않는다. 통계적으로 안정적인 p50/p95·일반 한국어 정확도·최적 기본 모델을 입증하는 실험이 아니다.

CER/WER와6개 합성 lexical check를 보고한다. 숫자 표기 차이가 CER에 포함되며 lexical checks는 명시한 대체 표기를 별도로 인정한다. 이 검사는 날짜/부정 의미나 일정·메일 실행이 옳다는 증명이 아니다. 실행 결과 MEASURED와 JUnit 절차 통과를 정확도 합격으로 바꾸어 말하지 않는다. 실제 사람의 모의 통화와 전체 후속 분석 평가는 별도로 필요하다.

RTF, 앱 PSS250ms 샘플 peak, 최대 thermal status, 각 샘플 전후 배터리 비율·온도만 수집한다. 앱 PSS는 별도 AICore 메모리를 제외한다. 시스템 관리 가중치의 해시를 추정하지 않는다. 보고서에 전사 원문·녹음 ID·연락처·파일경로를 넣지 않는다. 마이크·통화·기존 녹음은 열지 않는다.

## 실행 자원과 보존

benchmark/inference gate가 이미 사용 중이면 실행하지 않는다. 실행 중인 사용자 작업의 엔진을 내리지 않는다. 실제 benchmark 소유권을 얻은 뒤에만 기존 앱 엔진을 해제하고 direct ASR 인스턴스를 순차 사용하며 끝나면 해제한다. 전역 ASR 선택/언어는 constructor override로 보존하며 `selectAsr`/`selectAsrLanguage`를 호출하지 않는다.

준비용 TTS 파일과 이 실행이 만든 UUID cache 폴더만 정리한다. 개인 노트·대화·녹음은 만들거나 삭제하지 않는다. 모든 gate는 root의 단일 기기 예약 창에서만 실행하며 다른 모델·이미지·내구성 검사와 겹치지 않는다. 테스트는 시스템 네트워크 자체를 차단하지 않으며, 시스템 서비스의 독립적인 네트워크 동작까지0이라고 주장하지 않는다.

산출물은 external files의 `benchmarks/cached-call-asr-readiness.json`, `cached-call-asr-benchmark.json`, `cached-call-audio-source-hash.json`이다. 시작 때 상태를 초기화해 이전 성공 보고서를 재사용하지 않는다. 프로세스 강제 종료라면 STARTING 또는 미완료 보고서를 통과로 해석하지 않는다.
