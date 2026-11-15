"""Future root-run host-only timing tests. No real process, GPU, model, file verification or network."""
import ast
from pathlib import Path
import re
from types import SimpleNamespace
import threading
import unittest

SOURCE = Path(__file__).resolve().parents[1]/'deployed/engines.py'
SHA = '03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8'


class Clock:
    value = 0.0
    def monotonic(self): return self.value
    def advance(self, amount): self.value += amount


def methods(namespace):
    tree = ast.parse(SOURCE.read_text(encoding='utf-8'))
    engine = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Engines')
    selected = [node for node in engine.body if isinstance(node, ast.FunctionDef) and node.name in {'load_llm', 'start_llm'}]
    module = ast.Module(body=[ast.ClassDef(name='MeasuredEngine', bases=[], keywords=[], body=selected,
                                          decorator_list=[], type_params=[])], type_ignores=[])
    exec(compile(ast.fix_missing_locations(module), '<host-timing-only>', 'exec'), namespace)
    return namespace['MeasuredEngine']


class ColdLoadTimingTest(unittest.TestCase):
    def environment(self, fail_first=False):
        clock = Clock(); verified = []; starts = []
        def verify(path, expected): verified.append((path, expected)); clock.advance(10)
        namespace = dict(time=clock, re=re, GEMMAS={'pc-text': (Path('synthetic-model'), SHA)},
                         verify_file=verify, available_memory=lambda: 8*1024**3, Busy=type('Busy', (Exception,), {}))
        base = methods(namespace)
        class Fake(base):
            def __init__(self):
                self.lock = threading.RLock(); self.llm = None; self.llm_alias = None; self.llm_ctx = 0
            def stop_llm(self): self.llm = None; self.llm_alias = None; self.llm_ctx = 0
            def start_llm(self, path, layers):
                starts.append(layers)
                if fail_first and len(starts) == 1: clock.advance(7); raise RuntimeError('synthetic-failure')
                clock.advance(20); self.llm = SimpleNamespace(poll=lambda: None)
                return {'processLaunchMs': 100.0, 'readyWaitMs': 19900.0}
        return Fake(), clock, verified, starts, base, namespace

    def request(self): return dict(modelId='pc-text', modelShaPrefix=SHA, contextSize=8192)

    def test_hash_and_native_readiness_are_distinct_measured_regions(self):
        engine, _, verified, starts, _, _ = self.environment()
        reply = engine.load_llm(self.request()); timing = reply['loadTimings']
        self.assertEqual(SHA, reply['modelSha256']); self.assertEqual(8192, reply['contextSize'])
        self.assertTrue(reply['structuredOutput']); self.assertEqual([30], starts); self.assertEqual(1, len(verified))
        self.assertEqual(10000.0, timing['verifyFileMs']); self.assertEqual(20000.0, timing['nativeAttemptsMs'])
        self.assertEqual(100.0, timing['successfulLaunchMs']); self.assertEqual(19900.0, timing['successfulReadyWaitMs'])
        self.assertEqual(30000.0, timing['totalMs']); self.assertFalse(timing['residentReused'])

    def test_existing_resident_fast_path_remains_unchanged_without_new_warm_reuse(self):
        engine, _, verified, starts, _, _ = self.environment()
        engine.load_llm(self.request()); second = engine.load_llm(self.request())['loadTimings']
        self.assertTrue(second['residentReused']); self.assertEqual(0, second['attemptCount'])
        self.assertEqual(0.0, second['verifyFileMs']); self.assertIsNone(second['successfulReadyWaitMs'])
        self.assertEqual(1, len(starts)); self.assertEqual(1, len(verified))

    def test_retry_count_keeps_existing_ladder_and_includes_failed_attempt_wall_time(self):
        engine, _, _, starts, _, _ = self.environment(True)
        timing = engine.load_llm(self.request())['loadTimings']
        self.assertEqual([30, 20], starts); self.assertEqual(2, timing['attemptCount'])
        self.assertEqual(27000.0, timing['nativeAttemptsMs']); self.assertEqual(37000.0, timing['totalMs'])

    def test_start_method_measures_process_creation_separately_from_ready_wait(self):
        _, clock, _, _, base, namespace = self.environment()
        commands = []
        class Socket:
            def __enter__(self): return self
            def __exit__(self, *_): return False
            def bind(self, _): pass
            def getsockname(self): return ('synthetic-host', 1)
        def launch(command, **kwargs):
            commands.append(command); self.assertEqual({'LLAMA_API_KEY': 'synthetic-key'}, kwargs['env'])
            clock.advance(3); return SimpleNamespace(poll=lambda: None)
        namespace.update(socket=SimpleNamespace(socket=Socket), uuid=SimpleNamespace(uuid4=lambda: SimpleNamespace(hex='synthetic-key')),
                         __import__=lambda name: SimpleNamespace(environ={}), TOOLS=Path('synthetic-runtime'), HOME=Path('synthetic-home'),
                         FLAGS=0, subprocess=SimpleNamespace(Popen=launch, DEVNULL=None), constrain=lambda _: clock.advance(1))
        engine = base()
        def upstream(*_args, **_kwargs):
            clock.advance(2)
            class Reply:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *_): return False
            return Reply()
        engine.upstream = upstream
        result = engine.start_llm(Path('synthetic-model'), 30)
        self.assertEqual(4000.0, result['processLaunchMs']); self.assertEqual(2000.0, result['readyWaitMs'])
        self.assertEqual('8192', commands[0][commands[0].index('--ctx-size')+1])
        self.assertEqual('30', commands[0][commands[0].index('--n-gpu-layers')+1])


if __name__ == '__main__': unittest.main()
