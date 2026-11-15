"""Pure child lifecycle controls. No CLI/account/network/device is contacted."""
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest.mock import Mock

from agent_bridge import AgentBridge, BridgeError
from agent_provider_supervisor import AgentProviderSupervisor, load_config
from codex_agent_provider import AgentReport, ProviderError

OWNER = "10000000-0000-4000-8000-000000000001"
SID = "20000000-0000-4000-8000-000000000002"


class ProviderSupervisorTests(unittest.TestCase):
    def fixture(self, runner=None):
        bridge = Mock()
        bridge.authenticate_phone.return_value = {"revision": 1}
        bridge.dispatch_authenticated.return_value = (200, {"state": "REVOKED"})
        bridge.bootstrap_agent.return_value = {"sessionId": SID, "parentRunId": OWNER,
                                               "agentCapability": "synthetic_" + "a" * 40, "revision": 1}
        return bridge, AgentProviderSupervisor(bridge, object(), runner or (lambda *a, **kw: AgentReport("reported_done", "synthetic", "", 1, 0)))

    def finished(self, manager):
        for _ in range(100):
            value = manager.status(OWNER, SID, "phone")
            if value["state"] == "FINISHED":
                return value
            threading.Event().wait(.005)
        self.fail("synthetic runner did not finish")

    def test_launch_deduplicates_after_finish_and_report_has_no_effect_verification(self):
        bridge, manager = self.fixture()
        manager.launch(OWNER, SID, "phone", "synthetic owner request")
        report = self.finished(manager)
        manager.launch(OWNER, SID, "phone", "synthetic owner request")
        bridge.bootstrap_agent.assert_called_once()
        self.assertEqual({"state", "report", "errorCode"}, set(report))
        self.assertNotIn("verified", json.dumps(report).lower())
        self.assertEqual("reported_done", report["report"]["state"])
        self.assertEqual({"providerConfigured": True, "activeProviders": 0}, manager.health())

    def test_phone_authority_and_exact_request_are_checked_before_bootstrap(self):
        bridge, manager = self.fixture()
        bridge.authenticate_phone.side_effect = BridgeError("REQUEST_DIGEST", 403)
        with self.assertRaises(BridgeError):
            manager.launch(OWNER, SID, "agent-role", "different prompt")
        bridge.bootstrap_agent.assert_not_called()
        self.assertEqual({}, manager.children)

    def test_different_retry_never_relaunches(self):
        bridge, manager = self.fixture()
        manager.launch(OWNER, SID, "phone", "first")
        self.finished(manager)
        with self.assertRaisesRegex(BridgeError, "PROVIDER_REQUEST_CHANGED"):
            manager.launch(OWNER, SID, "phone", "second")
        bridge.bootstrap_agent.assert_called_once()

    def test_cancellation_is_owned_and_revocation_reaches_child(self):
        started = threading.Event(); release = threading.Event(); observed = []
        def runner(*args, **kwargs):
            started.set(); self.assertTrue(release.wait(2))
            observed.append(kwargs["cancelled"].is_set())
            raise ProviderError("PROVIDER_CANCELLED")
        bridge, manager = self.fixture(runner)
        manager.launch(OWNER, SID, "phone", "owner")
        self.assertTrue(started.wait(1))
        self.assertEqual("CANCELLING", manager.cancel(OWNER, SID, "phone")["state"])
        release.set(); result = self.finished(manager)
        self.assertEqual([True], observed)
        self.assertIsNone(result["report"])
        self.assertEqual("PROVIDER_CANCELLED", result["errorCode"])

    def test_live_authority_revocation_without_explicit_cancel_stops_child(self):
        started = threading.Event(); release = threading.Event(); observed = []
        def runner(*args, **kwargs):
            started.set(); self.assertTrue(release.wait(2))
            observed.append(kwargs["cancelled"].is_set())
            raise ProviderError("PROVIDER_CANCELLED")
        bridge, manager = self.fixture(runner)
        manager.launch(OWNER, SID, "phone", "owner")
        self.assertTrue(started.wait(1))
        bridge.authenticate_phone.side_effect = BridgeError("SESSION_EXPIRED", 410)
        release.set()
        for _ in range(100):
            with manager.lock:
                if manager.children[SID].state == "FINISHED": break
            threading.Event().wait(.005)
        self.assertEqual([True], observed)

    def test_unconfigured_and_duplicate_config_fail_closed(self):
        bridge, manager = self.fixture(); manager.config = None
        with self.assertRaisesRegex(BridgeError, "PROVIDER_NOT_CONFIGURED"):
            manager.launch(OWNER, SID, "phone", "owner")
        with tempfile.TemporaryDirectory() as root:
            self.assertIsNone(load_config(root))
            Path(root, "agent-provider.json").write_text('{"executable":"a","executable":"b"}')
            self.assertIsNone(load_config(root))

    def test_status_cancel_require_phone_role_even_after_finished(self):
        bridge, manager = self.fixture()
        manager.launch(OWNER, SID, "phone", "owner"); self.finished(manager)
        bridge.authenticate_phone.side_effect = BridgeError("CAPABILITY_ROLE", 403)
        for operation in (manager.status, manager.cancel):
            with self.assertRaises(BridgeError): operation(OWNER, SID, "agent")

    def real_fixture(self):
        owner = str(uuid.uuid4()); request = "synthetic lifecycle owner request"
        bridge = AgentBridge()
        status, created = bridge.dispatch_authenticated("POST", "/v1/agent/sessions", owner, {
            "version": 1, "parentRunId": owner, "projectBinding": "synthetic-project", "authorityRevision": 1,
            "allowExternalAgent": True, "expiresAfterMs": 60000, "pcWorkspaceProfile": "phone-only",
            "requestDigest": hashlib.sha256(request.encode()).hexdigest(),
            "phoneTools": [{"name": "save_note", "description": "synthetic note",
                            "inputSchema": {"type": "object", "properties": {"content": {"type": "string"}},
                                            "required": ["content"], "additionalProperties": False}}]})
        self.assertEqual(201, status)
        sid, phone = created["sessionId"], created["phoneCapability"]
        path = "/v1/agent/sessions/" + sid
        self.assertEqual(200, bridge.dispatch_authenticated("GET", path, owner, capability=phone)[0])
        started, release = threading.Event(), threading.Event(); self.addCleanup(release.set)
        def runner(*args, **kwargs):
            started.set()
            if not release.wait(2): raise ProviderError("PROVIDER_TIMEOUT")
            raise ProviderError("PROVIDER_RUN_FAILED")
        manager = AgentProviderSupervisor(bridge, object(), runner)
        manager.launch(owner, sid, phone, request); self.assertTrue(started.wait(1))
        agent = bridge.bootstrap_agent(owner, sid, local_authenticated=True)["agentCapability"]
        call_id = str(uuid.uuid4())
        self.assertEqual(202, bridge.dispatch_authenticated("POST", path + "/call", owner,
            {"callId": call_id, "tool": "save_note", "arguments": {"content": "synthetic"}}, agent)[0])
        return bridge, manager, owner, sid, phone, path, call_id, release

    def test_actual_broker_queued_call_is_cancelled_before_provider_cancel_ack(self):
        bridge, manager, owner, sid, phone, path, call_id, release = self.real_fixture()
        manager.cancel(owner, sid, phone)
        call = next(row for row in bridge.task_evidence(owner, sid, phone)["phoneCalls"] if row["callId"] == call_id)
        self.assertEqual("CANCELLED", call["state"])
        release.set()

    def test_actual_broker_claimed_call_stays_unknown_after_cancel_never_requeued(self):
        bridge, manager, owner, sid, phone, path, call_id, release = self.real_fixture()
        _, current = bridge.dispatch_authenticated("GET", path, owner, capability=phone)
        self.assertEqual(200, bridge.dispatch_authenticated("POST", path + "/claim", owner,
            {"callId": call_id, "sequence": 1, "revision": current["revision"]}, phone)[0])
        manager.cancel(owner, sid, phone)
        call = next(row for row in bridge.task_evidence(owner, sid, phone)["phoneCalls"] if row["callId"] == call_id)
        self.assertEqual("UNKNOWN_EFFECT", call["state"])
        release.set()

    def test_actual_broker_provider_failure_revokes_queued_call_before_finished(self):
        bridge, manager, owner, sid, phone, path, call_id, release = self.real_fixture()
        release.set()
        for _ in range(200):
            state = manager.status(owner, sid, phone)
            if state["state"] == "FINISHED": break
            threading.Event().wait(.005)
        self.assertEqual("FINISHED", state["state"])
        self.assertEqual("PROVIDER_RUN_FAILED", state["errorCode"])
        call = next(row for row in bridge.task_evidence(owner, sid, phone)["phoneCalls"] if row["callId"] == call_id)
        self.assertEqual("CANCELLED", call["state"])


if __name__ == "__main__":
    unittest.main()
