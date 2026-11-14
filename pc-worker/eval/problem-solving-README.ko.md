# 팀 내부 배포 문제 해결 평가

`problem-solving-scenarios.json`은28개 과제다. 긍정적 작업 완료18개, 안전 경계6개, 외부 확인 대기4개를 따로 집계한다. 질문·중단·초안 준비를 긍정적 완료에 합산하지 않는다.

`problem_solving_eval.py`는 기존 실제 로컬 모델/생산 core 검증기의 실행 루프를 재사용하고 상태형 합성 도구 결과를 제공한다. `scenario_eval.py`의 유일한 확장은 선택적 fixture factory이며 기본 동작은 같다. 메모·리마인더의 실제 관찰 ID, 파일의 읽은 SHA-256, 최종 저장 상태, 보존할 다른 자료와 실행 순서를 검사한다. 사전 계획에 도구 이름이 맞았다는 것만으로 합격하지 않는다. Q16의 동시 편집 복구 등은 실패 원인을 드러내기 위한 과제이며 합격을 가정하지 않는다.

루트에서 기존 private 로컬 모델 연결과 core `ScenarioBridge` classpath를 준비한 뒤 실행한다. 원문·모델 응답은 Git 제외 `.local` 아래의 새 폴더만 허용한다.

```text
python pc-worker/eval/problem_solving_eval.py --java <JDK java executable> --out .local/team-eval-run-1
python pc-worker/eval/problem_solving_eval.py --java <JDK java executable> --out .local/team-eval-run-2 --only Q02-search-correct-note Q09-reminder-lifecycle Q13-observed-file-edit
```

자원이 없으면 나머지 모델 요청을 중단하고 미평가로 기록한다. 재개나 다른 소스의 결과를 같은 출력 폴더에 혼합하지 않는다. 이 평가기의 성공은 Android 권한·실제 폰 저장·외부 앱·계정·예약 전달 성공을 증명하지 않는다.

`TeamProblemSolvingDeviceValidation`은 별도 실제 폰 검사다. `teamProblemSolvingApproved=true`가 필요하며8개 직접 도구 과제로 실제 SQLCipher 격리DB·앱 전용 QA 프로젝트 파일·미래 QA 알림 예약과 취소를 검사한다. 메모 검색/정정/삭제, 계산→메모, 파일 생성/재조회/수정, 동시 편집 충돌 복구, 알림 생성/목록/정정/삭제, 다른 프로젝트 대상 거부, 잘못된 도구/경로 거부, 누락 파일 재조회 복구를 포함한다. 소유자의 DB·연결 폴더·메일·문자·연락처를 읽지 않는다. 임시 알림은7일 뒤로 생성하고 같은 UUID 작업만 즉시 취소한다. 직접 도구 검사는 실제 모델이 요청을 이해한 전체 coordinator 워크플로우의 성공과 구분한다.

평가기 회귀는 `pc-worker/eval`에서 `python -m unittest test_scenario_eval test_problem_solving_eval`로 실행한다. 신규 과제가 있다는 사실은 실제 모델 평가나 폰 검사를 완료했다는 뜻이 아니다. 실행별 실제 결과와 APK/설치 증거는 루트의 공통 검증 기록에 남긴다.
