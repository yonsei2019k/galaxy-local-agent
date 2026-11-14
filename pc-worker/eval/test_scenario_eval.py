import unittest
import tempfile
from pathlib import Path
from scenario_eval import Fixtures, score, RECORDING, find_call, limitation_disclosed, has_recorded_model_results


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
        state, value = fixture.execute('reply_gmail_message', {'messageId': 'mail-fixture','to':'fixture@example.invalid'})
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

    def test_generic_direct_word_is_not_capability_disclosure(self):
        self.assertFalse(limitation_disclosed('원하는 조건을 직접 골라 주세요. 이후 자동 알림을 설정하겠습니다.',['자동 알림']))
        self.assertFalse(limitation_disclosed('가격은 지금 확인할 수 없습니다. 나중에 자동 알림을 설정하겠습니다.',['자동 알림','감시']))
        self.assertTrue(limitation_disclosed('지속 가격 감시 기능은 현재 도구에 포함되어 있지 않습니다.',['감시']))

    def test_reply_cannot_use_recipient_from_untrusted_body(self):
        fixture=Fixtures({});fixture.execute('search_gmail_messages',{})
        state,_=fixture.execute('reply_gmail_message',{'messageId':'mail-fixture','to':'other@example.invalid'})
        self.assertEqual('FAILED',state)

    def test_resume_revision_guard_covers_new_scenario_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'manifest.json').write_text('{"selected_ids":["P01"]}',encoding='utf-8')
            self.assertFalse(has_recorded_model_results(folder))
            (folder/'P01-exact-note.json').write_text('{"rounds":[{"stage":"planner"}]}',encoding='utf-8')
            self.assertTrue(has_recorded_model_results(folder))

    def test_asking_in_prose_does_not_prove_execution_was_paused(self):
        result={'plan':{'action':'TOOL_TASK'},'steps':[],'rejections':[],'final':'어느 연락처인가요?'}
        self.assertFalse(score({'clarification':True},result)['passed'])
        result.update(status='paused',pause_code='awaiting_owner_input')
        self.assertTrue(score({'clarification':True},result)['passed'])


if __name__ == '__main__':
    unittest.main()
