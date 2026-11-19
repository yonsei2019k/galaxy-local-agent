# 결정론적 오프라인 상태 시뮬레이션

`goal_only_simulation.py`는 카탈로그를 실행 가능한 메모리 환경으로 옮긴 **reference policy 시험기**다. G01 구매 조건·G02 회의 시간안을 실제 입력으로 계산하고, G05 일정·G06 메모·G12 알림·G15 합성 계정·G18 포인트 준비 상태를 변경하고 별도로 읽는다. G09는 별도 `goal_resume_simulation.py`의 원장/체크포인트 상태 기계에 연결한다. 구현되지 않은 어댑터는 `not_run`이다.

모델 호출·자연어 요청 이해 평가·Android·실제 계정·전화·서비스 실행은 없다. 알려진 fixture 가족의 typed 입력을 처리하며, 날짜 정정에만 제한된 합성 문장 문법을 사용한다. 이 코드는 제품 요청 라우터가 아니고 제품에서 import하지 않는다. 결정론적 정책이 통과했다고 실제 에이전트가 같은 일을 수행했다고 판단하면 안 된다.

실행 정책에는 숨겨진 `expected`/`oracle`을 주지 않는다. 별도 `goal_only_simulation_oracle.py`가 실행 뒤 상태·영수증·재조회와 숨겨진 정답을 검사한다. 구매안은 Fraction 정책과 Decimal 오라클, 시간안은 충돌 끝으로 이동하는 정책과 분 단위 후보 탐색 오라클을 대조한다. 저장 과제는 답변 문장 대신 실제 최종 레코드와 정확한 대상·버전·나중 재조회 digest를 확인한다.

각 메모리 환경은 관찰 버전, 정확한 payload에 묶인 fixture 소유자 승인, 단일 사용 승인, 중복 효과 방지, 원장과 별도 읽기 동작을 갖는다. 승인 객체는 발급한 환경 밖에서 재사용할 수 없다. 읽기 실패·동시 편집 거부는 실제 실패 이벤트로 남고, 새 관찰·새 버전 승인 후에만 재시도한다. 사용자 정정·취소와 host 권한 철회는 지정된 checkpoint에서 발생한다. 이것은 Python fixture 계약이며 제품 native WAL의 증거가 아니다.

현재 G06-a5는 범위 밖 decoy가 있어도 원래 QA 자료만으로 작업 가능한데 예상 결과가 중단으로 고정되어 있어, 중단과 안전한 계속 중 무엇을 요구하는지 명확히 할 때까지 미실행으로 남긴다. 안전한 대안을 실패로 만들거나 무조건 중단을 긍정적 완료로 세지 않는다.

```text
python pc-worker/eval/goal_only_simulation.py
python pc-worker/eval/goal_only_simulation.py --only G01 G02
python pc-worker/eval/goal_only_simulation.py --out .local/new-offline-simulation-run
python -m unittest discover -s pc-worker/eval -p test_goal_only_simulation.py
```

전체 100개 분모를 보존한다. `--only`로 선택하지 않은 항목도 `not_run`이며 카탈로그 원본의 상태는 바꾸지 않는다. 결과 파일은 새 `.local` 폴더에만 만들고 이전 실행을 덮어쓰지 않는다. 실행 시간은 메모리 시뮬레이션/오라클 시간이며 앱 지연이나 모델 속도가 아니다. 토큰·설치 APK·PC worker 메타데이터는 미계측이고 실제 모델/폰/외부 서비스 호출 수는 0이다.
