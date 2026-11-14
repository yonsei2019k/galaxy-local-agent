"""Summarize recorded scenario transcripts without model calls or rewriting original results."""
import collections
import argparse
import hashlib
import json
import sys
from pathlib import Path


def summarize(folder, cases_path):
    cases = json.loads(cases_path.read_text(encoding='utf-8'))
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['cases_sha256'] != hashlib.sha256(cases_path.read_bytes()).hexdigest():
        raise ValueError('CASE_REVISION_MISMATCH')
    if 'selected_ids' in manifest:
        cases = [case for case in cases if case['id'] in manifest['selected_ids']]
    rows, missing = [], []
    for case in cases:
        path = folder / (case['id'] + '.json')
        if not path.is_file():
            missing.append(case['id'])
            continue
        result = json.loads(path.read_text(encoding='utf-8'))
        rating = result['score']
        kind = 'gap_acknowledged' if case.get('gap') else 'blocked_by_core_guard' if case.get('blocked') else 'cancellation_interpreted' if case.get('expected_actions')==['CANCEL'] else 'read_only_response' if case.get('readOnly') else 'awaiting_owner_choice' if case.get('clarification') else 'confirmation_pending' if case.get('prepared') else 'synthetic_completed'
        unavailable = result.get('error') in {'PC_BUSY', 'COMPUTE_OWNER_MISMATCH', 'UNREACHABLE', 'MODEL_MISSING'}
        rows.append({'id': case['id'], 'passed': rating['passed'], 'observed_outcome': 'not_evaluated' if unavailable else kind if rating['passed'] else 'failed',
                     'failed_checks': [key for key, value in rating['checks'].items() if not value],
                     'error': result.get('error'), 'seconds': result['seconds'],
                     'planner_attempts': len([r for r in result['rounds'] if r['stage'] == 'planner']),
                     'execution_calls': len(result['steps'])})
    return {'scope': 'real_model_synthetic_core_integration_not_device_workflow', 'expected': len(cases),
            'recorded': len(rows), 'evaluated': sum(r['observed_outcome']!='not_evaluated' for r in rows), 'missing': missing, 'passed': sum(r['passed'] for r in rows),
            'outcomes': dict(collections.Counter(r['observed_outcome'] for r in rows)), 'cases': rows}


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder',type=Path)
    parser.add_argument('--cases',type=Path,default=Path(__file__).with_name('scenarios.json'))
    args=parser.parse_args()
    folder = args.folder
    report = summarize(folder,args.cases)
    (folder / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
