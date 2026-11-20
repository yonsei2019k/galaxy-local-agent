"""Offline MCP bridge regressions. Fake transports only; no phone/model/credentials."""

import copy
import io
import json
import threading
import unittest
import urllib.error
from unittest.mock import patch

import device_tools_mcp as mcp


SESSION = "10000000-0000-4000-8000-000000000001"
OWNER = "20000000-0000-4000-8000-000000000002"
CALL = "30000000-0000-4000-8000-000000000003"
OTHER = "40000000-0000-4000-8000-000000000004"
CAPABILITY = "synthetic_qa_capability_00000000000000"
BASE = "/v1/agent/sessions/" + SESSION


def binding():
    return mcp.Binding(SESSION, OWNER, CAPABILITY, 1)


def catalog():
    return [
        {"name": "read_note", "description": "Read an explicitly allowed note.",
         "inputSchema": {"type": "object", "additionalProperties": False,
                         "required": ["id"], "properties": {
                             "id": {"type": "string", "minLength": 1, "maxLength": 64}}}},
        {"name": "pc_read_file", "description": "Read inside the captured PC workspace.",
         "inputSchema": {"type": "object", "additionalProperties": False,
                         "required": ["path"], "properties": {
                             "path": {"type": "string", "minLength": 1, "maxLength": 128},
                             "offset": {"type": "integer", "minimum": 0, "maximum": 1000}}}},
    ]


def snapshot():
    return {"sessionId": SESSION, "revision": 1, "state": "ACTIVE",
            "allowExternalAgent": True, "expiresAtEpochMs": 2_000_000, "tools": catalog(),
            "lastSequence": 0, "pendingCall": None, "restartPolicy": "RAM_ONLY_RESTART_REVOKES"}


def receipt(state="COMPLETED", **result_changes):
    result = {"success": True, "content": "synthetic observed content",
              "verification": "VERIFIED", "effectNotApplied": False, **result_changes}
    value = {"callId": CALL, "sequence": 1, "state": state}
    if state not in {"QUEUED", "CLAIMED", "RUNNING", "UNKNOWN_EFFECT", "CANCELLED"}:
        value["result"] = result
        value["resultSource"] = "PHONE_EXECUTOR"
    return value


class Clock:
    def __init__(self):
        self.seconds = 1000.0

    def now(self):
        return self.seconds

    def wait(self, event, seconds):
        self.seconds += seconds
        return event.is_set()


class FakeTransport:
    def __init__(self):
        self.status = snapshot()
        self.responses = [receipt()]
        self.calls = []
        self.on_post = None

    def request(self, method, path, body=None, timeout=5):
        self.calls.append((method, path, copy.deepcopy(body)))
        if method == "GET" and path == BASE:
            return copy.deepcopy(self.status)
        if method == "POST" and path == BASE + "/call":
            if self.on_post:
                self.on_post()
        elif method != "GET" or path != BASE + "/calls/" + CALL:
            raise AssertionError("Unexpected transport target")
        value = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(value, Exception):
            raise value
        return copy.deepcopy(value)

    def submissions(self):
        return [value for value in self.calls if value[0] == "POST"]


def fixture():
    transport, clock = FakeTransport(), Clock()
    gateway = mcp.Gateway(binding(), transport, clock.now, clock.now, clock.wait, lambda: CALL)
    gateway.list_tools()
    return gateway, transport, clock


def rpc(method, params=None, identifier=1):
    return {"jsonrpc": "2.0", "id": identifier, "method": method,
            "params": {} if params is None else params}


def ready_server(gateway):
    server = mcp.MCPServer(gateway)
    response = server.handle(rpc("initialize", {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "synthetic-client", "version": "1"}}))
    assert response["result"]["protocolVersion"] == "2024-11-05"
    server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})
    return server


class GatewayTests(unittest.TestCase):
    def assert_code(self, code, function, *args):
        with self.assertRaises(mcp.BridgeError) as caught:
            function(*args)
        self.assertEqual(code, caught.exception.code)

    def test_dynamic_phone_and_pc_catalog_only_from_bound_session(self):
        gateway, transport, _ = fixture()
        tools = gateway.list_tools()["tools"]
        self.assertEqual(["read_note", "pc_read_file"], [tool["name"] for tool in tools])
        self.assertTrue(all(call[:2] == ("GET", BASE) for call in transport.calls))
        tools[0]["inputSchema"]["required"] = []
        self.assertEqual(["id"], gateway.catalog["read_note"]["inputSchema"]["required"])

    def test_phone_call_transmits_no_owner_or_authority_in_model_arguments(self):
        gateway, transport, _ = fixture()
        result = gateway.call_tool("read_note", {"id": "qa-note"})
        payload = json.loads(result["content"][0]["text"])
        self.assertTrue(payload["effectVerified"])
        self.assertEqual(CALL,payload["callId"])
        self.assertEqual(1,payload["sequence"])
        self.assertFalse(result["isError"])
        self.assertEqual([( "POST", BASE + "/call", {
            "callId": CALL, "tool": "read_note", "arguments": {"id": "qa-note"}})], transport.submissions())
        self.assertNotIn(CAPABILITY, result["content"][0]["text"])

    def test_scoped_pc_tool_is_broker_call_not_local_path_read(self):
        gateway, transport, _ = fixture()
        transport.responses[0]["resultSource"] = "PC_WORKSPACE"
        gateway.call_tool("pc_read_file", {"path": "qa.txt", "offset": 0})
        self.assertEqual("pc_read_file", transport.submissions()[0][2]["tool"])
        self.assertEqual({"path": "qa.txt", "offset": 0}, transport.submissions()[0][2]["arguments"])

    def test_unknown_and_malformed_arguments_never_enqueue(self):
        for name, arguments in (("shell", {}), ("read_note", {}), ("read_note", []),
                                ("read_note", {"id": 1}), ("read_note", {"id": ""}),
                                ("read_note", {"id": "x", "owner": OTHER}),
                                ("pc_read_file", {"path": "qa", "offset": True})):
            with self.subTest(name=name, arguments=arguments):
                gateway, transport, _ = fixture()
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool(name, arguments)
                self.assertEqual([], transport.submissions())

    def test_call_without_discovery_cannot_enqueue(self):
        transport = FakeTransport()
        gateway = mcp.Gateway(binding(), transport)
        self.assert_code("UNKNOWN_TOOL", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual([], transport.calls)

    def test_expired_revoked_external_disabled_owner_session_revision_decline(self):
        variants = ({"sessionId": OTHER}, {"revision": 0}, {"revision": True},
                    {"state": "CANCELLED"}, {"state": "CLOSED"},
                    {"allowExternalAgent": False}, {"expiresAtEpochMs": 999_999},
                    {"expiresAtEpochMs": "2000000"}, {"restartPolicy": "DURABLE"},
                    {"lastSequence": True}, {"lastSequence": 19})
        for changes in variants:
            with self.subTest(changes=changes):
                gateway, transport, _ = fixture()
                transport.status.update(changes)
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("read_note", {"id": "x"})
                self.assertEqual([], transport.submissions())

    def test_schema_swap_same_revision_and_expiry_extension_reject(self):
        for field in ("schema", "expires"):
            with self.subTest(field=field):
                gateway, transport, _ = fixture()
                if field == "schema":
                    transport.status["tools"][0]["inputSchema"]["required"] = []
                else:
                    transport.status["expiresAtEpochMs"] += 1000
                self.assert_code("SESSION_CHANGED", gateway.call_tool, "read_note", {"id": "x"})
                self.assertEqual([], transport.submissions())

    def test_claim_revision_can_increase_but_never_go_backwards(self):
        gateway, transport, _ = fixture()
        transport.status["revision"] = 3
        gateway.call_tool("read_note", {"id": "x"})
        self.assertEqual(3, gateway.last_revision)
        transport.status["revision"] = 2
        self.assert_code("SESSION_SCOPE_REJECTED", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(1, len(transport.submissions()))

    def test_unsupported_or_duplicate_catalog_declines_whole_list(self):
        changes = ("duplicate", "remote_ref", "pattern", "name", "extra", "schema_type")
        for change in changes:
            with self.subTest(change=change):
                transport = FakeTransport()
                if change == "duplicate":
                    transport.status["tools"].append(copy.deepcopy(transport.status["tools"][0]))
                elif change == "remote_ref":
                    transport.status["tools"][0]["inputSchema"]["$ref"] = "https://invalid.example/schema"
                elif change == "pattern":
                    transport.status["tools"][0]["inputSchema"]["properties"]["id"]["pattern"] = "(a+)+$"
                elif change == "name":
                    transport.status["tools"][0]["name"] = "../shell"
                elif change == "extra":
                    transport.status["tools"][0]["authority"] = "allow"
                else:
                    transport.status["tools"][0]["inputSchema"] = {"type": "string"}
                gateway = mcp.Gateway(binding(), transport, lambda: 1000)
                with self.assertRaises(mcp.BridgeError):
                    gateway.list_tools()
                self.assertIsNone(gateway.catalog)
                self.assertEqual([], transport.submissions())

    def test_polling_waits_for_actual_result_and_never_reposts(self):
        gateway, transport, _ = fixture()
        transport.responses = [receipt("QUEUED"), receipt("CLAIMED"), receipt("RUNNING"), receipt()]
        result = gateway.call_tool("read_note", {"id": "x"})
        self.assertTrue(json.loads(result["content"][0]["text"])["effectVerified"])
        self.assertEqual(1, len(transport.submissions()))
        polls = [call for call in transport.calls if "/calls/" in call[1]]
        self.assertEqual(3, len(polls))
        self.assertTrue(all(call[1] == BASE + "/calls/" + CALL for call in polls))

    def test_prepared_is_not_verified_completion(self):
        gateway, transport, _ = fixture()
        transport.responses = [receipt(verification="PREPARED")]
        result = gateway.call_tool("read_note", {"id": "x"})
        self.assertFalse(json.loads(result["content"][0]["text"])["effectVerified"])
        self.assertEqual("PREPARED", json.loads(result["content"][0]["text"])["result"]["verification"])

    def test_known_not_applied_failure_keeps_original_content_and_allows_new_call(self):
        gateway, transport, _ = fixture()
        transport.responses = [receipt(success=False, verification="FAILED", effectNotApplied=True,
                                       content="Permission denied; no write applied.")]
        result = gateway.call_tool("read_note", {"id": "x"})
        self.assertTrue(result["isError"])
        self.assertIn("Permission denied", result["content"][0]["text"])
        self.assertIsNone(gateway.uncertain_call)

    def test_ambiguous_post_failure_is_not_retried_and_blocks_next_call(self):
        gateway, transport, _ = fixture()
        transport.responses = [mcp.BridgeError("TRANSPORT_UNAVAILABLE")]
        self.assert_code("TRANSPORT_UNAVAILABLE", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(CALL, gateway.uncertain_call)
        self.assert_code("PREVIOUS_EFFECT_UNRESOLVED", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(1, len(transport.submissions()))

    def test_unknown_state_does_not_replay_a_claimed_write(self):
        for state in ("UNKNOWN_EFFECT", "CANCELLED", "UNRECOGNIZED"):
            with self.subTest(state=state):
                gateway, transport, _ = fixture()
                transport.responses = [receipt(state)]
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("read_note", {"id": "x"})
                self.assertEqual(CALL, gateway.uncertain_call)
                self.assertEqual(1, len(transport.submissions()))

    def test_wrong_call_or_changed_sequence_or_fake_result_reject(self):
        bad = []
        value = receipt(); value["callId"] = OTHER; bad.append([value])
        value = receipt(); value["sequence"] = True; bad.append([value])
        value = receipt(); value["sequence"] = 2; bad.append([receipt("CLAIMED"), value])
        value = receipt("QUEUED"); value["result"] = receipt()["result"]; bad.append([value])
        value = receipt(); value["result"]["verification"] = "MODEL_SAYS_DONE"; bad.append([value])
        value = receipt(); value["result"]["success"] = "true"; bad.append([value])
        for responses in bad:
            with self.subTest(responses=responses):
                gateway, transport, _ = fixture()
                transport.responses = responses
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("read_note", {"id": "x"})
                self.assertEqual(CALL, gateway.uncertain_call)

    def test_phone_result_cannot_impersonate_pc_workspace_or_omit_host_source(self):
        for source in (None, "MODEL", "PC_WORKSPACE"):
            with self.subTest(source=source):
                gateway, transport, _ = fixture()
                transport.responses[0]["resultSource"] = source
                self.assert_code("INVALID_RESULT_SOURCE", gateway.call_tool, "read_note", {"id": "x"})
                self.assertEqual(CALL, gateway.uncertain_call)

    def test_result_enum_and_effect_flags_match_actual_broker_contract(self):
        for changes in ({"verification": "UNKNOWN_EFFECT"},
                        {"verification": "VERIFIED", "success": False},
                        {"verification": "PREPARED", "success": False},
                        {"verification": "UNVERIFIED", "success": False, "effectNotApplied": True},
                        {"verification": "FAILED", "effectNotApplied": True}):
            with self.subTest(changes=changes):
                gateway, transport, _ = fixture()
                transport.responses = [receipt(**changes)]
                self.assert_code("INVALID_CALL_RECEIPT", gateway.call_tool, "read_note", {"id": "x"})
                self.assertEqual(CALL, gateway.uncertain_call)

    def test_public_skip_is_bound_to_the_current_submission(self):
        transport, clock = FakeTransport(), Clock()
        transport.status["tools"][0]["name"] = "read_webpage"
        gateway = mcp.Gateway(binding(), transport, clock.now, clock.now, clock.wait, lambda: CALL)
        gateway.list_tools()
        transport.responses = [{"callId": CALL, "state": "SKIPPED", "reason": "PUBLIC_READ_REPEAT"}, receipt()]
        value = gateway.call_tool("read_webpage", {"id": "repeated"})
        self.assertTrue(value["isError"])
        self.assertIn("PUBLIC_READ_REPEAT", value["content"][0]["text"])
        self.assertIsNone(gateway.uncertain_call)
        self.assertFalse(gateway.call_tool("read_webpage", {"id": "alternative"})["isError"])
        self.assertEqual(2, len(transport.submissions()))

    def test_known_public_failure_can_try_another_read_but_invalid_skip_cannot(self):
        transport, clock = FakeTransport(), Clock()
        transport.status["tools"][0]["name"] = "read_webpage"
        gateway = mcp.Gateway(binding(), transport, clock.now, clock.now, clock.wait, lambda: CALL)
        gateway.list_tools()
        transport.responses = [receipt(success=False, verification="FAILED"),
                               {"callId": OTHER, "state": "SKIPPED", "reason": "PUBLIC_READ_REPEAT"}]
        self.assertTrue(gateway.call_tool("read_webpage", {"id": "first"})["isError"])
        self.assertIsNone(gateway.uncertain_call)
        self.assert_code("INVALID_CALL_RECEIPT", gateway.call_tool, "read_webpage", {"id": "second"})
        self.assertEqual(CALL, gateway.uncertain_call)

    def test_cancel_before_submission_has_zero_effect_requests(self):
        gateway, transport, _ = fixture()
        event = threading.Event(); event.set()
        self.assert_code("CANCELLED_BEFORE_SUBMISSION", gateway.call_tool, "read_note", {"id": "x"}, event)
        self.assertEqual([], transport.submissions())
        self.assertIsNone(gateway.uncertain_call)

    def test_cancel_after_enqueue_stops_wait_without_session_cancel_or_replay(self):
        gateway, transport, _ = fixture()
        event = threading.Event()
        transport.on_post = event.set
        transport.responses = [receipt("CLAIMED")]
        self.assert_code("WAIT_CANCELLED_EFFECT_UNKNOWN", gateway.call_tool, "read_note", {"id": "x"}, event)
        self.assertEqual(CALL, gateway.uncertain_call)
        self.assertEqual(1, len(transport.submissions()))
        self.assertFalse(any(call[1].endswith("/cancel") for call in transport.calls))

    def test_ttl_during_wait_is_unknown_and_never_clips_or_retries(self):
        gateway, transport, clock = fixture()
        transport.responses = [receipt("CLAIMED")]
        gateway.wait = lambda event, seconds: setattr(clock, "seconds", 2001)
        self.assert_code("SESSION_EXPIRED_EFFECT_UNKNOWN", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(1, len(transport.submissions()))

    def test_wait_timeout_is_bounded_and_sticky(self):
        gateway, transport, clock = fixture()
        transport.responses = [receipt("CLAIMED")]
        gateway.wait = lambda event, seconds: setattr(clock, "seconds", clock.seconds + 121)
        self.assert_code("WAIT_TIMEOUT_EFFECT_UNKNOWN", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(CALL, gateway.uncertain_call)

    def test_oversized_multibyte_body_never_submitted(self):
        gateway, transport, _ = fixture()
        self.assert_code("BYTE_LIMIT", gateway.call_tool, "read_note", {"id": "한" * 23000})
        self.assertEqual([], transport.submissions())

    def test_oversized_result_is_not_reported_as_truncated_success(self):
        gateway, transport, _ = fixture()
        transport.responses = [receipt(content="한" * 50000)]
        self.assert_code("BYTE_LIMIT", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(CALL, gateway.uncertain_call)

    def test_outer_json_escaping_is_included_in_complete_result_byte_limit(self):
        gateway, transport, _ = fixture()
        transport.responses = [receipt(content="\"" * 50000)]
        self.assert_code("BYTE_LIMIT", gateway.call_tool, "read_note", {"id": "x"})
        self.assertEqual(CALL, gateway.uncertain_call)


def plan_arguments(required=None):
    return {"goal": "Read the explicitly requested QA note.",
            "requiredTools": ["read_note"] if required is None else required,
            "constraints": ["Use the current captured scope."]}


def plan_metadata(arguments=None, state="ACCEPTED"):
    return {"state": state, "planDigest": "1" * 64, "requestDigest": "2" * 64,
            **copy.deepcopy(plan_arguments() if arguments is None else arguments)}


class PlanTransport(FakeTransport):
    def __init__(self):
        super().__init__()
        self.status.update(requiresPlan=True, plan=None)
        self.plan_state = "AWAITING_HOST"
        self.on_plan = None

    def request(self, method, path, body=None, timeout=5):
        if method == "POST" and path == BASE + "/plan":
            self.calls.append((method, path, copy.deepcopy(body)))
            self.status["plan"] = plan_metadata(body, self.plan_state)
            self.status["revision"] += 1
            if self.on_plan is not None:
                self.on_plan()
            return copy.deepcopy(self.status)
        return super().request(method, path, body, timeout)

    def plans(self):
        return [value for value in self.calls if value[:2] == ("POST", BASE + "/plan")]

    def domain_calls(self):
        return [value for value in self.calls if value[:2] == ("POST", BASE + "/call")]


def plan_fixture():
    transport, clock = PlanTransport(), Clock()
    gateway = mcp.Gateway(binding(), transport, clock.now, clock.now, clock.wait, lambda: CALL)
    gateway.list_tools()
    return gateway, transport, clock


class PlanTests(unittest.TestCase):
    # Reuse the error assertion without inheriting/rerunning the baseline cases.
    assert_code = GatewayTests.assert_code

    def test_legacy_omitted_and_explicit_false_have_no_virtual_tool(self):
        for explicit in (False, True):
            with self.subTest(explicit=explicit):
                gateway, transport, _ = fixture()
                if explicit:
                    transport.status["requiresPlan"] = False
                tools = gateway.list_tools()["tools"]
                self.assertEqual(["read_note", "pc_read_file"], [tool["name"] for tool in tools])
                self.assert_code("UNKNOWN_TOOL", gateway.call_tool, "plan_task", plan_arguments())
                self.assertEqual([], transport.submissions())
                self.assertFalse(gateway.call_tool("read_note", {"id": "qa"})["isError"])

    def test_plan_required_session_advertises_stable_virtual_schema_only(self):
        gateway, transport, _ = plan_fixture()
        first = gateway.list_tools()
        self.assertEqual(["plan_task", "read_note", "pc_read_file"], [tool["name"] for tool in first["tools"]])
        self.assertNotIn("plan_task", gateway.catalog)
        schema = first["tools"][0]["inputSchema"]
        self.assertEqual(["pc_read_file", "read_note"], schema["properties"]["requiredTools"]["items"]["enum"])
        transport.status["plan"] = plan_metadata(state="AWAITING_HOST")
        self.assertEqual(first, gateway.list_tools())
        transport.status["plan"]["state"] = "ACCEPTED"
        transport.status["revision"] += 1
        self.assertEqual(first, gateway.list_tools())
        self.assertEqual([], transport.submissions())

    def test_domain_call_before_phone_acceptance_never_enqueues(self):
        for state in (None, "AWAITING_HOST", "REJECTED"):
            with self.subTest(state=state):
                gateway, transport, _ = plan_fixture()
                if state is not None:
                    transport.status["plan"] = plan_metadata(state=state)
                self.assert_code("PLAN_NOT_ACCEPTED", gateway.call_tool, "read_note", {"id": "qa"})
                self.assertEqual([], transport.submissions())

    def test_virtual_plan_waits_for_phone_ack_without_consuming_call_id_or_sequence(self):
        gateway, transport, clock = plan_fixture()
        def accept(event, seconds):
            clock.wait(event, seconds)
            transport.status["plan"]["state"] = "ACCEPTED"
            transport.status["revision"] += 1
        gateway.wait = accept
        gateway.new_id = lambda: self.fail("A plan must not consume a domain call ID")
        value = gateway.call_tool("plan_task", plan_arguments())
        payload = json.loads(value["content"][0]["text"])
        self.assertFalse(value["isError"])
        self.assertEqual("ephemeral_plan", payload["kind"])
        self.assertEqual("ACCEPTED", payload["state"])
        self.assertEqual("PHONE_PLAN_ACK", payload["evidenceSource"])
        self.assertFalse(payload["effectVerified"])
        self.assertNotIn("callId", payload)
        self.assertNotIn("sequence", payload)
        self.assertEqual([( "POST", BASE + "/plan", plan_arguments())], transport.plans())
        self.assertEqual([], transport.domain_calls())
        self.assertEqual(0, transport.status["lastSequence"])
        self.assertTrue(all(not call[1].endswith("/plan-ack") for call in transport.calls))

    def test_accepted_plan_restricts_tools_and_preserves_root_actual_call_observations(self):
        gateway, transport, _ = plan_fixture()
        transport.plan_state = "ACCEPTED"
        gateway.call_tool("plan_task", plan_arguments())
        self.assert_code("TOOL_OUTSIDE_ACCEPTED_PLAN", gateway.call_tool, "pc_read_file", {"path": "qa.txt"})
        self.assertEqual([], transport.domain_calls())
        result = gateway.call_tool("read_note", {"id": "qa"})
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(CALL, payload["callId"])
        self.assertEqual(1, payload["sequence"])
        self.assertTrue(payload["effectVerified"])
        self.assertEqual(1, len(transport.domain_calls()))

    def test_empty_tool_plan_is_answer_only_and_cannot_call_a_domain_tool(self):
        gateway, transport, _ = plan_fixture()
        transport.plan_state = "ACCEPTED"
        result = gateway.call_tool("plan_task", plan_arguments([]))
        self.assertFalse(result["isError"])
        self.assertEqual([], json.loads(result["content"][0]["text"])["requiredTools"])
        self.assert_code("TOOL_OUTSIDE_ACCEPTED_PLAN", gateway.call_tool, "read_note", {"id": "qa"})
        self.assertEqual([], transport.domain_calls())

    def test_invalid_plan_arguments_never_submit_or_expand_authority(self):
        invalid = [plan_arguments(["shell"]), plan_arguments(["plan_task"]),
                   plan_arguments(["read_note", "read_note"]),
                   {**plan_arguments(), "goal": " "}, {**plan_arguments(), "goal": "x" * 1001},
                   {**plan_arguments(), "constraints": ["x"] * 7},
                   {**plan_arguments(), "constraints": [" "]},
                   {**plan_arguments(), "parentRunId": OTHER}, {**plan_arguments(), "accepted": True}]
        for arguments in invalid:
            with self.subTest(arguments=arguments):
                gateway, transport, _ = plan_fixture()
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("plan_task", arguments)
                self.assertEqual([], transport.submissions())

    def test_exact_plan_reuse_has_no_second_post_but_changed_plan_is_rejected(self):
        gateway, transport, _ = plan_fixture()
        transport.plan_state = "ACCEPTED"
        first = gateway.call_tool("plan_task", plan_arguments())
        self.assertEqual(first, gateway.call_tool("plan_task", plan_arguments()))
        gateway.call_tool("read_note", {"id": "qa"})
        self.assert_code("PLAN_CHANGED", gateway.call_tool, "plan_task", {**plan_arguments(), "goal": "A different goal"})
        self.assertEqual(1, gateway.rejected_calls)
        self.assertEqual(1, len(transport.plans()))
        self.assertEqual(1, len(transport.domain_calls()))

    def test_plan_text_limit_matches_broker_and_phone_utf16_units(self):
        for changes in ({"goal": "😀" * 501}, {"constraints": ["😀" * 121]}):
            with self.subTest(changes=changes):
                gateway, transport, _ = plan_fixture()
                self.assert_code("INVALID_ARGUMENTS", gateway.call_tool, "plan_task", {**plan_arguments(), **changes})
                self.assertEqual([], transport.submissions())
        gateway, transport, _ = plan_fixture()
        transport.plan_state = "ACCEPTED"
        self.assertFalse(gateway.call_tool("plan_task", {**plan_arguments(), "goal": "😀" * 500})["isError"])

    def test_plan_digest_payload_and_terminal_state_cannot_change(self):
        for changes in ({"planDigest": "3" * 64}, {"requestDigest": "3" * 64},
                        {"requiredTools": ["pc_read_file"]}, {"state": "AWAITING_HOST"},
                        {"state": "REJECTED"}):
            with self.subTest(changes=changes):
                gateway, transport, _ = plan_fixture()
                transport.plan_state = "ACCEPTED"
                gateway.call_tool("plan_task", plan_arguments())
                transport.status["plan"].update(changes)
                for _ in range(mcp.MAX_REJECTED_CALLS + 1):
                    self.assert_code("PLAN_CHANGED", gateway.call_tool, "read_note", {"id": "qa"})
                self.assertEqual(0, gateway.rejected_calls)
                self.assertEqual([], transport.domain_calls())

    def test_plan_mode_missing_metadata_collision_or_downgrade_fails_closed(self):
        for kind in ("missing", "malformed", "collision", "downgrade", "dropped_plan"):
            with self.subTest(kind=kind):
                gateway, transport, _ = plan_fixture()
                if kind == "missing":
                    del transport.status["plan"]
                elif kind == "malformed":
                    transport.status["plan"] = {"state": "ACCEPTED"}
                elif kind == "collision":
                    transport.status["tools"][0]["name"] = "plan_task"
                elif kind == "downgrade":
                    del transport.status["requiresPlan"]
                    del transport.status["plan"]
                else:
                    transport.status["plan"] = plan_metadata()
                    gateway.list_tools()
                    transport.status["plan"] = None
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("read_note", {"id": "qa"})
                self.assertEqual([], transport.submissions())

    def test_host_rejection_has_no_effect_and_model_cannot_ack_it(self):
        gateway, transport, _ = plan_fixture()
        transport.plan_state = "REJECTED"
        self.assert_code("PLAN_REJECTED", gateway.call_tool, "plan_task", plan_arguments())
        self.assert_code("PLAN_NOT_ACCEPTED", gateway.call_tool, "read_note", {"id": "qa"})
        self.assertEqual([], transport.domain_calls())
        self.assertIsNone(gateway.uncertain_call)
        self.assertTrue(all(not call[1].endswith("/plan-ack") for call in transport.calls))

    def test_cancelled_plan_wait_does_not_cancel_a_grant_or_enqueue_an_effect(self):
        gateway, transport, _ = plan_fixture()
        cancel = threading.Event()
        transport.on_plan = cancel.set
        self.assert_code("PLAN_WAIT_CANCELLED", gateway.call_tool, "plan_task", plan_arguments(), cancel)
        self.assertEqual(1, len(transport.plans()))
        self.assertEqual([], transport.domain_calls())
        self.assertIsNone(gateway.uncertain_call)
        self.assertTrue(all(not call[1].endswith("/cancel") for call in transport.calls))

    def test_plan_wait_timeout_expiry_and_revocation_do_not_autoretry(self):
        for kind in ("timeout", "expiry", "revoked"):
            with self.subTest(kind=kind):
                gateway, transport, clock = plan_fixture()
                def change(event, seconds):
                    if kind == "timeout":
                        clock.seconds += 121
                    elif kind == "expiry":
                        clock.seconds = 2001
                    else:
                        transport.status["state"] = "REVOKED"
                gateway.wait = change
                with self.assertRaises(mcp.BridgeError):
                    gateway.call_tool("plan_task", plan_arguments())
                self.assertEqual(1, len(transport.plans()))
                self.assertEqual([], transport.domain_calls())

    def test_lost_plan_response_only_allows_same_captured_proposal(self):
        gateway, transport, clock = plan_fixture()
        def lost_response():
            raise mcp.BridgeError("TRANSPORT_UNAVAILABLE")
        transport.on_plan = lost_response
        self.assert_code("TRANSPORT_UNAVAILABLE", gateway.call_tool, "plan_task", plan_arguments())
        self.assert_code("PLAN_CHANGED", gateway.call_tool, "plan_task", {**plan_arguments(), "goal": "Another goal"})
        transport.status["plan"]["state"] = "ACCEPTED"
        value = gateway.call_tool("plan_task", plan_arguments())
        self.assertFalse(value["isError"])
        self.assertEqual(1, len(transport.plans()))
        self.assertEqual([], transport.domain_calls())

    def test_transport_plan_endpoint_preserves_fixed_headers_and_disallows_plan_ack(self):
        transport = mcp.WorkerTransport(binding())
        transport._config = {"port": 12345, "token": "synthetic-worker-token"}
        captured = []
        class Opener:
            def open(self, request, timeout):
                captured.append(request)
                return io.BytesIO(mcp.json_bytes(snapshot()))
        transport._opener = Opener()
        transport.request("POST", BASE + "/plan", plan_arguments())
        request = captured[0]
        self.assertEqual(plan_arguments(), json.loads(request.data))
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual(OWNER, headers[mcp.OWNER_HEADER.lower()])
        self.assertEqual(CAPABILITY, headers[mcp.CAPABILITY_HEADER.lower()])
        with self.assertRaises(mcp.BridgeError):
            transport.request("POST", BASE + "/plan-ack", {"accepted": True})
        self.assertEqual(1, len(captured))


class SchemaTests(unittest.TestCase):
    def test_broker_fixed_decimal_and_uuid_identifier_patterns_are_supported(self):
        for pattern, valid, invalid in ((r"^[0-9]{1,19}$", "123", "../123"),
                (r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", CALL, "not-uuid")):
            with self.subTest(pattern=pattern):
                schema = {"type": "string", "pattern": pattern}
                mcp.validate_schema(schema)
                mcp.validate_arguments(valid, schema)
                with self.assertRaises(mcp.BridgeError):
                    mcp.validate_arguments(invalid, schema)

    def test_one_of_quote_or_literal_rejects_ambiguous_payload(self):
        schema = {"type": "object", "additionalProperties": False,
                  "properties": {"content": {"type": "string"}, "quoteId": {"type": "string"}},
                  "oneOf": [{"required": ["content"]}, {"required": ["quoteId"]}]}
        mcp.validate_schema(schema)
        mcp.validate_arguments({"content": "literal"}, schema)
        mcp.validate_arguments({"quoteId": "qa-reference"}, schema)
        for value in ({}, {"content": "x", "quoteId": "y"}, {"content": "x", "grant": True}):
            with self.assertRaises(mcp.BridgeError):
                mcp.validate_arguments(value, schema)

    def test_nested_arrays_integer_bool_and_unique_constraints(self):
        schema = {"type": "array", "minItems": 1, "maxItems": 2, "uniqueItems": True,
                  "items": {"type": "integer", "minimum": 1, "maximum": 7}}
        mcp.validate_schema(schema)
        mcp.validate_arguments([1, 7], schema)
        for value in ([True], [0], [8], [1, 1], [], [1, 2, 3]):
            with self.assertRaises(mcp.BridgeError):
                mcp.validate_arguments(value, schema)


class MCPTests(unittest.TestCase):
    def test_initialize_negotiates_supported_protocol_and_requires_initialized_notification(self):
        gateway, _, _ = fixture()
        server = mcp.MCPServer(gateway)
        self.assertIn("error", server.handle(rpc("tools/list")))
        initialized = server.handle(rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {}}))
        self.assertEqual("2024-11-05", initialized["result"]["protocolVersion"])
        self.assertIn("error", server.handle(rpc("tools/list")))
        server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(2, len(server.handle(rpc("tools/list"))["result"]["tools"]))

    def test_notification_tool_call_and_authority_methods_cannot_enqueue(self):
        gateway, transport, _ = fixture()
        server = ready_server(gateway)
        notification = rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}})
        del notification["id"]
        self.assertIsNone(server.handle(notification))
        for method in ("sessions/register", "sessions/claim", "sessions/result", "sessions/cancel", "sessions/close"):
            self.assertEqual(-32601, server.handle(rpc(method))["error"]["code"])
        self.assertEqual([], transport.submissions())

    def test_authority_fields_or_malformed_rpc_never_enqueue(self):
        cases = [rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}, "owner": OTHER}),
                 rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}, "sessionId": OTHER}),
                 rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}}, True),
                 [rpc("tools/call")], {"jsonrpc": "1.0", "id": 1, "method": "tools/call"}]
        for message in cases:
            with self.subTest(message=message):
                gateway, transport, _ = fixture()
                server = ready_server(gateway)
                self.assertIn("error", server.handle(message))
                self.assertEqual([], transport.submissions())

    def test_cancel_notification_interrupts_active_wait_and_wrong_id_does_not(self):
        gateway, transport, _ = fixture()
        server = ready_server(gateway)
        started, resume = threading.Event(), threading.Event()
        def wait(event, seconds):
            started.set()
            resume.wait(2)
            return event.is_set()
        gateway.wait = wait
        transport.responses = [receipt("CLAIMED")]
        replies = []
        thread = threading.Thread(target=lambda: replies.append(server.handle(
            rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}}, "active"))))
        thread.start()
        try:
            self.assertTrue(started.wait(2))
            server.handle({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "other"}})
            self.assertFalse(next(iter(server.active.values())).is_set())
            concurrent = server.handle(rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}}, 2))
            self.assertEqual("CALL_IN_PROGRESS", concurrent["error"]["message"])
            server.handle({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "active"}})
            resume.set()
        finally:
            resume.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual("WAIT_CANCELLED_EFFECT_UNKNOWN", replies[0]["result"]["content"][0]["text"])
        self.assertEqual(1, len(transport.submissions()))

    def test_binary_framing_duplicate_keys_nan_invalid_utf8_and_byte_limit(self):
        gateway, transport, _ = fixture()
        server = ready_server(gateway)
        source = io.BytesIO(b'{"jsonrpc":"2.0","id":1,"id":2,"method":"ping"}\n'
                            b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":NaN}}\n'
                            b'\xff\n' + ("한" * 45000).encode("utf-8") + b'\n'
                            + mcp.json_bytes(rpc("ping", identifier=9)) + b'\n')
        sink = io.BytesIO()
        mcp.serve(server, source, sink)
        replies = [json.loads(line) for line in sink.getvalue().splitlines()]
        self.assertEqual([-32700, -32700, -32700, -32600], [reply["error"]["code"] for reply in replies[:4]])
        self.assertEqual({"jsonrpc": "2.0", "id": 9, "result": {}}, replies[4])
        self.assertEqual([], transport.submissions())

    def test_cancel_arriving_before_worker_starts_cannot_be_lost(self):
        gateway, transport, _ = fixture()
        server = ready_server(gateway)
        message = rpc("tools/call", {"name": "read_note", "arguments": {"id": "x"}}, "slow-start")
        cancel = {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "slow-start"}}
        source = io.BytesIO(mcp.json_bytes(message) + b"\n" + mcp.json_bytes(cancel) + b"\n")
        sink = io.BytesIO()
        class DelayedThread:
            def __init__(self, target, args, daemon):
                self.target, self.args = target, args
                self.alive = False
            def start(self):
                self.alive = True
            def is_alive(self):
                return self.alive
            def join(self, timeout):
                self.target(*self.args)
                self.alive = False
        with patch.object(mcp.threading, "Thread", DelayedThread):
            mcp.serve(server, source, sink)
        replies = [json.loads(line) for line in sink.getvalue().splitlines()]
        self.assertEqual("CANCELLED_BEFORE_SUBMISSION", replies[0]["result"]["content"][0]["text"])
        self.assertEqual([], transport.submissions())
        self.assertEqual({}, server.active)


def nested_fixture():
    transport, clock = FakeTransport(), Clock()
    transport.status["tools"].append(
        {"name": "save_item", "description": "Save a nested item.",
         "inputSchema": {"type": "object", "additionalProperties": False,
                         "required": ["item"], "properties": {
                             "item": {"type": "object", "additionalProperties": False,
                                      "required": ["name"], "properties": {
                                          "name": {"type": "string", "minLength": 1, "maxLength": 32}}}}}})
    gateway = mcp.Gateway(binding(), transport, clock.now, clock.now, clock.wait, lambda: CALL)
    gateway.list_tools()
    return gateway, transport, clock


def call_text(server, name, arguments):
    reply = server.handle(rpc("tools/call", {"name": name, "arguments": arguments}))["result"]
    return reply["isError"], reply["content"][0]["text"]


class RejectionDetailTests(unittest.TestCase):
    def test_stringified_object_is_rejected_with_type_hint_and_never_coerced(self):
        gateway, transport, _ = nested_fixture()
        server = ready_server(gateway)
        stringified = '{"name": "secret-value-xyz"}'
        is_error, text = call_text(server, "save_item", {"item": stringified})
        self.assertTrue(is_error)
        self.assertTrue(text.startswith(
            "INVALID_ARGUMENTS: /item expected object, received string — pass the value itself"), text)
        self.assertNotIn("secret-value-xyz", text)
        self.assertEqual([], transport.submissions())

    def test_unexpected_key_lists_allowed_keys_without_echoing_the_key(self):
        gateway, transport, _ = fixture()
        server = ready_server(gateway)
        is_error, text = call_text(server, "read_note", {"id": "x", "leaked_key_name": 1})
        self.assertTrue(is_error)
        self.assertIn("1 unexpected key(s); allowed: id", text)
        self.assertNotIn("leaked_key_name", text)
        self.assertEqual([], transport.submissions())

    def test_missing_required_lists_schema_key(self):
        gateway, _, _ = fixture()
        server = ready_server(gateway)
        is_error, text = call_text(server, "read_note", {})
        self.assertTrue(is_error)
        self.assertEqual("INVALID_ARGUMENTS: / missing required: id", text)

    def test_other_violations_name_keyword_not_value(self):
        gateway, _, _ = fixture()
        server = ready_server(gateway)
        _, text = call_text(server, "read_note", {"id": ""})
        self.assertEqual("INVALID_ARGUMENTS: /id violates minLength/maxLength", text)
        _, text = call_text(server, "pc_read_file", {"path": "a", "offset": 5000})
        self.assertEqual("INVALID_ARGUMENTS: /offset violates minimum/maximum", text)

    def test_plan_unknown_required_tool_does_not_echo_the_name(self):
        gateway, transport, _ = plan_fixture()
        with self.assertRaises(mcp.BridgeError) as caught:
            gateway.call_tool("plan_task", plan_arguments(["secret_tool_name"]))
        self.assertEqual("INVALID_ARGUMENTS", caught.exception.code)
        self.assertNotIn("secret_tool_name", caught.exception.public_text())
        self.assertIn("/requiredTools/0 must be one of: pc_read_file, read_note", caught.exception.public_text())
        self.assertEqual([], transport.calls[1:])

    def test_limit_blocks_valid_call_after_six_counted_rejections_without_submitting(self):
        gateway, transport, _ = fixture()
        for _ in range(mcp.MAX_REJECTED_CALLS):
            with self.assertRaises(mcp.BridgeError):
                gateway.call_tool("read_note", {"id": 1})
        self.assertEqual(6, gateway.rejected_calls)
        calls_before = len(transport.calls)
        with self.assertRaises(mcp.BridgeError) as caught:
            gateway.call_tool("read_note", {"id": "valid"})
        self.assertEqual("REJECTED_CALL_LIMIT", caught.exception.code)
        self.assertIn("stop calling tools", caught.exception.public_text())
        self.assertEqual(6, gateway.rejected_calls)
        self.assertEqual(calls_before, len(transport.calls))
        self.assertEqual([], transport.submissions())

    def test_unknown_tool_oversize_and_outside_plan_are_counted(self):
        gateway, _, _ = fixture()
        with self.assertRaises(mcp.BridgeError):
            gateway.call_tool("shell", {})
        with self.assertRaises(mcp.BridgeError):
            gateway.call_tool("read_note", {"id": "é" * 40000})
        self.assertEqual(2, gateway.rejected_calls)
        plan_gateway, transport, _ = plan_fixture()
        transport.status["plan"] = plan_metadata(state="ACCEPTED")
        with self.assertRaises(mcp.BridgeError) as caught:
            plan_gateway.call_tool("pc_read_file", {"path": "a"})
        self.assertEqual("TOOL_OUTSIDE_ACCEPTED_PLAN", caught.exception.code)
        self.assertEqual(1, plan_gateway.rejected_calls)

    def test_in_progress_plan_not_accepted_and_post_submission_failures_are_not_counted(self):
        gateway, _, _ = fixture()
        self.assertTrue(gateway._call_lock.acquire(blocking=False))
        try:
            with self.assertRaises(mcp.BridgeError) as caught:
                gateway.call_tool("read_note", {"id": "x"})
            self.assertEqual("CALL_IN_PROGRESS", caught.exception.code)
        finally:
            gateway._call_lock.release()
        self.assertEqual(0, gateway.rejected_calls)
        plan_gateway, _, _ = plan_fixture()
        with self.assertRaises(mcp.BridgeError) as caught:
            plan_gateway.call_tool("read_note", {"id": "x"})
        self.assertEqual("PLAN_NOT_ACCEPTED", caught.exception.code)
        self.assertEqual(0, plan_gateway.rejected_calls)
        failing, transport, _ = fixture()
        transport.responses = [urllib.error.URLError("down")]
        with self.assertRaises(mcp.BridgeError):
            failing.call_tool("read_note", {"id": "x"})
        self.assertEqual(0, failing.rejected_calls)

    def test_json_string_object_argument_is_never_coerced_into_a_submission(self):
        gateway, transport, _ = nested_fixture()
        with self.assertRaises(mcp.BridgeError):
            gateway.call_tool("save_item", {"item": json.dumps({"name": "x"})})
        with self.assertRaises(mcp.BridgeError):
            gateway.call_tool("save_item", json.dumps({"item": {"name": "x"}}))
        self.assertEqual([], transport.submissions())


class TransportTests(unittest.TestCase):
    def test_private_json_bootstrap_is_exact_bounded_and_not_combined_with_legacy(self):
        data = {"sessionId": SESSION, "parentRunId": OWNER, "agentCapability": CAPABILITY, "revision": 1}
        env = {"ZIPHAP_DEVICE_BRIDGE_BOOTSTRAP": json.dumps(data)}
        self.assertEqual(binding(), mcp.Binding.from_environment(env))
        bad = [json.dumps({**data, "phoneCapability": "not-agent-authority"}),
               json.dumps({**data, "path": "arbitrary"}), json.dumps({**data, "revision": True}),
               json.dumps({**data, "revision": "1"}), json.dumps({**data, "agentCapability": "a" * 4100}),
               json.dumps(data)[:-1] + ',"revision":2}']
        for raw in bad:
            with self.subTest(length=len(raw)), self.assertRaises(mcp.BridgeError):
                mcp.Binding.from_environment({"ZIPHAP_DEVICE_BRIDGE_BOOTSTRAP": raw})
        with self.assertRaises(mcp.BridgeError):
            mcp.Binding.from_environment({**env, "ZIPHAP_AGENT_SESSION_ID": SESSION})

    def test_bootstrap_requires_all_fixed_fields_and_hides_capability_repr(self):
        env = {"ZIPHAP_AGENT_SESSION_ID": SESSION, "ZIPHAP_AGENT_OWNER": OWNER,
               "ZIPHAP_AGENT_CAPABILITY": CAPABILITY, "ZIPHAP_AGENT_SESSION_REVISION": "1"}
        value = mcp.Binding.from_environment(env)
        self.assertEqual(binding(), value)
        self.assertNotIn(CAPABILITY, repr(value))
        for key in env:
            changed = dict(env); del changed[key]
            with self.assertRaises(mcp.BridgeError):
                mcp.Binding.from_environment(changed)
        for capability in ("short", CAPABILITY + "\r\nInjected: true"):
            with self.assertRaises(mcp.BridgeError):
                mcp.Binding(SESSION, OWNER, capability)

    def test_transport_rejects_other_session_grant_and_arbitrary_path_before_config(self):
        transport = mcp.WorkerTransport(binding())
        for method, path, body in (("GET", "https://invalid.example/", None),
                                   ("GET", "/v1/agent/sessions/" + OTHER, None),
                                   ("POST", BASE + "/result", {}),
                                   ("POST", BASE + "/cancel", {}),
                                   ("POST", BASE + "/close", {}),
                                   ("POST", "/v1/llm/chat", {})):
            with self.subTest(path=path), patch.object(transport, "_load") as load:
                with self.assertRaises(mcp.BridgeError):
                    transport.request(method, path, body)
                load.assert_not_called()

    def test_exact_owner_capability_loopback_and_bounded_response(self):
        transport = mcp.WorkerTransport(binding())
        transport._config = {"port": 12345, "token": "synthetic-worker-token"}
        captured = []
        class Opener:
            def open(self, request, timeout):
                captured.append((request, timeout))
                return io.BytesIO(mcp.json_bytes(snapshot()))
        transport._opener = Opener()
        self.assertEqual(SESSION, transport.request("GET", BASE)["sessionId"])
        request, timeout = captured[0]
        self.assertEqual("https://127.0.0.1:12345" + BASE, request.full_url)
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual(OWNER, headers[mcp.OWNER_HEADER.lower()])
        self.assertEqual(CAPABILITY, headers[mcp.CAPABILITY_HEADER.lower()])
        self.assertEqual("Bearer synthetic-worker-token", headers["authorization"])
        self.assertLessEqual(timeout, 5)

    def test_transport_never_exposes_http_error_body_or_exception_url(self):
        transport = mcp.WorkerTransport(binding())
        transport._config = {"port": 12345, "token": "synthetic-worker-token"}
        secret = "synthetic-secret-not-to-echo"
        class Opener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError("https://" + secret, 403, secret, {}, io.BytesIO(secret.encode()))
        transport._opener = Opener()
        with self.assertRaises(mcp.BridgeError) as caught:
            transport.request("GET", BASE)
        self.assertEqual("SCOPE_REJECTED", str(caught.exception))
        self.assertNotIn(secret, str(caught.exception))

    def test_oversized_or_duplicate_json_http_response_never_returns_partial_data(self):
        for raw in (b"x" * (mcp.MAX_MESSAGE_BYTES + 1), b'{"state":"ACTIVE","state":"REVOKED"}'):
            with self.subTest(length=len(raw)):
                transport = mcp.WorkerTransport(binding())
                transport._config = {"port": 12345, "token": "synthetic-worker-token"}
                class Opener:
                    def open(self, request, timeout):
                        return io.BytesIO(raw)
                transport._opener = Opener()
                with self.assertRaises(mcp.BridgeError):
                    transport.request("GET", BASE)

    def test_redirect_handler_declines_every_destination(self):
        with self.assertRaises(mcp.BridgeError) as caught:
            mcp.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://invalid.example")
        self.assertEqual("REDIRECT_BLOCKED", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
