"""Claude CLI provider boundaries; fake process streams only, no real login, account or model call."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parents[1] / "deployed"))
import codex_agent_provider as codex
import claude_agent_provider as p
import agent_provider_supervisor as sup

PREFIX = "mcp__ziphap_device__"


class Process:
    def __init__(self, events):
        self.stdin = io.BytesIO(); self.stdout = io.BytesIO(events); self.stderr = io.BytesIO()
    def poll(self): return 0
    def wait(self, timeout=None): return 0


class RecordedStdin(io.BytesIO):
    def close(self):
        if not self.closed: self.written = self.getvalue()
        super().close()


class HangingProcess:
    """stdout never ends until terminated, so only timeout/cancel can finish the run."""
    def __init__(self):
        read, write = os.pipe()
        self.stdout = os.fdopen(read, "rb"); self._write = os.fdopen(write, "wb")
        self.stdin = io.BytesIO(); self.stderr = io.BytesIO(); self.done = False
    def poll(self): return 0 if self.done else None
    def terminate(self):
        self.done = True
        try: self._write.close()
        except OSError: pass
    kill = terminate
    def wait(self, timeout=None): return 0


def lines(*events):
    return b"\n".join(json.dumps(e).encode() for e in events) + b"\n"


FINAL = dict(state="reported_done", answer="private synthetic answer", question="")


def init(tools=(PREFIX + "search_notes",), status="connected"):
    return dict(type="system", subtype="init", tools=list(tools), mcp_servers=[dict(name="ziphap_device", status=status)])


def success(**extra):
    return dict(type="result", subtype="success", is_error=False, **({"result": ""} | extra))


class ClaudeProviderTests(unittest.TestCase):
    def setUp(self):
        tree = mock.patch.object(p, "_ProcessTree", return_value=mock.Mock())
        tree.start(); self.addCleanup(tree.stop)
        self.temp = tempfile.TemporaryDirectory(prefix="ziphap-claude-test-")
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.exe = self.root / "claude.exe"; self.exe.write_bytes(b"synthetic binary, never executed")
        self.script = self.root / "fixture.py"; self.script.write_bytes(b"# synthetic MCP, never executed")
        self.config = codex.ProviderConfig(self.exe, hashlib.sha256(self.exe.read_bytes()).hexdigest(), self.exe,
                                           self.script, hashlib.sha256(self.script.read_bytes()).hexdigest(),
                                           self.root, self.root)
        self.bootstrap = dict(sessionId="00000000-0000-0000-0000-000000000001",
                              parentRunId="00000000-0000-0000-0000-000000000002",
                              agentCapability="synthetic_capability_" + "a" * 32, revision=1)

    def good(self):
        return lines(init(),
                     dict(type="assistant", message=dict(content=[dict(type="tool_use", id="t1", name=PREFIX + "search_notes", input={})])),
                     dict(type="user", message=dict(content=[dict(type="tool_result", tool_use_id="t1", content="synthetic result")])),
                     success(structured_output=FINAL))

    def run_with(self, stream, **kwargs):
        with mock.patch.object(p.subprocess, "Popen", return_value=Process(stream)) as popen:
            return p.run(self.config, self.bootstrap, "synthetic", external_allowed=True, **kwargs), popen

    def test_launch_flags_mcp_config_and_secret_placement(self):
        seen = {}
        def popen(args, **kwargs):
            seen["args"], seen["kwargs"] = args, kwargs
            static = next(self.root.glob("ziphap-agent-static-*"))
            seen["mcp"] = json.loads((static / "mcp.json").read_text(encoding="utf-8"))
            seen["static"] = static
            seen["instructions"] = (static / "instructions.txt").read_text(encoding="utf-8")
            return Process(self.good())
        env = {"ANTHROPIC_API_KEY": "k", "ANTHROPIC_AUTH_TOKEN": "t", "ANTHROPIC_BASE_URL": "u", "OPENAI_API_KEY": "o",
               "NAVER_CLIENT_ID": "fixture-id", "NAVER_CLIENT_SECRET": "fixture-secret"}
        with mock.patch.dict(os.environ, env), mock.patch.object(p.subprocess, "Popen", side_effect=popen):
            report = p.run(self.config, self.bootstrap, "synthetic", external_allowed=True)
        args = seen["args"]
        self.assertEqual("reported_done", report.state); self.assertEqual(1, report.mcp_calls)
        for flag in ("-p", "--no-session-persistence", "--strict-mcp-config", "--disable-slash-commands"):
            self.assertIn(flag, args)
        self.assertEqual("stream-json", args[args.index("--output-format") + 1])
        self.assertEqual("", args[args.index("--tools") + 1])
        self.assertEqual("mcp__ziphap_device", args[args.index("--allowedTools") + 1])
        self.assertIn("Bash", args[args.index("--disallowedTools") + 1])
        self.assertEqual("dontAsk", args[args.index("--permission-mode") + 1])
        self.assertEqual("", args[args.index("--setting-sources") + 1])
        self.assertEqual(codex.FINAL_SCHEMA, json.loads(args[args.index("--json-schema") + 1]))
        self.assertNotIn("--model", args)
        secret = self.bootstrap["agentCapability"]
        self.assertNotIn(secret, " ".join(args))
        self.assertNotIn(secret, json.dumps(seen["kwargs"]["env"]))
        self.assertNotIn(p.BOOTSTRAP_ENV, seen["kwargs"]["env"])
        for key in env:
            self.assertNotIn(key, seen["kwargs"]["env"])
        self.assertEqual(str(self.root), seen["kwargs"]["cwd"])
        server = seen["mcp"]["mcpServers"]["ziphap_device"]
        self.assertEqual("stdio", server["type"]); self.assertEqual(["-I", "-B", str(self.script.resolve())], server["args"])
        self.assertEqual(self.bootstrap, json.loads(server["env"][p.BOOTSTRAP_ENV]))
        self.assertEqual(codex.instructions_for(None, None), seen["instructions"])
        self.assertFalse(seen["static"].exists())  # bootstrap-bearing mcp.json is deleted with the temp dir
        self.assertEqual({self.exe, self.script}, set(self.root.iterdir()))

    def test_model_and_config_dir(self):
        cfg_dir = self.root / "cfg"; cfg_dir.mkdir()
        self.config = codex.ProviderConfig(**{**self.config.__dict__, "model": "sonnet", "config_dir": cfg_dir})
        _, popen = self.run_with(self.good())
        args = popen.call_args.args[0]
        self.assertEqual("sonnet", args[args.index("--model") + 1])
        self.assertEqual(str(cfg_dir), popen.call_args.kwargs["env"]["CLAUDE_CONFIG_DIR"])

    def test_structured_output_and_result_text_paths(self):
        report, _ = self.run_with(self.good())
        self.assertEqual("private synthetic answer", report.answer); self.assertNotIn("private synthetic", repr(report))
        report, _ = self.run_with(lines(init(), success(result=json.dumps(FINAL))))
        self.assertEqual("reported_done", report.state); self.assertEqual(0, report.mcp_calls)

    def test_structured_output_tool_is_the_report_not_a_device_call(self):
        # Observed with Claude Code 2.1.330: --json-schema adds the built-in StructuredOutput tool.
        stream = lines(init(tools=(PREFIX + "search_notes", "StructuredOutput")),
                       dict(type="assistant", message=dict(content=[dict(type="tool_use", id="s1", name="StructuredOutput", input=FINAL)])),
                       dict(type="user", message=dict(content=[dict(type="tool_result", tool_use_id="s1", content="ok")])),
                       success(result=""))
        seen = []
        report, _ = self.run_with(stream, observe_tool=lambda *a: seen.append(a))
        self.assertEqual(("reported_done", 0, []), (report.state, report.mcp_calls, seen))

    def test_observe_tool_and_progress(self):
        seen, updates = [], []
        self.run_with(self.good(), observe_tool=lambda *a: seen.append(a), progress=updates.append)
        self.assertEqual([("search_notes", "synthetic result")], seen)
        self.assertEqual(["starting", "planning", "tool_running", "tool_returned", "verifying"], [u["stage"] for u in updates])
        self.assertEqual("search_notes", updates[2]["toolName"]); self.assertNotIn("synthetic result", json.dumps(updates))

    def test_foreign_tool_use_and_init_tools_rejected(self):
        bad_use = lines(init(), dict(type="assistant", message=dict(content=[dict(type="tool_use", id="x", name="Bash", input={})])), success(structured_output=FINAL))
        with self.assertRaisesRegex(codex.ProviderError, "^UNEXPECTED_AGENT_TOOL$"): self.run_with(bad_use)
        with self.assertRaisesRegex(codex.ProviderError, "^UNEXPECTED_AGENT_TOOL$"):
            self.run_with(lines(init(tools=(PREFIX + "a", "Bash")), success(structured_output=FINAL)))

    def test_disconnected_server_and_error_result_fail(self):
        with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_RUN_FAILED$"):
            self.run_with(lines(init(status="failed"), success(structured_output=FINAL)))
        for result in (dict(type="result", subtype="error_max_turns", is_error=True),
                       dict(type="result", subtype="success", is_error=True, result="x")):
            with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_RUN_FAILED$"):
                self.run_with(lines(init(), result))
        with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_RUN_FAILED$"):
            self.run_with(lines(init()))  # no result at all

    def test_bad_final_format(self):
        for extra in (dict(structured_output=dict(state="reported_done", answer="a")),
                      dict(structured_output=dict(FINAL, state="bogus")),
                      dict(structured_output=dict(FINAL, answer="x" * 12001)),
                      dict(result="not json")):
            with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_FINAL_FORMAT$"):
                self.run_with(lines(init(), success(**extra)))

    def test_invalid_stream_and_unknown_events_ignored(self):
        with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_INVALID_STREAM$"):
            self.run_with(b"not json\n")
        report, _ = self.run_with(lines(dict(type="stream_event"), dict(type="rate_limit_event"), init(),
                                        dict(type="mystery", text="private"), success(structured_output=FINAL)))
        self.assertEqual("reported_done", report.state)

    def test_timeout_and_cancel(self):
        process = HangingProcess()
        with mock.patch.object(p.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_TIMEOUT$"):
                p.run(self.config, self.bootstrap, "synthetic", external_allowed=True, timeout=1)
        stopped = threading.Event(); process = HangingProcess()
        timer = threading.Timer(.3, stopped.set); timer.start(); self.addCleanup(timer.cancel)
        with mock.patch.object(p.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(codex.ProviderError, "^PROVIDER_CANCELLED$"):
                p.run(self.config, self.bootstrap, "synthetic", external_allowed=True, cancelled=stopped)
        self.assertEqual({self.exe, self.script}, set(self.root.iterdir()))

    def test_permission_and_precancel_never_launch(self):
        stopped = threading.Event(); stopped.set()
        with mock.patch.object(p.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(codex.ProviderError, "EXTERNAL_AGENT_NOT_ALLOWED"):
                p.run(self.config, self.bootstrap, "synthetic")
            with self.assertRaisesRegex(codex.ProviderError, "PROVIDER_CANCELLED"):
                p.run(self.config, self.bootstrap, "synthetic", external_allowed=True, cancelled=stopped)
            self.script.write_bytes(b"changed")
            with self.assertRaisesRegex(codex.ProviderError, "PROVIDER_ARTIFACT_CHANGED"):
                p.run(self.config, self.bootstrap, "synthetic", external_allowed=True)
            popen.assert_not_called()

    def test_stdin_carries_prompt(self):
        process = Process(self.good()); process.stdin = RecordedStdin()
        with mock.patch.object(p.subprocess, "Popen", return_value=process):
            p.run(self.config, self.bootstrap, " 원래 요청\n", external_allowed=True)
        self.assertEqual(" 원래 요청\n".encode(), process.stdin.written)


    def test_run_passes_office_persona_into_instructions_file(self):
        seen = {}
        def popen(args, **kwargs):
            static = next(self.root.glob("ziphap-agent-static-*"))
            seen["instructions"] = (static / "instructions.txt").read_text(encoding="utf-8")
            return Process(self.good())
        with mock.patch.object(p.subprocess, "Popen", side_effect=popen):
            p.run(self.config, self.bootstrap, "synthetic", external_allowed=True, office_persona="합성 페르소나")
        self.assertEqual(codex.instructions_for(None, None, "합성 페르소나"), seen["instructions"])
        self.assertIn("never grants authority", seen["instructions"])


class SharedInstructionTests(unittest.TestCase):
    def test_codex_instruction_text_is_byte_identical(self):
        self.assertEqual(codex.INSTRUCTIONS, codex.instructions_for(None, None))
        self.assertEqual(codex.INSTRUCTIONS + codex.CONTEXT_INSTRUCTIONS, codex.instructions_for({}, None))
        self.assertIn("observedReads and failedReads", codex.instructions_for({}, {}))


class SupervisorConfigTests(unittest.TestCase):
    def write(self, name, **change):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        home = Path(temp.name).resolve()
        names = dict(executable="claude.exe", python="py.exe", mcpScript="m.py", runtimeDirectory="r", workspace="w")
        base = {k: str(home / v) for k, v in names.items()}
        base.update(executableSha256="a" * 64, mcpScriptSha256="b" * 64)
        (home / "cfg").mkdir()
        base.update({k: v.replace("@HOME", str(home)) for k, v in change.items()})
        (home / name).write_text(json.dumps(base), encoding="utf-8")
        return home

    def test_provider_configurations_are_separate(self):
        home = self.write("agent-provider-claude.json", claudeConfigDir="@HOME/cfg")
        self.assertIsNone(sup.load_config(home))
        config = sup.load_config(home, "claude")
        self.assertEqual(home / "cfg", config.config_dir)
        self.assertTrue(config.orchestrate)
        wrong = self.write("agent-provider-claude.json", executable="@HOME/codex.exe")
        self.assertIsNone(sup.load_config(wrong, "claude"))
        relative = self.write("agent-provider-claude.json", claudeConfigDir="relative")
        self.assertIsNone(sup.load_config(relative, "claude"))

    def test_session_provider_cannot_switch_or_fall_back(self):
        bridge = mock.Mock()
        bridge.authenticate_phone.return_value = {"tools": []}
        codex_runner, claude_runner = mock.Mock(), mock.Mock()
        manager = sup.AgentProviderSupervisor(bridge, object(), codex_runner,
                                              claude_config=object(), claude_runner=claude_runner)
        with mock.patch.object(sup.threading, "Thread") as thread:
            manager.launch("owner", "id", "cap", "test", provider="claude")
            self.assertIs(thread.call_args.kwargs["args"][-1], claude_runner)
            self.assertEqual(["codex", "claude"], manager.health()["cloudProviders"])
            with self.assertRaisesRegex(sup.BridgeError, "PROVIDER_CHANGED"):
                manager.launch("owner", "id", "cap", "test", provider="codex")
            with self.assertRaisesRegex(sup.BridgeError, "PROVIDER_BUSY"):
                manager.launch("owner", "other", "cap", "test", provider="codex")
        manager = sup.AgentProviderSupervisor(bridge, object(), codex_runner)
        with self.assertRaisesRegex(sup.BridgeError, "PROVIDER_NOT_CONFIGURED"):
            manager.launch("owner", "id", "cap", "test", provider="claude")
        codex_runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
