import unittest
from scenario_eval import Fixtures, score, RECORDING, find_call


class ScenarioEvaluationTests(unittest.TestCase):
    def test_recording_id_requires_prior_observation(self):
        fixture = Fixtures({})
        self.assertEqual('FAILED', fixture.execute('transcribe_recording', {'recordingId': RECORDING})[0])
        fixture.execute('list_audio_recordings', {})
        self.assertEqual('VERIFIED', fixture.execute('transcribe_recording', {'recordingId': RECORDING})[0])

    def test_mail_never_sends_and_requires_observed_target(self):
        fixture = Fixtures({})
        self.assertEqual('FAILED', fixture.execute('reply_gmail_message', {'messageId': 'made-up'})[0])
        fixture.execute('search_gmail_messages', {})
        state, value = fixture.execute('reply_gmail_message', {'messageId': 'mail-fixture'})
        self.assertEqual('PREPARED', state)
        self.assertFalse(value['sent'])

    def test_unknown_fixture_is_failed_not_success(self):
        self.assertEqual('FAILED', Fixtures({}).execute('delete_phone_files', {})[0])

    def test_correct_plan_without_execution_fails(self):
        case = {'tool_groups': [['save_note']]}
        result = {'plan': {'requiredTools': ['save_note']}, 'steps': [], 'rejections': [], 'final': '저장했습니다'}
        self.assertFalse(score(case, result)['passed'])

    def test_prepared_is_not_send_success(self):
        result = {'plan': {}, 'steps': [], 'rejections': [], 'final': '확인 후 발송했습니다'}
        self.assertFalse(score({'prepared': True}, result)['passed'])

    def test_original_duplicate_keys_reach_the_core_validator(self):
        raw = '{"type":"tool_call","tool":"save_note","arguments":{"content":"a","content":"b"}}'
        call, retained = find_call('설명\n' + raw)
        self.assertEqual('save_note', call['tool'])
        self.assertEqual(raw, retained)
        self.assertEqual(2, retained.count('"content"'))

    def test_busy_worker_is_not_a_semantic_evaluation(self):
        result={'plan':None,'steps':[],'rejections':[],'final':None,'error':'PC_BUSY'}
        rating=score({'gap':True},result)
        self.assertFalse(rating['evaluated'])
        self.assertFalse(rating['checks']['interpretation'])
        self.assertEqual('not_evaluated_resource_unavailable',rating['outcome'])


if __name__ == '__main__':
    unittest.main()
