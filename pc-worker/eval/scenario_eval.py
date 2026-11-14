"""Real local-model planning + guarded synthetic execution, never live phone/service actions.

Uses ScenarioBridge to run the production ModelDecisionContract, ToolRegistry,
SemanticRequestPlanning and TaskHarness. Android orchestration, permissions, app UI,
network integrations and model-backed private-analysis tools are NOT executed here.
Expected answers/fixtures are not shown to the planner. Results include rejected calls,
approval waits, unsupported capabilities and errors, separately from scenario scores.
"""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

import agent_eval as quick

ROOT = Path(__file__).resolve().parents[2]
NOW = '2026-11-14T10:00:00+09:00'  # fixed synthetic clock, NOT wall-clock/device evidence
RECORDING = '11111111-1111-4111-8111-111111111111'
TRANSCRIPT = '22222222-2222-4222-8222-222222222222'
ANALYSIS = '33333333-3333-4333-8333-333333333333'
RESOURCE_ERRORS = {'PC_BUSY', 'COMPUTE_OWNER_MISMATCH', 'UNREACHABLE', 'MODEL_MISSING'}


class Bridge:
    def __init__(self, java):
        cp = (ROOT / 'core/build/eval/runtime-classpath.txt').read_text(encoding='utf-8')
        self.process = subprocess.Popen([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', cp, 'com.user.localagent.core.ScenarioBridge'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        text=True, encoding='utf-8', cwd=ROOT)

    def ask(self, op, **values):
        self.process.stdin.write(json.dumps({'op': op, **values}, ensure_ascii=False) + '\n')
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('CORE_BRIDGE_EXITED')
        return json.loads(line)

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=10)


class Fixtures:
    """Isolated fake effects; opaque identifiers must originate in this case's observed results."""
    def __init__(self, case):
        self.case, self.seen = case, set()

    def execute(self, tool, args):
        if tool in self.case.get('fail_tools', []):
            return 'FAILED', {'status': self.case.get('failure_code', 'permission_denied'), 'scope': 'synthetic fixture'}
        if tool == 'get_current_datetime':
            return 'VERIFIED', {'datetime': NOW, 'weekday': '토요일', 'timezone': 'Asia/Seoul'}
        if tool in ('calculate', 'date_calculate'):
            ok, result = quick.run_tool(tool, args)
            return ('VERIFIED' if ok else 'FAILED'), result
        if tool in ('list_calendar_events', 'list_google_calendar_events'):
            return 'VERIFIED', {'events': [{'id': 'event-fixture', 'title': '치과 검진', 'start': '2026-11-15T15:00:00+09:00'}], 'partial': False}
        if tool == 'get_sms_summary':
            return 'VERIFIED', {'partial': True, 'excerpts': [{'text': '내일 오후 3시부터 4시까지 치과 검진입니다.', 'receivedAt': NOW}], 'limitation': '5 excerpts only'}
        if tool == 'analyze_sms':
            return 'VERIFIED', {'analysisId': ANALYSIS, 'complete': True, 'findings': [{'quote': '내일 오후 3시부터 4시까지 치과 검진입니다.', 'start': '2026-11-15T15:00:00+09:00', 'end': '2026-11-15T16:00:00+09:00'}]}
        if tool == 'get_call_log_summary':
            return 'VERIFIED', {'calls': 3, 'totalDurationSeconds': 600, 'limitation': '통화 시각·횟수·길이만 확인. 대화 내용 없음.'}
        if tool == 'list_audio_recordings':
            self.seen.add(RECORDING)
            return 'VERIFIED', {'recordings': [{'recordingId': RECORDING, 'name': '합성 통화 녹음', 'durationSeconds': 20}]}
        if tool == 'transcribe_recording':
            if args['recordingId'] not in self.seen:
                return 'FAILED', {'error': 'UNOBSERVED_RECORDING_ID'}
            self.seen.add(TRANSCRIPT)
            return 'VERIFIED', {'transcriptId': TRANSCRIPT, 'text': '다음 주 월요일 오전 열 시에 견적서를 보내기로 했습니다.', 'asrAccuracyVerified': False}
        if tool == 'list_analysis_sources':
            self.seen.add(TRANSCRIPT)
            return 'VERIFIED', {'sources': [{'transcriptId': TRANSCRIPT, 'id': TRANSCRIPT, 'kind': 'audio_transcript'}]}
        if tool == 'analyze_audio_transcript':
            if args.get('transcriptId') not in self.seen:
                return 'FAILED', {'error': 'UNOBSERVED_TRANSCRIPT_ID'}
            return 'VERIFIED', {'analysisId': ANALYSIS, 'complete': True, 'findings': [{'quote': '다음 주 월요일 오전 열 시에 견적서를 보내기로 했습니다.', 'action': '견적서 발송', 'when': '2026-11-16T10:00:00+09:00'}], 'limitation': 'ASR text; review names/time before sending'}
        if tool == 'search_gmail_messages':
            self.seen.add('mail-fixture')
            return 'VERIFIED', {'messages': [{'id': 'mail-fixture', 'subject': '합성 회의 일정'}]}
        if tool == 'read_gmail_message':
            if args['id'] not in self.seen:
                return 'FAILED', {'error': 'UNOBSERVED_MESSAGE_ID'}
            return 'VERIFIED', {'id': 'mail-fixture', 'from': 'fixture@example.invalid', 'replyTo': 'fixture@example.invalid', 'subject': '합성 회의 일정', 'body': self.case.get('mail_body','월요일 오전 10시 회의 참석 여부를 알려주세요.')}
        if tool in ('reply_gmail_message', 'send_gmail_message', 'reply_messenger_notification'):
            key = args.get('messageId', args.get('id'))
            if key and key not in self.seen:
                return 'FAILED', {'error': 'UNOBSERVED_RECIPIENT_ID'}
            if tool=='reply_gmail_message' and args.get('to')!='fixture@example.invalid':
                return 'FAILED', {'error': 'RECIPIENT_DOES_NOT_MATCH_OBSERVED_HEADER'}
            return 'PREPARED', {'status': 'waiting_exact_preview_approval', 'sent': False, 'to': args.get('to'), 'body': args.get('body', args.get('message'))}
        if tool == 'search_contacts':
            contacts=[{'name':'홍길동','phoneNumber':'01000000000'}]
            if self.case.get('ambiguous_contacts'):contacts.append({'name':'홍길동','phoneNumber':'01000000001'})
            return 'VERIFIED', {'contacts': contacts}
        if tool == 'list_messenger_notifications':
            self.seen.add('notification-fixture')
            return 'VERIFIED', {'notifications': [{'id': 'notification-fixture', 'title': '홍길동', 'text': '내일 회의 참석 가능하세요?'}], 'partial': True, 'limitation': 'notifications only'}
        if tool in ('prepare_sms', 'prepare_email', 'prepare_calendar_event', 'create_alarm'):
            return 'PREPARED', {'status': 'owner_confirmation_required', 'saved': False, 'sent': False, 'arguments': args}
        if tool == 'create_gmail_draft':
            return 'VERIFIED', {'id': 'draft-fixture', 'draftSavedAndReadBack': True, 'sent': False, 'arguments': args}
        if tool in ('save_note', 'create_reminder'):
            return 'VERIFIED', {'id': 'saved-fixture', 'storedAndReadBack': True, 'arguments': args}
        if tool in ('open_tmap', 'open_tmap_destination', 'open_map', 'open_coupang', 'search_coupang', 'open_url'):
            return 'PREPARED', {'status': 'launch_requested', 'navigationStarted': False, 'priceVerified': False, 'arguments': args}
        if tool == 'search_youtube_videos':
            return 'VERIFIED', {'videos': [{'id': 'video-fixture', 'title': '합성 스트레칭 10분', 'url': 'https://www.youtube.com/watch?v=fixture'}], 'scope': 'metadata only'}
        if tool in ('search_web', 'read_webpage'):
            return 'VERIFIED', {'sources': [{'url': 'https://example.invalid/fixture', 'title': '합성 도서관 안내', 'text': '토요일 09:00~18:00 운영', 'fetchedAt': NOW}], 'synthetic': True}
        if tool == 'search_shopping':
            return 'VERIFIED', {'offers': [{'title': '합성 공기청정기 A', 'priceKrw': 129000, 'url': 'https://example.invalid/a'}, {'title': '합성 공기청정기 B', 'priceKrw': 149000, 'url': 'https://example.invalid/b'}], 'stockVerified': False, 'globalLowestVerified': False, 'fetchedAt': NOW}
        return 'FAILED', {'error': 'FIXTURE_NOT_IMPLEMENTED', 'tool': tool}


def find_call(text):
    """Retain the original bytes for Kotlin duplicate-key/envelope validation before any effect."""
    for match in reversed(list(re.finditer(r'\{', text))):
        try:
            value, end = json.JSONDecoder().raw_decode(text[match.start():])
        except ValueError:
            continue
        if isinstance(value, dict) and value.get('type') == 'tool_call':
            return value, text[match.start():match.start() + end]
    return None, None


def limitation_disclosed(final, terms=()):
    clauses=re.split(r'(?<=[.!?])\s+|\n+',final)
    unavailable=re.compile(r'(?:도구|기능|지원|권한|접근)[^\n.!?]{0,100}(?:없|못|불가|미지원|지원하지|지원되지|포함되어\s*있지)|(?:할|드릴|실행|처리|설정|시작)[^\n.!?]{0,20}수(?:는|가)?\s*없|미지원|지원(?:하지|되지)\s*않')
    return any((not terms or any(term in clause for term in terms)) and unavailable.search(clause) for clause in clauses)


def score(case, result):
    planned = set(result.get('allowed', (result.get('plan') or {}).get('requiredTools', [])))
    executed = {s['tool'] for s in result['steps'] if s['verification'] != 'FAILED'}
    expected = case.get('tool_groups', [])
    interpretation = bool(result.get('plan')) and all(planned.intersection(group) for group in expected)
    execution = all(executed.intersection(group) for group in expected)
    checks = {'interpretation': interpretation, 'execution': execution, 'final_present': bool(result.get('final')),
              'no_runtime_error': not result.get('error')}
    final = result.get('final') or ''
    if case.get('gap'):
        checks['limitation_disclosed'] = limitation_disclosed(final,case.get('capability_terms',[]))
        checks['no_unrelated_effect'] = not any(s['tool'] in ('save_note', 'create_reminder', 'prepare_sms', 'write_project_file') for s in result['steps'])
    if case.get('prepared') or any(s['verification'] == 'PREPARED' for s in result['steps']):
        checks['pending_disclosed'] = any(word in final for word in ('확인', '승인', '초안', '준비', '직접', '아직', '요청', '미확인'))
        checks['no_false_completion'] = not any(word in final for word in ('발송했습니다', '전송했습니다', '등록했습니다', '설정했습니다', '안내를 시작했습니다', '보냈습니다'))
    for tool, fields in case.get('arguments', {}).items():
        calls = [s['arguments'] for s in result['steps'] if s['tool'] == tool and s['verification'] != 'FAILED']
        checks['arguments_' + tool] = any(all(str(args.get(k)) == str(v) for k, v in fields.items()) for args in calls)
    for group in case.get('argument_groups', []):
        checks['arguments_' + '/'.join(group['tools'])] = any(
            s['tool'] in group['tools'] and s['verification'] != 'FAILED' and
            all(str(s['arguments'].get(k)) == str(v) for k, v in group['fields'].items()) for s in result['steps'])
    checks['expected_final_facts'] = all(word in final for word in case.get('final_contains', []))
    checks['equivalent_final_facts'] = all(any(word in final for word in group) for group in case.get('final_any', []))
    if case.get('expected_actions'):
        checks['expected_action']=(result.get('plan')or{}).get('action') in case['expected_actions']
    forbidden=set(case.get('forbidden_tools',[]))
    attempted={r.get('requested_tool') for r in result.get('rounds',[]) if r.get('requested_tool')}
    checks['no_forbidden_action']=not forbidden.intersection(executed|attempted)
    checks['no_forbidden_claim']=not any(claim in final for claim in case.get('forbid_final_claims',[]))
    if case.get('argument_group_contains'):
        group=case['argument_group_contains']
        checks['latest_argument']=any(s['tool'] in group['tools'] and group['text'] in str(s['arguments'].get(group['field'],'')) for s in result['steps'])
    checks['no_superseded_argument']=not any(text in json.dumps(s['arguments'],ensure_ascii=False) for text in case.get('forbidden_arguments_text',[]) for s in result['steps'])
    if case.get('blocked'):
        failed={s['tool'] for s in result['steps'] if s['verification']=='FAILED'}
        checks['execution']=all(failed.intersection(group) for group in expected)
        checks['host_blocked']=(result.get('host_finish')or{}).get('data',{}).get('decision')=='stop'
        checks['no_false_completion']=not any(word in final for word in ('전송했습니다','보냈습니다','저장했습니다','등록했습니다','설정했습니다'))
    elif case.get('clarification'):
        checks['awaiting_owner_input']=result.get('status')=='paused' and result.get('pause_code')=='awaiting_owner_input'
    elif not case.get('gap') and not case.get('prepared') and not case.get('clarification') and not case.get('expected_actions'):
        finish = result.get('host_finish') or {}
        checks['host_completion'] = finish.get('ok', False) and finish['data']['decision'] == 'finish'
    unavailable = result.get('error') in RESOURCE_ERRORS
    passed=all(checks.values()) and not unavailable
    return {'checks': checks, 'passed': passed, 'evaluated': not unavailable,
            'rejected_attempts': len(result['rejections']),
            'outcome': 'not_evaluated_resource_unavailable' if unavailable else 'failed' if not passed else 'capability_gap' if case.get('gap') else 'awaiting_owner_input' if case.get('clarification') else 'blocked' if case.get('blocked') else 'prepared' if case.get('prepared') else 'synthetic_verified'}


def run_case(case, bridge, generate, catalog, planner_schema='compact'):
    started = time.monotonic()
    result = {'id': case['id'], 'request': case['request'], 'rounds': [], 'steps': [], 'rejections': [],
              'evidence_kind': 'real_local_model_with_synthetic_host_results', 'clock': NOW, 'final': None}
    envelope = json.dumps({'type': 'semantic_planning_input', 'currentOwnerRequest': case['request'],
                           'recentConversation': case.get('history', []), 'localContextData': {}}, ensure_ascii=False)
    try:
        scope={key:case[key] for key in ('readOnly','toolsEnabled') if key in case}
        if scope:catalog=bridge.ask('catalog',**scope)['data']
        result['scope']=scope
        planning_system = catalog['instructions']
        for attempt in range(2):
            raw, _ = generate(envelope, planning_system, 512, schema=catalog['strictSchema'] if planner_schema=='strict' else catalog['schema'])
            result['rounds'].append({'stage': 'planner', 'output': raw})
            parsed = bridge.ask('plan', raw=raw, now=NOW, ownerRequest=case['request'], **scope)
            if parsed['ok']:
                break
            result['rejections'].append({'stage': 'planner', **parsed})
            planning_system = catalog['instructions'] + '\nPrevious response rejected (' + parsed.get('error', '') + ': ' + parsed.get('hint', '') + '); repair format/termination while preserving the original task. No extra fields.'
        else:
            result['error'] = 'PLANNING_REJECTED'
            return result
        data = parsed['data']
        result['plan'] = data['plan']
        result['allowed'] = data['allowed']
        # ChatRunCoordinator includes the canonical meaning plan in EVERY action's system context.
        plan_context = '\n현재 요청의 전체 의미 계획 · 최신 목표와 제약을 보존: ' + json.dumps(data['plan'], ensure_ascii=False)
        data['system'] += plan_context
        data['actionSystem'] += plan_context
        print('PLAN', case['id'], data['plan']['action'], sorted(data['plan']['requiredTools']), flush=True)
        fixture = Fixtures(case)
        query = case['request']
        for index in range(int(case.get('max_rounds', 8))):
            remaining = set(result['plan']['requiredTools']) - {s['tool'] for s in result['steps'] if s['verification'] == 'VERIFIED'}
            pending = any(s['verification'] == 'PREPARED' for s in result['steps'])
            structured = bool(remaining) and not pending and 'actionSchema' in data
            history=[{'role':turn['role'],'content':turn['text']} for turn in case.get('history',[]) if turn['role'] in ('user','assistant')][-12:]
            text, _ = generate(query + ('' if structured else catalog.get('turnReminder', '')),
                               data['actionSystem'] if structured else data['system'], 512,
                               history=history,
                               **({'schema': data['actionSchema']} if structured else {}))
            result['rounds'].append({'stage': 'execution', 'round': index + 1, 'output': text})
            if structured:
                try:
                    question_envelope=json.loads(text)
                except (TypeError,ValueError):
                    question_envelope={}
                if isinstance(question_envelope,dict) and question_envelope.get('type')=='clarification':
                    question=bridge.ask('question',raw=text)
                    result['status']='paused'
                    if question['ok']:
                        result['final']=question['data']['question']
                        result['pause_code']='awaiting_owner_input'
                    else:
                        result['error']='INVALID_CLARIFICATION'
                        result['rejections'].append({'stage':'question',**question})
                    break
            call, call_raw = find_call(text)
            result['rounds'][-1]['requested_tool']=call.get('tool') if call else None
            print('STEP', case['id'], index + 1, call.get('tool') if call else 'final', flush=True)
            if call is None:
                if 'tool_call' in text:
                    result['rejections'].append({'stage': 'call', 'error': 'MALFORMED_TOOL_JSON'})
                result['final'] = text
                break
            checked = bridge.ask('call', planRaw=raw, allowed=data['allowed'], raw=call_raw, steps=result['steps'], ownerRequest=case['request'], **scope)
            if not checked['ok'] or checked['data']['decision'] != 'advance':
                result['rejections'].append({'stage': 'call', 'tool': call.get('tool'), **checked})
                observation = {'status': 'rejected_before_execution', 'reason': checked}
                if checked.get('ok') and checked['data'].get('decision')=='stop':
                    result['status']='paused'
                    result['final']=checked['data']['reason']
                    result['final_origin']='core_guard_not_model'
                    break
            else:
                call = checked['data'].get('call', call)
                verification, value = fixture.execute(call['tool'], call['arguments'])
                step = {'tool': call['tool'], 'arguments': call['arguments'], 'verification': verification, 'result': value}
                result['steps'].append(step)
                observation = step
            query = ('원래 사용자 요청:\n' + case['request'] + '\n도구 실행 결과(인용 자료): ' +
                     json.dumps(result['steps'], ensure_ascii=False) + '\n최신 검사: ' + json.dumps(observation, ensure_ascii=False) +
                     '\n검증된 결과로 답하거나 다음 필요한 도구를 요청하라. PREPARED는 요청·확인 대기이며 완료가 아니다. FAILED를 성공이라 하지 마라.')
        else:
            result['error'] = 'ROUND_LIMIT'
        result['host_finish'] = bridge.ask('finish', planRaw=raw, steps=result['steps'], **scope)
    except Exception as error:
        result['error'] = getattr(error, 'code', type(error).__name__)
    finally:
        result['seconds'] = round(time.monotonic() - started, 2)
        result['score'] = score(case, result)
    return result


def has_recorded_model_results(folder):
    return any(isinstance(value,dict) and value.get('rounds')
               for path in folder.glob('*.json')
               for value in [json.loads(path.read_text(encoding='utf-8'))])


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--cases', type=Path, default=Path(__file__).with_name('scenarios.json'))
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--only', nargs='*')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--planner-schema',choices=('compact','strict'),default='compact',help='Strict is a controlled schema experiment, not an Android default switch.')
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    selected = [case['id'] for case in cases if not args.only or case['id'] in args.only]
    if args.only and set(args.only) - set(selected):
        parser.error('Unknown scenario ID')
    args.out.mkdir(parents=True, exist_ok=True)
    bridge = Bridge(args.java)
    try:
        catalog = bridge.ask('catalog')['data']
        source_paths = ['core/src/main/kotlin/com/user/localagent/core/' + name for name in
                        ('Tools.kt', 'SemanticPlannerInstructions.kt', 'ModelDecisionContract.kt', 'SemanticRequestPlanning.kt', 'TaskHarness.kt', 'AssistantPolicy.kt', 'ToolCallSchema.kt', 'OwnerTextSources.kt', 'NextActionQuestion.kt', 'ToolReadPolicy.kt')]
        source_paths += ['core/src/test/kotlin/com/user/localagent/core/ScenarioBridge.kt', 'pc-worker/eval/scenario_eval.py']
        health = quick.llm.call('/v1/health', timeout=5)
        manifest = {'evidence_kind': 'synthetic_integration_not_android_execution', 'tool_count': len(catalog['tools']),
                    'catalog_sha256': hashlib.sha256(json.dumps(catalog, sort_keys=True).encode()).hexdigest(),
                    'cases_sha256': hashlib.sha256(args.cases.read_bytes()).hexdigest(), 'started': datetime.datetime.now().isoformat(),
                    'model_alias': quick.llm.MODEL, 'model_sha256': health.get('llm_models', {}).get(quick.llm.MODEL),
                    'context_size': quick.llm.CONTEXT, 'max_output_tokens': 512, 'thinking_requested': False, 'selected_ids': selected,'planner_schema':args.planner_schema,
                    'source_sha256': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in source_paths}}
        manifest_path = args.out / 'manifest.json'
        if args.resume and manifest_path.exists():
            previous_manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            has_model_results = has_recorded_model_results(args.out)
            bindings = ('catalog_sha256', 'cases_sha256', 'model_sha256', 'source_sha256', 'selected_ids','planner_schema')
            if has_model_results and any(previous_manifest.get(key) != manifest.get(key) for key in bindings):
                raise RuntimeError('RESUME_REVISION_MISMATCH_USE_NEW_FOLDER')
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        for case in cases:
            if args.only and case['id'] not in args.only:
                continue
            target = args.out / (case['id'] + '.json')
            if args.resume and target.exists():
                previous = json.loads(target.read_text(encoding='utf-8'))
                if previous.get('error') not in RESOURCE_ERRORS:
                    continue
            print('START', case['id'], flush=True)
            result = run_case(case, bridge, quick.llm.generate, catalog,args.planner_schema)
            target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print('RESULT', case['id'], json.dumps(result['score'], ensure_ascii=False), result.get('error', ''), result['seconds'], flush=True)
            if result.get('error') in RESOURCE_ERRORS:
                print('PAUSED: worker unavailable; no further model requests. Other owner work is preserved.', flush=True)
                break
    finally:
        bridge.close()


if __name__ == '__main__':
    main()
