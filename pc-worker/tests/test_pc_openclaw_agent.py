import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from uuid import UUID

from pc_openclaw_agent import PcAgentError, PcOpenClawAgent, Subscription, SubscriptionLoginRequired
from pc_openclaw_agent import create_prompt, load_config, parse_result, run_openclaw
from pc_openclaw_agent import runtime_config, verify_claude_subscription, verify_codex_subscription

OWNER = str(UUID(int=1))
OTHER = str(UUID(int=2))
KEY = str(UUID(int=3))
MODEL = "gpt-5.4"
CODEX = Subscription("codex", MODEL, Path("fixture"), oauth_profile="openai:test")
FAKE = SimpleNamespace(subscriptions={"codex": CODEX})
BODY = {"jobId": KEY, "request": "문서 검토", "provider": "codex", "allowCloud": True}


def envelope(**overrides):
    return json.dumps({"ok": True, "status": "ok", "final": "문서 검토",
                       "model": MODEL, "provider": "openai", "codeModeEngaged": False,
                       "toolSummary": {"tools": []}, **overrides}, ensure_ascii=False).encode()


def eventual_state(agent):
    for _ in range(100):
        row = agent.inspect(OWNER, KEY)
        if row["state"] in ("RESULT_READY", "FAILED", "CANCELLED"):
            return row
        time.sleep(0.01)
    raise AssertionError("PC job did not reach a terminal state")


class JobTests(unittest.TestCase):
    def test_launch_is_idempotent_and_owner_bound_through_stop(self):
        entered, exited = threading.Event(), threading.Event()

        def runner(config, subscription, request, cancel):
            entered.set()
            cancel.wait(3)
            exited.set()
            return "must not disclose after cancellation"

        agent = PcOpenClawAgent(FAKE, runner)
        self.assertEqual(agent.start(OWNER, BODY)["state"], "RUNNING")
        self.assertTrue(entered.wait(1))
        self.assertEqual(agent.start(OWNER, BODY)["state"], "RUNNING")
        with self.assertRaises(PcAgentError) as denied:
            agent.inspect(OTHER, KEY, stop=True)
        self.assertEqual(denied.exception.status, 404)
        with self.assertRaises(PcAgentError):
            agent.start(OWNER, {**BODY, "provider": "claude"})
        self.assertEqual(agent.inspect(OWNER, KEY, stop=True)["state"], "STOPPING")
        self.assertTrue(exited.wait(1))
        result = eventual_state(agent)
        self.assertEqual(result["state"], "CANCELLED")
        self.assertNotIn("answer", result)

    def test_transport_returns_result_and_expires_receipt(self):
        now = [0]
        agent = PcOpenClawAgent(FAKE, lambda *_: "결과", clock=lambda: now[0])
        capabilities = agent.dispatch("GET", "/v1/pc-agent/capabilities", None, None)[1]
        self.assertEqual(capabilities["providers"], ["codex"])
        self.assertEqual(agent.dispatch("POST", "/v1/pc-agent/jobs", OWNER, lambda _: BODY)[0], 202)
        self.assertEqual(eventual_state(agent)["answer"], "결과")
        self.assertEqual(agent.dispatch("GET", f"/v1/pc-agent/jobs/{KEY}", OWNER, None)[1]["execution"], "pc")
        now[0] = 901
        with self.assertRaises(PcAgentError):
            agent.inspect(OWNER, KEY)

    def test_rejects_unconfigured_route_and_phone_supplied_path_or_model(self):
        agent = PcOpenClawAgent(FAKE)
        for body in ({**BODY, "path": "outside"}, {**BODY, "model": "uncontrolled"},
                     {**BODY, "provider": "ollama"}, {**BODY, "allowCloud": False}):
            with self.subTest(body=body), self.assertRaises(ValueError):
                agent.start(OWNER, body)
        with self.assertRaises(ValueError):
            agent.start(None, BODY)
        with self.assertRaises(PcAgentError) as denied:
            agent.start(OWNER, {**BODY, "provider": "claude"})
        self.assertEqual(denied.exception.code, "PC_AGENT_SUBSCRIPTION_NOT_CONFIGURED")


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.workspace = self.home / "selected"
        self.workspace.mkdir()
        (self.workspace / "source.md").write_text("로컬 PC 문서", encoding="utf-8")
        self.data = {"node": str(self.home / "node.exe"), "nodeSha256": "0" * 64,
                     "entry": str(self.home / "fixture.mjs"), "entrySha256": "1" * 64,
                     "workspace": str(self.workspace), "sources": ["source.md"],
                     "subscriptions": {"codex": {"model": MODEL, "state": str(self.home),
                                                 "oauthProfile": "openai:test"}}}

    def configuration(self):
        (self.home / "pc-openclaw.json").write_text(json.dumps(self.data), encoding="utf-8")
        return load_config(self.home)

    def test_sources_are_bounded_and_profile_requires_oauth(self):
        config = self.configuration()
        self.assertIn("로컬 PC 문서", create_prompt(config, "요약").decode())
        (self.workspace / "source.md").write_bytes(b"x" * 48001)
        with self.assertRaises(ValueError):
            create_prompt(config, "요약")
        self.data["sources"] = ["../outside.md"]
        self.assertIsNone(self.configuration())
        self.data["sources"] = ["source.md"]
        self.data["subscriptions"]["codex"]["oauthProfile"] = "anthropic:test"
        self.assertIsNone(self.configuration())

    def test_report_rejects_malformed_and_unexpected_provider_or_tool(self):
        self.assertEqual(parse_result(envelope(), CODEX), "문서 검토")
        for raw in (envelope(provider="anthropic"), envelope(model="other"),
                    envelope(codeModeEngaged=True), envelope(toolSummary={"tools": ["write"]}),
                    b'{"ok":true,"ok":true}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_result(raw, CODEX)

    def test_codex_requires_only_the_selected_oauth_profile(self):
        selected = {"id": "openai:test", "provider": "openai", "type": "oauth"}
        with patch("pc_openclaw_agent.run_headless") as child:
            for profiles in ([], [{**selected, "type": "api_key"}],
                             [selected, {**selected, "id": "openai:other"}]):
                child.return_value = json.dumps({"provider": "openai", "profiles": profiles}).encode()
                with self.subTest(profiles=profiles), self.assertRaises(SubscriptionLoginRequired):
                    verify_codex_subscription("node.exe", "entry.mjs", "openai:test",
                                              self.home, {}, threading.Event())

    def test_claude_requires_native_subscription_login(self):
        claude = Subscription("claude", "sonnet", self.home, cli_home=self.home)
        config = runtime_config(claude)
        defaults = config["agents"]["defaults"]
        self.assertEqual(defaults["models"]["anthropic/sonnet"]["agentRuntime"]["id"], "claude-cli")
        self.assertEqual(config["plugins"]["allow"], ["anthropic"])
        self.assertTrue(config["tools"]["fs"]["workspaceOnly"])
        self.assertNotIn("cliBackends", defaults)
        with patch("pc_openclaw_agent.run_headless") as child:
            child.return_value = b'{"loggedIn":true,"authMethod":"apiKey","apiProvider":"firstParty"}'
            with self.assertRaises(SubscriptionLoginRequired):
                verify_claude_subscription("Claude.exe", self.home, {}, threading.Event())
            child.return_value = b'{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}'
            verify_claude_subscription("Claude.exe", self.home, {}, threading.Event())
            self.assertEqual(child.call_args.args[0], ["Claude.exe", "auth", "status", "--json"])

    @unittest.skipUnless(shutil.which("node.exe"), "Windows Node fixture")
    def test_real_headless_child_uses_stdin_and_subscription_runtime(self):
        node = Path(shutil.which("node.exe"))
        script = self.home / "fixture.mjs"
        script.write_text("""
import fs from 'node:fs';
const args = process.argv.slice(2);
if (args.join(' ') === 'models auth list --provider openai --json') {
    process.stdout.write(JSON.stringify({provider:'openai', profiles:[
        {id:'openai:test',provider:'openai',type:'oauth'}]}));
    process.exit(0);
}
const config = JSON.parse(fs.readFileSync(args[args.indexOf('--config') + 1], 'utf-8'));
const prompt = fs.readFileSync(0, 'utf-8');
if (args[0] !== 'agent' || args[1] !== 'exec' || !args.includes('--json')
    || args.includes('문서 요청') || !prompt.includes('문서 요청') || !prompt.includes('로컬 PC 문서')
    || config.auth.profiles['openai:test'].mode !== 'oauth'
    || config.agents.defaults.models['openai/gpt-5.4'].agentRuntime.id !== 'codex'
    || config.agents.defaults.model.fallbacks.length !== 0
    || process.env.OPENAI_API_KEY || process.env.ANTHROPIC_API_KEY
    || config.tools.allow.join(',') !== 'session_status' || config.tools.elevated.enabled)
    process.exit(2);
process.stdout.write(JSON.stringify({ok:true,status:'ok',final:'문서 검토',
    provider:'openai',model:'gpt-5.4',codeModeEngaged:false,toolSummary:{tools:[]}}));
""", encoding="utf-8")
        self.data.update(node=str(node), nodeSha256=hashlib.sha256(node.read_bytes()).hexdigest(),
                         entry=str(script), entrySha256=hashlib.sha256(script.read_bytes()).hexdigest())
        config = self.configuration()
        with patch.dict("os.environ", {"OPENAI_API_KEY": "fixture", "ANTHROPIC_API_KEY": "fixture"}):
            self.assertEqual(run_openclaw(config, config.subscriptions["codex"], "문서 요청",
                                          threading.Event()), "문서 검토")


if __name__ == "__main__":
    unittest.main()
