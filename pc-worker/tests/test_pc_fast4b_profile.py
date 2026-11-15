"""Root-run pure host wiring tests; never import the worker or access a model/runtime/API."""
import ast
import copy
import hashlib
import io
import json
from pathlib import Path
import re
from types import SimpleNamespace
import threading
import time
import unittest
import uuid


DEPLOYED = Path(__file__).resolve().parents[1] / 'deployed'
LEGACY = 'pc-text'
FAST = 'pc-text-4b'
LEGACY_SHA = '03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8'
FAST_SHA = '217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14'
OWNER = '10000000-0000-4000-8000-000000000001'
OTHER_OWNER = '20000000-0000-4000-8000-000000000002'
KEY = '30000000-0000-4000-8000-000000000003'


def extracted_class(tree, name, methods, bases=()):
    source = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == name)
    return ast.ClassDef(name=name, bases=[ast.Name(id=n, ctx=ast.Load()) for n in bases], keywords=[],
                        body=[n for n in source.body if isinstance(n, ast.FunctionDef) and n.name in methods],
                        decorator_list=[], type_params=[])


def subject():
    """Compile selected public host functions only, with fake native/network/filesystem boundaries."""
    tree = ast.parse((DEPLOYED / 'engines.py').read_text(encoding='utf-8'))
    functions = {'validate_text_request', 'prepare_text_payload', 'text_contract_receipt',
                 'text_contract_capabilities', 'verify_file', 'ThinkGuard', 'TextContractStream'}
    constants = {'THINKING_ALLOWANCE', 'THINK_LIMIT', '_TEXT_LANGUAGE_RULE'}
    nodes = [n for n in tree.body if
             isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in functions or
             isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in constants for t in n.targets)]
    nodes.append(extracted_class(tree, 'Engines', {'health', 'load_llm', 'require_llm', 'tokens', 'chat'}))
    owned = ast.parse((DEPLOYED / 'owned_engines.py').read_text(encoding='utf-8'))
    names = {'owner_value', 'request_key', 'terminate', 'OwnershipMismatch', 'RequestNotActive', 'ComputeCancelled'}
    nodes += [n for n in owned.body if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in names]
    nodes.append(extracted_class(owned, 'OwnedEngines', {
        'check_owner', '_begin', '_finish', 'stop_llm', 'upstream', 'load_llm', 'tokens', 'chat',
        '_cancel_record', 'cancel_chat', 'unload_llm', '_release_owner'
    }, bases=('Engines',)))
    profiles = ast.parse((DEPLOYED / 'pc_text_profiles.py').read_text(encoding='utf-8'))
    nodes += [n for n in profiles.body if isinstance(n, (ast.Assign, ast.FunctionDef))]
    api = dict(json=json, hashlib=hashlib, uuid=uuid, time=time, re=re, threading=threading,
               Path=Path, _verified={}, _UNSET=object(), SOFT_CANCEL_WAIT=0, FINAL_CANCEL_WAIT=0,
               subprocess=SimpleNamespace(TimeoutExpired=TimeoutError),
               Busy=type('Busy', (Exception,), {}), available_memory=lambda: 8 * 1024**3,
               MODEL_ID='synthetic-image-model', MODEL_SHA='0' * 64,
               reference_models=lambda: [], public_models=lambda: [],
               GEMMAS={LEGACY: (Path('synthetic-9b.gguf'), LEGACY_SHA),
                       FAST: (Path('synthetic-4b.gguf'), FAST_SHA)})
    exec(compile(ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[])), '<pure-4b-host>', 'exec'), api)
    return api


def registered_models(api, environ):
    """Execute the actual registration statements only, never module imports/startup/native code."""
    tree = ast.parse((DEPLOYED / 'engines.py').read_text(encoding='utf-8'))
    nodes = [n for n in tree.body if
             isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'GEMMAS' for t in n.targets) or
             isinstance(n, ast.Expr) and isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Attribute) and
             isinstance(n.value.func.value, ast.Name) and n.value.func.value.id == 'GEMMAS' and n.value.func.attr == 'update']
    namespace = dict(api, GEMMA_ROOT=Path('synthetic-gemma-root'), os=SimpleNamespace(environ=environ))
    exec(compile(ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[])), '<pure-profile-registration>', 'exec'), namespace)
    return namespace['GEMMAS']


class Reply(io.BytesIO):
    status = 200
    def __init__(self, value):
        super().__init__(value)
        self.close_calls = 0
    def close(self):
        if not self.closed:
            self.close_calls += 1
        super().close()


class Process:
    """In-memory lifecycle only; no process is launched."""
    def __init__(self):
        self.live = True
    def poll(self): return None if self.live else 0
    def terminate(self): self.live = False
    def kill(self): self.live = False
    def wait(self, timeout=None): self.live = False; return 0


class PcFast4bProfileTest(unittest.TestCase):
    def setUp(self):
        self.api = subject()

    def request(self, alias=FAST, **changes):
        value = dict(requestId=KEY, modelId=alias, maxTokens=1024, thinking=False,
                     messages=[dict(role='system', content='공개 합성 지시'),
                               dict(role='user', content='공개 합성 질문')])
        value.update(changes)
        return value

    def load_request(self, alias=FAST, **changes):
        value = dict(requestId=str(uuid.uuid4()), modelId=alias,
                     modelShaPrefix=FAST_SHA if alias == FAST else LEGACY_SHA, contextSize=8192)
        value.update(changes)
        return value

    def fake(self, resident=None, fail_layers=(), verify_failure=False, lines=None):
        api = self.api
        class Engine(api['OwnedEngines']):
            def __init__(self):
                self.lock = threading.RLock(); self.owner_lock = threading.RLock()
                self.upstream_lock = threading.Lock(); self.upstreams = {}
                self.owner = OWNER if resident else None; self.owner_set = bool(resident)
                self.operation = None; self.records = {}; self.request_local = threading.local()
                self.llm = Process() if resident else None; self.llm_alias = resident
                self.llm_ctx = 8192 if resident else 0; self.llm_port = None; self.llm_key = None
                self.children = [self.llm] if self.llm else []; self.starts = []; self.verified = []
                self.calls = []; self.completions = []; self.max_live = len(self.children)
            def start_llm(self, path, layers):
                self.starts.append((path, layers)); self.llm = Process(); self.children.append(self.llm)
                self.max_live = max(self.max_live, sum(p.poll() is None for p in self.children))
                if layers in fail_layers: raise RuntimeError('synthetic native load failure')
                return {'processLaunchMs': 1.0, 'readyWaitMs': 2.0}
        engine = Engine()
        def verify(path, expected):
            engine.verified.append((path, expected))
            if verify_failure: raise ValueError('MODEL_INTEGRITY')
        api['verify_file'] = verify
        def upstream(base, path, payload, timeout=180):
            base.calls.append((path, copy.deepcopy(payload)))
            if path == '/apply-template': return Reply(b'{"prompt":"synthetic rendered input"}')
            if path == '/tokenize': return Reply(b'{"tokens":[1,2,3]}')
            if path.startswith('/slots/'): return Reply(b'{}')
            response = Reply(lines if lines is not None else
                             b'data: {"choices":[{"delta":{"content":"synthetic"},"finish_reason":null}]}\n\n'
                             b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
            base.completions.append(response)
            return response
        api['Engines'].upstream = upstream
        return engine

    @staticmethod
    def drain(response):
        chunks = []
        while True:
            part = response.readline()
            if not part: break
            chunks.append(part)
        return b''.join(chunks)

    def test_missing_or_invalid_optional_path_registers_no_fast_alias(self):
        configure = self.api['configured_fast4b_models']; key = self.api['FAST_4B_PATH_ENV']
        for env in ({}, {key: ''}, {key: None}, {key: 1}, {key: 'relative/model.gguf'},
                    {key: ' model.gguf '}, {key: '\x00'}, {key: 'x' * 4097},
                    {key: '\\\\synthetic-server\\share\\model.gguf'}, {key: '//synthetic-server/share/model.gguf'}):
            with self.subTest(value_type=type(env.get(key)).__name__):
                self.assertEqual({}, configure(env))

    def test_explicit_absolute_path_registers_only_fixed_alias_and_full_pin_without_loading(self):
        path = Path('synthetic-deployment').absolute() / 'synthetic-comparison.gguf'
        mapping = self.api['configured_fast4b_models']({self.api['FAST_4B_PATH_ENV']: str(path)})
        self.assertEqual({FAST: (path, FAST_SHA)}, mapping)
        self.assertNotIn(LEGACY, mapping)  # Existing9B registration remains elsewhere, unchanged.

    def test_real_registration_and_health_omit_disabled_fast_profile_and_keep_original_legacy_pin(self):
        key = self.api['FAST_4B_PATH_ENV']
        for env in ({}, {key: 'relative/model.gguf'}, {key: str(Path('synthetic-4b.gguf').absolute())}):
            models = registered_models(self.api, env)
            expected = FAST in models
            self.assertEqual(bool(env.get(key) and Path(env[key]).is_absolute()), expected)
            self.assertEqual(LEGACY_SHA, models[LEGACY][1])
            self.api['GEMMAS'] = {alias: (SimpleNamespace(is_file=lambda: True), sha) for alias, (_, sha) in models.items()}
            health = self.fake().health()
            self.assertEqual(expected, FAST in health['llm_models'])
            self.assertEqual(LEGACY_SHA, health['llm_models'][LEGACY])
            self.assertEqual('unknown', health['textGenerationContract']['qualification'])

    def test_unregistered_fast_alias_fails_before_lock_and_keeps_legacy_resident(self):
        engine = self.fake(LEGACY); resident = engine.llm; self.api['GEMMAS'].pop(FAST)
        class ForbiddenLock:
            def acquire(self, **_): raise AssertionError('unregistered profile reached admission')
        engine.lock = ForbiddenLock()
        with self.assertRaisesRegex(ValueError, 'LLM_PROFILE'):
            engine.load_llm(self.load_request(), OWNER)
        self.assertIs(resident, engine.llm); self.assertIsNone(resident.poll()); self.assertFalse(engine.starts)

    def test_fast_load_returns_exact_typed_identity_and_verifies_full_hash_first(self):
        for context in (4096, 8192):
            engine = self.fake(); request = self.load_request(contextSize=context)
            reply = engine.load_llm(request, OWNER)
            self.assertEqual((FAST, FAST_SHA, context, request['requestId']),
                             (reply['modelId'], reply['modelSha256'], reply['contextSize'], reply['requestId']))
            self.assertIs(True, reply['structuredOutput'])
            self.assertEqual([(Path('synthetic-4b.gguf'), FAST_SHA)], engine.verified)
            self.assertEqual([(Path('synthetic-4b.gguf'), 24)], engine.starts)
            self.assertEqual(OWNER, engine.owner); self.assertIsNone(engine.operation)

    def test_fast_requires_full_pin_before_admission_but_legacy_prefix_contract_is_preserved(self):
        for value in (FAST_SHA[:12], LEGACY_SHA, FAST_SHA[:-1] + '0'):
            engine = self.fake(LEGACY); resident = engine.llm
            with self.assertRaisesRegex(ValueError, 'MODEL_SHA_MISMATCH'):
                engine.load_llm(self.load_request(modelShaPrefix=value), OWNER)
            self.assertIs(resident, engine.llm); self.assertFalse(engine.starts)
        engine = self.fake()
        reply = engine.load_llm(self.load_request(LEGACY, modelShaPrefix=LEGACY_SHA[:12]), OWNER)
        self.assertEqual(LEGACY_SHA, reply['modelSha256'])

    def test_base_fast_load_also_rejects_short_pin_without_outer_owned_wrapper(self):
        engine = self.fake()
        request = self.load_request(modelShaPrefix=FAST_SHA[:12]); request.pop('requestId')
        with self.assertRaisesRegex(ValueError, 'MODEL_SHA_MISMATCH'):
            self.api['Engines'].load_llm(engine, request)
        self.assertFalse(engine.starts); self.assertFalse(engine.verified)

    def test_alias_swap_stops_previous_child_before_loading_and_reuses_only_same_profile(self):
        engine = self.fake(LEGACY); old = engine.llm
        engine.load_llm(self.load_request(), OWNER)
        self.assertIsNotNone(old.poll()); self.assertEqual(1, engine.max_live)
        reply = engine.load_llm(self.load_request(), OWNER)
        self.assertTrue(reply['loadTimings']['residentReused']); self.assertEqual(1, len(engine.starts))
        engine.load_llm(self.load_request(LEGACY), OWNER)
        self.assertEqual(LEGACY, engine.llm_alias); self.assertEqual(1, engine.max_live)
        self.assertEqual([24, 30], [layers for _, layers in engine.starts])

    def test_each_alias_retains_its_own_gpu_retry_ladder_without_profile_fallback(self):
        for alias, failures, expected in ((FAST, (24,), [24, 0]), (LEGACY, (30, 20), [30, 20, 0])):
            engine = self.fake(fail_layers=failures)
            reply = engine.load_llm(self.load_request(alias), OWNER)
            self.assertEqual(expected, [layers for _, layers in engine.starts])
            self.assertEqual(alias, reply['modelId']); self.assertEqual(1, engine.max_live)

    def test_integrity_or_all_native_attempts_failure_never_loads_another_alias(self):
        for integrity in (True, False):
            engine = self.fake(verify_failure=integrity, fail_layers=(24, 0))
            with self.assertRaises(ValueError if integrity else RuntimeError):
                engine.load_llm(self.load_request(), OWNER)
            self.assertEqual([] if integrity else [24, 0], [layers for _, layers in engine.starts])
            self.assertTrue(all(path == Path('synthetic-4b.gguf') for path, _ in engine.starts))
            self.assertIsNone(engine.llm); self.assertIsNone(engine.operation)

    def test_contended_native_lock_keeps_foreign_active_operation_and_resident_untouched(self):
        engine = self.fake(LEGACY); resident = engine.llm
        active = object(); engine.owner = OTHER_OWNER; engine.operation = active
        class ContendedLock:
            def acquire(self, **_): return False
            def release(self): raise AssertionError('unacquired lock released')
        engine.lock = ContendedLock()
        with self.assertRaises(self.api['Busy']): engine.load_llm(self.load_request(), OWNER)
        self.assertIs(active, engine.operation); self.assertIs(resident, engine.llm)
        self.assertEqual(OTHER_OWNER, engine.owner); self.assertFalse(engine.starts)

    def test_idle_load_preserves_existing_explicit_owner_handoff_policy_with_one_child(self):
        engine = self.fake(LEGACY); old = engine.llm; engine.owner = OTHER_OWNER
        engine.load_llm(self.load_request(), OWNER)
        self.assertEqual(OWNER, engine.owner); self.assertIsNotNone(old.poll()); self.assertEqual(1, engine.max_live)

    def test_foreign_owner_or_anonymous_request_cannot_generate_or_take_over_an_owned_load(self):
        for alias in (FAST, LEGACY):
            engine = self.fake(alias); resident = engine.llm
            with self.assertRaises(self.api['OwnershipMismatch']): engine.tokens(self.request(alias), OTHER_OWNER)
            with self.assertRaises(self.api['OwnershipMismatch']): engine.chat(self.request(alias), self.drain, OTHER_OWNER)
            with self.assertRaises(self.api['OwnershipMismatch']): engine.load_llm(self.load_request(alias), None)
            self.assertIs(resident, engine.llm); self.assertFalse(engine.calls); self.assertFalse(engine.starts)

    def test_resident_alias_mismatch_rejects_before_generation_and_does_not_reinterpret_role(self):
        engine = self.fake(FAST)
        with self.assertRaisesRegex(ValueError, 'LLM_NOT_READY'):
            engine.chat(self.request(LEGACY), self.drain, OWNER)
        self.assertFalse(engine.completions)  # Failure cleanup may stop its own resident, never loads9B.
        self.assertFalse(engine.starts)
        engine = self.fake(FAST)
        request = self.request(messages=[{'role': 'tool', 'content': 'not a supported wire role'}])
        with self.assertRaisesRegex(ValueError, 'MESSAGE_FORMAT'): engine.tokens(request, OWNER)
        self.assertFalse(engine.calls)

    def test_both_text_aliases_share_language_thinking_schema_and_count_preparation(self):
        for alias in (FAST, LEGACY):
            for thinking in (False, True):
                for cap, schema in ((512, None), (513, None), (1024, {'type': 'object'})):
                    request = self.request(alias, thinking=thinking, maxTokens=cap, schema=schema)
                    original = copy.deepcopy(request)
                    payload, policy = self.api['prepare_text_payload'](request, alias)
                    counted, count_policy = self.api['prepare_text_payload'](request, alias, for_count=True)
                    expected = thinking and cap > 512 and schema is None
                    self.assertEqual(payload, counted); self.assertEqual(policy, count_policy)
                    self.assertEqual(original, request); self.assertEqual(cap + (900 if expected else 0), payload['max_tokens'])
                    self.assertIs(expected, policy['appliedThinking']); self.assertIs(expected, policy['legacyMayReopen'])
                    self.assertEqual(1, payload['messages'][0]['content'].count(self.api['_TEXT_LANGUAGE_RULE']))
        _, policy = self.api['prepare_text_payload'](self.request(thinking=True), 'gemma-e2b')
        self.assertIs(False, policy['appliedThinking'])

    def test_real_count_and_chat_emit_alias_bound_full_identity_without_qualifying_renderer(self):
        for alias, sha in ((FAST, FAST_SHA), (LEGACY, LEGACY_SHA)):
            engine = self.fake(alias); request = self.request(alias, schema={'type': 'object'})
            counted = engine.tokens(request, OWNER)
            request = dict(request, requestId=str(uuid.uuid4()))
            output = []; engine.chat(request, lambda response: output.append(self.drain(response)), OWNER)
            generated = json.loads(output[0].split(b'\n', 1)[0][6:])['ziphap_text_contract']
            self.assertEqual(3, counted['count']); self.assertEqual(alias, counted['modelId'])
            for receipt in (counted['textContract'], generated):
                self.assertEqual((alias, sha, 8192), (receipt['modelId'], receipt['modelSha256'], receipt['contextSize']))
                self.assertEqual('unknown', receipt['qualification']); self.assertFalse(receipt['exactRendererVerified'])
                self.assertIsNone(receipt['thinkingHonored']); self.assertEqual(1, receipt['wireVersion'])
            self.assertEqual(counted['textContract']['preparedInputSha256'], generated['preparedInputSha256'])
            self.assertEqual(request['requestId'], generated['requestId'])
            self.assertEqual(1, len(engine.completions)); self.assertFalse(engine.upstreams)

    def test_false_never_reopens_and_true_retains_existing_bounded_reopen_for_fast_profile(self):
        lines = b'data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\ndata: [DONE]\n\n'
        for thinking, expected in ((False, 1), (True, 2)):
            engine = self.fake(FAST, lines=lines)
            engine.chat(self.request(thinking=thinking), self.drain, OWNER)
            self.assertEqual(expected, len(engine.completions))
            self.assertTrue(all(response.closed for response in engine.completions))
            self.assertFalse(engine.upstreams)

    def test_cancel_is_same_owner_request_bound_and_only_acknowledges_cleanup_after_finish(self):
        for alias in (FAST, LEGACY):
            engine = self.fake(alias); observed = []
            def send(response):
                response.readline()
                with self.assertRaises(self.api['OwnershipMismatch']): engine.cancel_chat(KEY, OTHER_OWNER)
                observed.append(engine.cancel_chat(KEY, OWNER))
            with self.assertRaises(self.api['ComputeCancelled']): engine.chat(self.request(alias), send, OWNER)
            self.assertTrue(observed[0]['cancelled']); self.assertFalse(observed[0]['cleanup_complete'])
            final = engine.cancel_chat(KEY, OWNER)
            self.assertTrue(final['cancelled']); self.assertTrue(final['cleanup_complete'])
            self.assertIsNone(engine.operation); self.assertIsNone(engine.llm); self.assertFalse(engine.upstreams)
            self.assertEqual(1, len(engine.completions)); self.assertFalse(engine.starts)

    def test_unload_releases_only_current_owner_and_leaves_no_resident_child(self):
        engine = self.fake(FAST); resident = engine.llm
        with self.assertRaises(self.api['OwnershipMismatch']): engine.unload_llm(OTHER_OWNER)
        self.assertIsNone(resident.poll())
        result = engine.unload_llm(OWNER)
        self.assertTrue(result['released']); self.assertTrue(result['cleanup_complete'])
        self.assertIsNone(engine.llm); self.assertFalse(engine.owner_set); self.assertIsNotNone(resident.poll())

    def test_actual_file_verifier_requires_entire_hash_not_shared_prefix(self):
        payload = b'public synthetic GGUF stand-in, not a real model'
        digest = hashlib.sha256(payload).hexdigest()
        class File:
            def stat(self): return SimpleNamespace(st_size=len(payload), st_mtime_ns=1)
            def open(self, mode):
                self.mode = mode
                return io.BytesIO(payload)
        file = File(); verify = self.api['verify_file']
        verify(file, digest); self.assertEqual('rb', file.mode)
        with self.assertRaisesRegex(ValueError, 'MODEL_INTEGRITY'): verify(file, digest[:12])
        with self.assertRaisesRegex(ValueError, 'MODEL_INTEGRITY'): verify(file, digest[:-1] + ('0' if digest[-1] != '0' else '1'))


if __name__ == '__main__': unittest.main()
