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
from test_provider_conversation_context import fixture as context_fixture

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

    def test_early_question_reconsiders_once_in_same_session_with_remaining_budget(self):
        attempts = []
        def runner(config, bootstrap, request, *, read_recovery=None, timeout, **kwargs):
            attempts.append((bootstrap, request, read_recovery, timeout))
            return AgentReport("needs_input", "", "필수 선택", 3, 1)
        bridge, manager = self.fixture(runner)
        bridge.read_recovery_context.return_value = {"acceptedTools": ["search_web"], "failedReads": []}
        manager.launch(OWNER, SID, "phone", "합성 조사")
        report = self.finished(manager)["report"]
        self.assertEqual(2, len(attempts))
        self.assertEqual(attempts[0][:2], attempts[1][:2])
        self.assertIsNone(attempts[0][2])
        self.assertEqual(bridge.read_recovery_context.return_value, attempts[1][2])
        self.assertLessEqual(attempts[1][3], attempts[0][3])
        self.assertEqual(("needs_input", 6, 2), (report["state"], report["elapsedMs"], report["mcpCalls"]))
        bridge.bootstrap_agent.assert_called_once()

    def test_history_reaches_explicitly_supported_runner_without_changing_owner_request_binding(self):
        seen = []
        def runner(config, bootstrap, request, *, conversation_context=None, **kwargs):
            seen.append((request, conversation_context))
            return AgentReport("partial", "현재 요청 결과", "", 1, 0)
        bridge, manager = self.fixture(runner)
        context = context_fixture(); expected = json.loads(json.dumps(context))
        manager.launch(OWNER, SID, "phone", "그 작업 이어서", conversation_context=context)
        result = self.finished(manager)
        context["recentTurns"][0]["text"] = "caller mutation after capture"
        self.assertEqual([("그 작업 이어서", expected)], seen)
        bound_requests = [call.kwargs["owner_request"] for call in bridge.authenticate_phone.call_args_list
                          if call.kwargs["owner_request"] is not None]
        self.assertTrue(bound_requests)
        self.assertTrue(all(request == "그 작업 이어서" for request in bound_requests))
        self.assertEqual(hashlib.sha256("그 작업 이어서".encode()).hexdigest(), manager.children[SID].digest)
        self.assertEqual("partial", result["report"]["state"])
        self.assertNotIn("recentTurns", repr(manager.children[SID]))
        self.assertNotIn("recentTurns", json.dumps(result))

    def test_context_is_part_of_retry_identity_including_terminal_runs(self):
        calls = []
        def runner(*args, conversation_context=None, **kwargs):
            calls.append(conversation_context); return AgentReport("partial", "synthetic", "", 1, 0)
        bridge, manager = self.fixture(runner)
        context = context_fixture()
        manager.launch(OWNER, SID, "phone", "current", context); self.finished(manager)
        manager.launch(OWNER, SID, "phone", "current", dict(reversed(list(context.items()))))
        for changed in (None, context | {"previousTask": None}):
            with self.assertRaisesRegex(BridgeError, "PROVIDER_CONTEXT_CHANGED"):
                manager.launch(OWNER, SID, "phone", "current", changed)
        self.assertEqual(1, len(calls)); bridge.bootstrap_agent.assert_called_once()

    def test_new_context_on_legacy_sid_cannot_silently_reuse_the_prior_report(self):
        bridge, manager = self.fixture()
        manager.launch(OWNER, SID, "phone", "current"); self.finished(manager)
        with self.assertRaisesRegex(BridgeError, "PROVIDER_CONTEXT_CHANGED"):
            manager.launch(OWNER, SID, "phone", "current", context_fixture())
        bridge.bootstrap_agent.assert_called_once()

    def test_nonempty_context_requires_declared_runner_support_before_bootstrap(self):
        def legacy(config, bootstrap, request, *, external_allowed, cancelled, timeout):
            self.fail("legacy runtime must not discard context")
        def opaque(*args, **kwargs):
            self.fail("generic kwargs are not an explicit context contract")
        for runner in (legacy, opaque):
            bridge, manager = self.fixture(runner)
            with self.assertRaisesRegex(BridgeError, "PROVIDER_CONTEXT_UNSUPPORTED"):
                manager.launch(OWNER, SID, "phone", "current", context_fixture())
            bridge.bootstrap_agent.assert_not_called(); self.assertEqual({}, manager.children)

    def test_invalid_context_never_gets_authority_or_launches(self):
        bridge, manager = self.fixture()
        for context in ({}, context_fixture() | {"approval": True}, context_fixture() | {"version": True}):
            with self.assertRaisesRegex(BridgeError, "INVALID_CONVERSATION_CONTEXT"):
                manager.launch(OWNER, SID, "phone", "current", context)
        bridge.authenticate_phone.assert_not_called(); bridge.bootstrap_agent.assert_not_called()

    def test_explicit_empty_history_remains_compatible_with_legacy_runner(self):
        def legacy(config, bootstrap, request, *, external_allowed, cancelled, timeout):
            return AgentReport("partial", "synthetic", "", 1, 0)
        _, manager = self.fixture(legacy)
        manager.launch(OWNER, SID, "phone", "current", {"version": 1, "recentTurns": [], "previousTask": None})
        self.assertEqual("partial", self.finished(manager)["report"]["state"])

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

    def test_more_than_64_finished_runs_do_not_permanently_lock_provider(self):
        bridge, manager = self.fixture()
        first = None
        for index in range(70):
            sid = str(uuid.uuid4());first = first or sid
            manager.launch(OWNER, sid, "phone", "synthetic request")
            for _ in range(100):
                if manager.status(OWNER, sid, "phone")["state"] == "FINISHED":break
                threading.Event().wait(.005)
            self.assertEqual("FINISHED",manager.status(OWNER,sid,"phone")["state"])
        self.assertLessEqual(len(manager.children),64)
        self.assertNotIn(first,manager.children)
        self.assertEqual(70,bridge.bootstrap_agent.call_count)
        self.assertGreaterEqual(bridge.dispatch_authenticated.call_count,6)
        # Eviction first revokes the old broker authority; no terminal replay cache is silently dropped.
        self.assertTrue(all(call.args[1].endswith("/cancel") for call in bridge.dispatch_authenticated.call_args_list))

    def test_terminal_eviction_failure_preserves_replay_receipt_and_blocks_fresh_launch(self):
        bridge, manager = self.fixture()
        for index in range(64):
            sid = str(uuid.uuid4());manager.launch(OWNER,sid,"phone","request")
            for _ in range(100):
                if manager.status(OWNER,sid,"phone")["state"] == "FINISHED":break
                threading.Event().wait(.005)
        before = set(manager.children)
        bridge.dispatch_authenticated.side_effect = BridgeError("SYNTHETIC_REVOKE_FAILURE",503)
        with self.assertRaises(BridgeError):manager.launch(OWNER,str(uuid.uuid4()),"phone","request")
        self.assertEqual(before,set(manager.children))
        self.assertEqual(64,bridge.bootstrap_agent.call_count)

    def test_progress_is_bounded_sanitized_and_old_runner_signature_remains_compatible(self):
        def runner(config,bootstrap,request,*,external_allowed,cancelled,timeout):
            return AgentReport("partial","synthetic","",1,0)
        _, manager = self.fixture(runner)
        manager.launch(OWNER,SID,"phone","request");self.assertNotIn("progress",self.finished(manager))
        def reported(*args,progress,**kwargs):
            progress({"stage":"hidden_reasoning","text":"must not display"})
            progress({"stage":"tool_running","calls":True})
            progress({"stage":"planning","calls":0,"text":"private prompt","sequence":999,"elapsedMs":99999999})
            progress({"stage":"tool_running","calls":0,"toolName":"save_note"})
            for _ in range(600):progress({"stage":"tool_returned","calls":1,"reasoning":"secret"})
            return AgentReport("reported_done","synthetic","",1,1)
        bridge, manager = self.fixture(reported)
        bridge.authenticate_phone.return_value = {"revision":1,"tools":[{"name":"save_note"}]}
        manager.launch(OWNER,SID,"phone","request");value=self.finished(manager)
        self.assertEqual({"sequence","stage","elapsedMs","calls"},set(value["progress"]))
        self.assertEqual(512,value["progress"]["sequence"])
        self.assertEqual("tool_returned",value["progress"]["stage"])
        self.assertEqual(1,value["progress"]["calls"])
        self.assertNotIn("secret",json.dumps(value));self.assertNotIn("private prompt",json.dumps(value))

    def test_cancelled_runner_cannot_publish_late_success(self):
        started = threading.Event();release = threading.Event()
        def runner(*args,**kwargs):
            started.set();release.wait(2)
            return AgentReport("reported_done","late untrusted answer","",1,0)
        _, manager=self.fixture(runner)
        manager.launch(OWNER,SID,"phone","request");self.assertTrue(started.wait(1))
        manager.cancel(OWNER,SID,"phone");release.set();result=self.finished(manager)
        self.assertIsNone(result["report"]);self.assertEqual("PROVIDER_CANCELLED",result["errorCode"])

    def test_generic_failure_with_unconfirmed_revoke_blocks_idle_and_new_work_until_reconciled(self):
        def failed(*args,**kwargs):raise RuntimeError("synthetic failure")
        bridge,manager=self.fixture(failed)
        bridge.dispatch_authenticated.side_effect=BridgeError("SYNTHETIC_REVOKE_FAILURE",503)
        manager.launch(OWNER,SID,"phone","request")
        for _ in range(100):
            with manager.lock:
                if manager.children[SID].state=="REVOCATION_UNCONFIRMED":break
            threading.Event().wait(.005)
        self.assertEqual("REVOCATION_UNCONFIRMED",manager.status(OWNER,SID,"phone")["state"])
        self.assertEqual(1,manager.health()["activeProviders"])
        with self.assertRaisesRegex(BridgeError,"PROVIDER_BUSY"):manager.launch(OWNER,str(uuid.uuid4()),"phone","new request")
        bridge.dispatch_authenticated.side_effect=None
        bridge.dispatch_authenticated.return_value=(200,{"state":"REVOKED"})
        self.assertEqual(0,manager.health()["activeProviders"])
        self.assertEqual("FINISHED",manager.status(OWNER,SID,"phone")["state"])

    def test_cancel_and_report_publication_share_the_same_gate(self):
        started=threading.Event();release=threading.Event()
        def runner(*args,**kwargs):
            started.set();release.wait(2);return AgentReport("reported_done","must not publish after cancel","",1,0)
        _,manager=self.fixture(runner);manager.launch(OWNER,SID,"phone","request");self.assertTrue(started.wait(1))
        with manager.lock:
            release.set()
            manager.cancel(OWNER,SID,"phone")
        value=self.finished(manager)
        self.assertIsNone(value["report"]);self.assertEqual("PROVIDER_CANCELLED",value["errorCode"])

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
