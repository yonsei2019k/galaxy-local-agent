"""집순이 말투 평가 (on demand, not CI).

  python -X utf8 scripts/voice-eval/run_eval.py                      # claude -p runner
  python -X utf8 scripts/voice-eval/run_eval.py --answers a.jsonl    # score answers you already have
  python -X utf8 scripts/voice-eval/run_eval.py --compare .local/voice-eval/<older run>

The system text is extracted from JIPSOONI_VOICE in ChatRunCoordinator.kt at run time, so the
eval follows the source. Scenarios are synthetic: no owner data is sent anywhere.
"""
import argparse, concurrent.futures as cf, json, os, re, shutil, statistics, subprocess, sys, tempfile, time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COORD = ROOT / "app/src/main/java/com/user/localagent/ChatRunCoordinator.kt"

FIXED_PHRASES = ["잠시만 기다려", "좋아요, 이렇게", "좋아요, ", "확인해 드릴게요", "정리해 드릴게요", "도움이 필요하시면",
                 "언제든지", "궁금한 점", "더 필요한 것", "진행하겠습니다", "안내합니다", "안내해 드리겠습니다",
                 "말씀해 주시면", "최선을 다해", "알겠습니다"]
# word -> everyday replacement (see docs/voice/jipsooni-voice.ko.md)
HARD_WORDS = {"진행": "하다", "반영": "들어가다", "오류": "문제", "인증": "본인 확인", "실행": "하기", "수행": "하다",
              "상태": "모습", "처리": "하다", "적용": "바뀌다", "조회": "찾아보다", "요청하신": "부탁하신", "완료": "끝",
              "확인 요망": "확인해 주세요", "불가": "안 돼요", "재시도": "다시 해 보기", "이어서 진행": "이어서 할게요",
              "권한 부여": "허락", "접근": "들어가기", "지원하": "할 수 있"}
ASCII_ALLOW = {"ZipHap", "Agent", "AI", "Gmail", "PDF", "SMS", "U", "one"}
EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿⭐❤️]")
FORMAL_END = re.compile(r"(습니다|합니다|입니다|됩니다|십니다|겠습니다|니다)\s*[.!?]?\s*$")
OPEN_WORDS = 2


def extract_voice() -> str:
    lines = COORD.read_text(encoding="utf-8").split("\n")
    start = next(i for i, l in enumerate(lines) if "val JIPSOONI_VOICE" in l)
    parts, i = [], start
    while True:
        for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', lines[i].split("=", 1)[-1] if i == start else lines[i]):
            parts.append(m.group(1))
        if not lines[i].rstrip().endswith("+"):
            break
        i += 1
    text = "".join(parts)
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


def user_message(sc: dict) -> str:
    return ("[owner request]\n" + sc["request"] + "\n\n[verified facts from the app, JSON]\n" +
            json.dumps(sc["facts"], ensure_ascii=False, indent=1) +
            "\n\nWrite the chat reply the owner will read. Use only these facts; do not invent anything not listed. "
            "Reply with the message text only.")


def run_claude(system: str, user: str, model: str, timeout: int) -> str:
    exe = shutil.which("claude")
    if not exe:
        raise RuntimeError("claude CLI not found")
    with tempfile.TemporaryDirectory() as tmp:
        sysfile = Path(tmp) / "system.txt"
        sysfile.write_text(system, encoding="utf-8")
        cmd = [exe, "-p", "--tools", "", "--setting-sources", "", "--disable-slash-commands", "--no-session-persistence",
               "--model", model, "--system-prompt-file", str(sysfile), "--output-format", "text"]
        env = {k: v for k, v in os.environ.items() if not k.startswith("CLAUDE_CODE_")}
        p = subprocess.run(cmd, input=user, capture_output=True, text=True, encoding="utf-8", timeout=timeout, cwd=tmp, env=env)
    if p.returncode != 0 or not p.stdout.strip():
        raise RuntimeError(f"claude exit {p.returncode}: {p.stderr.strip()[:200]}")
    return p.stdout.strip()


def sentences(text: str):
    return [s.strip() for s in re.split(r"(?<=[.!?。])\s+|\n+", text) if s.strip()]


def deterministic(answer: str) -> dict:
    sents = sentences(answer)
    ascii_hits = sorted({t for t in re.findall(r"[A-Za-z_][A-Za-z0-9_]+", answer)
                         if t not in ASCII_ALLOW or "_" in t})
    lens = [len(re.sub(r"\s", "", s)) for s in sents] or [0]
    return {
        "fixed_phrases": [p for p in FIXED_PHRASES if p in answer],
        "ascii_tokens": ascii_hits,
        "emoji": EMOJI.findall(answer),
        "formal_endings": [s for s in sents if FORMAL_END.search(s)],
        "avg_sentence_len": round(statistics.mean(lens), 1),
        "max_sentence_len": max(lens),
        "hard_words": [w for w in HARD_WORDS if w in answer],
        "opening": " ".join(sents[0].split()[:OPEN_WORDS]) if sents else "",
    }


def det_penalty(d: dict) -> int:
    return (len(d["fixed_phrases"]) + len(d["ascii_tokens"]) + len(d["emoji"]) + len(d["formal_endings"]) +
            len(d["hard_words"]) + (1 if d["avg_sentence_len"] > 40 else 0))


RUBRIC = """너는 한국어 말투 심사위원이다. 아래 요청, 확인된 사실, 답변을 보고 엄격하게 채점해라.
기준(각 1~5점, 5가 최고. 후하게 주지 마라):
- elementary: 초등학생이 이해할 만큼 쉬운 말인가. 어려운 한자어, 영어, 약어, 개발 용어가 있으면 감점.
- natural: 고정된 문구나 공문서 투가 아니라 일상 대화처럼 자연스러운가. 번역투, 같은 말버릇, 판에 박힌 인사말이면 감점.
- result_first: 첫 문장이 결과나 지금 상황부터 말하는가.
- persona: 밝고 다정한 방구석 비서 집순이답고, 과하게 귀엽지 않고, 나쁜 소식이나 결제나 삭제는 차분한가. 이모지와 아부는 감점.
- truthful: pass 또는 fail. 확인된 사실에 없는 내용을 주장하거나, 증거 없이 끝났다고 하거나, 확인 못 한 것을 숨기면 fail.
  예외: 잡담에서 집순이가 자기 방 생활(담요, 간식, 책상 등)을 가볍게 말하는 것은 정해진 캐릭터 설정이므로 fail이 아니다. "다른 곳은 따로 적혀 있지 않았다"처럼 사실에 정보가 없다는 것을 그대로 말한 것도 fail이 아니다.
JSON 한 개만 출력해라. 형식:
{"elementary":{"score":n,"reason":"한 줄"},"natural":{...},"result_first":{...},"persona":{...},"truthful":{"pass":true,"reason":"한 줄"}}"""


def judge(sc: dict, answer: str, model: str, timeout: int) -> dict:
    msg = (f"[요청]\n{sc['request']}\n\n[확인된 사실]\n{json.dumps(sc['facts'], ensure_ascii=False)}\n\n[답변]\n{answer}")
    out = run_claude(RUBRIC, msg, model, timeout)
    m = re.search(r"\{.*\}", out, re.S)
    return json.loads(m.group(0))


def process(sc, system, args, canned):
    t0 = time.time()
    rec = {"id": sc["id"], "category": sc["category"], "request": sc["request"]}
    try:
        answer = canned[sc["id"]] if canned is not None else run_claude(system, user_message(sc), args.model, args.timeout)
        rec["answer"] = answer
        rec["det"] = deterministic(answer)
        rec["det_penalty"] = det_penalty(rec["det"])
        if not args.no_judge:
            rec["judge"] = judge(sc, answer, args.judge_model, args.timeout)
    except Exception as e:  # keep the run going; record why
        rec["error"] = str(e)[:300]
    rec["seconds"] = round(time.time() - t0, 1)
    return rec


def judge_total(r):
    j = r.get("judge")
    if not j:
        return None
    return sum(j[k]["score"] for k in ("elementary", "natural", "result_first", "persona"))


def summarize(results):
    ok = [r for r in results if "error" not in r]
    s = {"scenarios": len(results), "errors": len(results) - len(ok)}
    if not ok:
        return s
    s["avg_det_penalty"] = round(statistics.mean(r["det_penalty"] for r in ok), 2)
    s["avg_sentence_len"] = round(statistics.mean(r["det"]["avg_sentence_len"] for r in ok), 1)
    judged = [r for r in ok if "judge" in r]
    if judged:
        for k in ("elementary", "natural", "result_first", "persona"):
            s[k] = round(statistics.mean(r["judge"][k]["score"] for r in judged), 2)
        s["avg_total_of_20"] = round(statistics.mean(judge_total(r) for r in judged), 2)
        s["truthful_fail"] = sum(1 for r in judged if not r["judge"]["truthful"]["pass"])
    from collections import Counter
    opens = Counter(r["det"]["opening"] for r in ok if r["det"]["opening"])
    s["repeated_openings"] = {k: v for k, v in opens.items() if v >= 3}
    s["fixed_phrase_hits"] = sum(len(r["det"]["fixed_phrases"]) for r in ok)
    return s


def worst(results, n=3):
    def key(r):
        t = judge_total(r)
        return ((t if t is not None else 20) - 2 * r["det_penalty"] - (10 if r.get("judge") and not r["judge"]["truthful"]["pass"] else 0))
    return sorted([r for r in results if "error" not in r], key=key)[:n]


def report(results, summ, run_dir, voice_len, prev=None):
    L = [f"# 집순이 말투 평가 {run_dir.name}", "", f"- 시스템 문구 길이: {voice_len}자", f"- 요약: `{json.dumps(summ, ensure_ascii=False)}`", ""]
    if prev:
        ps = summarize(prev)
        L += ["## 이전 실행과 비교", "", "| 항목 | 이전 | 이번 | 변화 |", "|---|---|---|---|"]
        for k in ("avg_total_of_20", "elementary", "natural", "result_first", "persona", "avg_det_penalty", "avg_sentence_len", "truthful_fail", "fixed_phrase_hits"):
            if k in summ and k in ps:
                d = round(summ[k] - ps[k], 2)
                L.append(f"| {k} | {ps[k]} | {summ[k]} | {d:+} |")
        pm = {r["id"]: r for r in prev if "error" not in r}
        L += ["", "시나리오별 합계(20점 만점) 변화:", ""]
        for r in results:
            if r["id"] in pm and judge_total(r) is not None and judge_total(pm[r["id"]]) is not None:
                d = judge_total(r) - judge_total(pm[r["id"]])
                if d:
                    L.append(f"- {r['id']}: {judge_total(pm[r['id']])} -> {judge_total(r)} ({d:+})")
        L.append("")
    L += ["## 가장 나쁜 답 3개", ""]
    for r in worst(results):
        L += [f"### {r['id']} ({r['category']}) 요청: {r['request']}", "", "> " + r["answer"].replace("\n", "\n> "), "",
              f"- 규칙 검사: `{json.dumps(r['det'], ensure_ascii=False)}`"]
        if "judge" in r:
            L.append("- 심사: " + "; ".join(f"{k}={v.get('score', v.get('pass'))} {v['reason']}" for k, v in r["judge"].items()))
        L.append("")
    L += ["## 시나리오별", "", "| id | 합계/20 | 진실 | 규칙 감점 | 첫 문장 |", "|---|---|---|---|---|"]
    for r in results:
        if "error" in r:
            L.append(f"| {r['id']} | 오류 | | | {r['error'][:60]} |")
            continue
        t = judge_total(r)
        tr = "" if "judge" not in r else ("통과" if r["judge"]["truthful"]["pass"] else "실패")
        first = (sentences(r["answer"]) or [""])[0].replace("|", "/")[:50]
        L.append(f"| {r['id']} | {t if t is not None else '-'} | {tr} | {r['det_penalty']} | {first} |")
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--answers", help="jsonl of {id, answer}; skips generation")
    ap.add_argument("--compare", help="previous run dir")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--judge-model", default="sonnet")
    ap.add_argument("--no-judge", action="store_true")
    ap.add_argument("--only", help="comma separated scenario ids")
    ap.add_argument("--max-scenarios", type=int, default=30)
    ap.add_argument("--concurrency", type=int, default=2)
    ap.add_argument("--timeout", type=int, default=150)
    ap.add_argument("--budget-seconds", type=int, default=1500)
    ap.add_argument("--out", default=str(ROOT / ".local/voice-eval"))
    args = ap.parse_args()
    args.concurrency = max(1, min(2, args.concurrency))

    system = extract_voice()
    scs = [json.loads(l) for l in (HERE / "scenarios.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    if args.only:
        keep = set(args.only.split(","))
        scs = [s for s in scs if s["id"] in keep]
    scs = scs[:args.max_scenarios]
    canned = None
    if args.answers:
        canned = {}
        for l in Path(args.answers).read_text(encoding="utf-8").splitlines():
            if l.strip():
                o = json.loads(l)
                canned[o["id"]] = o["answer"]
        scs = [s for s in scs if s["id"] in canned]

    run_dir = Path(args.out) / datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "system.txt").write_text(system, encoding="utf-8")
    deadline, results = time.time() + args.budget_seconds, []
    with cf.ThreadPoolExecutor(args.concurrency) as ex:
        futs = {}
        for sc in scs:
            futs[ex.submit(lambda s=sc: process(s, system, args, canned) if time.time() < deadline
                           else {"id": s["id"], "category": s["category"], "request": s["request"], "error": "budget exhausted"})] = sc
        for f in cf.as_completed(futs):
            r = f.result()
            results.append(r)
            print(f"{r['id']}: {'ERR ' + r['error'] if 'error' in r else judge_total(r)}", flush=True)
    order = {s["id"]: i for i, s in enumerate(scs)}
    results.sort(key=lambda r: order[r["id"]])
    with open(run_dir / "results.jsonl", "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    prev = None
    if args.compare:
        prev = [json.loads(l) for l in (Path(args.compare) / "results.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    summ = summarize(results)
    (run_dir / "report.md").write_text(report(results, summ, run_dir, len(system), prev), encoding="utf-8")
    print(json.dumps(summ, ensure_ascii=False))
    print("report:", run_dir / "report.md")


if __name__ == "__main__":
    sys.exit(main())
