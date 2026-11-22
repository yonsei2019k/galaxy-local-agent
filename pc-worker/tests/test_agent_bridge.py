"""Synthetic bridge tests. Root runs these; authoring is not execution evidence.

No paired config/credentials, worker/provider/model/device, server import or shell.
Temporary workspace tests perform only their own synthetic local file operations.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployed"))
from agent_bridge import (AgentBridge, BridgeError, MAX_CALLS, PHONE_LEASE_MS, RESTART_POLICY,
                          WorkspaceExecutor, canonical, parse_wire, result_digest)


def uid():
    return str(uuid.uuid4())


def clock_tool():
    return {"name": "get_current_datetime", "description": "Synthetic registered clock",
            "inputSchema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False}}


def note_tool():
    return {"name": "save_note", "description": "Synthetic note requires phone-owned fixture approval",
            "inputSchema": {"type": "object", "properties": {
                "title": {"type": "string", "maxLength": 300},
                "content": {"type": "string", "minLength": 1, "maxLength": 12000}},
                "required": ["content"], "additionalProperties": False}}


def verified(content="synthetic host result"):
    return {"success": True, "content": content, "verification": "VERIFIED", "effectNotApplied": False}


class Clock:
    def __init__(self):
        self.value = 100_000
    def now(self):
        return self.value
    def epoch(self):
        return 2_000_000_000_000 + self.value


class AgentBridgeTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.broker = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        self.owner = uid()
        self.grant = {"version": 1, "parentRunId": self.owner, "projectBinding": "synthetic-project-binding",
                      "authorityRevision": 7, "allowExternalAgent": True, "expiresAfterMs": 600_000,
                      "phoneTools": [clock_tool(), note_tool()], "pcWorkspaceProfile": None,
                      "requestDigest": "a" * 64}
        self.base = "/v1/agent/sessions"
        status, created = self.broker.dispatch_authenticated("POST", self.base, self.owner, self.grant)
        self.assertEqual(201, status)
        self.created = created
        self.sid = created["sessionId"]
        self.phone = created["phoneCapability"]
        self.url = self.base + "/" + self.sid
        self.assertEqual(200, self.phone_request("GET")[0])  # Phone confirms capability receipt.
        self.agent = self.broker.bootstrap_agent(self.owner, self.sid, local_authenticated=True)["agentCapability"]

    def phone_request(self, method, suffix="", body=None):
        return self.broker.dispatch_authenticated(method, self.url + suffix, self.owner, body, self.phone)

    def agent_request(self, method, suffix="", body=None):
        return self.broker.dispatch_authenticated(method, self.url + suffix, self.owner, body, self.agent)

    def propose(self, tool="get_current_datetime", args=None, cid=None):
        body = {"callId": cid or uid(), "tool": tool, "arguments": args if args is not None else {}}
        return body, self.agent_request("POST", "/call", body)

    def claim(self, proposed):
        _, current = self.phone_request("GET")
        body = {"callId": proposed["callId"], "sequence": current["pendingCall"]["sequence"], "revision": current["revision"]}
        status, ack = self.phone_request("POST", "/claim", body)
        self.assertEqual(200, status)
        return body, ack

    def post_result(self, proposed, claim_ack, result=None):
        pending = claim_ack["pendingCall"]
        body = {"callId": proposed["callId"], "sequence": pending["sequence"],
                "revision": pending["claimRevision"], "result": result or verified()}
        return body, self.phone_request("POST", "/result", body)

    def test_caps_are_role_bound_and_private_bootstrap_is_not_a_route(self):
        self.assertNotIn("agentCapability", self.created)
        self.assertNotIn("phoneCapability", self.agent_request("GET")[1])
        self.assertNotIn(self.phone, repr(self.broker))
        for suffix, body in (("/claim", {}), ("/result", {}), ("/cancel", {"revision": 1}), ("/close", {"revision": 1})):
            self.assertEqual(403, self.agent_request("POST", suffix, body)[0])
        self.assertEqual(403, self.phone_request("POST", "/call", {"callId": uid(), "tool": "get_current_datetime", "arguments": {}})[0])
        self.assertEqual(404, self.agent_request("POST", "/bootstrap", {})[0])
        with self.assertRaises(BridgeError):
            self.broker.bootstrap_agent(self.owner, self.sid)

    def test_missing_external_consent_and_cross_owner_are_denied(self):
        for value in (False, None):
            grant = copy.deepcopy(self.grant)
            grant["parentRunId"] = uid()
            if value is None:
                del grant["allowExternalAgent"]
            else:
                grant["allowExternalAgent"] = value
            self.assertEqual(403, self.broker.dispatch_authenticated("POST", self.base, grant["parentRunId"], grant)[0])
        self.assertEqual(403, self.broker.dispatch_authenticated("GET", self.url, uid(), capability=self.phone)[0])
        self.assertEqual(409, self.broker.dispatch_authenticated("POST", self.base, self.owner, self.grant)[0])

    def test_initial_confirmation_and_phone_only_heartbeat(self):
        other = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        _, created = other.dispatch_authenticated("POST", self.base, self.owner, self.grant)
        with self.assertRaises(BridgeError):
            other.bootstrap_agent(self.owner, created["sessionId"], local_authenticated=True)
        self.clock.value += PHONE_LEASE_MS - 1
        self.assertEqual(200, self.agent_request("GET")[0])
        self.clock.value += 1
        self.assertEqual(410, self.agent_request("GET")[0])  # Agent poll did not renew phone lease.
        self.assertEqual("REVOKED", self.phone_request("GET")[1]["state"])

    def test_queue_claim_result_metadata_and_actual_phone_source(self):
        proposal, (status, queued) = self.propose("save_note", {"content": "synthetic 😀\r\nbody"})
        self.assertEqual(202, status)
        _, snapshot = self.phone_request("GET")
        p = snapshot["pendingCall"]
        self.assertEqual(self.grant["projectBinding"], p["projectBinding"])
        self.assertEqual(7, p["authorityRevision"])
        self.assertEqual(self.grant["requestDigest"], p["requestDigest"])
        claim, ack = self.claim(proposal)
        self.assertEqual(claim["revision"] + 1, ack["revision"])
        self.assertEqual(ack["revision"], ack["pendingCall"]["claimRevision"])
        result, (status, final) = self.post_result(proposal, ack, verified("persisted synthetic note readback"))
        self.assertEqual(200, status)
        self.assertIsNone(final["pendingCall"])
        self.assertEqual(result_digest(result["result"]), final["resultAck"]["resultDigest"])
        self.assertNotIn("result", final)
        _, observed = self.agent_request("GET", "/calls/" + proposal["callId"])
        self.assertEqual("COMPLETED", observed["state"])
        self.assertEqual("PHONE_EXECUTOR", observed["resultSource"])
        self.assertEqual(result["result"], observed["result"])

    def test_duplicate_proposal_and_lost_result_ack_never_repeat_an_effect(self):
        proposal, (_, queued) = self.propose("save_note", {"content": "synthetic"})
        self.assertEqual(queued, self.agent_request("POST", "/call", proposal)[1])
        altered = copy.deepcopy(proposal); altered["arguments"]["content"] = "different"
        self.assertEqual(409, self.agent_request("POST", "/call", altered)[0])
        claim, ack = self.claim(proposal)
        self.assertEqual(ack, self.phone_request("POST", "/claim", claim)[1])
        result, (_, completed) = self.post_result(proposal, ack)
        self.assertEqual(completed, self.phone_request("POST", "/result", result)[1])
        result["result"]["content"] = "replacement"
        self.assertEqual(409, self.phone_request("POST", "/result", result)[0])
        self.assertEqual(1, self.phone_request("GET")[1]["lastSequence"])

    def test_claim_cas_sequence_and_no_source_or_authority_fields_from_agent(self):
        proposal, _ = self.propose()
        _, state = self.phone_request("GET")
        for bad in ({"callId": proposal["callId"], "sequence": 1, "revision": state["revision"] - 1},
                    {"callId": proposal["callId"], "sequence": 2, "revision": state["revision"]}):
            self.assertEqual(409, self.phone_request("POST", "/claim", bad)[0])
        forged = proposal | {"source": "PHONE_EXECUTOR"}
        self.assertEqual(400, self.agent_request("POST", "/call", forged)[0])
        _, ack = self.claim(proposal)
        body = {"callId": proposal["callId"], "sequence": 1, "revision": ack["revision"] + 1, "result": verified()}
        self.assertEqual(409, self.phone_request("POST", "/result", body)[0])

    def test_cancellation_before_claim_and_after_claim_have_distinct_truth(self):
        proposal, _ = self.propose()
        _, cancelled = self.phone_request("POST", "/cancel", {"revision": 1})  # Stale cancel may revoke, never revive.
        self.assertEqual("REVOKED", cancelled["state"])
        self.assertIsNone(cancelled["pendingCall"])
        self.assertEqual(410, self.phone_request("POST", "/claim", {"callId": proposal["callId"], "sequence": 1, "revision": 2})[0])

    def test_claimed_cancel_allows_only_original_actual_result_reconciliation(self):
        proposal, _ = self.propose("save_note", {"content": "synthetic"})
        _, ack = self.claim(proposal)
        _, cancelled = self.phone_request("POST", "/cancel", {"revision": ack["revision"]})
        self.assertEqual("CANCELLING", cancelled["state"])
        self.assertEqual("UNKNOWN_EFFECT", cancelled["pendingCall"]["state"])
        self.assertEqual(409, self.phone_request("POST", "/close", {"revision": cancelled["revision"]})[0])
        _, (status, reconciled) = self.post_result(proposal, ack)
        self.assertEqual(200, status)
        self.assertEqual("REVOKED", reconciled["state"])
        self.assertIsNone(reconciled["pendingCall"])

    def test_unknown_effect_is_sticky_and_no_new_write_or_claim_is_replayed(self):
        proposal, _ = self.propose("save_note", {"content": "synthetic"})
        _, ack = self.claim(proposal)
        unknown = {"success": False, "content": "effect not reconciled", "verification": "UNVERIFIED", "effectNotApplied": False}
        body, (_, result) = self.post_result(proposal, ack, unknown)
        self.assertEqual("UNKNOWN_EFFECT", result["pendingCall"]["state"])
        self.assertEqual(409, self.propose()[1][0])
        self.assertEqual("UNKNOWN_EFFECT", self.agent_request("POST", "/call", proposal)[1]["state"])
        self.assertEqual(409, self.phone_request("POST", "/claim", {"callId": proposal["callId"], "sequence": 1, "revision": result["revision"]})[0])
        body["result"] = verified()
        self.assertEqual(409, self.phone_request("POST", "/result", body)[0])

    def test_eighteen_call_budget_and_same_parent_cannot_reset_it(self):
        for _ in range(MAX_CALLS):
            proposal, (status, _) = self.propose()
            self.assertEqual(202, status)
            _, ack = self.claim(proposal)
            self.assertEqual(200, self.post_result(proposal, ack)[1][0])
        self.assertEqual(429, self.propose()[1][0])
        self.assertEqual(409, self.broker.dispatch_authenticated("POST", self.base, self.owner, self.grant)[0])

    def test_expiry_restart_and_clock_rollback_do_not_fabricate_persistence(self):
        self.clock.value += 600_001
        self.assertEqual("EXPIRED", self.phone_request("GET")[1]["state"])
        self.assertEqual(410, self.agent_request("GET")[0])
        restarted = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        status, value = restarted.dispatch_authenticated("GET", self.url, self.owner, capability=self.phone)
        self.assertEqual(410, status)
        self.assertEqual("SESSION_MISSING_OR_RESTART_REVOKED", value["error"])

    def test_rollback_closes_authority_and_snapshot_mutation_cannot_change_grant(self):
        snapshot = self.phone_request("GET")[1]
        snapshot["tools"][0]["name"] = "shell"
        self.grant["phoneTools"][0]["inputSchema"]["additionalProperties"] = True
        self.assertEqual(403, self.propose("shell")[1][0])
        self.assertEqual(400, self.propose(args={"newAuthority": True})[1][0])
        self.clock.value -= 1
        self.assertEqual(410, self.phone_request("GET")[0])

    def test_strict_json_schema_and_result_bounds(self):
        for raw in ('{"a":1,"a":2}', '{"a":NaN}', b'\xff', '[]'):
            with self.assertRaises(BridgeError):
                parse_wire(raw)
        self.assertEqual(400, self.propose(args={"unexpected": True})[1][0])
        with self.assertRaises(BridgeError):
            result_digest({"success": True, "content": "x", "verification": "VERIFIED", "effectNotApplied": True})
        with self.assertRaises(BridgeError):
            result_digest(verified("x" * 131073))
        grant = copy.deepcopy(self.grant); grant["parentRunId"] = uid()
        grant["phoneTools"][0]["inputSchema"]["$ref"] = "file:///not-loaded"
        self.assertEqual(400, self.broker.dispatch_authenticated("POST", self.base, grant["parentRunId"], grant)[0])

    def test_close_drops_private_bodies_and_revokes_agent_access(self):
        proposal, _ = self.propose("save_note", {"content": "private synthetic body"})
        _, ack = self.claim(proposal)
        self.post_result(proposal, ack)
        _, state = self.phone_request("GET")
        status, closed = self.phone_request("POST", "/close", {"revision": state["revision"]})
        self.assertEqual((200, "CLOSED"), (status, closed["state"]))
        self.assertIsNone(closed["pendingCall"])
        self.assertEqual(410, self.agent_request("GET", "/calls/" + proposal["callId"])[0])

    def test_synthetic_wire_fixture_roundtrip_uses_exact_claim_revision_and_ack_digest(self):
        path = Path(__file__).resolve().parents[2] / "protocol/device-bridge/v1/fixtures/synthetic-wire.json"
        wire = json.loads(path.read_text(encoding="utf-8"))
        owner = wire["register"]["parentRunId"]
        broker = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        status, created = broker.dispatch_authenticated("POST", self.base, owner, wire["register"])
        self.assertEqual(201, status)
        url = self.base + "/" + created["sessionId"]
        phone = created["phoneCapability"]
        broker.dispatch_authenticated("GET", url, owner, capability=phone)
        agent = broker.bootstrap_agent(owner, created["sessionId"], local_authenticated=True)["agentCapability"]
        self.assertEqual(202, broker.dispatch_authenticated("POST", url + "/call", owner, wire["propose"], agent)[0])
        self.assertEqual(200, broker.dispatch_authenticated("POST", url + "/claim", owner, wire["claim"], phone)[0])
        status, ack = broker.dispatch_authenticated("POST", url + "/result", owner, wire["report"], phone)
        self.assertEqual(200, status)
        self.assertEqual(result_digest(wire["report"]["result"]), ack["resultAck"]["resultDigest"])
        self.assertEqual(200, broker.dispatch_authenticated("POST", url + "/close", owner, wire["control"], phone)[0])


def detail_tool():
    return {"name": "schedule_item", "description": "Synthetic nested-schema tool",
            "inputSchema": {"type": "object", "additionalProperties": False, "required": ["title"], "properties": {
                "title": {"type": "string", "maxLength": 20},
                "details": {"type": "object", "additionalProperties": False, "properties": {
                    "start": {"type": "object", "additionalProperties": False,
                              "properties": {"hour": {"type": "integer"}}}}},
                "items": {"type": "array", "items": {"type": "object", "additionalProperties": False,
                                                       "properties": {}}}}}}


class AgentBridgeRejectedCallTests(unittest.TestCase):
    phone_request = AgentBridgeTests.phone_request
    agent_request = AgentBridgeTests.agent_request
    propose = AgentBridgeTests.propose

    def setUp(self):
        self.clock = Clock()
        self.broker = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        self.owner = uid()
        grant = {"version": 1, "parentRunId": self.owner, "projectBinding": "synthetic-project-binding",
                 "authorityRevision": 7, "allowExternalAgent": True, "expiresAfterMs": 600_000,
                 "phoneTools": [clock_tool(), detail_tool()], "pcWorkspaceProfile": None, "requestDigest": "a" * 64}
        self.base = "/v1/agent/sessions"
        status, created = self.broker.dispatch_authenticated("POST", self.base, self.owner, grant)
        self.assertEqual(201, status)
        self.sid = created["sessionId"]
        self.phone = created["phoneCapability"]
        self.url = self.base + "/" + self.sid
        self.assertEqual(200, self.phone_request("GET")[0])
        self.agent = self.broker.bootstrap_agent(self.owner, self.sid, local_authenticated=True)["agentCapability"]

    def reject(self, args, tool="schedule_item"):
        status, body = self.propose(tool, args)[1]
        return status, body, json.dumps(body, ensure_ascii=False)

    def test_stringified_object_reports_path_and_type_without_value_or_coercion(self):
        secret = '{"start": "SECRET-VALUE-123"}'
        status, body, text = self.reject({"title": "t", "details": secret})
        self.assertEqual(400, status)
        self.assertEqual("ARGUMENT_TYPE", body["error"])
        self.assertEqual({"path": "/details", "expected": "object", "received": "string"},
                         {k: v for k, v in body["detail"].items() if k != "hint"})
        self.assertIn("hint", body["detail"])
        self.assertNotIn("SECRET-VALUE-123", text)
        # No coercion: nothing was created or dispatched.
        self.assertEqual({}, self.broker._sessions[self.sid].calls)
        status, body, _ = self.reject({"title": "t", "details": {"start": "x"}})
        self.assertEqual("/details/start", body["detail"]["path"])
        self.assertEqual("/items/0", self.reject({"title": "t", "items": [5]})[1]["detail"]["path"])

    def test_unknown_key_reports_only_count(self):
        status, body, text = self.reject({"title": "t", "zzSecretKeyName": 1, "qqOther": 2})
        self.assertEqual("ARGUMENT_UNKNOWN", body["error"])
        self.assertEqual({"path": "", "unknownKeyCount": 2}, body["detail"])
        self.assertNotIn("zzSecretKeyName", text)
        self.assertNotIn("qqOther", text)

    def test_missing_required_lists_schema_key(self):
        status, body, _ = self.reject({})
        self.assertEqual("ARGUMENT_REQUIRED", body["error"])
        self.assertEqual({"path": "", "missing": ["title"]}, body["detail"])

    def test_plain_errors_stay_without_detail(self):
        status, body = self.propose("nonexistent", {})[1]
        self.assertEqual({"error": "TOOL_NOT_OFFERED"}, body)

    def test_rejection_cap_blocks_valid_call_without_touching_calls_or_revision(self):
        good, (status, _) = self.propose("get_current_datetime", {})
        self.assertEqual(202, status)
        revision = self.phone_request("GET")[1]["revision"]
        calls_before = set(self.broker._sessions[self.sid].calls)
        # Clear the active call so only the cap can block a fresh valid call.
        self.broker._sessions[self.sid].active = None
        for _ in range(6):
            self.assertEqual(400, self.propose("schedule_item", {"title": "t", "details": "x"})[1][0])
        self.assertEqual(6, self.broker._sessions[self.sid].rejected_calls)
        self.assertEqual(revision, self.phone_request("GET")[1]["revision"])
        status, body = self.propose("get_current_datetime", {})[1]
        self.assertEqual((429, {"error": "REJECTED_CALL_LIMIT"}), (status, body))
        self.assertEqual(calls_before, set(self.broker._sessions[self.sid].calls))
        self.assertEqual(revision, self.phone_request("GET")[1]["revision"])
        # Idempotent replay of an existing call is unaffected by the cap.
        self.assertEqual(200, self.agent_request("POST", "/call", good)[0])

    def test_uncounted_rejections_do_not_consume_the_cap(self):
        proposal, (status, _) = self.propose("get_current_datetime", {})
        self.assertEqual(202, status)
        for _ in range(8):
            self.assertEqual(409, self.propose("get_current_datetime", {})[1][0])  # CALL_ALREADY_ACTIVE
        self.assertEqual(0, self.broker._sessions[self.sid].rejected_calls)


class AgentBridgePlanTests(unittest.TestCase):
    phone_request = AgentBridgeTests.phone_request
    agent_request = AgentBridgeTests.agent_request
    propose = AgentBridgeTests.propose
    claim = AgentBridgeTests.claim
    post_result = AgentBridgeTests.post_result

    def setUp(self):
        self.clock = Clock()
        self.broker = AgentBridge(clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
        self.owner, self.base = uid(), "/v1/agent/sessions"
        self.owner_text = "합성 요청 😀 e\u0301\r\n"
        self.grant = {"version": 1, "parentRunId": self.owner, "projectBinding": "synthetic",
                      "authorityRevision": 7, "allowExternalAgent": True, "expiresAfterMs": 600000,
                      "phoneTools": [clock_tool(), note_tool()], "pcWorkspaceProfile": "phone-only",
                      "requestDigest": hashlib.sha256(self.owner_text.encode("utf-8")).hexdigest(), "requirePlan": True}
        self._register()

    def _register(self):
        status, self.created = self.broker.dispatch_authenticated("POST", self.base, self.owner, self.grant)
        self.assertEqual(201, status)
        self.sid, self.phone = self.created["sessionId"], self.created["phoneCapability"]
        self.url = self.base + "/" + self.sid
        self.assertEqual(200, self.phone_request("GET")[0])
        self.agent = self.broker.bootstrap_agent(self.owner, self.sid, local_authenticated=True)["agentCapability"]

    def plan(self, tools=None):
        return {"goal": "현재 요청의 합성 결과", "requiredTools": ["get_current_datetime"] if tools is None else tools,
                "constraints": ["원문 e\u0301와 😀 보존"]}

    def accept(self, plan=None, accepted=True):
        status, state = self.agent_request("POST", "/plan", plan or self.plan())
        self.assertEqual(200, status)
        status, state = self.phone_request("POST", "/plan-ack", {"planDigest": state["plan"]["planDigest"], "accepted": accepted})
        self.assertEqual(200, status)
        return state

    def test_false_and_omitted_keep_legacy_snapshot_and_call_wire(self):
        for explicit in (False, None):
            grant = copy.deepcopy(self.grant)
            grant["parentRunId"] = owner = uid()
            if explicit is None:
                grant.pop("requirePlan")
            else:
                grant["requirePlan"] = explicit
            status, created = self.broker.dispatch_authenticated("POST", self.base, owner, grant)
            self.assertEqual(201, status)
            self.assertNotIn("requiresPlan", created)
            self.assertNotIn("plan", created)
            url = self.base + "/" + created["sessionId"]
            self.broker.dispatch_authenticated("GET", url, owner, capability=created["phoneCapability"])
            agent = self.broker.bootstrap_agent(owner, created["sessionId"], local_authenticated=True)["agentCapability"]
            self.assertEqual(202, self.broker.dispatch_authenticated("POST", url + "/call", owner,
                {"callId": uid(), "tool": "get_current_datetime", "arguments": {}}, agent)[0])

    def test_strict_plan_flag_and_reserved_virtual_tool(self):
        for flag in (0, 1, "true", None):
            grant = self.grant | {"parentRunId": uid(), "requirePlan": flag}
            self.assertEqual(400, self.broker.dispatch_authenticated("POST", self.base, grant["parentRunId"], grant)[0])
        spec = clock_tool(); spec["name"] = "plan_task"
        grant = self.grant | {"parentRunId": uid(), "phoneTools": [spec]}
        self.assertEqual(400, self.broker.dispatch_authenticated("POST", self.base, grant["parentRunId"], grant)[0])

    def test_exact_public_read_repeats_do_not_spend_a_phone_call(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        tool = note_tool() | {"name": "search_web"}
        self.grant["phoneTools"] = [tool]
        self._register()
        self.accept(self.plan(["search_web"]))
        args = {"content": "same public query"}
        failure = {"success": False, "verification": "FAILED", "effectNotApplied": False, "content": "temporary"}
        for _ in range(2):
            proposed, (status, _) = self.propose("search_web", args)
            self.assertEqual(202, status)
            _, ack = self.claim(proposed)
            self.post_result(proposed, ack, failure)
        blocked, (status, result) = self.propose("search_web", args)
        self.assertEqual((200, {"callId": blocked["callId"], "state": "SKIPPED", "reason": "PUBLIC_READ_REPEAT"}),
                         (status, result))
        self.assertEqual(2, self.phone_request("GET")[1]["lastSequence"])
        changed = {"content": "alternative public query"}
        proposed, (status, _) = self.propose("search_web", changed)
        self.assertEqual(202, status)
        _, ack = self.claim(proposed)
        self.post_result(proposed, ack)
        self.assertEqual("SKIPPED", self.propose("search_web", changed)[1][1]["state"])
        self.assertEqual("COMPLETED", self.propose("search_web", changed, cid=proposed["callId"])[1][1]["state"])

    def test_plan_ack_capability_roles_and_no_action_before_acceptance(self):
        self.assertEqual(409, self.propose()[1][0])
        self.assertEqual(403, self.phone_request("POST", "/plan", self.plan())[0])
        _, state = self.agent_request("POST", "/plan", self.plan())
        ack = {"planDigest": state["plan"]["planDigest"], "accepted": True}
        self.assertEqual(403, self.agent_request("POST", "/plan-ack", ack)[0])
        self.assertEqual(409, self.propose()[1][0])
        self.assertEqual(200, self.phone_request("POST", "/plan-ack", ack)[0])
        self.assertEqual(403, self.propose("save_note", {"content": "synthetic"})[1][0])
        self.assertEqual(202, self.propose()[1][0])

    def test_bound_digest_exact_lists_idempotency_revision_and_immutable_plan(self):
        plan = self.plan(["save_note", "get_current_datetime"])
        status, state = self.agent_request("POST", "/plan", plan)
        self.assertEqual(200, status)
        bound = {key: self.grant[key] for key in ("version", "parentRunId", "projectBinding", "authorityRevision", "requestDigest")}
        expected = hashlib.sha256(canonical(bound | plan)).hexdigest()
        self.assertEqual(expected, state["plan"]["planDigest"])
        self.assertEqual((2, 0), (state["revision"], state["lastSequence"]))
        self.assertEqual(state, self.agent_request("POST", "/plan", copy.deepcopy(plan))[1])
        changed = plan | {"requiredTools": list(reversed(plan["requiredTools"]))}
        self.assertEqual(409, self.agent_request("POST", "/plan", changed)[0])
        ack = {"planDigest": expected, "accepted": True}
        _, accepted = self.phone_request("POST", "/plan-ack", ack)
        self.assertEqual((3, 0), (accepted["revision"], accepted["lastSequence"]))
        self.assertEqual(accepted, self.phone_request("POST", "/plan-ack", ack)[1])
        self.assertEqual(409, self.phone_request("POST", "/plan-ack", ack | {"accepted": False})[0])
        self.assertEqual(3, self.agent_request("POST", "/plan", plan)[1]["revision"])
        accepted["plan"]["requiredTools"].append("shell")
        plan["constraints"].append("mutated")
        self.assertNotIn("shell", self.phone_request("GET")[1]["plan"]["requiredTools"])

    def test_empty_selection_is_answer_only_and_rejection_cannot_be_reversed(self):
        self.accept(self.plan([]))
        self.assertEqual(403, self.propose()[1][0])
        self.assertEqual([], self.broker.task_evidence(self.owner, self.sid, self.phone)["requiredTools"])
        self.owner = uid(); self.grant["parentRunId"] = self.owner; self._register()
        state = self.accept(accepted=False)
        self.assertEqual(409, self.propose()[1][0])
        self.assertEqual(409, self.phone_request("POST", "/plan-ack",
            {"planDigest": state["plan"]["planDigest"], "accepted": True})[0])

    def test_failed_screen_read_releases_call_but_failed_screen_action_remains_uncertain(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        for name in ("read_screen", "screen_action"):
            self.grant["phoneTools"].append(clock_tool() | {"name": name})
        self._register()
        self.accept(self.plan(["read_screen", "screen_action"]))
        failure = {"success": False, "verification": "FAILED", "effectNotApplied": False, "content": "fixture"}
        read, _ = self.propose("read_screen")
        _, ack = self.claim(read)
        _, (status, snapshot) = self.post_result(read, ack, failure)
        self.assertEqual(200, status)
        self.assertIsNone(snapshot["pendingCall"])
        self.assertEqual([{"tool": "read_screen", "arguments": {}}],
                         self.broker.read_recovery_context(self.owner, self.sid, self.phone)["failedReads"])
        action, response = self.propose("screen_action")
        self.assertEqual(202, response[0])
        _, ack = self.claim(action)
        _, (_, snapshot) = self.post_result(action, ack, failure)
        self.assertEqual("UNKNOWN_EFFECT", snapshot["pendingCall"]["state"])
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))
        self.assertEqual(409, self.propose("screen_action")[1][0])

    def test_owner_recall_recovers_only_the_missing_approved_read(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        self.grant["pcWorkspaceProfile"] = "phone-only"
        memory, history = "search_memories", "search_conversation_history"
        for name in (memory, history):
            self.grant["phoneTools"].append(clock_tool() | {"name": name})
        self._register()
        self.accept(self.plan([memory, history]))
        first, _ = self.propose(memory)
        _, ack = self.claim(first)
        self.post_result(first, ack)
        recovery = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertEqual([history], recovery["acceptedTools"])
        self.assertEqual([memory], [item["tool"] for item in recovery["observedReads"]])
        self.assertEqual(403, self.propose(memory)[1][0])
        self.assertEqual(200, self.propose(memory, cid=first["callId"])[1][0])
        second, response = self.propose(history)
        self.assertEqual(202, response[0])
        _, ack = self.claim(second)
        self.post_result(second, ack)
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))

    def test_archived_source_recovery_preserves_only_verified_receipts(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        self.grant["pcWorkspaceProfile"] = "phone-only"
        history, archive = "search_conversation_history", "list_analysis_sources"
        for name in (history, archive):
            self.grant["phoneTools"].append(clock_tool() | {"name": name})
        self._register()
        self.accept(self.plan([history, archive]))
        previous, _ = self.propose(history)
        _, ack = self.claim(previous)
        self.post_result(previous, ack)
        context = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertEqual([archive], context["acceptedTools"])
        self.assertEqual([history], [item["tool"] for item in context["observedReads"]])
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))

    def test_owner_recall_does_not_recover_with_mutation(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        self.grant["pcWorkspaceProfile"] = "phone-only"
        self.grant["phoneTools"].append(clock_tool() | {"name": "search_memories"})
        self.grant["phoneTools"].append(clock_tool() | {"name": "patch_note"})
        self._register()
        self.accept(self.plan(["search_memories", "patch_note"]))
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))

    def test_browser_recovery_observes_the_page_and_prevents_a_second_navigation(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        open_tool, read_tool = "open_app_browser_page", "read_app_browser_page"
        for name in (open_tool, read_tool):
            self.grant["phoneTools"].append(clock_tool() | {"name": name})
        self._register()
        self.accept(self.plan([open_tool, read_tool]))
        opened, _ = self.propose(open_tool)
        _, ack = self.claim(opened)
        self.post_result(opened, ack)
        recovery = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertTrue(recovery["browserOpened"])
        self.assertEqual([read_tool], recovery["acceptedTools"])
        self.assertEqual([], recovery["observedReads"])
        self.assertEqual(200, self.propose(open_tool, cid=opened["callId"])[1][0])
        self.assertEqual(403, self.propose(open_tool)[1][0])
        observation, response = self.propose(read_tool)
        self.assertEqual(202, response[0])
        _, ack = self.claim(observation)
        self.post_result(observation, ack)
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))
        synthesis = self.broker.read_recovery_context(self.owner, self.sid, self.phone, before_plan=True)
        self.assertTrue(synthesis["browserObservationComplete"])
        self.assertEqual([], synthesis["acceptedTools"])
        self.assertEqual(verified()["content"], synthesis["observedReads"][0]["result"])
        self.assertEqual(403, self.propose(read_tool)[1][0])

    def test_recovery_context_requires_phone_accepted_reads_and_proven_absence_of_effect(self):
        context = lambda: self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertIsNone(context())
        self.assertFalse(self.broker.read_recovery_context(self.owner, self.sid, self.phone, before_plan=True)["planAccepted"])
        self.accept()
        self.assertEqual([], context()["failedReads"])
        proposed, _ = self.propose()
        self.assertIsNone(context())
        _, ack = self.claim(proposed)
        self.assertIsNone(context())
        self.post_result(proposed, ack, {"success": False, "verification": "FAILED", "effectNotApplied": True, "content": "fixture"})
        self.assertEqual([{"tool": "get_current_datetime", "arguments": {}}], context()["failedReads"])
        proposed, _ = self.propose()
        _, ack = self.claim(proposed)
        self.post_result(proposed, ack)
        self.assertIsNone(context())

    def test_recovery_separates_verified_reads_and_refuses_exact_repeats(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        read = note_tool() | {"name": "read_note"}
        self.grant["phoneTools"] = [clock_tool(), read, clock_tool() | {"name": "read_webpage"}, note_tool()]
        self._register()
        required = ["get_current_datetime", "read_note", "read_webpage"]
        self.accept(self.plan(required))
        note = {"title": "synthetic", "content": "first"}
        confirmed = []
        for name, args in [("get_current_datetime", {}), ("read_note", note)]:
            proposed, (status, _) = self.propose(name, args)
            self.assertEqual(202, status)
            _, ack = self.claim(proposed)
            self.post_result(proposed, ack)
            confirmed.append(proposed)

        recovery = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertEqual(required, recovery["acceptedTools"])
        self.assertEqual(required[:2], recovery["completedReadTools"])
        self.assertEqual(["read_webpage"], recovery["remainingReads"])
        self.assertEqual(["read_note"], recovery["alternateTargetReads"])
        self.assertEqual(required[:2], [row["tool"] for row in recovery["observedReads"]])
        before = self.phone_request("GET")[1]
        for call in confirmed:
            proposed, (status, response) = self.propose(call["tool"], dict(reversed(list(call["arguments"].items()))))
            self.assertEqual(200, status)
            self.assertEqual({"callId": proposed["callId"], "state": "SKIPPED", "reason": "RECOVERY_READ_REPEAT"},
                             response)
        self.assertEqual(before, self.phone_request("GET")[1])
        self.assertEqual("COMPLETED", self.propose(cid=confirmed[0]["callId"])[1][1]["state"])
        self.assertEqual(403, self.propose("save_note", {"content": "outside accepted scope"})[1][0])
        self.assertEqual(202, self.propose("read_note", note | {"content": "other target"})[1][0])

    def test_read_recovery_keeps_failed_lookup_retryable(self):
        self.accept()
        first, _ = self.propose()
        _, ack = self.claim(first)
        self.post_result(first, ack, {"success": False, "content": "synthetic",
                                     "verification": "FAILED", "effectNotApplied": True})
        recovery = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertEqual([], recovery["completedReadTools"])
        self.assertEqual(["get_current_datetime"], recovery["remainingReads"])
        self.assertEqual(202, self.propose()[1][0])

    def test_settled_read_finalization_reuses_the_receipt_and_blocks_new_calls(self):
        recovery = lambda: self.broker.read_recovery_context(self.owner, self.sid, self.phone, settled_only=True)
        self.assertIsNone(recovery())
        self.accept()
        proposed, _ = self.propose()
        _, ack = self.claim(proposed)
        self.assertIsNone(recovery())
        self.post_result(proposed, ack)
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))
        context = recovery()
        self.assertTrue(context["readObservationsComplete"])
        self.assertEqual([], context["acceptedTools"])
        self.assertEqual(verified()["content"], context["observedReads"][0]["result"])
        self.assertEqual(200, self.propose(cid=proposed["callId"])[1][0])
        status, failure = self.propose()[1]
        self.assertEqual(403, status)
        self.assertEqual("RECOVERY_FINAL_ANSWER_ONLY", failure["error"])

    def test_exhausted_read_budget_can_finish_from_bounded_verified_observations(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        self.grant["phoneTools"] = [clock_tool(), clock_tool() | {"name": "read_webpage"}]
        self._register()
        self.accept(self.plan(["get_current_datetime", "read_webpage"]))
        for index in range(MAX_CALLS):
            proposed, (status, _) = self.propose()
            self.assertEqual(202, status)
            _, ack = self.claim(proposed)
            self.post_result(proposed, ack, verified() | {"content": str(index) + ("x" * 1600)})
        context = self.broker.read_recovery_context(self.owner, self.sid, self.phone, before_plan=True)
        self.assertTrue(context["readBudgetExhausted"])
        self.assertTrue(context["observationsOmitted"])
        self.assertEqual([], context["acceptedTools"])
        self.assertLessEqual(len(canonical(context)), 16384)
        self.assertTrue(context["observedReads"][-1]["result"].startswith(str(MAX_CALLS - 1)))
        status, rejection = self.propose("read_webpage")[1]
        self.assertEqual((403, "RECOVERY_FINAL_ANSWER_ONLY"), (status, rejection["error"]))

    def test_write_scope_cannot_restart_a_provider_for_a_read(self):
        self.accept(self.plan(["get_current_datetime", "save_note"]))
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))

    def test_note_lookup_is_available_for_reconsideration_only_before_patch(self):
        self.owner = uid()
        self.grant["parentRunId"] = self.owner
        read, patch = clock_tool(), note_tool()
        read["name"], patch["name"] = "search_notes", "patch_note"
        self.grant["phoneTools"] = [read, patch]
        self._register()
        self.accept(self.plan(["search_notes", "patch_note"]))
        proposed, _ = self.propose("search_notes")
        _, ack = self.claim(proposed)
        self.post_result(proposed, ack)
        recovery = self.broker.read_recovery_context(self.owner, self.sid, self.phone)
        self.assertEqual(verified()["content"], recovery["observedReads"][0]["result"])
        self.assertNotIn("remainingReads", recovery)
        patch_call, _ = self.propose("patch_note", {"content": "synthetic"})
        _, ack = self.claim(patch_call)
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))
        self.post_result(patch_call, ack)
        self.assertIsNone(self.broker.read_recovery_context(self.owner, self.sid, self.phone))
        self.assertEqual(202, self.propose("search_notes")[1][0])  # Readback after a verified change.

    def test_plan_shapes_unoffered_tools_utf16_bounds_and_nonascii_ack_fail_closed(self):
        invalid = [self.plan(["shell"]), self.plan(["get_current_datetime"] * 2),
                   self.plan(["pc_read_file"]), self.plan(["plan_task"]),
                   self.plan() | {"goal": "😀" * 501}, self.plan() | {"goal": ""},
                   self.plan() | {"constraints": ["😀" * 121]}, self.plan() | {"constraints": ["x"] * 7},
                   self.plan() | {"newAuthority": True}]
        for plan in invalid:
            self.assertEqual(400, self.agent_request("POST", "/plan", plan)[0])
        self.assertIsNone(self.phone_request("GET")[1]["plan"])
        self.accept()
        self.assertEqual(400, self.phone_request("POST", "/plan-ack", {"planDigest": "😀", "accepted": True})[0])

    def test_phone_only_cannot_advertise_select_or_dispatch_pc_even_with_injection(self):
        self.assertEqual({"get_current_datetime", "save_note"}, {t["name"] for t in self.created["tools"]})
        self.accept()
        self.assertEqual(403, self.propose("pc_read_file", {"path": "anything"})[1][0])
        self.assertEqual([], self.broker.task_evidence(self.owner, self.sid, self.phone)["pcCalls"])
        with self.assertRaises(BridgeError):
            AgentBridge({"phone-only": "not-opened"})

    def test_host_auth_uses_exact_owner_utf8_phone_role_and_live_launch_only(self):
        self.assertEqual(self.sid, self.broker.authenticate_phone(self.owner, self.sid, self.phone, self.owner_text)["sessionId"])
        for text in (self.owner_text.rstrip(), self.owner_text.replace("e\u0301", "é")):
            with self.assertRaises(BridgeError):
                self.broker.authenticate_phone(self.owner, self.sid, self.phone, text)
        for cap in (self.agent, "x" * 32):
            with self.assertRaises(BridgeError):
                self.broker.authenticate_phone(self.owner, self.sid, cap)
        self.phone_request("POST", "/cancel", {"revision": 1})
        self.assertEqual("REVOKED", self.broker.authenticate_phone(self.owner, self.sid, self.phone)["state"])
        with self.assertRaises(BridgeError):
            self.broker.authenticate_phone(self.owner, self.sid, self.phone, self.owner_text)

    def test_lease_expiry_prevents_late_plan_and_ack(self):
        _, state = self.agent_request("POST", "/plan", self.plan())
        self.clock.value += PHONE_LEASE_MS
        self.assertEqual(410, self.phone_request("POST", "/plan-ack",
            {"planDigest": state["plan"]["planDigest"], "accepted": True})[0])
        self.assertEqual(410, self.agent_request("POST", "/plan", self.plan())[0])

    def test_phone_result_is_metadata_only_and_prepared_unknown_blockers_are_retained(self):
        self.accept(self.plan(["save_note"]))
        proposed, _ = self.propose("save_note", {"content": "private synthetic"})
        _, ack = self.claim(proposed)
        prepared = verified("private synthetic prepared"); prepared["verification"] = "PREPARED"
        self.post_result(proposed, ack, prepared)
        evidence = self.broker.task_evidence(self.owner, self.sid, self.phone)
        self.assertFalse(evidence["phoneEvidenceImported"])
        self.assertEqual(["save_note"], evidence["phoneToolsRequiringLocalEvidence"])
        self.assertEqual([], evidence["verifiedPcTools"])
        self.assertEqual(1, len(evidence["preparedCalls"]))
        self.assertNotIn("private synthetic", json.dumps(evidence))
        proposed, _ = self.propose("save_note", {"content": "another synthetic"})
        _, ack = self.claim(proposed)
        self.phone_request("POST", "/cancel", {"revision": ack["revision"]})
        self.assertEqual("UNKNOWN_EFFECT", self.broker.task_evidence(self.owner, self.sid, self.phone)["unresolvedCalls"][0]["state"])
        with self.assertRaises(BridgeError):
            self.broker.task_evidence(self.owner, self.sid, self.agent)

    def test_pc_evidence_checks_actual_bytes_partial_reads_and_returns_detached_values(self):
        with tempfile.TemporaryDirectory(prefix="bridge-plan-qa-") as name:
            root = Path(name)
            (root / "fixture.txt").write_text("synthetic 😀", encoding="utf-8")
            (root / "other.txt").write_text("other", encoding="utf-8")
            self.broker = AgentBridge({"qa": root}, clock_ms=self.clock.now, epoch_ms=self.clock.epoch)
            self.grant["pcWorkspaceProfile"] = "qa"
            self._register()
            self.accept(self.plan(["pc_inspect_workspace", "pc_read_file"]))
            self.assertEqual(200, self.propose("pc_inspect_workspace", {"limit": 1})[1][0])
            first = self.broker.task_evidence(self.owner, self.sid, self.phone)
            self.assertEqual([], first["verifiedPcTools"])
            self.assertFalse(first["pcCalls"][0]["verifiedComplete"])
            self.assertEqual(200, self.propose("pc_read_file", {"path": "fixture.txt"})[1][0])
            evidence = self.broker.task_evidence(self.owner, self.sid, self.phone)
            self.assertEqual(["pc_read_file"], evidence["verifiedPcTools"])
            row = evidence["pcCalls"][1]
            self.assertEqual("BROKER_PC", row["evidenceSource"])
            self.assertEqual(result_digest(row["result"]), row["resultDigest"])
            evidence["pcCalls"][1]["result"]["content"] = "forged"
            self.assertTrue(self.broker.task_evidence(self.owner, self.sid, self.phone)["pcCalls"][1]["verifiedComplete"])
            _, state = self.phone_request("GET")
            self.phone_request("POST", "/close", {"revision": state["revision"]})
            self.assertEqual([], self.broker.task_evidence(self.owner, self.sid, self.phone)["verifiedPcTools"])


class WorkspaceBridgeTests(unittest.TestCase):
    def test_complete_read_hash_and_bounded_directory_metadata(self):
        with tempfile.TemporaryDirectory(prefix="bridge-qa-") as name:
            root = Path(name)
            data = "synthetic 😀\r\ne\u0301".encode("utf-8")
            (root / "fixture.txt").write_bytes(data)
            (root / "other.txt").write_bytes(b"other")
            executor = WorkspaceExecutor(root)
            result = executor.execute("pc_read_file", {"path": "fixture.txt"})
            body = json.loads(result["content"])
            self.assertEqual(data, body["text"].encode("utf-8"))
            self.assertEqual(hashlib.sha256(data).hexdigest(), body["sha256"])
            self.assertTrue(body["complete"])
            self.assertFalse(body["partial"])
            inspected = json.loads(executor.execute("pc_inspect_workspace", {"limit": 1})["content"])
            self.assertTrue(inspected["partial"])
            self.assertFalse(inspected["enumerationComplete"])
            self.assertNotIn(str(root), result["content"])

    def test_escape_device_binary_oversize_and_hardlink_do_not_become_complete_reads(self):
        with tempfile.TemporaryDirectory(prefix="bridge-qa-") as name:
            root = Path(name)
            (root / "large.txt").write_bytes(b"x" * 32769)
            (root / "binary.bin").write_bytes(b"\xff\x00")
            executor = WorkspaceExecutor(root)
            for path in ("../outside", "/absolute", "C:/outside", "a\\b", "NUL", "folder/../file", "file:ads", "a."):
                with self.assertRaises(BridgeError):
                    executor.execute("pc_read_file", {"path": path})
            with self.assertRaises(BridgeError):
                executor.execute("pc_read_file", {"path": "large.txt"})
            with self.assertRaises(UnicodeError):
                executor.execute("pc_read_file", {"path": "binary.bin"})
            (root / "original.txt").write_bytes(b"synthetic")
            os.link(root / "original.txt", root / "linked.txt")
            with self.assertRaises(BridgeError):
                executor.execute("pc_read_file", {"path": "linked.txt"})

    @unittest.skipIf(os.name == "nt", "Real Windows reparse/junction qualification is a separate Root device/runtime check")
    def test_posix_symlink_and_replaced_workspace_identity_are_denied(self):
        with tempfile.TemporaryDirectory(prefix="bridge-qa-") as name:
            root = Path(name) / "workspace"; root.mkdir()
            outside = Path(name) / "outside.txt"; outside.write_text("not authorized", encoding="utf-8")
            (root / "link.txt").symlink_to(outside)
            executor = WorkspaceExecutor(root)
            with self.assertRaises(OSError):
                executor.execute("pc_read_file", {"path": "link.txt"})
            root.rename(Path(name) / "old"); root.mkdir()
            with self.assertRaises(BridgeError):
                executor.execute("pc_inspect_workspace", {})

    def test_pc_read_has_single_active_cancellation_fence_and_no_redispatch(self):
        with tempfile.TemporaryDirectory(prefix="bridge-qa-") as name:
            started, release = threading.Event(), threading.Event()
            executions = []
            def execute(profile, tool, arguments):
                executions.append((profile, tool))
                started.set()
                if not release.wait(3):
                    raise RuntimeError("synthetic test release missing")
                return verified("bounded synthetic read")
            clock = Clock()
            broker = AgentBridge({"qa": name}, clock_ms=clock.now, epoch_ms=clock.epoch, pc_executor=execute)
            owner = uid()
            grant = {"version": 1, "parentRunId": owner, "projectBinding": "synthetic", "authorityRevision": 1,
                     "allowExternalAgent": True, "expiresAfterMs": 60000, "phoneTools": [], "pcWorkspaceProfile": "qa", "requestDigest": "a" * 64}
            _, created = broker.dispatch_authenticated("POST", "/v1/agent/sessions", owner, grant)
            url = "/v1/agent/sessions/" + created["sessionId"]
            phone = created["phoneCapability"]
            broker.dispatch_authenticated("GET", url, owner, capability=phone)
            agent = broker.bootstrap_agent(owner, created["sessionId"], local_authenticated=True)["agentCapability"]
            proposal = {"callId": uid(), "tool": "pc_inspect_workspace", "arguments": {}}
            returned = []
            thread = threading.Thread(target=lambda: returned.append(broker.dispatch_authenticated("POST", url + "/call", owner, proposal, agent)))
            thread.start()
            try:
                self.assertTrue(started.wait(3))
                self.assertEqual(409, broker.dispatch_authenticated("POST", url + "/call", owner, proposal | {"callId": uid()}, agent)[0])
                _, state = broker.dispatch_authenticated("GET", url, owner, capability=phone)
                _, cancelled = broker.dispatch_authenticated("POST", url + "/cancel", owner, {"revision": state["revision"]}, phone)
                self.assertEqual("CANCELLING", cancelled["state"])
            finally:
                release.set(); thread.join(3)
            self.assertEqual(1, len(executions))
            self.assertEqual("CANCELLED", returned[0][1]["state"])
            self.assertNotIn("result", returned[0][1])


if __name__ == "__main__":
    unittest.main()
