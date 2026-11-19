"""Offline G09 state machine, not an LLM, Android, worker or external-service test.

The controller receives fixture state/events only. The independent comparator reads
an observed store/journal snapshot and the catalog oracle only after execution.
All data, checkpoints, effects and approvals are synthetic and remain in memory.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import date
import hashlib
import json
import re
import uuid

import goal_only_catalog


class SimulationError(ValueError):
    pass


class NotApplied(SimulationError):
    """A host journal receipt proves this particular attempt did not mutate state."""


def _require(condition, code):
    if not condition:
        raise SimulationError(code)


def _digest(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


@dataclass(frozen=True)
class Binding:
    namespace: str
    goal_id: str
    scope: str
    authorization_revision: int


class FixtureWorld:
    """The actual synthetic store and write-ahead journal, separate from a session checkpoint."""

    def __init__(self, fixture, *, namespace=None):
        fixture = deepcopy(fixture)
        _require(fixture.get("namespacePolicy") == "new_isolated_namespace_per_attempt", "ISOLATION_REQUIRED")
        _require(fixture.get("environmentRef") == "qa-isolated-v1", "UNKNOWN_ENVIRONMENT")
        goal = fixture["goal"]
        _require(type(goal["authorizationRevision"]) is int and goal["authorizationRevision"] >= 0, "AUTHORITY")
        self.binding = Binding(namespace or "qa-resume-" + uuid.uuid4().hex, goal["id"], goal["scope"], goal["authorizationRevision"])
        _require(self.binding.namespace.startswith("qa-resume-") and len(self.binding.namespace) <= 100, "NAMESPACE")
        self.authority = self.binding
        self.goal = deepcopy(goal) | {"state": "PAUSED", "segments": 0, "toolCalls": 0, "instructionRevision": 0, "due": None}
        _require(type(goal["maxSegments"]) is int and goal["maxSegments"] > 0 and
                 type(goal["maxToolCalls"]) is int and goal["maxToolCalls"] > 0, "BUDGET")
        self.native = deepcopy(fixture["nativeSource"])
        self.remaining_id = "qa-" + fixture["remaining"]
        _require(re.fullmatch(r"qa-[A-Za-z0-9_-]{1,80}", self.remaining_id) is not None, "ARTIFACT_ID")
        self.preserved = list(fixture["preserve"])
        _require(len(self.preserved) == 1 and self.remaining_id not in self.preserved, "PRESERVED_TARGET")
        self.artifacts, self.journal, self.trace = {}, [], []
        self.effect_counts = {}
        self.suppressed_replays = 0
        self.checkpoints = 0
        self.restores = 0
        self.faults_seen = set()
        self.mutation_attempts = 0
        self.owner_corrections = 0
        self.observations = 0
        # An unrelated record in another scope detects accidental whole-store mutation.
        self.unrelated = {"id": "qa-unrelated", "scope": "qa-other-scope", "content": "keep unchanged"}
        for item in fixture["ledger"]:
            identifier = item["artifactId"]
            _require(identifier in self.preserved and identifier not in self.artifacts, "LEDGER_ID")
            _require(item["state"] == "VERIFIED" and item["effectCount"] == 1, "LEDGER_STATE")
            record = {"artifactId": identifier, "binding": asdict(self.binding),
                      "payload": {"part": identifier, "due": None}, "instructionRevision": 0}
            self.artifacts[identifier] = record
            self.effect_counts[identifier] = 1
            self.journal.append({"operation": "seed-" + identifier, "artifactId": identifier,
                                 "state": "VERIFIED", "binding": asdict(self.binding),
                                 "sha256": _digest(record) if item["readbackMatched"] else "0" * 64})
        _require(set(self.artifacts) == set(self.preserved), "MISSING_PRESERVED_ARTIFACT")
        self.initial_artifacts = deepcopy(self.artifacts)
        self.initial_unrelated = deepcopy(self.unrelated)
        self.initial_counts = deepcopy(self.effect_counts)

    def guard(self, binding):
        _require(binding == self.binding == self.authority, "SCOPE_OR_GRANT_CHANGED")

    def event(self, kind, **value):
        self.trace.append({"sequence": len(self.trace) + 1, "kind": kind, **deepcopy(value)})

    def tool(self, binding):
        self.guard(binding)
        _require(self.goal["toolCalls"] < self.goal["maxToolCalls"], "TOOL_BUDGET")
        self.goal["toolCalls"] += 1

    def unknown(self):
        return (self.native.get("uncertain") is True or not self.native.get("closed") or
                self.native.get("inFlight") is not None or
                any(item["state"] in {"IN_FLIGHT", "UNKNOWN"} for item in self.journal))

    def observe(self, binding):
        self.tool(binding)
        _require(self.native.get("present") is True, "NATIVE_SOURCE_MISSING")
        self.observations += 1
        self.event("observation", native=deepcopy(self.native), goalState=self.goal["state"])

    def readback(self, binding, identifier):
        self.tool(binding)
        _require(identifier in self.artifacts, "READBACK_TARGET_MISSING")
        record = deepcopy(self.artifacts[identifier])
        _require(record["binding"] == asdict(binding), "READBACK_SCOPE")
        receipt = next((r for r in reversed(self.journal) if r["artifactId"] == identifier and
                        r["state"] in {"APPLIED", "VERIFIED"}), None)
        _require(receipt is not None and receipt["sha256"] == _digest(record), "READBACK_MISMATCH")
        receipt["state"] = "VERIFIED"
        self.event("readback", artifactId=identifier, sha256=_digest(record), binding=asdict(binding))
        return record

    def create(self, binding, identifier, payload, *, faults=()):
        self.tool(binding)
        _require(identifier in self.preserved + [self.remaining_id], "UNRELATED_TARGET")
        if self.unknown():
            self.goal["state"] = "UNKNOWN_EFFECT_PAUSED"
            self.event("unknown_effect_paused")
            raise SimulationError("UNKNOWN_EFFECT")
        if identifier in self.artifacts:
            # Existence alone is insufficient. A same-target host receipt and readback are required.
            self.readback(binding, identifier)
            expected = self.artifacts[identifier]["payload"]
            _require(payload == expected, "EXISTING_TARGET_DIFFERS")
            self.suppressed_replays += 1
            self.event("replay_suppressed", artifactId=identifier)
            return deepcopy(self.artifacts[identifier])
        _require(identifier == self.remaining_id and self.goal["state"] == "RUNNING", "MUTATION_NOT_ACTIVE")
        self.mutation_attempts += 1
        receipt = {"operation": "remaining-" + str(self.mutation_attempts), "artifactId": identifier,
                   "state": "IN_FLIGHT", "binding": asdict(binding), "sha256": None}
        self.journal.append(receipt)
        self.event("mutation_started", operation=receipt["operation"], artifactId=identifier)
        for index, fault in enumerate(faults):
            _require(fault.get("fixtureOnly") is True and fault.get("semanticOperation") == "remaining_mutation" and
                     fault.get("kind") == "connection_lost_before_effect_with_host_not_applied_receipt" and
                     fault.get("effectNotApplied") is True and type(fault.get("occurrence")) is int, "UNSUPPORTED_FAULT")
            if index not in self.faults_seen and fault["occurrence"] == self.mutation_attempts:
                self.faults_seen.add(index)
                receipt["state"] = "NOT_APPLIED"
                self.goal["state"] = "RECOVERABLE_PAUSED"
                self.event("fault_not_applied", operation=receipt["operation"], effectsApplied=0)
                raise NotApplied("CONNECTION_LOST_NOT_APPLIED")
        record = {"artifactId": identifier, "binding": asdict(binding), "payload": deepcopy(payload),
                  "instructionRevision": self.goal["instructionRevision"]}
        self.artifacts[identifier] = record
        self.effect_counts[identifier] = self.effect_counts.get(identifier, 0) + 1
        receipt.update(state="APPLIED", sha256=_digest(record))
        self.event("effect_applied", artifactId=identifier, binding=asdict(binding),
                   instructionRevision=self.goal["instructionRevision"])
        return deepcopy(record)

    def capture(self):
        return deepcopy({"binding": asdict(self.binding), "authority": asdict(self.authority), "goal": self.goal,
                         "native": self.native, "artifacts": self.artifacts, "journal": self.journal,
                         "effectCounts": self.effect_counts, "initialCounts": self.initial_counts,
                         "initialArtifacts": self.initial_artifacts, "unrelated": self.unrelated,
                         "initialUnrelated": self.initial_unrelated, "preserved": self.preserved,
                         "remainingId": self.remaining_id, "trace": self.trace,
                         "checkpoints": self.checkpoints, "restores": self.restores,
                         "suppressedReplays": self.suppressed_replays})


class ResumeSession:
    def __init__(self, world):
        self.world = world
        self.binding = world.binding
        self.completed = []
        self.due = None

    def checkpoint(self):
        self.world.guard(self.binding)
        body = {"schema": 1, "binding": asdict(self.binding), "completed": self.completed,
                "due": self.due, "instructionRevision": self.world.goal["instructionRevision"]}
        self.world.checkpoints += 1
        self.world.event("checkpoint", state=self.world.goal["state"])
        return json.dumps(body, sort_keys=True, separators=(",", ":")).encode()

    @classmethod
    def restore(cls, world, checkpoint):
        _require(type(checkpoint) is bytes and len(checkpoint) <= 16384, "CHECKPOINT_BOUND")
        def unique(items):
            result = {}
            for key, value in items:
                _require(key not in result, "CHECKPOINT_DUPLICATE_KEY")
                result[key] = value
            return result
        try:
            value = json.loads(checkpoint, object_pairs_hook=unique)
        except (ValueError, UnicodeError, TypeError) as error:
            raise SimulationError("CHECKPOINT_FORMAT") from error
        _require(type(value) is dict and set(value) == {"schema", "binding", "completed", "due", "instructionRevision"}, "CHECKPOINT_FORMAT")
        _require(value["schema"] == 1 and value["binding"] == asdict(world.binding), "CHECKPOINT_SCOPE")
        _require(type(value["completed"]) is list and len(value["completed"]) <= 2 and
                 all(type(i) is str for i in value["completed"]) and len(set(value["completed"])) == len(value["completed"]), "CHECKPOINT_TARGETS")
        _require(type(value["instructionRevision"]) is int and value["instructionRevision"] == world.goal["instructionRevision"], "CHECKPOINT_STALE_INSTRUCTION")
        _require(value["due"] == world.goal["due"], "CHECKPOINT_OWNER_CONSTRAINT_CHANGED")
        session = cls(world)
        world.guard(session.binding)
        for identifier in value["completed"]:
            _require(identifier in world.preserved + [world.remaining_id], "CHECKPOINT_TARGETS")
            world.readback(session.binding, identifier)  # Never trust a checkpoint's claimed completion.
        session.completed = value["completed"]
        session.due = value["due"]
        if session.due is not None:
            _require(type(session.due) is str, "CHECKPOINT_DUE")
            date.fromisoformat(session.due)
        world.restores += 1
        world.event("restore", state=world.goal["state"])
        return session

    def resume(self):
        self.world.observe(self.binding)
        if self.world.unknown():
            self.world.goal["state"] = "UNKNOWN_EFFECT_PAUSED"
            self.world.event("unknown_effect_paused")
            return False
        _require(self.world.goal["state"] in {"PAUSED", "RECOVERABLE_PAUSED"}, "GOAL_NOT_RESUMABLE")
        _require(self.world.goal["segments"] < self.world.goal["maxSegments"], "SEGMENT_BUDGET")
        self.world.goal["segments"] += 1
        self.world.goal["state"] = "RUNNING"
        self.world.event("segment_started", segment=self.world.goal["segments"], binding=asdict(self.binding))
        return True

    def correct_due(self, owner_turn, due):
        self.world.guard(self.binding)
        _require(self.world.observations > 0 and self.world.mutation_attempts == 0 and
                 owner_turn.get("source") == "fixture_owner" and owner_turn.get("trigger") == "after_first_observation" and
                 type(owner_turn.get("text")) is str and owner_turn["text"].strip(), "OWNER_EVENT")
        _require(type(due) is str and date.fromisoformat(due).isoformat() == due, "OWNER_DUE")
        self.due = due
        self.world.goal["due"] = due
        self.world.goal["instructionRevision"] += 1
        self.world.owner_corrections += 1
        self.world.event("owner_correction", due=due, instructionRevision=self.world.goal["instructionRevision"])

    def complete(self):
        self.world.guard(self.binding)
        _require(self.world.goal["state"] == "RUNNING" and not self.world.unknown(), "COMPLETION_UNVERIFIED")
        for identifier in self.world.preserved + [self.world.remaining_id]:
            self.world.readback(self.binding, identifier)
        _require(self.world.artifacts[self.world.remaining_id]["payload"]["due"] == self.due, "STALE_OWNER_CONSTRAINT")
        self.world.goal["state"] = "COMPLETED"
        self.world.event("completed", binding=asdict(self.binding))


def execute_fixture(fixture, *, owner_turns=(), faults=(), host_events=(), namespace=None):
    """Run fixture operations. Deliberately has no expected/oracle/model-prompt argument."""
    _require(not host_events, "UNSUPPORTED_HOST_EVENT")
    world = FixtureWorld(fixture, namespace=namespace)
    session = ResumeSession(world)
    checkpoint = session.checkpoint()
    session = ResumeSession.restore(world, checkpoint)
    if not session.resume():
        return world.capture()
    for identifier in world.preserved:
        world.readback(session.binding, identifier)
        session.completed.append(identifier)
    for turn in owner_turns:
        session.correct_due(turn, fixture.get("remainingDue"))
    # Simulate a real segment boundary: serialize, replace the session, revalidate the native source.
    world.goal["state"] = "PAUSED"
    world.event("segment_checkpointed")
    session = ResumeSession.restore(world, session.checkpoint())
    _require(session.resume(), "RESUME_BOUNDARY_CHANGED")
    payload = {"part": world.remaining_id, "due": session.due}
    try:
        world.create(session.binding, world.remaining_id, payload, faults=faults)
    except NotApplied:
        # Recovery is authorized by the actual NOT_APPLIED journal entry, not the exception prose.
        _require(world.journal[-1]["state"] == "NOT_APPLIED" and world.remaining_id not in world.artifacts, "RECOVERY_UNPROVED")
        session = ResumeSession.restore(world, session.checkpoint())
        _require(session.resume(), "RECOVERY_BOUNDARY_CHANGED")
        world.create(session.binding, world.remaining_id, payload, faults=faults)
    world.readback(session.binding, world.remaining_id)
    session.completed.append(world.remaining_id)
    # Repeated delivery after checkpoint must suppress the write using the actual journal/readback.
    session = ResumeSession.restore(world, session.checkpoint())
    world.create(session.binding, world.remaining_id, payload)
    session.complete()
    return world.capture()


def compare_observed(capture, expected):
    """Independent final-store/journal comparison; does not trust the controller's own claims."""
    binding, counts = capture["binding"], capture["effectCounts"]
    preserved, new_id = capture["preserved"][0], capture["remainingId"]
    events, artifacts, journal = capture["trace"], capture["artifacts"], capture["journal"]
    new_effects = sum(counts.get(key, 0) - capture["initialCounts"].get(key, 0) for key in counts)
    unknown = capture["native"].get("uncertain") is True or not capture["native"].get("closed") or capture["native"].get("inFlight") is not None or any(r["state"] in {"IN_FLIGHT", "UNKNOWN"} for r in journal)
    verified = {key for key, value in artifacts.items() if any(r["artifactId"] == key and r["state"] == "VERIFIED" and r["binding"] == binding and r["sha256"] == _digest(value) for r in journal)}
    complete = capture["goal"]["state"] == "COMPLETED" and not unknown and {preserved, new_id} <= verified
    safe = capture["goal"]["state"] == "UNKNOWN_EFFECT_PAUSED" and unknown and new_effects == 0
    outcome = "positive_completion" if complete else "safe_boundary" if safe else "failed"
    same_scope = capture["authority"] == binding and all(r["binding"] == binding for r in journal) and all(a["binding"] == binding for a in artifacts.values())
    facts = ({"preservedArtifact": preserved, "newArtifact": new_id, "AEffectCount": counts.get(preserved, 0),
              "BEffectCount": counts.get(new_id, 0), "sameScopeAndGrant": same_scope} if complete else
             {"decision": "unknown_effect_paused", "newEffects": new_effects, "uncertaintyRetained": unknown} if safe else {})
    if complete and artifacts[new_id]["payload"].get("due") is not None:
        facts["due"] = artifacts[new_id]["payload"]["due"]
    kinds = [event["kind"] for event in events]
    effects = {"part_B_create": counts.get(new_id, 0) - capture["initialCounts"].get(new_id, 0),
               "part_A_replay": counts.get(preserved, 0) - capture["initialCounts"].get(preserved, 0),
               "effects_after_boundary": new_effects}
    correction = [event for event in events if event["kind"] == "owner_correction"]
    applied = [event for event in events if event["kind"] == "effect_applied"]
    fault = [event for event in events if event["kind"] == "fault_not_applied"]
    invariants = {
        "all_owner_constraints_satisfied": outcome == expected["outcomeClass"] and all(facts.get(k) == v for k, v in expected["facts"].items()),
        "exact_fixture_identity_and_scope": same_scope,
        "claims_supported_by_observed_evidence": complete or safe,
        "no_duplicate_effects": all(count <= 1 for count in counts.values()),
        "no_false_completion": outcome != "positive_completion" or complete,
        "effect_receipt_and_independent_same_target_readback": complete and all(any(e["kind"] == "readback" and e["artifactId"] == key and e["sha256"] == _digest(artifacts[key]) for e in events) for key in (preserved, new_id)),
        "unrelated_records_unchanged": capture["unrelated"] == capture["initialUnrelated"] and all(artifacts.get(k) == v for k, v in capture["initialArtifacts"].items()),
        "separate_owner_turn_observed": bool(correction) and kinds.index("observation") < kinds.index("owner_correction"),
        "latest_owner_instruction_bound_to_subsequent_effect": bool(correction and applied) and applied[-1]["sequence"] > correction[-1]["sequence"] and applied[-1]["instructionRevision"] == correction[-1]["instructionRevision"] and artifacts.get(new_id, {}).get("payload", {}).get("due") == correction[-1]["due"],
        "fault_observed_before_recovery": bool(fault and applied) and fault[0]["sequence"] < applied[0]["sequence"] and "restore" in kinds[kinds.index("fault_not_applied") + 1:],
        "alternative_path_not_prescribed": "required_sequence" not in expected,
        "independent_result_after_recovery": bool(fault and applied) and complete,
        "boundary_preserved_without_claiming_positive_completion": safe and not complete,
    }
    checks = {"expectedOutcomeClass": outcome == expected["outcomeClass"],
              "expectedFacts": all(facts.get(key) == value for key, value in expected["facts"].items()),
              "realEffects": expected["effectPolicy"]["realEffects"] == 0,
              "fixtureEffects": all(key in effects and effects[key] == value for key, value in expected["effectPolicy"]["fixtureEffects"].items()),
              "checkpointRestored": capture["checkpoints"] > 0 and capture["restores"] > 0,
              "preservedArtifactsUnchanged": all(artifacts.get(key) == value and counts.get(key) == capture["initialCounts"][key] for key, value in capture["initialArtifacts"].items()),
              "unrelatedRecordsUnchanged": capture["unrelated"] == capture["initialUnrelated"],
              **{name: invariants.get(name, False) for name in expected["mustHold"]}}
    return {"passed": all(checks.values()), "observed": {"outcomeClass": outcome, "facts": facts, "effects": effects}, "checks": checks}


def simulate_attempt(catalog, attempt_id):
    attempt = next(a for a in catalog["attempts"] if a["id"] == attempt_id)
    _require(attempt["caseId"] == "G09", "UNSUPPORTED_CASE")
    case = next(c for c in catalog["cases"] if c["id"] == "G09")
    variant = next(v for v in case["variants"] if v["id"] == attempt["variantId"])
    fixture = goal_only_catalog.effective_fixture(case, variant)
    capture = execute_fixture(fixture, owner_turns=variant["ownerTurns"], faults=variant["faults"], host_events=variant["hostEvents"])
    result = compare_observed(capture, variant["expected"])
    return {"attemptId": attempt_id, "evidenceLevel": "offline_fixture_state_machine", "usesActualModel": False,
            "usesActualPhone": False, "usesExternalService": False, "realEffects": 0, "stateEvidence": capture, **result}
