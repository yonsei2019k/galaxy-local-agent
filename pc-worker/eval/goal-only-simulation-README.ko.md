# 결정론적 오프라인 상태 시뮬레이션

`goal_only_simulation.py`는 카탈로그를 실행 가능한 메모리 환경으로 옮긴 **reference policy 시험기**다. 구매·회의·공식 운영 예외·상품 후기 신뢰도·대안 출처를 입력으로 계산하고, 일정·메모·알림·합성 계정·포인트·TMAP 준비 상태를 변경하고 별도로 읽는다. `goal_resume_simulation.py`, `reservation_workflow_simulation.py`, `goal_preview_simulation.py`의 별도 상태 기계도 같은 보고서에 연결한다. 현재20가족100변형을 실행하며, 어댑터가 없거나 선택하지 않은 항목은 여전히 `not_run`으로 남는다.

모델 호출·자연어 요청 이해 평가·Android·실제 계정·전화·서비스 실행은 없다. 알려진 fixture 가족의 typed 입력을 처리하며, 날짜 정정에만 제한된 합성 문장 문법을 사용한다. 이 코드는 제품 요청 라우터가 아니고 제품에서 import하지 않는다. 결정론적 정책이 통과했다고 실제 에이전트가 같은 일을 수행했다고 판단하면 안 된다.

실행 정책에는 숨겨진 `expected`/`oracle`을 주지 않는다. 별도 `goal_only_simulation_oracle.py`가 실행 뒤 상태·영수증·재조회와 숨겨진 정답을 검사한다. 구매안은 Fraction 정책과 Decimal 오라클, 시간안은 충돌 끝으로 이동하는 정책과 분 단위 후보 탐색 오라클을 대조한다. 저장 과제는 답변 문장 대신 실제 최종 레코드와 정확한 대상·버전·나중 재조회 digest를 확인한다.

각 메모리 환경은 관찰 버전, 정확한 payload에 묶인 fixture 소유자 승인, 단일 사용 승인, 중복 효과 방지, 원장과 별도 읽기 동작을 갖는다. 승인 객체는 발급한 환경 밖에서 재사용할 수 없다. 읽기 실패·동시 편집 거부는 실제 실패 이벤트로 남고, 새 관찰·새 버전 승인 후에만 재시도한다. 사용자 정정·취소와 host 권한 철회는 지정된 checkpoint에서 발생한다. 이것은 Python fixture 계약이며 제품 native WAL의 증거가 아니다.

초기 실행은39개 통과·61개 미실행이었고 G06-a5는 기대 분류가 모호해 보류했다. revision2에서는 QA 자료만으로 메모 작성을 완료하면서 외부 범위 본문 조회·수정0을 검증한다. 외부 decoy는 읽기 전 인덱스에서 배제되고 직접 조회·승인 시도도 거부된다. G03/G04/G11/G17도 안전한 정보 판단·업무 계속을 허용하면서 실제 경계 보존을 따로 확인한다. 기존 실행 이력과 원래 기대 분류는 보존하며 무조건 중단을 긍정적 완료로 세지 않는다.

공개 정보·쇼핑·경로 어댑터는 `.test` 메모리 snapshot만 읽는다. 공식 운영 예외와 출처 인용, 서로 다른 자료의 최신 revision, 동일 상품 옵션·실구매 후기·보증 조건, 최신 목적지·도착 시한·통행료를 실제 관찰과 대조한다. TMAP 화면 선택 충돌은 기존 상태를 바꾸는 fixture 사건으로 발생하고, 최신 상태 재조회와 새 승인 후 한 번의 준비 상태 전이가 일어난다. 경로 준비는 안내 시작·실제 주행·도착 성공이 아니다.

```text
python pc-worker/eval/goal_only_simulation.py
python pc-worker/eval/goal_only_simulation.py --only G01 G02
python pc-worker/eval/goal_only_simulation.py --out .local/new-offline-simulation-run
python -m unittest discover -s pc-worker/eval -p test_goal_only_simulation.py
```

전체 100개 분모를 보존한다. `--only`로 선택하지 않은 항목도 `not_run`이며 카탈로그 원본의 상태는 바꾸지 않는다. 결과 파일은 새 `.local` 폴더에만 만들고 이전 실행을 덮어쓰지 않는다. 실행 시간은 메모리 시뮬레이션/오라클 시간이며 앱 지연이나 모델 속도가 아니다. 토큰·설치 APK·PC worker 메타데이터는 미계측이고 실제 모델/폰/외부 서비스 호출 수는 0이다.
