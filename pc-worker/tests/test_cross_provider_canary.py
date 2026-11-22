"""Cross-provider canary contract using synthetic data and a real in-memory broker."""
import copy
import hashlib
import sys
import threading
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployed"))
from agent_bridge import AgentBridge, BridgeError
from agent_provider_supervisor import AgentProviderSupervisor
from codex_agent_provider import AgentReport


def session(bridge):
    owner = str(uuid.uuid4())
    request = "synthetic request"
    grant = {"version": 1, "parentRunId": owner, "projectBinding": "synthetic",
             "authorityRevision": 1, "allowExternalAgent": True, "expiresAfterMs": 60000,
             "pcWorkspaceProfile": "phone-only", "requestDigest": hashlib.sha256(request.encode()).hexdigest(),
             "phoneTools": [{"name": "get_current_datetime", "description": "synthetic clock",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}}]}
    status, created = bridge.dispatch_authenticated("POST", "/v1/agent/sessions", owner, grant)
    assert status == 201
    sid, phone = created["sessionId"], created["phoneCapability"]
    assert bridge.dispatch_authenticated("GET", "/v1/agent/sessions/" + sid, owner, capability=phone)[0] == 200
    return owner, sid, phone, request


def context(text):
    return {"version": 1, "recentTurns": [{"role": "user", "text": text,
            "sourceRunId": "30000000-0000-4000-8000-000000000003"}], "previousTask": None}


class CrossProviderCanary(unittest.TestCase):
    def test_claude_gets_detached_context_and_retry_cannot_swap_provider_or_history(self):
        bridge = AgentBridge()
        owner, sid, phone, request = session(bridge)
        entered, release = threading.Event(), threading.Event()
        captured = []
        def claude(config, bootstrap, owner_request, *, external_allowed, cancelled, timeout, conversation_context):
            entered.set()
            self.assertTrue(release.wait(5))
            captured.append(copy.deepcopy(conversation_context))
            return AgentReport("reported_done", "synthetic", "", 1, 0)
        def codex(*args, **kwargs):
            self.fail("A provider swap executed")
        manager = AgentProviderSupervisor(bridge, object(), codex, claude_config=object(), claude_runner=claude)
        history = context("original synthetic turn")
        self.addCleanup(release.set)
        manager.launch(owner, sid, phone, request, history, provider="claude")
        self.assertTrue(entered.wait(5))
        history["recentTurns"][0]["text"] = "mutated after launch"
        with self.assertRaisesRegex(BridgeError, "PROVIDER_CHANGED"):
            manager.launch(owner, sid, phone, request, context("original synthetic turn"), provider="codex")
        with self.assertRaisesRegex(BridgeError, "CONTEXT_CHANGED"):
            manager.launch(owner, sid, phone, request, context("foreign synthetic turn"), provider="claude")
        release.set()
        for _ in range(200):
            if manager.status(owner, sid, phone)["state"] == "FINISHED":
                break
            threading.Event().wait(.01)
        self.assertEqual("FINISHED", manager.status(owner, sid, phone)["state"])
        self.assertEqual("original synthetic turn", captured[0]["recentTurns"][0]["text"])

    def test_unconfigured_claude_never_falls_back_to_codex(self):
        bridge = AgentBridge()
        owner, sid, phone, request = session(bridge)
        calls = []
        def codex(*args, **kwargs):
            calls.append(True)
        manager = AgentProviderSupervisor(bridge, object(), codex)
        with self.assertRaisesRegex(BridgeError, "PROVIDER_NOT_CONFIGURED"):
            manager.launch(owner, sid, phone, request, context("synthetic"), provider="claude")
        self.assertEqual([], calls)
        self.assertEqual({}, manager.children)


if __name__ == "__main__":
    unittest.main()
