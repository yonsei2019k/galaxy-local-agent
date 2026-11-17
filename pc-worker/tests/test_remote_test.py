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


if __name__ == '__main__':
    unittest.main()
