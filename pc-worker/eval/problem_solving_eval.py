"""Problem-solving evaluation with stateful synthetic effects and separate outcome classes.

The real local model and production core validator may run, but no Android/provider,
account, external application or real reminder delivery is exercised by this module.
Positive completion requires an observed host finish AND the requested final state.
Clarification/refusal never counts as a positively completed task.
"""
import argparse
import copy
import datetime
import hashlib
import json
from pathlib import Path

import scenario_eval as base


class ProblemFixtures(base.Fixtures):
    """Each case owns its own records; IDs and edit hashes must be observed first."""
    def __init__(self, case):
        super().__init__(case)
        self.notes = {item['id']: copy.deepcopy(item) for item in case.get('initial_notes', [])}
        self.reminders = {item['id']: copy.deepcopy(item) for item in case.get('initial_reminders', [])}
        self.files = copy.deepcopy(case.get('initial_files', {}))
        self.memories = {}
        self.documents = []
        self.reads = {}
        self.counts = {}
        self.mutations = []
        self.next_id = 0

    def identifier(self, kind):
        self.next_id += 1
        return 'qa-' + kind + '-' + str(self.next_id)

    @staticmethod
    def digest(text):
        return hashlib.sha256(text.encode('utf-8')).hexdigest()

    @staticmethod
    def safe_path(value):
        parts = value.split('/')
        return bool(value and not value.startswith('/') and '\\' not in value and
                    ':' not in value and '\x00' not in value and all(part not in ('', '.', '..') for part in parts))

    def execute(self, tool, args):
        self.counts[tool] = self.counts.get(tool, 0) + 1
        if tool in self.case.get('fail_once_tools', {}) and self.counts[tool] == 1:
            return 'FAILED', {'error': self.case['fail_once_tools'][tool], 'executed': False, 'synthetic': True}
        if tool in self.case.get('fail_tools', []):
            return super().execute(tool, args)
        if tool == 'search_notes':
            items = [note for note in self.notes.values()
                     if args.get('query', '') in note.get('title', '') + note.get('content', '')
                     and ('scope' not in args or note.get('scope', 'default') == args['scope'])]
            items = items[:int(args.get('limit', 10))]
            self.seen.update(note['id'] for note in items)
            return 'VERIFIED', {'notes': copy.deepcopy(items), 'complete': True, 'synthetic': True}
        if tool == 'save_note':
            record = {'id': self.identifier('note'), 'title': args.get('title', '메모'),
                      'content': args.get('content', ''), 'scope': args.get('scope', 'default')}
            self.notes[record['id']] = record
            self.seen.add(record['id']); self.mutations.append(tool)
            return 'VERIFIED', {**record, 'storedAndReadBack': True, 'synthetic': True}
        if tool in ('update_note', 'delete_note'):
            record = self.notes.get(args.get('id'))
            if not record or record['id'] not in self.seen:
                return 'FAILED', {'error': 'UNOBSERVED_NOTE_ID', 'executed': False}
            if tool == 'update_note':
                if args.get('scope') != record.get('scope', 'default'):
                    return 'FAILED', {'error': 'NOTE_SCOPE_MISMATCH', 'executed': False}
                record.update(content=args['content'], title=args.get('title', record['title']))
            else:
                del self.notes[record['id']]
            self.mutations.append(tool)
            return 'VERIFIED', {'id': record['id'], 'storedAndReadBack': True, 'synthetic': True}
        if tool == 'list_reminders':
            items = [item for item in self.reminders.values()
                     if ('scope' not in args or args['scope'] == item.get('scope', 'default'))
                     and (args.get('includeCompleted', False) or item.get('status', 'pending') == 'pending')]
            items = items[:int(args.get('limit', 10))]
            self.seen.update(item['id'] for item in items)
            return 'VERIFIED', {'reminders': copy.deepcopy(items), 'synthetic': True}
        if tool in ('create_reminder', 'update_reminder', 'delete_reminder'):
            if tool == 'create_reminder':
                if datetime.datetime.fromisoformat(args['datetime']) <= datetime.datetime.fromisoformat(base.NOW):
                    return 'FAILED', {'error': 'PAST_DATETIME', 'executed': False}
                record = {'id': self.identifier('reminder'), 'title': args['title'], 'datetime': args['datetime'],
                          'scope': args.get('scope', 'default'), 'status': 'pending'}
                self.reminders[record['id']] = record; self.seen.add(record['id'])
            else:
                record = self.reminders.get(args.get('id'))
                if not record or record['id'] not in self.seen:
                    return 'FAILED', {'error': 'UNOBSERVED_REMINDER_ID', 'executed': False}
                if tool == 'update_reminder':
                    if args.get('scope') != record.get('scope', 'default'):
                        return 'FAILED', {'error': 'REMINDER_SCOPE_MISMATCH', 'executed': False}
                    record.update({key: args[key] for key in ('title', 'datetime') if key in args})
                else:
                    del self.reminders[record['id']]
            self.mutations.append(tool)
            return 'VERIFIED', {**record, 'queueReadBack': True, 'deliveryVerified': False, 'synthetic': True}
        if tool in ('list_project_files', 'read_project_file', 'write_project_file'):
            path = args.get('path', '')
            if path and not self.safe_path(path):
                return 'FAILED', {'error': 'OUTSIDE_PROJECT', 'executed': False}
            if tool == 'list_project_files':
                return 'VERIFIED', {'entries': [{'path': name, 'directory': False} for name in self.files
                                               if not path or name.startswith(path.rstrip('/') + '/')], 'synthetic': True}
            if tool == 'read_project_file':
                if path not in self.files:
                    return 'FAILED', {'error': 'FILE_NOT_FOUND', 'executed': False}
                text = self.files[path]; digest = self.digest(text); self.reads[path] = digest
                result = {'path': path, 'text': text, 'sha256': digest, 'truncated': False, 'synthetic': True}
                replacement = self.case.get('concurrent_change_after_first_read', {})
                if path in replacement and self.counts[tool] == 1:
                    self.files[path] = replacement[path]
                return 'VERIFIED', result
            if path in self.files:
                digest = self.digest(self.files[path])
                if args.get('expectedSha256') != digest or self.reads.get(path) != digest:
                    return 'FAILED', {'error': 'STALE_OR_UNOBSERVED_SHA256', 'executed': False}
            elif 'expectedSha256' in args:
                return 'FAILED', {'error': 'NO_EXISTING_FILE', 'executed': False}
            self.files[path] = args['content']; self.mutations.append(tool)
            return 'VERIFIED', {'path': path, 'sha256': self.digest(args['content']), 'readBack': True, 'synthetic': True}
        if tool == 'save_memory':
            item = {'id': self.identifier('memory'), 'content': args['content'], 'kind': args.get('kind', 'fact')}
            self.memories[item['id']] = item; self.seen.add(item['id']); self.mutations.append(tool)
            return 'VERIFIED', {**item, 'storedAndReadBack': True, 'synthetic': True}
        if tool == 'search_memories':
            items = [item for item in self.memories.values() if args['query'] in item['content']]
            return 'VERIFIED', {'memories': copy.deepcopy(items), 'synthetic': True}
        if tool == 'create_document':
            item = {'format': args['format'], 'title': args['title'], 'content': args['content']}
            self.documents.append(item); self.mutations.append(tool)
            return 'VERIFIED', {**item, 'privateArtifactVerified': True, 'exported': False, 'synthetic': True}
        return super().execute(tool, args)

    def snapshot(self):
        return copy.deepcopy({'notes': list(self.notes.values()), 'reminders': list(self.reminders.values()),
                              'files': self.files, 'memories': list(self.memories.values()),
                              'documents': self.documents, 'mutations': self.mutations})


def score_problem(case, result):
    rating = base.score(case, result)
    checks = rating['checks']
    state = result.get('synthetic_state', {})
    for index, rule in enumerate(case.get('state_checks', [])):
        collection = state.get(rule['collection'], [])
        if isinstance(collection, dict):
            checks['state_' + str(index)] = collection.get(rule['key']) == rule['value']
        else:
            matches = [item for item in collection if all(item.get(key) == value for key, value in rule.get('match', {}).items())]
            checks['state_' + str(index)] = len(matches) == rule.get('count', 1)
            if 'contains' in rule:
                checks['state_contains_' + str(index)] = any(rule['contains'] in item.get(rule.get('field', 'content'), '') for item in matches)
    if case.get('no_mutation'):
        checks['no_state_mutation'] = not state.get('mutations')
    if case.get('required_sequence'):
        observed = [step['tool'] for step in result['steps'] if step['verification'] == 'VERIFIED']
        remaining = iter(observed)
        checks['required_sequence'] = all(any(tool == wanted for tool in remaining) for wanted in case['required_sequence'])
    for index, rule in enumerate(case.get('observation_checks', [])):
        checks['observation_' + str(index)] = any(
            step['tool'] == rule['tool'] and step['verification'] == rule.get('verification', 'VERIFIED') and
            rule['contains'] in json.dumps(step.get('result'), ensure_ascii=False)
            for step in result['steps'])
    positive = case.get('objective', 'positive') == 'positive'
    if positive:
        checks['positive_completion_not_pause'] = (result.get('host_finish') or {}).get('data', {}).get('decision') == 'finish' and result.get('status') != 'paused'
    passed = all(checks.values()) and rating['evaluated']
    rating.update(passed=passed, objective=case.get('objective', 'positive'),
                  outcome=('not_evaluated_resource_unavailable' if not rating['evaluated'] else 'failed' if not passed else
                           'positive_synthetic_completion' if positive else 'prepared_awaiting_owner' if case.get('prepared') else 'safe_boundary'))
    return rating


def run_problem(case, bridge, generate, catalog, planner_schema='compact'):
    fixtures = []
    def factory(value):
        fixture = ProblemFixtures(value); fixtures.append(fixture); return fixture
    result = base.run_case(case, bridge, generate, catalog, planner_schema, fixture_factory=factory)
    result['synthetic_state'] = fixtures[0].snapshot() if fixtures else {}
    result['evidence_kind'] = 'real_local_model_with_stateful_synthetic_host_results_not_android'
    result['score'] = score_problem(case, result)
    return result


def summarize(results):
    return {objective: {'total': len(rows := [item for item in results if item['score']['objective'] == objective]),
                        'evaluated': sum(item['score']['evaluated'] for item in rows),
                        'passed': sum(item['score']['passed'] for item in rows)}
            for objective in ('positive', 'boundary', 'prepared')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--cases', type=Path, default=Path(__file__).with_name('problem-solving-scenarios.json'))
    parser.add_argument('--out', type=Path, required=True, help='A new private directory under this repository\'s ignored .local folder')
    parser.add_argument('--only', nargs='*')
    parser.add_argument('--planner-schema', choices=('compact', 'strict'), default='compact')
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    selected = [case for case in cases if not args.only or case['id'] in args.only]
    if not selected or args.only and set(args.only) - {case['id'] for case in selected}:
        parser.error('Unknown or empty scenario selection')
    if not args.out.resolve().is_relative_to((base.ROOT / '.local').resolve()):
        parser.error('Runtime model output must stay under the ignored repository .local folder')
    args.out.mkdir(parents=True, exist_ok=True)
    if any(args.out.glob('*.json')):
        parser.error('Use a fresh output directory so model/source evidence cannot be mixed')
    bridge = base.Bridge(args.java)
    try:
        catalog = bridge.ask('catalog')['data']
        health = base.quick.llm.call('/v1/health', timeout=5)
        inputs = [Path(__file__), Path(base.__file__), args.cases]
        inputs += list((base.ROOT / 'core/src/main/kotlin').rglob('*.kt'))
        inputs.append(base.ROOT / 'core/src/test/kotlin/com/user/localagent/core/ScenarioBridge.kt')
        manifest = {'evidence_kind': 'synthetic_integration_not_android_execution',
                    'started': datetime.datetime.now().isoformat(), 'selected_ids': [case['id'] for case in selected],
                    'model_alias': base.quick.llm.MODEL, 'model_sha256': health.get('llm_models', {}).get(base.quick.llm.MODEL),
                    'context_size': base.quick.llm.CONTEXT, 'max_output_tokens': 512, 'thinking_requested': False,
                    'planner_schema': args.planner_schema, 'tool_count': len(catalog['tools']),
                    'catalog_sha256': hashlib.sha256(json.dumps(catalog, sort_keys=True).encode()).hexdigest(),
                    'source_sha256': {str(path.relative_to(base.ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}}
        (args.out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        results = []
        for case in selected:
            result = run_problem(case, bridge, base.quick.llm.generate, catalog, args.planner_schema)
            results.append(result)
            (args.out / (case['id'] + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            (args.out / 'summary.json').write_text(json.dumps(summarize(results), ensure_ascii=False, indent=2), encoding='utf-8')
            print('RESULT', case['id'], result['score']['outcome'], result.get('error', ''), flush=True)
            if result.get('error') in base.RESOURCE_ERRORS:
                break
    finally:
        bridge.close()


if __name__ == '__main__':
    main()
