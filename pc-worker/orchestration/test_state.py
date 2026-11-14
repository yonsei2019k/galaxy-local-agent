from dataclasses import FrozenInstanceError, replace
import json
from pathlib import Path
import unittest

from contracts import ContractError, Control, HostObservation, parse_message
from state import Effect, HostAuthority, Phase, Source, StateMachine

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "protocol" / "orchestration" / "v1" / "fixtures"
MESSAGES = json.loads((FIXTURES / "messages.json").read_text(encoding="utf-8"))
SCRIPTS = json.loads((FIXTURES / "state-cases.json").read_text(encoding="utf-8"))


def authority(**changes):
    return HostAuthority("example-owner", "example-project", "example-branch", "example-scope",
                         "pc", 2, 7, frozenset({"workspace.read", "artifact.propose", "workspace.write"}),
                         **changes)


def task(case="task-local-read"):
    return parse_message(next(c["raw"] for c in MESSAGES["cases"] if c["id"] == case))


def control(view, action, **changes):
    values = dict(request_id=view.request_id, scope="example-scope", epoch=2,
                  expected_revision=view.revision, request_digest=view.request_digest,
                  action=action, approval_id=None)
    values.update(changes)
    return Control(**values)


def observation(view, event, **changes):
    values = dict(request_id=view.request_id, scope="example-scope", epoch=2,
                  expected_revision=view.revision, request_digest=view.request_digest,
                  event=event, result_digest="c" * 64 if event == "effect_verified" else None)
    values.update(changes)
    return HostObservation(**values)


class StateTests(unittest.TestCase):
    def setUp(self):
        self.machine = StateMachine()
        self.authority = authority()
        self.task = task()
        self.view = self.machine.submit(self.task, self.authority)

    def error(self, code, callback):
        with self.assertRaises(ContractError) as caught:
            callback()
        self.assertEqual(code, caught.exception.code)

    def send(self, view, action, **changes):
        return self.machine.control(control(view, action, **changes), self.authority, source=Source.OWNER)

    def observe(self, view, event, **changes):
        return self.machine.observe(observation(view, event, **changes), self.authority, source=Source.HOST)

    def test_every_published_state_golden(self):
        self.assertEqual(1, SCRIPTS["fixture_version"])
        self.assertEqual(len(SCRIPTS["cases"]), len({c["id"] for c in SCRIPTS["cases"]}))
        for case in SCRIPTS["cases"]:
            with self.subTest(case=case["id"]):
                machine, auth, request = StateMachine(), authority(), task(case["task_case"])
                view = machine.submit(request, auth)
                for step in case["steps"]:
                    before = view
                    if step["kind"] == "resubmit":
                        run = lambda: machine.submit(replace(request, **step.get("overrides", {})), auth)
                    elif step["kind"] == "control":
                        message = control(view, step["action"], expected_revision=step["expected_revision"],
                                          approval_id=step.get("approval_id"))
                        run = lambda: machine.control(message, auth, source=Source[step.get("source", "OWNER")])
                    else:
                        message = observation(view, step["event"], expected_revision=step["expected_revision"])
                        run = lambda: machine.observe(message, auth, source=Source[step.get("source", "HOST")])
                    if "error" in step:
                        self.error(step["error"], run)
                        self.assertEqual(before, machine.snapshot(request.request_id, auth))
                    else:
                        view = run()
                        expected = step["expect"]
                        self.assertEqual(expected, {"phase": view.phase.value, "effect": view.effect.value,
                                                    "revision": view.revision, "attempts": view.attempts})

    def test_parsed_external_intent_has_no_legacy_permission(self):
        external = replace(self.task, provider="external_agent")
        self.error("EXTERNAL_DENIED", lambda: StateMachine().submit(external, self.authority))
        self.assertFalse(self.authority.allow_external_agent)
        allowed = replace(self.authority, allow_external_agent=True)
        self.assertEqual(Phase.READY, StateMachine().submit(external, allowed).phase)

    def test_host_binding_cannot_be_a_wire_dictionary(self):
        self.error("HOST_TYPE", lambda: StateMachine().submit(self.task, self.authority.__dict__))
        self.error("HOST_TYPE", lambda: self.machine.submit(self.task.wire(), self.authority))

    def test_scope_target_capability_subset_and_required_capability(self):
        for request, code in ((replace(self.task, scope="example-foreign"), "SCOPE"),
                              (replace(self.task, target="phone"), "SCOPE"),
                              (task("task-local-write"), "CAPABILITY_SUBSET"),
                              (replace(self.task, operation="write_artifact"), "REQUIRED_CAPABILITY")):
            auth = replace(self.authority, capabilities=frozenset({"workspace.read"})) if code == "CAPABILITY_SUBSET" else self.authority
            with self.subTest(code=code):
                self.error(code, lambda: StateMachine().submit(request, auth))

    def test_all_captured_identity_components_are_rechecked(self):
        for field, value in (("owner", "example-other"), ("project", "example-other"),
                             ("branch", "example-other"), ("scope", "example-other"),
                             ("target", "phone"), ("epoch", 3), ("revision", 8)):
            changed = replace(self.authority, **{field: value})
            with self.subTest(field=field):
                self.error("STALE_BINDING", lambda: self.machine.snapshot(self.task.request_id, changed))
                self.error("STALE_BINDING", lambda: self.machine.control(control(self.view, "start"), changed, source=Source.OWNER))
                self.assertEqual(self.view, self.machine.snapshot(self.task.request_id, self.authority))

    def test_revoked_capabilities_and_external_permission_fail_even_without_revision_bump(self):
        revoked = replace(self.authority, capabilities=frozenset())
        self.error("CAPABILITY_SUBSET", lambda: self.machine.control(control(self.view, "start"), revoked, source=Source.OWNER))
        external, auth = replace(self.task, provider="external_agent"), replace(self.authority, allow_external_agent=True)
        machine = StateMachine()
        view = machine.submit(external, auth)
        self.error("EXTERNAL_DENIED", lambda: machine.control(control(view, "start"), self.authority, source=Source.OWNER))

    def test_later_global_opt_in_does_not_rewrite_local_request(self):
        changed = replace(self.authority, allow_external_agent=True)
        view = self.machine.submit(self.task, changed)
        self.assertEqual(self.view.request_digest, view.request_digest)
        self.assertEqual("local", self.task.provider)
        promoted = replace(self.task, provider="external_agent")
        self.error("IDEMPOTENCY_CONFLICT", lambda: self.machine.submit(promoted, changed))

    def test_same_key_and_different_payload_or_request_id_is_conflict(self):
        self.assertEqual(self.view, self.machine.submit(self.task, self.authority))
        for request in (replace(self.task, input_digest="b" * 64),
                        replace(self.task, request_id="example-other-request")):
            self.error("IDEMPOTENCY_CONFLICT", lambda: self.machine.submit(request, self.authority))
            self.assertEqual(self.view, self.machine.snapshot(self.task.request_id, self.authority))
        self.error("REQUEST_ID_CONFLICT", lambda: self.machine.submit(replace(self.task, idempotency_key="example-other-key"), self.authority))

    def test_resubmit_cannot_inherit_a_new_epoch_or_authority_revision(self):
        for auth in (replace(self.authority, epoch=3), replace(self.authority, revision=8)):
            self.error("STALE_BINDING", lambda: self.machine.submit(self.task, auth))

    def test_controls_are_bound_to_scope_epoch_revision_and_digest(self):
        for changes, code in (({"scope": "example-other"}, "SCOPE"), ({"epoch": 3}, "STALE_EPOCH"),
                              ({"expected_revision": 1}, "STALE_REVISION"),
                              ({"request_digest": "b" * 64}, "REQUEST_DIGEST")):
            with self.subTest(code=code):
                self.error(code, lambda: self.send(self.view, "start", **changes))
                self.assertEqual(self.view, self.machine.snapshot(self.task.request_id, self.authority))

    def test_wire_source_strings_or_agents_cannot_control_or_verify(self):
        for source in (Source.AGENT, Source.HOST, "owner_control", True, None):
            self.error("UNTRUSTED_SOURCE", lambda: self.machine.control(control(self.view, "start"), self.authority, source=source))
        running = self.send(self.view, "start")
        for source in (Source.AGENT, Source.OWNER, "host_observer", True, None):
            self.error("UNTRUSTED_SOURCE", lambda: self.machine.observe(observation(running, "effect_verified"), self.authority, source=source))
        self.assertEqual(running, self.machine.snapshot(self.task.request_id, self.authority))

    def test_write_requires_bound_one_use_approval_and_new_approval_for_retry(self):
        machine, request = StateMachine(), task("task-local-write")
        view = machine.submit(request, self.authority)
        self.error("PHASE", lambda: machine.control(control(view, "start"), self.authority, source=Source.OWNER))
        approval = control(view, "approve", approval_id="example-approved")
        ready = machine.control(approval, self.authority, source=Source.OWNER)
        self.error("STALE_REVISION", lambda: machine.control(approval, self.authority, source=Source.OWNER))
        running = machine.control(control(ready, "start"), self.authority, source=Source.OWNER)
        self.error("STALE_REVISION", lambda: machine.control(control(ready, "start"), self.authority, source=Source.OWNER))
        paused = machine.observe(observation(running, "not_applied"), self.authority, source=Source.HOST)
        retry = machine.control(control(paused, "retry"), self.authority, source=Source.OWNER)
        self.assertEqual(Phase.AWAITING_APPROVAL, retry.phase)
        self.error("APPROVAL_REUSED", lambda: machine.control(control(retry, "approve", approval_id="example-approved"), self.authority, source=Source.OWNER))

    def test_approval_cannot_cross_requests_or_survive_current_authority_change(self):
        first = task("task-local-write")
        view = self.machine.submit(replace(first, request_id="example-write", idempotency_key="example-write-key"), self.authority)
        ready = self.send(view, "approve", approval_id="example-one-use")
        changed = replace(self.authority, revision=8)
        self.error("STALE_BINDING", lambda: self.machine.control(control(ready, "start"), changed, source=Source.OWNER))
        second = replace(first, request_id="example-second", idempotency_key="example-second-key")
        other = self.machine.submit(second, self.authority)
        self.error("APPROVAL_REUSED", lambda: self.send(other, "approve", approval_id="example-one-use"))

    def test_cancellation_waits_for_host_ack_and_never_proves_absence_of_effect(self):
        running = self.send(self.view, "start")
        cancel = self.send(running, "cancel")
        self.assertEqual(Phase.CANCEL_REQUESTED, cancel.phase)
        self.assertEqual(Effect.IN_FLIGHT, cancel.effect)
        self.error("PHASE", lambda: self.send(cancel, "start"))
        self.error("STALE_REVISION", lambda: self.observe(running, "cancel_ack"))
        cancelled = self.observe(cancel, "cancel_ack")
        self.assertEqual((Phase.CANCELLED, Effect.UNKNOWN), (cancelled.phase, cancelled.effect))
        late = self.observe(cancelled, "effect_verified")
        self.assertEqual((Phase.CANCELLED, Effect.VERIFIED), (late.phase, late.effect))
        self.assertEqual("c" * 64, late.result_digest)

    def test_unknown_effect_never_retries_automatically_or_from_agent_claim(self):
        running = self.send(self.view, "start")
        paused = self.observe(running, "effect_unknown")
        self.assertEqual((Phase.PAUSED, Effect.UNKNOWN, 1), (paused.phase, paused.effect, paused.attempts))
        self.error("EFFECT_UNCERTAIN_OR_APPLIED", lambda: self.send(paused, "retry"))
        self.error("UNTRUSTED_SOURCE", lambda: self.machine.observe(observation(paused, "not_applied"), self.authority, source=Source.AGENT))
        not_applied = self.observe(paused, "not_applied")
        self.assertEqual(Phase.PAUSED, not_applied.phase)
        self.assertEqual(1, not_applied.attempts)
        ready = self.send(not_applied, "retry")
        self.assertEqual(Phase.READY, ready.phase)
        self.assertEqual(1, ready.attempts)  # Retry only permits a later explicit start.

    def test_attempt_limit_bounds_explicit_safe_retries(self):
        view = self.view
        for attempt in (1, 2):
            view = self.send(view, "start")
            self.assertEqual(attempt, view.attempts)
            view = self.observe(view, "not_applied")
            if attempt == 1:
                view = self.send(view, "retry")
        self.error("ATTEMPT_BOUND", lambda: self.send(view, "retry"))
        for value in (True, 0, 4, 1.0):
            self.error("ATTEMPT_BOUND", lambda: StateMachine(value))

    def test_host_observations_recheck_binding_and_cannot_complete_unstarted_work(self):
        self.error("PHASE", lambda: self.observe(self.view, "effect_verified"))
        running = self.send(self.view, "start")
        self.error("SCOPE", lambda: self.observe(running, "effect_verified", scope="example-other"))
        self.error("REQUEST_DIGEST", lambda: self.observe(running, "effect_verified", request_digest="b" * 64))
        completed = self.observe(running, "effect_verified")
        self.error("EFFECT_UNCERTAIN_OR_APPLIED", lambda: self.send(completed, "retry"))
        self.error("PHASE", lambda: self.send(completed, "cancel"))

    def test_cancel_before_start_needs_ack_and_does_not_start_work(self):
        cancel = self.send(self.view, "cancel")
        self.assertEqual((Phase.CANCEL_REQUESTED, Effect.NONE, 0),
                         (cancel.phase, cancel.effect, cancel.attempts))
        self.error("PHASE", lambda: self.send(cancel, "start"))
        acknowledged = self.observe(cancel, "cancel_ack")
        self.assertEqual((Phase.CANCELLED, Effect.NONE, 0),
                         (acknowledged.phase, acknowledged.effect, acknowledged.attempts))

    def test_effect_during_cancellation_is_preserved_without_positive_completion(self):
        running = self.send(self.view, "start")
        cancel = self.send(running, "cancel")
        applied = self.observe(cancel, "effect_verified")
        self.assertEqual((Phase.CANCEL_REQUESTED, Effect.VERIFIED), (applied.phase, applied.effect))
        acknowledged = self.observe(applied, "cancel_ack")
        self.assertEqual((Phase.CANCELLED, Effect.VERIFIED), (acknowledged.phase, acknowledged.effect))
        self.assertEqual(applied.result_digest, acknowledged.result_digest)

    def test_later_host_readback_can_resolve_unknown_without_reexecution(self):
        running = self.send(self.view, "start")
        paused = self.observe(running, "effect_unknown")
        reconciled = self.observe(paused, "effect_verified")
        self.assertEqual((Phase.COMPLETED, Effect.VERIFIED, 1),
                         (reconciled.phase, reconciled.effect, reconciled.attempts))

    def test_snapshots_and_authority_are_immutable_and_typed(self):
        with self.assertRaises(FrozenInstanceError):
            self.view.phase = Phase.COMPLETED
        for changes in ({"epoch": True}, {"revision": -1}, {"allow_external_agent": "true"},
                        {"capabilities": {"workspace.read"}}):
            with self.subTest(changes=changes):
                with self.assertRaises(ContractError):
                    replace(self.authority, **changes)

    def test_in_memory_job_capacity_is_bounded_without_hidden_eviction(self):
        for index in range(1, self.machine.MAX_JOBS):
            request = replace(self.task, request_id=f"example-r-{index}", idempotency_key=f"example-k-{index}")
            self.machine.submit(request, self.authority)
        extra = replace(self.task, request_id="example-overflow", idempotency_key="example-overflow-key")
        self.error("JOB_BOUND", lambda: self.machine.submit(extra, self.authority))
        self.assertEqual(self.view, self.machine.submit(self.task, self.authority))


if __name__ == "__main__":
    unittest.main()
