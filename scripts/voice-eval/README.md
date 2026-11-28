# 집순이 말투 평가

실행 (CI 아님, 필요할 때만):

    C:/Users/VICTUS/AppData/Local/Python/pythoncore-3.12-64/python.exe -X utf8 scripts/voice-eval/run_eval.py [--compare .local/voice-eval/<이전 실행>]

- 시스템 문구는 `ChatRunCoordinator.kt`의 `JIPSOONI_VOICE`를 실행할 때 읽어 온다.
- 기본 실행기는 이 PC의 `claude -p`(도구 없음, 설정 없음, 임시 폴더). 시나리오는 모두 합성 자료다.
- 이미 있는 답을 채점하려면 `--answers a.jsonl` (`{"id":..., "answer":...}` 줄들).
- 동시 2개, 호출당 150초, 전체 1500초 상한. 결과는 `.local/voice-eval/<시각>/report.md`와 `results.jsonl`.
