import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('remote_test', Path(__file__).resolve().parents[1] / 'deployed/remote_test.py')
remote = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(remote)


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class RemoteTestQueueTests(unittest.TestCase):
    def test_only_typed_read_only_clarification_can_be_followed_when_paused(self):
        queue = remote.RemoteTestQueue(Clock())
        parent = queue.enqueue({'text': 'travel test'})['requestId']
        queue.claim()
        with self.assertRaises(remote.RemoteTestError):
            queue.report(parent, {'state': 'paused', 'errorCode': 'execution_not_verified', 'canFollowUp': True})
        queue.report(parent, {'state': 'paused', 'errorCode': remote.CLARIFICATION_CODE, 'canFollowUp': True})
        queue.enqueue({'text': 'the departure airport', 'afterRequestId': parent})
        self.assertEqual(queue.claim()['request']['afterRequestId'], parent)

    def setUp(self):
        self.clock = Clock()
        self.queue = remote.RemoteTestQueue(self.clock)

    def test_each_request_is_claimed_once_and_reported_once(self):
        created = self.queue.enqueue({'text': ' 오늘 일정 알려줘 ', 'model': 'paired-pc-cloud-agent-v1'})
        claimed = self.queue.claim()['request']
        self.assertEqual(claimed, {'requestId': created['requestId'], 'text': '오늘 일정 알려줘', 'model': 'paired-pc-cloud-agent-v1'})
        self.assertIsNone(self.queue.claim()['request'])
        self.queue.report(created['requestId'], {'state': 'completed', 'answer': 'ok',
                                                 'tools': [{'name': 'list_calendar_events', 'success': True, 'summary': 's'}]})
        status = self.queue.status(created['requestId'])
        self.assertEqual(status['state'], 'reported')
        self.assertEqual(status['result']['tools'][0]['name'], 'list_calendar_events')
        with self.assertRaises(remote.RemoteTestError):
            self.queue.report(created['requestId'], {'state': 'completed'})

    def test_slash_commands_bad_models_and_unknown_fields_are_rejected(self):
        for body in ({'text': '/new'}, {'text': ''}, {'text': 'x', 'model': '../a'}, {'text': 'x', 'extra': 1},
                     {'text': 'x' * 2001}, {}):
            with self.assertRaises(remote.RemoteTestError):
                self.queue.enqueue(body)

    def test_unclaimed_report_and_bad_state_are_rejected(self):
        created = self.queue.enqueue({'text': 'hi'})
        with self.assertRaises(remote.RemoteTestError):
            self.queue.report(created['requestId'], {'state': 'completed'})
        self.queue.claim()
        with self.assertRaises(remote.RemoteTestError):
            self.queue.report(created['requestId'], {'state': 'done'})

    def test_stale_claim_times_out_but_late_report_is_kept(self):
        created = self.queue.enqueue({'text': 'hi'})
        self.queue.claim()
        self.clock.now += remote.CLAIM_TTL_S + 1
        self.assertEqual(self.queue.status(created['requestId'])['state'], 'timeout')
        self.queue.report(created['requestId'], {'state': 'completed', 'answer': 'late'})
        self.assertEqual(self.queue.status(created['requestId'])['result']['answer'], 'late')

    def test_pending_queue_is_bounded(self):
        for _ in range(remote.MAX_PENDING):
            self.queue.enqueue({'text': 'hi'})
        with self.assertRaises(remote.RemoteTestError):
            self.queue.enqueue({'text': 'hi'})

    def test_optional_receipt_is_bounded_and_legacy_reports_still_work(self):
        request_id = self.queue.enqueue({'text': 'hi'})['requestId']
        self.assertEqual(self.queue.claim()['receiptVersion'], 2)
        for invalid in ({'versionCode': True, 'elapsedMs': 0}, {'versionCode': 2020, 'elapsedMs': -1}, None):
            with self.assertRaises(remote.RemoteTestError):
                self.queue.report(request_id, {'state': 'failed', 'receipt': invalid})
        # Android monotonic elapsed time includes sleep, even past the claim timeout.
        receipt = {'versionCode': 2020, 'elapsedMs': 31 * 60 * 1000}
        self.queue.report(request_id, {'state': 'failed', 'receipt': receipt})
        self.assertEqual(self.queue.status(request_id)['result']['receipt'], receipt)

    def test_follow_up_requires_completed_capable_parent_and_consumes_it_once(self):
        parent = self.queue.enqueue({'text': 'first'})['requestId']
        follow = {'text': 'next', 'afterRequestId': parent}
        with self.assertRaises(remote.RemoteTestError):
            self.queue.enqueue(follow)
        self.queue.claim()
        with self.assertRaises(remote.RemoteTestError):
            self.queue.report(parent, {'state': 'failed', 'canFollowUp': True})
        self.queue.report(parent, {'state': 'completed', 'canFollowUp': True})
        with self.assertRaises(remote.RemoteTestError):
            self.queue.enqueue(follow | {'model': 'different'})
        child = self.queue.enqueue(follow)['requestId']
        self.assertEqual(self.queue.claim()['request']['afterRequestId'], parent)
        with self.assertRaises(remote.RemoteTestError):
            self.queue.enqueue(follow)
        self.queue.report(child, {'state': 'completed', 'canFollowUp': True})
        self.clock.now += remote.CLAIM_TTL_S
        with self.assertRaises(remote.RemoteTestError):
            self.queue.enqueue({'text': 'late', 'afterRequestId': child})

    def test_identical_result_retries_keep_the_original_receipt(self):
        request_id = self.queue.enqueue({'text': 'hi'})['requestId']
        self.queue.claim()
        body = {'state': 'completed', 'answer': 'first', 'canFollowUp': True}
        self.queue.report(request_id, body)
        self.clock.now += 1
        self.assertEqual(self.queue.report(request_id, body), {'ok': True})
        self.assertEqual(self.queue.status(request_id)['result']['reportedAt'], 1000)
        with self.assertRaises(remote.RemoteTestError):
            self.queue.report(request_id, body | {'answer': 'different'})

    def test_malformed_status_and_tool_success_do_not_report_completion(self):
        request_id = self.queue.enqueue({'text': 'hi'})['requestId']
        self.queue.claim()
        for body in ({'state': []}, {'state': 'completed', 'tools': [{'name': 'read', 'success': 'false'}]}):
            with self.assertRaises(remote.RemoteTestError):
                self.queue.report(request_id, body)
        self.assertEqual(self.queue.status(request_id)['state'], 'claimed')


if __name__ == '__main__':
    unittest.main()
