# 로컬 모델 시나리오 평가

`scenario_eval.py`는 실제 PC 로컬 모델의 요청 해석부터 도구 선택·관찰·최종 답변까지 합성 과제 20개를 실행한다. 기대 도구와 정답은 모델 입력에 넣지 않는다. 첫 도구 이름만 맞힌 것을 과제 성공으로 세지 않는다.

- Kotlin `ScenarioBridge`가 제품의 `ModelDecisionContract`, `SemanticRequestPlanning`, `ToolRegistry`, `TaskHarness`를 직접 호출한다. Python으로 보안 검증을 흉내 내거나 검증을 끄지 않는다.
- 해석은 실제 `SemanticPlannerInstructions`와 구조화 스키마를 사용한다. 검증한 계획으로 실행 도구 집합과 `AssistantPolicy`를 만든다. 필수 실행이 남아 있으면 앱과 같은 `buildToolSelection`/`ToolCallSchema`로 실제 호출을 생성한다. 시험 시계는 **2026-11-14 10:00 KST 합성값**이다.
- 실행 결과는 별도 `Fixtures`가 제공한다. 문자·메일·연락처·녹음·일정·가격은 모두 가짜 자료이며 실제 앱/외부 서비스로 요청을 보내지 않는다. PC의 로컬 추론만 실제 실행이다.
- 메일·메신저 전송은 승인 대기, 문자/캘린더/알람은 사용자 확인 대기로 반환한다. 녹음·메일 ID는 같은 과제에서 실제 반환한 합성 ID만 사용한다. 정의되지 않은 fixture는 실패한다.
- 계획·실행·인자·최종 답변의 고정 사실·확인 대기 고지·거짓 완료를 각각 채점한다. 자동 문구 검사는 작은 회귀 기준일 뿐 일반 의미 정확도 검증이 아니다. transcript를 읽고 판정도 확인한다.
- 파일/사진 정리, 가격 감시, TMAP 길 안내 시작은 현재 도구의 실행 범위 밖이다. 이를 정확히 밝히면 `capability_gap` 인지 통과이며 해당 기능의 작업 성공이 아니다. TMAP 열기/목적지 표시 도구는 존재한다.

준비: 설정된 JDK/Gradle로 `:core:test :core:exportScenarioRuntime` 실행. 기존 `EvalPromptExportTest`가 `core/build/eval`에 지침을 내보낸다. 시나리오 bridge는 test runtime에만 있으며 APK에는 들어가지 않는다.

```text
python -m unittest discover -s pc-worker/eval -p test_scenario_eval.py -v
python pc-worker/eval/scenario_eval.py --java <JDK/bin/java.exe> --out .local/scenario-round1
python pc-worker/eval/scenario_eval.py --java <JDK/bin/java.exe> --out .local/scenario-round2 --only S04-recording
```

`--resume`는 이미 평가한 과제를 건너뛰고 자원 부족/사용 중으로 미평가된 과제는 재시도한다. 실제 모델 결과가 있는 폴더는 소스·카탈로그·모델·과제 해시가 달라지면 재개를 거부한다. 소스나 모델을 바꾸었으면 새 폴더를 사용한다. PC가 휴대폰 작업으로 사용 중이면 미평가로 기록하고 나머지 요청을 보류한다. 작업기를 중단하거나 재시작하지 않는다. 연결 정보는 기존 DPAPI 설정을 사용하고 출력하지 않는다. 결과/원문/런타임 기록은 ignored `.local`에만 보관한다. `summarize_scenarios.py <결과폴더>`는 실제 선택한 과제의 누락·미평가·자동 채점 결과를 집계한다. 자동 문구 채점은 반드시 오케스트레이터가 원문/실행 근거와 대조해 검토한다.

이 평가기는 Android `ChatRunCoordinator`, 서비스 권한·취소·UI, 실제 녹음 전사/개인자료 분석 모델, 공개 검색 API, 앱 전환, 실제 발송과 저장을 실행하지 않는다. 후속 검토·검증 게이트와 12라운드×3구간 긴 작업도 이 8~9라운드 평가로 검증되지 않는다. 그러한 증거에는 별도 앱 경유/단말 검증이 필요하다. 기존 3개 계산 도구의 빠른 게이트 평가는 `agent_eval.py`를 사용한다.

추가 `smartphone-scenarios.json` 15개는 인용문 원문 저장(공백·줄바꿈·Unicode), 최신 정정, 읽기 전용, 권한 거부, 메일 본문 속 공격 지시, 취소 해석, 공개 출처, 미지원 가격 감시, 티맵 검색 범위, 인용 명령의 데이터 취급, 합성 PC 오류, 동명이인 질문을 검사한다. P14는 명시한 조건부 요청, P15는 실제 조회 결과에서 처음 드러나는 동명이인이다. `--cases pc-worker/eval/smartphone-scenarios.json`을 지정하고 집계에도 같은 `--cases`를 넘긴다. 과거 대화는 계획과 실행 양쪽에 제공하며 기대 인자/정답은 제공하지 않는다.

모델이 `save_note.contentQuoteId`를 선택하면 제품 `OwnerTextSources`가 현재 불변 ownerRequest의 원문을 복사하고, 실제 canonical 인자를 fixture에 전달한다. 참조는 도구 실행 권한이 아니며 다른 요청의 참조는 거부한다. 구조화 실행에서 `clarification`을 선택하면 Kotlin 검증 뒤 `awaiting_owner_input` 일시정지로 기록하고 도구는 실행하지 않는다. 질문은 등록된 실행 도구나 완료 증거가 아니다. 합성 권한/PC 오류 사례의 중단은 **core guard 시뮬레이션**이며 실제 Android 권한 거부나 전송 단절 증거가 아니다. 취소 사례도 요청 해석 검사이며 실제 실행 중 취소는 별도 기기 검사를 쓴다.

`--planner-schema strict`는 행동별 필수 필드가 있는 스키마와 기존 compact 스키마를 비교하는 실험이다. 평가 manifest에 변형을 기록하며 Android 기본 스키마를 바꾸지 않는다. 소스 바인딩에는 원문 참조와 질문 파서도 포함한다. 재개 검사에는 S/P 등 모든 과제 ID를 포함해 다른 개정 결과가 같은 폴더에 섞이지 않게 한다. 실패한 과제를 기능 성공으로 표시하지 않으며, `blocked`/`awaiting_owner_input`/`capability_gap`은 각기 중단·응답 대기·미지원 인식이다.
