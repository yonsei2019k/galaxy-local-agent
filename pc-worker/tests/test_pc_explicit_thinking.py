"""Future root-run pure host tests. Never imports runtime/security/model/worker modules."""
import ast
import copy
import hashlib
import io
import json
from pathlib import Path
import threading
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[1]
DEPLOYED = ROOT/'deployed'
MODEL_SHA = '03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8'
KEY = '10000000-0000-4000-8000-000000000001'  # Public synthetic protocol fixture only.


def subject():
    tree = ast.parse((DEPLOYED/'engines.py').read_text(encoding='utf-8'))
    names = {'validate_text_request', 'prepare_text_payload', 'text_contract_capabilities', 'text_contract_receipt',
             'TextContractStream', 'ThinkGuard'}
    constants = {'THINKING_ALLOWANCE', 'THINK_LIMIT', '_TEXT_LANGUAGE_RULE'}
    nodes = [node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name in names or
             isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id in constants for target in node.targets)]
    engine = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Engines')
    methods = [node for node in engine.body if isinstance(node, ast.FunctionDef) and node.name in {'require_llm', 'tokens', 'chat'}]
    nodes.append(ast.ClassDef(name='PureEngine', bases=[], keywords=[], body=methods, decorator_list=[], type_params=[]))
    namespace = dict(json=json, hashlib=hashlib, uuid=uuid, time=time,
                     Busy=type('Busy', (Exception,), {}), GEMMAS={'pc-text': (None, MODEL_SHA)})
    exec(compile(ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[])), '<pure-host-only>', 'exec'), namespace)
    return namespace


class Reply(io.BytesIO):
    status = 200
    def __init__(self, value):
        super().__init__(value); self.closes = 0
    def close(self):
        if not self.closed: self.closes += 1
        super().close()


class PcExplicitThinkingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = subject()

    def request(self, **changes):
        value = dict(requestId=KEY, modelId='pc-text', maxTokens=1024, thinking=False,
                     messages=[dict(role='system', content='공개 합성 지시'), dict(role='user', content='공개 합성 질문')])
        value.update(changes); return value

    def fake(self, lines=None):
        api = self.api
        class Engine(api['PureEngine']):
            def __init__(self):
                self.lock = threading.RLock(); self.upstream_lock = threading.Lock(); self.upstreams = {}
                self.llm = type('Process', (), {'poll': lambda _: None})(); self.llm_alias = 'pc-text'; self.llm_ctx = 8192
                self.calls = []; self.completions = []
            def upstream(self, path, payload, timeout=180):
                self.calls.append((path, copy.deepcopy(payload)))
                if path == '/apply-template': return Reply(json.dumps({'prompt': 'synthetic-render'}).encode())
                if path == '/tokenize': return Reply(b'{"tokens":[1,2,3]}')
                if path.startswith('/slots/'): return Reply(b'{}')
                reply = Reply(lines if lines is not None else b'data: {"choices":[{"delta":{"content":"synthetic"},"finish_reason":null}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
                self.completions.append(reply); return reply
        return Engine()

    def drain(self, response):
        output = bytearray()
        while True:
            line = response.readline()
            if not line: break
            output.extend(line)
        return bytes(output)

    def test_explicit_false_keeps_every_cap_without_extra_or_reopen(self):
        for cap in (1, 128, 512, 513, 1024, 4096):
            for schema in (None, {'type': 'object'}):
                with self.subTest(cap=cap, schema=schema is not None):
                    payload, policy = self.api['prepare_text_payload'](self.request(maxTokens=cap, schema=schema), 'pc-text')
                    self.assertEqual(cap, payload['max_tokens'])
                    self.assertIs(False, payload['chat_template_kwargs']['enable_thinking'])
                    self.assertEqual(0, policy['extraReasoningTokens']); self.assertIs(False, policy['legacyMayReopen'])

    def test_true_and_absent_keep_original_automatic_boundaries(self):
        for requested in (True, None):
            for cap, schema, expected in ((512, None, False), (513, None, True), (2048, None, True), (2048, {}, False)):
                value = self.request(maxTokens=cap, schema=schema)
                if requested is None: value.pop('thinking')
                else: value['thinking'] = requested
                payload, policy = self.api['prepare_text_payload'](value, 'pc-text')
                self.assertEqual(expected, policy['legacyMayReopen'])
                self.assertEqual(cap + (900 if expected else 0), payload['max_tokens'])

    def test_other_alias_does_not_acquire_automatic_thinking(self):
        payload, policy = self.api['prepare_text_payload'](self.request(thinking=True), 'gemma-e2b')
        self.assertIs(False, payload['chat_template_kwargs']['enable_thinking'])
        self.assertEqual(0, policy['extraReasoningTokens'])

    def test_source_is_not_mutated_and_count_uses_same_prepared_input(self):
        request = self.request(schema={'type': 'object'})
        original = copy.deepcopy(request)
        generated, gp = self.api['prepare_text_payload'](request, 'pc-text')
        counted, cp = self.api['prepare_text_payload'](request, 'pc-text', for_count=True)
        self.assertEqual(original, request); self.assertEqual(generated, counted); self.assertEqual(gp, cp)
        self.assertEqual(1, generated['messages'][0]['content'].count(self.api['_TEXT_LANGUAGE_RULE']))
        a = self.api['text_contract_receipt'](request, 'pc-text', 8192, generated, gp)
        b = self.api['text_contract_receipt'](request, 'pc-text', 8192, counted, cp)
        self.assertEqual(a, b); self.assertIsNone(a['thinkingHonored']); self.assertFalse(a['exactRendererVerified'])

    def test_legacy_count_keeps_unknown_thinking_without_fabricating_cap(self):
        request = self.request(); request.pop('thinking'); request.pop('maxTokens')
        payload, policy = self.api['prepare_text_payload'](request, 'pc-text', for_count=True)
        self.assertNotIn('max_tokens', payload); self.assertNotIn('chat_template_kwargs', payload)
        self.assertIsNone(policy['appliedThinking']); self.assertIsNone(policy['extraReasoningTokens'])

    def test_bad_bool_cap_schema_or_unknown_field_is_rejected(self):
        for changes in ({'thinking': None}, {'thinking': 0}, {'thinking': 'false'}, {'maxTokens': True},
                        {'maxTokens': 0}, {'maxTokens': 4097}, {'schema': []}, {'schema': {'x': 'a'*65536}}, {'unknown': True}):
            with self.subTest(fields=tuple(changes)):
                with self.assertRaises(ValueError): self.api['validate_text_request'](self.request(**changes), 'chat')

    def test_real_tokens_method_passes_false_and_common_messages_to_apply_template(self):
        engine = self.fake(); request = self.request(schema={'type': 'object'})
        result = engine.tokens(request)
        self.assertEqual(3, result['count'])
        template = engine.calls[0][1]
        generation, _ = self.api['prepare_text_payload'](request, 'pc-text')
        self.assertEqual(generation['messages'], template['messages'])
        self.assertEqual(generation['chat_template_kwargs'], template['chat_template_kwargs'])
        self.assertEqual(KEY, result['textContract']['requestId'])

    def test_false_length_before_content_never_creates_second_generation(self):
        engine = self.fake(b'data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\ndata: [DONE]\n\n')
        output = []; engine.chat(self.request(), lambda response: output.append(self.drain(response)))
        self.assertEqual(1, len(engine.completions)); self.assertEqual(1, engine.completions[0].closes)
        self.assertEqual(1, sum(path.startswith('/slots/') for path, _ in engine.calls))
        first = json.loads(output[0].split(b'\n', 1)[0][6:])['ziphap_text_contract']
        self.assertFalse(first['appliedThinking']); self.assertFalse(first['legacyMayReopen'])
        self.assertIn(b'data: [DONE]', output[0]); self.assertFalse(engine.upstreams)

    def test_true_preserves_guard_and_reopening_instead_of_silently_disabling_deep(self):
        engine = self.fake(b'data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\ndata: [DONE]\n\n')
        engine.chat(self.request(thinking=True), self.drain)
        self.assertEqual(2, len(engine.completions))
        payloads = [payload for path, payload in engine.calls if path == '/v1/chat/completions']
        self.assertTrue(payloads[0]['chat_template_kwargs']['enable_thinking']); self.assertEqual(1924, payloads[0]['max_tokens'])
        self.assertFalse(payloads[1]['chat_template_kwargs']['enable_thinking']); self.assertEqual(1024, payloads[1]['max_tokens'])

    def test_close_and_cancel_path_forwards_to_same_upstream_once(self):
        response = Reply(b'data: [DONE]\n\n')
        wrapped = self.api['TextContractStream'](response, {'requestId': KEY})
        prefix = wrapped.readline(4) + wrapped.readline()
        self.assertTrue(prefix.startswith(b'data: ')); wrapped.close(); wrapped.close()
        self.assertEqual(1, response.closes)

    def test_metadata_does_not_advertise_binary_off_or_exact_renderer_qualification(self):
        capability = self.api['text_contract_capabilities']()
        self.assertTrue(capability['strictThinkingBool']); self.assertFalse(capability['explicitFalseHonored'])
        self.assertFalse(capability['exactRendererVerified']); self.assertEqual('unknown', capability['qualification'])

    def test_owned_rejects_bad_flag_before_any_lock_or_operation(self):
        tree = ast.parse((DEPLOYED/'owned_engines.py').read_text(encoding='utf-8'))
        own = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'OwnedEngines')
        methods = [node for node in own.body if isinstance(node, ast.FunctionDef) and node.name in {'tokens', 'chat'}]
        module = ast.fix_missing_locations(ast.Module(body=[ast.ClassDef(name='Owned', bases=[], keywords=[], body=methods, decorator_list=[], type_params=[])], type_ignores=[]))
        namespace = dict(self.api, owner_value=lambda owner: owner, request_key=lambda request: request['requestId'])
        exec(compile(module, '<owned-pre-admission-only>', 'exec'), namespace)
        class Lock:
            calls = 0
            def acquire(self, **_): self.calls += 1; raise AssertionError('admission reached')
        for method in ('tokens', 'chat'):
            engine = namespace['Owned'](); engine.lock = Lock()
            with self.assertRaises(ValueError):
                if method == 'tokens': engine.tokens(self.request(thinking='false'))
                else: engine.chat(self.request(thinking='false'), self.drain)
            self.assertEqual(0, engine.lock.calls)


if __name__ == '__main__': unittest.main()
