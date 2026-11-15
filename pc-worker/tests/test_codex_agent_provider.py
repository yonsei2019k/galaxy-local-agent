"""Pure provider boundaries; no actual process, account, worker or model calls."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("ziphap_codex_provider_test_subject", Path(__file__).parents[1]/"deployed/codex_agent_provider.py")
p = importlib.util.module_from_spec(spec);sys.modules[spec.name] = p;spec.loader.exec_module(p)


class Process:
    def __init__(self, events):
        self.stdin = io.BytesIO();self.stdout = io.BytesIO(events);self.stderr = io.BytesIO()
    def poll(self):return 0
    def wait(self, timeout=None):return 0


class ProviderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ziphap-provider-test-")
        self.addCleanup(self.temp.cleanup);self.root = Path(self.temp.name).resolve()
        self.exe = self.root/"fixture.exe";self.exe.write_bytes(b"synthetic binary, never executed")
        self.script = self.root/"fixture.py";self.script.write_bytes(b"# synthetic MCP, never executed")
        self.config = p.ProviderConfig(self.exe,hashlib.sha256(self.exe.read_bytes()).hexdigest(),self.exe,
            self.script,hashlib.sha256(self.script.read_bytes()).hexdigest(),self.root,self.root)
        self.bootstrap = dict(sessionId="00000000-0000-0000-0000-000000000001",parentRunId="00000000-0000-0000-0000-000000000002",
            agentCapability="synthetic_capability_"+"a"*32,revision=1)
    def launch(self, bootstrap=None, allowed=True):
        return p.build_launch(self.config, self.bootstrap if bootstrap is None else bootstrap,
                              self.root/"instructions.txt",self.root/"schema.json",allowed)
    def test_legacy_external_permission_never_launches(self):
        with mock.patch.object(p.subprocess,"Popen") as process:
            with self.assertRaisesRegex(p.ProviderError,"EXTERNAL_AGENT_NOT_ALLOWED"):
                p.run(self.config,self.bootstrap,"synthetic request")
            process.assert_not_called()
    def test_pins_checked_before_launch(self):
        self.script.write_bytes(b"changed")
        with self.assertRaisesRegex(p.ProviderError,"PROVIDER_ARTIFACT_CHANGED"):self.launch()
    def test_bootstrap_cannot_escalate_to_phone_or_change_binding_shape(self):
        for value in [self.bootstrap|dict(phoneCapability="forbidden"),self.bootstrap|dict(revision=True),
                      self.bootstrap|dict(parentRunId="other"),self.bootstrap|dict(agentCapability="short"),{}]:
            with self.assertRaisesRegex(p.ProviderError,"INVALID_AGENT_BOOTSTRAP"):self.launch(value)
    def test_secret_only_in_child_environment_and_only_scoped_mcp_is_configured(self):
        with mock.patch.dict(p.os.environ,{"OPENAI_API_KEY":"fixture-api-key","CODEX_API_KEY":"fixture-api-key"}):
            args, environment = self.launch()
        self.assertNotIn(self.bootstrap["agentCapability"]," ".join(args))
        self.assertEqual(self.bootstrap,json.loads(environment[p.BOOTSTRAP_ENV]))
        self.assertNotIn("OPENAI_API_KEY",environment);self.assertNotIn("CODEX_API_KEY",environment)
        self.assertIn("--ignore-user-config",args);self.assertIn("--ephemeral",args)
        self.assertEqual("read-only",args[args.index("--sandbox")+1])
        self.assertIn("features.code_mode_host=true",args)
        for feature in ("shell_tool","unified_exec","apps","plugins","computer_use"):
            self.assertIn(feature,p.DISABLED_FEATURES)
    def test_preexisting_cancel_never_launches(self):
        stopped = threading.Event();stopped.set()
        with mock.patch.object(p.subprocess,"Popen") as process:
            with self.assertRaisesRegex(p.ProviderError,"PROVIDER_CANCELLED"):
                p.run(self.config,self.bootstrap,"synthetic",external_allowed=True,cancelled=stopped)
            process.assert_not_called()
    def events(self, server="ziphap_device"):
        return b"\n".join(json.dumps(event).encode() for event in [
            dict(type="item.completed",item=dict(type="mcp_tool_call",server=server)),
            dict(type="item.completed",item=dict(type="agent_message",text=json.dumps(dict(state="reported_done",answer="private synthetic answer",question="")))),
            dict(type="turn.completed")])+b"\n"
    def test_actual_stream_report_remains_untrusted_and_does_not_persist_personal_output(self):
        with mock.patch.object(p.subprocess,"Popen",return_value=Process(self.events())):
            report = p.run(self.config,self.bootstrap,"private synthetic owner",external_allowed=True)
        self.assertEqual("reported_done",report.state);self.assertEqual(1,report.mcp_calls)
        self.assertNotIn("private synthetic answer",repr(report))
        self.assertEqual({self.exe,self.script},set(self.root.iterdir()))
    def test_other_mcp_server_never_becomes_a_successful_provider_report(self):
        with mock.patch.object(p.subprocess,"Popen",return_value=Process(self.events("other"))):
            with self.assertRaisesRegex(p.ProviderError,"UNEXPECTED_AGENT_TOOL"):
                p.run(self.config,self.bootstrap,"synthetic",external_allowed=True)
    def test_duplicate_stream_keys_fail_closed(self):
        with mock.patch.object(p.subprocess,"Popen",return_value=Process(b'{"type":"turn.completed","type":"turn.failed"}\n')):
            with self.assertRaisesRegex(p.ProviderError,"PROVIDER_INVALID_STREAM"):
                p.run(self.config,self.bootstrap,"synthetic",external_allowed=True)


if __name__ == "__main__":unittest.main()
