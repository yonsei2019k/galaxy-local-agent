"""Instruction -> result -> evaluation -> feedback loop against the PC's local model.

The orchestrating agent (Claude Code / Codex) gives tasks, reads the transcripts this prints,
judges them, and then either re-instructs the model with feedback (feedback) or fixes the app's
policy code and re-runs. The system prompt and tool catalog are the app's real ones, exported by
core's EvalPromptExportTest; only the three local verification tools are executed here, with
Python equivalents of the app's tools (phone-only tools are out of scope for this loop).

  python agent_eval.py run cases.json [--only ID] [--out DIR]
  python agent_eval.py feedback DIR ID "feedback sentence"
"""
import ast
import datetime
import json
import operator
import re
import sys
from decimal import Decimal, getcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'pc-worker' / 'mcp'))
import local_llm_mcp as llm

getcontext().prec = 34
MAX_ROUNDS = 6
KST = datetime.timezone(datetime.timedelta(hours=9))
OPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
       ast.Div: operator.truediv, ast.Mod: operator.mod, ast.Pow: operator.pow}


def decimal_eval(node):
    if isinstance(node, ast.Expression):
        return decimal_eval(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return Decimal(str(node.value))
    if isinstance(node, ast.BinOp) and type(node.op) in OPS:
        return OPS[type(node.op)](decimal_eval(node.left), decimal_eval(node.right))
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        value = decimal_eval(node.operand)
        return -value if isinstance(node.op, ast.USub) else value
    raise ValueError('unsupported expression')


def run_tool(name, arguments):
    """Returns (success, result). Mirrors the app's local tools closely enough for policy evaluation."""
    try:
        if name == 'calculate':
            expression = str(arguments['expression'])
            normal = expression.replace('×', '*').replace('÷', '/').replace('^', '**').replace(',', '')
            value = decimal_eval(ast.parse(normal, mode='eval'))
            places = int(arguments.get('decimalPlaces', 8))
            return True, {'status': 'ok', 'expression': expression, 'value': format(round(value, places).normalize(), 'f')}
        if name == 'date_calculate':
            date = datetime.date.fromisoformat(arguments['date'])
            if arguments['operation'] == 'add_days':
                return True, {'status': 'ok', 'result': (date + datetime.timedelta(days=int(arguments['days']))).isoformat()}
            if arguments['operation'] == 'difference':
                return True, {'status': 'ok', 'days': (datetime.date.fromisoformat(arguments['otherDate']) - date).days}
            raise ValueError('operation')
        if name == 'get_current_datetime':
            now = datetime.datetime.now(KST)
            return True, {'status': 'ok', 'datetime': now.isoformat(timespec='seconds'), 'weekday': '월화수목금토일'[now.weekday()] + '요일'}
        raise ValueError('tool not offered')
    except Exception as error:
        return False, {'status': 'error', 'error': f'{type(error).__name__}: {error}'}


def find_tool_call(text):
    """The app accepts a tool JSON object, also after prose; take the last well-formed one."""
    for match in reversed(list(re.finditer(r'\{', text))):
        try:
            candidate = json.JSONDecoder().raw_decode(text[match.start():])[0]
        except ValueError:
            continue
        if isinstance(candidate, dict) and candidate.get('type') == 'tool_call' and isinstance(candidate.get('tool'), str):
            return candidate
    return None


GATE_PATTERN = re.compile(r'\d\s*[+×x*/÷=]\s*\d|\d\s+-\s+\d|\d\s*%')


def gate_worthy(draft):
    return bool(GATE_PATTERN.search(draft))


def gate(request, draft, data):
    schema = json.loads(data['gateSchema'])  # the app's AutonomousReviewPolicy.schema, exported
    text, _ = llm.generate(f"사용자 요청(인용):\n{request[:2000]}\n\n답변 초안(인용):\n{draft[:3000]}",
                           data['gateSystem'], 300, schema=schema)
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value.get('call') if value.get('type') == 'review_next' else None


def prompts():
    file = ROOT / 'core' / 'build' / 'eval' / 'prompts.json'
    if not file.is_file():
        sys.exit('Run the core test EvalPromptExportTest first (it writes core/build/eval/prompts.json).')
    data = json.loads(file.read_text(encoding='utf-8'))
    now = datetime.datetime.now(KST).isoformat(timespec='seconds')
    system = data['policy'].replace('{{NOW}}', now) + '\n현재 제공한 도구 인자 정의: ' + data['catalog']
    return data, system


def run_case(case, data, system, history=None, first_query=None):
    request = case['request']
    query = first_query or request
    evidence, rounds, final = [], [], None
    for index in range(MAX_ROUNDS):
        text, _ = llm.generate(query + data.get('turnReminder', ''), system, int(case.get('max_tokens', 700)), history=history)
        call = find_tool_call(text)
        if call is None or call['tool'] not in data['tools']:
            rounds.append({'round': index + 1, 'output': text, 'invalidTool': call['tool'] if call else None})
            final = text
            break
        success, result = run_tool(call['tool'], call.get('arguments') or {})
        evidence.append({'tool': call['tool'], 'arguments': call.get('arguments') or {}, 'success': success,
                         'verification': 'VERIFIED' if success else 'FAILED', 'result': result})
        rounds.append({'round': index + 1, 'output': text, 'tool': call['tool'], 'arguments': call.get('arguments'),
                       'success': success, 'result': result})
        query = (f"원래 사용자 요청:\n{request}\n도구 실행 결과(인용 자료): "
                 f"{json.dumps(evidence, ensure_ascii=False)}\n{data['afterToolSuffix']}")
    if final is not None and not evidence and gate_worthy(final):
        # Mirror the app's verification gate: one structured decision whether a local tool should check the draft.
        decision = gate(request, final, data)
        if decision:
            success, result = run_tool(decision['tool'], decision.get('arguments') or {})
            rounds.append({'round': len(rounds) + 1, 'gate': True, 'tool': decision['tool'], 'arguments': decision.get('arguments'), 'success': success, 'result': result})
            evidence.append({'tool': decision['tool'], 'success': success, 'verification': 'VERIFIED' if success else 'FAILED', 'result': result})
            if success:
                note, _ = llm.generate(f"원래 사용자 요청:\n{request}\n이미 사용자에게 보인 답변 초안을 도구로 다시 확인했다. "
                                       f"도구 실행 결과(인용 자료): {json.dumps(evidence, ensure_ascii=False)}\n"
                                       "초안을 반복하지 말고 '검증:'으로 시작하는 짧은 확인 결과만 한국어로 덧붙여라. 값이 다르면 바로잡은 값과 이유를 쓴다. 새 도구는 부르지 않는다.",
                                       system, 400, history=[{'role': 'user', 'content': request}, {'role': 'assistant', 'content': final}])
                final = final.rstrip() + '\n\n' + note
    return {'id': case['id'], 'request': request, 'expect': case.get('expect'), 'rounds': rounds, 'final': final,
            'tools': [e['tool'] for e in evidence], 'finished': final is not None}


def show(result):
    state = '완료' if result['finished'] else '판단 한도 도달'
    print(f"\n=== {result['id']} · 도구 {result['tools'] or '없음'} · {state}")
    print('요청:', result['request'])
    if result.get('expect'):
        print('기대:', result['expect'])
    for item in result['rounds']:
        if item.get('tool'):
            print(f"  [{item['round']}] {item['tool']} {json.dumps(item['arguments'], ensure_ascii=False)}"
                  f" -> {json.dumps(item['result'], ensure_ascii=False)}")
    print('답변:', (result['final'] or '(없음)').strip())


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    arguments = sys.argv[1:]
    if not arguments:
        sys.exit(__doc__)
    data, system = prompts()
    if arguments[0] == 'run':
        cases = json.loads(Path(arguments[1]).read_text(encoding='utf-8'))
        only = arguments[arguments.index('--only') + 1] if '--only' in arguments else None
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
        out = Path(arguments[arguments.index('--out') + 1]) if '--out' in arguments else ROOT / '.local' / 'eval-loop' / stamp
        out.mkdir(parents=True, exist_ok=True)
        for case in cases:
            if only and case['id'] != only:
                continue
            result = run_case(case, data, system)
            show(result)
            (out / f"{case['id']}.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print('\n결과 폴더:', out)
    elif arguments[0] == 'feedback':
        folder = Path(arguments[1])
        previous = json.loads((folder / f'{arguments[2]}.json').read_text(encoding='utf-8'))
        history = [{'role': 'user', 'content': previous['request']}, {'role': 'assistant', 'content': previous['final'] or ''}]
        case = {'id': previous['id'] + '-fb', 'request': previous['request'], 'expect': previous.get('expect')}
        result = run_case(case, data, system, history=history, first_query=arguments[3])
        result['feedback'] = arguments[3]
        show(result)
        (folder / f"{case['id']}.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
