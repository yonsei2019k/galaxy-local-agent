"""Offline MCP boundary regressions. No worker, model, credentials, sockets or phone."""
import io
import json
import stat
import tempfile
import unittest
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import local_llm_mcp as mcp


def request(arguments=None, name='local_ask', **fields):
    return {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
            'params': {'name': name, 'arguments': arguments or {}}, **fields}


def sse(*events, done=True):
    lines = [('data: ' + json.dumps(event) + '\n\n').encode() for event in events]
    if done:
        lines.append(b'data: [DONE]\n\n')
    return io.BytesIO(b''.join(lines))


class TextStream(io.StringIO):
    def reconfigure(self, **_):
        pass


class DispatchTests(unittest.TestCase):
    def setUp(self):
        self.generate = self.enterContext(patch.object(mcp, 'generate', return_value=('fixture summary', 3)))
        self.enterContext(patch.object(mcp, 'config', side_effect=AssertionError('No credentials in offline tests')))

    def assert_rejected(self, message):
        result = mcp.handle(message)
        self.assertTrue('error' in result or result.get('result', {}).get('isError'))
        self.generate.assert_not_called()

    def test_notification_cannot_execute_a_tool(self):
        message = request({'prompt': 'fixture'})
        del message['id']
        self.assertIsNone(mcp.handle(message))
        self.generate.assert_not_called()

    def test_malformed_envelopes_cannot_execute(self):
        for message in ([], None, request({'prompt': 'fixture'}, jsonrpc='1.0'),
                        request({'prompt': 'fixture'}, id=None), request({'prompt': 'fixture'}, id=True)):
            with self.subTest(message=message):
                self.assert_rejected(message)

    def test_invalid_argument_types_bounds_and_unknown_fields_cannot_execute(self):
        for args in ({'prompt': {}}, {'prompt': 'fixture', 'deep': 'false'},
                     {'prompt': 'fixture', 'max_tokens': True}, {'prompt': 'fixture', 'max_tokens': '10'},
                     {'prompt': 'fixture', 'max_tokens': 0}, {'prompt': 'fixture', 'max_tokens': 4097},
                     {'prompt': 'fixture', 'execute': True}, {'prompt': 'fixture', 'schema': []}):
            with self.subTest(args=args):
                self.assert_rejected(request(args))

    def test_batch_validates_every_item_before_first_generation(self):
        self.assert_rejected(request({'items': [{'prompt': 'valid'}, {'prompt': 10}]}, name='local_batch'))

    def test_digest_rejects_relative_paths_before_reading(self):
        with patch.object(mcp, 'read_text') as read:
            self.assert_rejected(request({'paths': ['relative.txt'], 'instruction': 'summarise'}, name='local_digest_files'))
            read.assert_not_called()

    def test_valid_ask_still_returns_actual_local_output(self):
        result = mcp.handle(request({'prompt': 'fixture', 'max_tokens': 50}))
        self.assertFalse(result['result']['isError'])
        self.assertEqual('fixture summary', json.loads(result['result']['content'][0]['text'])['text'])
        self.generate.assert_called_once()

    def test_valid_batch_preserves_identifiers_and_each_result(self):
        self.generate.side_effect = [('first', 1), ('second', 1)]
        result = mcp.handle(request({'items': [{'id': 'case-a', 'prompt': 'a'}, {'id': 'case-b', 'prompt': 'b'}]}, name='local_batch'))
        data = json.loads(result['result']['content'][0]['text'])
        self.assertFalse(result['result']['isError'])
        self.assertTrue(data['complete'])
        self.assertEqual([{'id': 'case-a', 'text': 'first'}, {'id': 'case-b', 'text': 'second'}], data['results'])

    def test_valid_digest_reads_requested_files_without_changing_them(self):
        with tempfile.TemporaryDirectory() as folder:
            files = [Path(folder) / 'a.txt', Path(folder) / 'b.txt']
            for index, path in enumerate(files):
                path.write_text('합성 자료 ' + str(index), encoding='utf-8')
            before = [path.read_bytes() for path in files]
            result = mcp.handle(request({'paths': list(map(str, files)), 'instruction': 'summarise'}, name='local_digest_files'))
            data = json.loads(result['result']['content'][0]['text'])
            self.assertFalse(result['result']['isError'])
            self.assertTrue(data['complete'])
            self.assertEqual(2, len(data['files']))
            self.assertTrue(all(row['result'] == 'fixture summary' for row in data['files']))
            self.assertEqual(before, [path.read_bytes() for path in files])

    def test_initialize_does_not_claim_an_unimplemented_protocol(self):
        result = mcp.handle({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2099-01-01'}})
        self.assertEqual('2024-11-05', result['result']['protocolVersion'])
        self.generate.assert_not_called()

    def test_busy_status_is_a_snapshot_without_model_execution(self):
        with patch.object(mcp, 'call', return_value={'compute_busy': True, 'cleanup_pending': False}):
            result = mcp.handle(request(name='local_status'))
        data = json.loads(result['result']['content'][0]['text'])
        self.assertFalse(data['available'])
        self.assertTrue(data['busy'])
        self.generate.assert_not_called()

    def test_unexpected_local_failure_does_not_leak_exception_text(self):
        self.generate.side_effect = RuntimeError('PRIVATE_FIXTURE_VALUE')
        result = mcp.handle(request({'prompt': 'fixture'}))
        self.assertTrue(result['result']['isError'])
        self.assertNotIn('PRIVATE_FIXTURE_VALUE', json.dumps(result))

    def test_oversized_input_does_not_hide_the_next_request(self):
        raw = 'x' * 513 + '\n' + json.dumps({'jsonrpc': '2.0', 'id': 2, 'method': 'ping'}) + '\n'
        output = TextStream()
        with patch.object(mcp, 'MAX_MESSAGE_CHARS', 512), patch.object(mcp.sys, 'stdin', TextStream(raw)), patch.object(mcp.sys, 'stdout', output):
            mcp.main()
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertIn('error', rows[0])
        self.assertEqual(2, rows[1]['id'])
        self.generate.assert_not_called()

    def test_worker_error_never_echoes_arbitrary_error_text(self):
        self.generate.side_effect = mcp.WorkerError('PRIVATE_FIXTURE_VALUE')
        result = mcp.handle(request({'prompt': 'fixture'}))
        self.assertTrue(result['result']['isError'])
        self.assertNotIn('PRIVATE_FIXTURE_VALUE', json.dumps(result))

    def test_digest_file_failure_is_a_partial_tool_error(self):
        path = str(Path(tempfile.gettempdir()) / 'ziphap-synthetic-absent.txt')
        with patch.object(mcp, 'read_text', side_effect=OSError('PRIVATE_FIXTURE_VALUE')):
            result = mcp.handle(request({'paths': [path], 'instruction': 'summarise'}, name='local_digest_files'))
        self.assertTrue(result['result']['isError'])
        data = json.loads(result['result']['content'][0]['text'])
        self.assertFalse(data['complete'])
        self.assertNotIn('PRIVATE_FIXTURE_VALUE', json.dumps(data))

    def test_batch_preserves_completed_items_but_stops_on_phone_busy(self):
        self.generate.side_effect = [('first result', 2), mcp.WorkerError('PC_BUSY'), ('must not run', 2)]
        result = mcp.handle(request({'items': [{'prompt': 'a'}, {'prompt': 'b'}, {'prompt': 'c'}]}, name='local_batch'))
        self.assertTrue(result['result']['isError'])
        data = json.loads(result['result']['content'][0]['text'])
        self.assertFalse(data['complete'])
        self.assertEqual('first result', data['results'][0]['text'])
        self.assertEqual('PC_BUSY', data['results'][1]['error'])
        self.assertEqual(2, self.generate.call_count)

    def test_stdio_rejects_duplicate_json_keys_and_continues(self):
        raw = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"local_ask","arguments":{"prompt":"a","prompt":"b"}}}\n'
        raw += json.dumps({'jsonrpc': '2.0', 'id': 2, 'method': 'ping'}) + '\n'
        output = TextStream()
        with patch.object(mcp.sys, 'stdin', TextStream(raw)), patch.object(mcp.sys, 'stdout', output):
            mcp.main()
        results = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertIn('error', results[0])
        self.assertEqual(2, results[1]['id'])
        self.generate.assert_not_called()


class StreamTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(mcp, 'ensure_loaded'))

    def test_eof_after_partial_text_is_not_success(self):
        with patch.object(mcp, 'call', return_value=sse({'choices': [{'delta': {'content': 'partial'}}]}, done=False)):
            with self.assertRaises(mcp.WorkerError) as error:
                mcp.generate('fixture')
        self.assertEqual('STREAM_INCOMPLETE', error.exception.code)

    def test_output_limit_is_not_a_complete_answer_even_with_done(self):
        stream = sse({'choices': [{'delta': {'content': 'cut'}, 'finish_reason': 'length'}]})
        with patch.object(mcp, 'call', return_value=stream):
            with self.assertRaises(mcp.WorkerError) as error:
                mcp.generate('fixture')
        self.assertEqual('OUTPUT_TRUNCATED', error.exception.code)

    def test_valid_stopped_stream_preserves_text_and_usage(self):
        stream = sse({'choices': [{'delta': {'content': 'fixture'}, 'finish_reason': 'stop'}]}, {'usage': {'completion_tokens': 3}})
        with patch.object(mcp, 'call', return_value=stream):
            self.assertEqual(('fixture', 3), mcp.generate('fixture'))

    def test_malformed_stream_fails_without_returning_partial_output(self):
        with patch.object(mcp, 'call', return_value=io.BytesIO(b'data: {not-json}\n\n')):
            with self.assertRaises(mcp.WorkerError) as error:
                mcp.generate('fixture')
        self.assertEqual('INVALID_STREAM', error.exception.code)


class ResourceTests(unittest.TestCase):
    def test_busy_precedes_resident_reuse(self):
        health = {'compute_busy': True, 'cleanup_pending': False,
                  'llmResident': {'modelId': mcp.MODEL, 'contextSize': mcp.CONTEXT, 'ownerMatches': True}}
        with patch.object(mcp, 'call', return_value=health) as call:
            with self.assertRaises(mcp.WorkerError) as error:
                mcp.ensure_loaded()
            self.assertEqual('PC_BUSY', error.exception.code)
            self.assertEqual(1, call.call_count)

    def test_cleanup_and_unconfirmed_health_do_not_load(self):
        for health in ({'compute_busy': False, 'cleanup_pending': True}, {}):
            with patch.object(mcp, 'call', return_value=health) as call:
                with self.assertRaises(mcp.WorkerError):
                    mcp.ensure_loaded()
                self.assertEqual(1, call.call_count)

    def test_other_phone_owner_is_not_reloaded(self):
        health = {'compute_busy': False, 'cleanup_pending': False,
                  'llmResident': {'modelId': mcp.MODEL, 'contextSize': mcp.CONTEXT, 'ownerMatches': False}}
        with patch.object(mcp, 'call', return_value=health) as call:
            with self.assertRaises(mcp.WorkerError) as error:
                mcp.ensure_loaded()
            self.assertEqual('COMPUTE_OWNER_MISMATCH', error.exception.code)
            self.assertEqual(1, call.call_count)

    def test_digest_reduction_cannot_repeat_without_shrinking(self):
        def reply(*_, **__):
            if generate.call_count >= 20:
                raise mcp.WorkerError('TEST_ABORT')
            return 'x' * 100, 1
        with patch.object(mcp, 'generate', side_effect=reply) as generate, patch.object(mcp, 'CHUNK_CHARS', 50):
            with self.assertRaises(mcp.WorkerError):
                mcp.digest('a' * 90, 'fixture', 100)
            self.assertLess(generate.call_count, 20)

    def test_file_growth_cannot_bypass_the_read_bound(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'fixture.txt'
            path.write_bytes(b'x' * 33)
            before_growth = SimpleNamespace(st_size=0, st_mode=stat.S_IFREG)
            with patch.object(mcp, 'MAX_FILE_BYTES', 32), patch.object(mcp.Path, 'stat', return_value=before_growth):
                with self.assertRaises(ValueError):
                    mcp.read_text(str(path))

    def test_transport_rejects_redirects(self):
        with self.assertRaises(mcp.WorkerError) as error:
            mcp.NoRedirect().redirect_request(urllib.request.Request('https://example.invalid/source'), None, 302, 'redirect', {}, 'https://example.invalid/other')
        self.assertEqual('REDIRECT_BLOCKED', error.exception.code)


class TransportTests(unittest.TestCase):
    def test_loopback_transport_uses_no_proxy_or_redirect_handler(self):
        with patch.object(mcp, 'config', return_value={'port': 19443, 'token': 'SYNTHETIC_TEST_TOKEN'}), \
             patch.object(mcp.urllib.request, 'urlopen', side_effect=AssertionError('No real network')), \
             patch.object(mcp.urllib.request, 'build_opener') as build:
            build.return_value.open.return_value = io.BytesIO(b'{"fixture":true}')
            self.assertEqual({'fixture': True}, mcp.call('/v1/health'))
            handlers = build.call_args.args
            self.assertTrue(any(isinstance(handler, mcp.NoRedirect) for handler in handlers))
            self.assertTrue(any(isinstance(handler, urllib.request.ProxyHandler) and handler.proxies == {} for handler in handlers))

    def test_worker_http_failure_does_not_return_private_body(self):
        error = mcp.urllib.error.HTTPError('https://example.invalid', 500, 'fixture', {}, io.BytesIO(b'{"error":"PRIVATE_FIXTURE_VALUE"}'))
        with patch.object(mcp, 'config', return_value={'port': 19443, 'token': 'SYNTHETIC_TEST_TOKEN'}), \
             patch.object(mcp.urllib.request, 'build_opener') as build:
            build.return_value.open.side_effect = error
            with self.assertRaises(mcp.WorkerError) as caught:
                mcp.call('/v1/health')
            self.assertEqual('WORKER_ERROR', caught.exception.code)
            self.assertNotIn('PRIVATE_FIXTURE_VALUE', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
