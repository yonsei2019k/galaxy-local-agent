import json
import unittest
from pathlib import Path

from problem_solving_eval import ProblemFixtures, score_problem, summarize, run_problem


class ProblemSolvingEvaluationTests(unittest.TestCase):
    @staticmethod
    def result(tools=(), steps=(), state=None, finish='finish', status=None):
        return {'plan': {'action': 'TOOL_TASK', 'requiredTools': list(tools)}, 'allowed': list(tools),
                'steps': list(steps), 'rejections': [], 'rounds': [], 'final': '관찰한 결과입니다.',
                'host_finish': {'ok': True, 'data': {'decision': finish}},
                'synthetic_state': state or {}, 'status': status}

    def test_positive_pause_is_not_task_completion(self):
        case = {'objective': 'positive', 'tool_groups': [['search_notes']], 'no_mutation': True}
        result = self.result(['search_notes'], [{'tool': 'search_notes', 'arguments': {}, 'verification': 'VERIFIED'}],
                             {'mutations': []}, status='paused')
        self.assertFalse(score_problem(case, result)['passed'])

    def test_verified_tool_name_does_not_replace_requested_final_state(self):
        case = {'objective': 'positive', 'tool_groups': [['save_note']],
                'state_checks': [{'collection': 'notes', 'match': {'title': '정답', 'content': '정확한 원문'}}]}
        result = self.result(['save_note'], [{'tool': 'save_note', 'arguments': {}, 'verification': 'VERIFIED'}],
                             {'notes': [{'title': '정답', 'content': '바뀐 원문'}]})
        self.assertFalse(score_problem(case, result)['passed'])

    def test_success_and_safety_outcomes_remain_separate(self):
        positive = self.result(['search_notes'], [{'tool': 'search_notes', 'arguments': {}, 'verification': 'VERIFIED'}])
        positive['score'] = score_problem({'objective': 'positive', 'tool_groups': [['search_notes']]}, positive)
        boundary = self.result(status='paused', finish='stop')
        boundary['plan'] = {'action': 'ANSWER', 'requiredTools': []}
        boundary['score'] = score_problem({'objective': 'boundary', 'expected_actions': ['ANSWER'], 'no_mutation': True}, boundary)
        counts = summarize([positive, boundary])
        self.assertEqual(1, counts['positive']['passed'])
        self.assertEqual(1, counts['boundary']['passed'])
        self.assertEqual(0, counts['prepared']['total'])

    def test_prepared_sms_is_not_positive_completion(self):
        result = self.result(['prepare_sms'], [{'tool': 'prepare_sms', 'arguments': {}, 'verification': 'PREPARED'}], finish='stop')
        result['final'] = '초안 요청만 전달했고 발송은 확인되지 않았습니다.'
        rating = score_problem({'objective': 'prepared', 'prepared': True, 'tool_groups': [['prepare_sms']]}, result)
        self.assertTrue(rating['passed'])
        self.assertEqual('prepared_awaiting_owner', rating['outcome'])

    def test_unobserved_note_cannot_be_edited(self):
        fixture = ProblemFixtures({'initial_notes': [{'id': 'n', 'title': 'QA', 'content': '원문', 'scope': 'default'}]})
        self.assertEqual('FAILED', fixture.execute('update_note', {'id': 'n', 'scope': 'default', 'content': '변경'})[0])
        fixture.execute('search_notes', {'query': 'QA'})
        self.assertEqual('VERIFIED', fixture.execute('update_note', {'id': 'n', 'scope': 'default', 'content': '변경'})[0])
        self.assertEqual('변경', fixture.snapshot()['notes'][0]['content'])

    def test_created_note_id_can_be_used_without_guessing(self):
        fixture = ProblemFixtures({})
        state, note = fixture.execute('save_note', {'title': 'QA', 'content': '원문'})
        self.assertEqual('VERIFIED', state)
        self.assertEqual('VERIFIED', fixture.execute('update_note', {'id': note['id'], 'scope': 'default', 'content': '정정'})[0])
        self.assertEqual(1, len(fixture.notes))

    def test_reminder_requires_observation_and_preserves_datetime(self):
        fixture = ProblemFixtures({'initial_reminders': [{'id': 'r', 'title': 'QA', 'datetime': '2026-11-18T10:00:00+09:00', 'scope': 'default'}]})
        self.assertEqual('FAILED', fixture.execute('update_reminder', {'id': 'r', 'scope': 'default', 'title': '정정'})[0])
        fixture.execute('list_reminders', {})
        self.assertEqual('VERIFIED', fixture.execute('update_reminder', {'id': 'r', 'scope': 'default', 'title': '정정'})[0])
        self.assertEqual('2026-11-18T10:00:00+09:00', fixture.reminders['r']['datetime'])

    def test_stale_file_digest_requires_new_read_before_repair(self):
        fixture = ProblemFixtures({'initial_files': {'a.txt': '원문'}, 'concurrent_change_after_first_read': {'a.txt': '동시 수정'}})
        _, first = fixture.execute('read_project_file', {'path': 'a.txt'})
        self.assertEqual('FAILED', fixture.execute('write_project_file', {'path': 'a.txt', 'content': '잘못된 교체', 'expectedSha256': first['sha256']})[0])
        _, fresh = fixture.execute('read_project_file', {'path': 'a.txt'})
        self.assertEqual('VERIFIED', fixture.execute('write_project_file', {'path': 'a.txt', 'content': '동시 수정 보존', 'expectedSha256': fresh['sha256']})[0])
        self.assertEqual('동시 수정 보존', fixture.files['a.txt'])

    def test_bad_paths_and_unknown_tools_do_not_create_success(self):
        fixture = ProblemFixtures({})
        for path in ('../private.txt', '/private.txt', 'C:/private.txt', 'a\\b.txt'):
            self.assertEqual('FAILED', fixture.execute('write_project_file', {'path': path, 'content': 'x'})[0])
        self.assertEqual('FAILED', fixture.execute('delete_phone_files', {})[0])
        self.assertEqual({}, fixture.files)

    def test_failure_once_is_real_failed_step_without_state_mutation(self):
        fixture = ProblemFixtures({'fail_once_tools': {'search_notes': 'QUERY_TOO_BROAD'}})
        state, error = fixture.execute('search_notes', {'query': 'QA'})
        self.assertEqual('FAILED', state)
        self.assertFalse(error['executed'])
        self.assertEqual('VERIFIED', fixture.execute('search_notes', {'query': 'QA 제목 전체'})[0])
        self.assertEqual([], fixture.mutations)

    def test_required_sequence_counts_actual_verified_observations(self):
        case = {'objective': 'positive', 'tool_groups': [['search_notes'], ['update_note']],
                'required_sequence': ['search_notes', 'update_note']}
        steps = [{'tool': name, 'arguments': {}, 'verification': 'VERIFIED'} for name in ('update_note', 'search_notes')]
        result = self.result(['search_notes', 'update_note'], steps)
        self.assertFalse(score_problem(case, result)['passed'])

    def test_empty_successful_read_does_not_prove_expected_source_was_observed(self):
        case = {'objective': 'positive', 'tool_groups': [['search_notes']],
                'observation_checks': [{'tool': 'search_notes', 'contains': '정확한 원문'}]}
        result = self.result(['search_notes'], [{'tool': 'search_notes', 'arguments': {}, 'verification': 'VERIFIED', 'result': {'notes': []}}])
        self.assertFalse(score_problem(case, result)['passed'])

    def test_actual_run_uses_injected_stateful_fixture_without_live_effects(self):
        class Bridge:
            def ask(self, operation, **values):
                if operation == 'plan':
                    return {'ok': True, 'data': {'plan': {'action': 'TOOL_TASK', 'requiredTools': ['save_note']},
                            'allowed': ['save_note'], 'system': '', 'actionSystem': '', 'actionSchema': '{}'}}
                if operation == 'call':
                    return {'ok': True, 'data': {'decision': 'advance', 'call': json.loads(values['raw'])}}
                if operation == 'finish':
                    return {'ok': True, 'data': {'decision': 'finish'}}
                raise AssertionError(operation)
        output = iter(['{"plan":"synthetic"}', '{"type":"tool_call","tool":"save_note","arguments":{"title":"QA","content":"원문"}}', '저장 근거를 확인했습니다.'])
        def generate(*args, **kwargs):
            return next(output), {}
        case = {'id': 'unit-only', 'request': 'QA 원문 저장', 'objective': 'positive', 'tool_groups': [['save_note']],
                'state_checks': [{'collection': 'notes', 'match': {'title': 'QA', 'content': '원문'}}]}
        result = run_problem(case, Bridge(), generate, {'instructions': '', 'schema': '{}' })
        self.assertTrue(result['score']['passed'])
        self.assertEqual('원문', result['synthetic_state']['notes'][0]['content'])
        self.assertIn('not_android', result['evidence_kind'])

    def test_scope_cannot_be_changed_by_a_seed_alias(self):
        seed = {'initial_notes': [{'id': 'n', 'title': 'QA', 'content': '원문', 'scope': 'team-a'}]}
        fixture = ProblemFixtures(seed)
        fixture.execute('search_notes', {'query': 'QA'})
        self.assertEqual('FAILED', fixture.execute('update_note', {'id': 'n', 'scope': 'team-b', 'content': '변경'})[0])
        self.assertEqual('원문', seed['initial_notes'][0]['content'])

    def test_resource_failure_is_not_evaluated_or_passed(self):
        result = self.result(finish='stop'); result['error'] = 'PC_BUSY'
        rating = score_problem({'objective': 'positive'}, result)
        self.assertFalse(rating['evaluated']); self.assertFalse(rating['passed'])
        self.assertEqual('not_evaluated_resource_unavailable', rating['outcome'])

    def test_default_suite_has_unique_objectives_and_positive_state_assertions(self):
        cases = json.loads(Path(__file__).with_name('problem-solving-scenarios.json').read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(cases), 24)
        self.assertEqual(len(cases), len({case['id'] for case in cases}))
        self.assertEqual({'positive', 'boundary', 'prepared'}, {case['objective'] for case in cases})
        for case in cases:
            if case['objective'] == 'positive':
                self.assertTrue(case.get('tool_groups'), case['id'])
                self.assertTrue(case.get('state_checks') or case.get('no_mutation'), case['id'])


if __name__ == '__main__':
    unittest.main()
