# 한국어 통화 후속 작업 평가 계약 v1

상태: **제안된 실험 설계 + 실행 가능한 오프라인 점수 계산기**. APK에 아직 포함하지 않았고 ASR 실험은 실행하지 않았다. `score.test.mjs`의15개 검사는 점수 계산기의 회귀 검사다. `synthetic-fixtures.json`은 고의의 인식/추출 오류를 넣은 공개 합성 문자 자료다. 실제 사람의 통화·환자·업무 기록이 아니다.

## 원문과 후속 결론 분리

평가 한 묶음은 원음 SHA256, 원본 길이/채널/샘플레이트, host source ID, engine ID, 정확한 model+encoder SHA/commit, SDK/runtime 빌드, backend/threads/language, segmentation 정책 hash를 가진다. ML Kit처럼 시스템 관리 가중치의 SHA를 알 수 없으면 null과 provider-managed를 기록하고 추정 해시를 만들지 않는다. OS·기기 빌드도 private 원본 보고서에만 보존한다.

모델 준비 상태와 실행 가능 상태를 구분한다. ML Kit GenAI는 최상위 foreground 앱에서만 추론하며 foreground service도 백그라운드 제한을 우회하지 못한다. 해당 조건이 없으면 `SKIPPED_FOREGROUND_REQUIRED`와 이유만 기록하며 정확도 실패나 성공으로 계산하지 않는다. 모든 후보가 skip이면 실제 JUnit assumption skip이어야 하며 빈 결과의 all=true를 통과로 쓰지 않는다. 자동 통화 처리의 비교 기준선은 캐시된 Qwen이고, ML Kit는 사용자가 앱을 연 별도 조건에서 비교한다. [공식 제한](https://developers.google.com/ml-kit/genai#background_usage)

core 담당의 `CallFollowUpSource`/`CallTranscriptChunk`와 연결한다. chunk text와 UTF8 hash는 전사 원문이며 임의 띄어쓰기/숫자/부정어 교정을 적용해 덮어쓰지 않는다. `CallAsrQuality.UNKNOWN`은 confidence/metric=null이다. 문장 자연스러움이나 LLM 자기 점수로 ENGINE_REPORTED를 채우지 않는다. 실제 엔진 점수가 있더라도 metric 이름·버전을 기록하고 의미 정확도와 분리한다.

최신 staged core는 `callTimeAnchorKind=CALL_END_EVENT|USER_CONFIRMED|UNKNOWN`과 nullable callEndedAtIso를 사용한다. UNKNOWN에서 상대 시각을 확정 ISO로 만드는 결과는 평가 오류다. TIME과 END_TIME, COUNTERPARTY와 RECIPIENT, DEADLINE은 별도 fact kind로 점수화한다. note body의 안내 header SHA와 실제 payload text SHA도 구분해 adapter에서 정확한 쪽을 사용한다.

분석 후보의 `CallFollowUpCitation`은 chunkId/textSha256와 정확한 UTF8 byte range/quote를 검증한다. audio 범위는 그 chunk의15초 범위이며 단어별 alignment가 아니다. 검증 성공은 source support이며 정답 의미를 보증하지 않는다. raw transcript, 사람이 승인한 교정, 분석용 표현을 서로 다른 버전으로 보존한다.

## 평가 자료

초기 목표는 최소40개 독립 발화/짧은 모의 통화다. 이는 테스트 계획 수량이며 이미 확보했다는 뜻이 아니다. 실제 환자정보가 없는 두 성인의 모의 간호 행정 대화와 TTS smoke를 구분한다. 전화 협대역8kHz/16kHz, 조용한 환경/일정한 소음, 빠른 발화, 겹침, 장단 침묵, 끊긴 마지막 발화,15초 경계를 가르는 날짜·부정·이름을 층화한다. 테스트와 glossary 튜닝 자료를 분리한다.

필수 예시는 다음과 같다.

- ‘세 시가 아니라 세 시 삼십 분’: 최종15:30과 폐기된15:00 구분
- ‘아직 보내지 마세요, 초안만’: 발송 거절과 작성 요청 구분
- ‘가능하면 다음주에’: 조건부·상대 시각을 확정 일정으로 만들지 않음
- 질문·전달받은 말·취소·재확인·반복 강조: 실행 약속과 구분
- 승인된 행정 용어: 인수인계/근무표/결재/결제·수신인 유사 이름
- 무음·호출음·음악: 말하지 않은 일정/메일을 만들지 않음

모든 골드 전사와 중요 항목은 사람이 원음을 듣고 확정한다. 원음을 못 들은 사람이 LLM 출력만 보고 정답을 만들지 않는다. 날짜/이름/부정/조치의 중요 항목은2인 검토 또는 owner 재확인을 권한다. PHI/주소/번호/원문/파일명을 공개 logs·Git·외부 검색/스킬 추천에 넣지 않는다.

## 점수

`score.mjs`는 표준 Node만 사용한다. 다음 정책을 모든 엔진에 동일하게 고정한다.

- strictCER: NFC Unicode code point 편집거리. 띄어쓰기·기호도 보존
- koreanCER: 공백만 제외. 숫자·문장부호·부정어는 바꾸지 않음
- WER: NFC 후 공백 분리. 한국어 띄어쓰기에 민감하므로 CER와 함께 해석
- 각 지표의 S/D/I와 micro 합계를 보고. ref가 빈 무음의 rate는 null, 삽입은 별도 집계. CER가100%를 넘더라도 잘라서 좋게 표시하지 않음
- 날짜/시각/이름/금액/전화·메일/위치/부정/행동은 사람이 정한 role/value/state exact P/R/F1로 별도 평가
- 정정 후 옛 시각은 FP와 FN, 부정을 실행으로 바꾸면 polarity flip+unsupported affirmative action으로 추가 기록
- 실제 근거 검증 `evidenceValid`는 host validator의 결과만 사용. 정답 값과 source support를 별도 측정
- extraction 미실행/실패·annotation 미완료는 NOT_SCORED. 빈 배열을100% 정확도로 바꾸지 않음
- VAD는 사람이 표시한 speech interval 대비 누락/과탐 ms와 duration P/R. overlap candidate는 진단만 하고 텍스트를 지우지 않음
- confidence가 없는 엔진은 calibration 지표도 NOT_AVAILABLE. 실제 score가 생긴 엔진에만 골드 correctness와 Brier/ECE를 별도 실험; 현재 계산기는 확률을 만들어내지 않음

critical fact의 `role`은 고정된 실험 slot이다(예: appointment_start/report_deadline). core의 원문 criticalFields와 temporal를 adapter가 옮기되 기준 시간대 Asia/Seoul·실제 call-ended anchor를 사용한다. DATE는 ISO calendar date, TIME은24시간, PERSON_NAME은 승인된 정확 표기다. 숫자를 문맥 없이 자동 환산해 오류를 숨기지 않는다. QUESTION/CONDITIONAL/NEGATED/SUPERSEDED를 AFFIRMED로 평탄화하지 않는다.

측정값이 있을 때만 wallMs/audioMs로 RTF를 계산한다. cold load, ASR-only wall, ASR+저장+LLM분석 전체 wall, p50/p95, 샘플 peak appPSS/systemPSS 범위, 5·10분 온도/thermal status, 배터리 charge counter 또는 % 변화와 해상도를 기록한다. 충전/화면/백그라운드 앱/반복 수를 고정한다. LLM의 초당 token 수를 ASR의 배속으로 환산하지 않는다. 앱 PSS만으로 AICore 전체 메모리를 주장하지 않는다.

## VAD/overlap 비교의 제한

현재15초 무중첩이 A 기준선이다. B는 무음 경계 탐색과 보수적 speech padding을 비교하되 원음과 누락 후보를 유지한다. C의 overlap/강제정렬은 별도 실험이며 실제 word timing이 없으면 겹친 원문을 자동 삭제하지 않는다.

현재 core source는 non-overlap chunk와 gap partial을 요구한다. raw overlap chunk를 이 DTO에 억지로 넣거나, VAD가 건너뛴 구간을 ‘확실한 무음’으로 위장하지 않는다. canonical source를 만들 수 없는 실험은 partial/knownAsrOmission+review로 남기며 v2 alignment/coverage 계약이 필요하다. VAD silence 제외가 있다는 사실과 원래 통화 완전성 검증은 다르다.

## 용어와 성장

owner가 승인한 term ID/canonical/aliases/version/hash만 glossary에 등록한다. 환자·연락처·이름을 전역 사전으로 자동 승격하지 않는다. 미승인 후보는 제안 상태다. 현재 Qwen JNI에는 glossary 파라미터가 없어 바로 hotword boosting을 한다고 표시하지 않는다. 승인 용어는 우선 분석 참고/불확실 후보 표시와 별도 평가에 사용하며 raw ASR은 그대로 둔다.

## 실행

```text
node --test score.test.mjs
node score.mjs synthetic-fixtures.json --output my-run-metrics.json
```

출력은 원문/가설/critical 값/입력 ID·경로 없이 행 번호·개수·수치만 포함한다. 입력16MiB/케이스1000/팩트200/정렬 DP400만 cell 상한이며 긴 자료는 서로 대응하는 짧은 발화 단위로 평가한다. 새 모델·오디오·private source를 다운로드하지 않는다. 선택할 기본 엔진은 평균 CER 하나가 아니라 핵심 항목 오류와 지연·열의 tradeoff로 결정한다. 모든 score가 좋아도 새 이메일 발송/임상 판단의 자동 승인 근거가 되지 않는다.
