"""Deterministic in-memory reference simulation, not a model/Android/service evaluation.

Known fixture families use typed task data. This module does not classify ordinary
natural-language requests and is never imported by production request routing.
Expected answers are supplied only to the independent assessor after execution.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_DOWN
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import time

import goal_only_catalog as catalog_api

SUPPORTED = {"purchase_constraints", "buffered_meetings", "appointment_correction", "notes_synthesis", "owner_correction_cancel", "points", "account_closure"}


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class FixtureFault(RuntimeError):
    pass


class StaleVersion(FixtureFault):
    pass


class FixtureBoundary(RuntimeError):
    pass


@dataclass(frozen=True)
class TaskInput:
    family: str
    prompt: str
    seed: dict
    overrides: dict
    owner_turns: list
    host_events: list
    faults: list
    approval_policy: dict
    defer_overrides: bool

    @classmethod
    def capture(cls, case, variant):
        # Deliberately exclude expected, oracle and requested outcome class.
        return cls(case["family"], variant["prompt"], deepcopy(case["fixtures"]), deepcopy(variant["fixtureOverrides"]),
                   deepcopy(variant["ownerTurns"]), deepcopy(variant["hostEvents"]), deepcopy(variant["faults"]),
                   deepcopy(case["fixtureApproval"]), bool(variant["ownerTurns"] and variant["kind"] == "changed_constraint_or_correction"))


@dataclass(frozen=True)
class Grant:
    namespace: str
    target: str
    operation: str
    expected_version: int
    payload_digest: str
    serial: int
    issuer: object = field(repr=False)


class World:
    """Stateful fixture executor. Reads, grants, rejected writes and readbacks are distinct events."""
    def __init__(self, task: TaskInput, attempt_id: str):
        self.task = task
        self.namespace = f"simulation:{attempt_id}"
        self.fixture = deepcopy(task.seed)
        if not task.defer_overrides:
            self.fixture.update(deepcopy(task.overrides))
        self.events = []
        self.fired_turns = set()
        self.fired_host_events = set()
        self.fired_faults = set()
        self.observed_versions = {}
        self.used_grants = set()
        self.committed_payloads = set()
        self.cancelled = False
        self.permission = True
        self.unknown = self.fixture.get("redemptionState") == "unknown_after_dispatch"
        self.records = {}
        self.allowed = set()
        self.operation_ns = 0
        self._grant_serial = 0
        self._grant_issuer = object()
        self.issued_grants = {}
        for group in ("calendar", "notes", "accounts"):
            for record in self.fixture.get(group, []):
                self._seed(group, record)
        for key in ("target", "keep"):
            if key in self.fixture:
                self._seed("reminders", self.fixture[key])
        self.initial = deepcopy(self.records)
        self.initial_point_lots = deepcopy(self.fixture.get("pointLots"))
        self.emit("environment_created", evidenceLevel="deterministic_in_memory_simulation", realEffects=0)
        if self.unknown:
            self.emit("prior_uncertainty_observed", origin="fixture_redemption", retained=True)

    def _seed(self, group, record):
        value = deepcopy(record)
        value["_namespace"] = self.namespace
        value["version"] = value.get("version", 1)
        self.records[(group, value["id"])] = value

    def emit(self, kind, **fields):
        self.events.append(deepcopy({"sequence": len(self.events) + 1, "kind": kind, **fields}))

    def checkpoint(self, trigger):
        for index, turn in enumerate(self.task.owner_turns):
            if index not in self.fired_turns and turn["trigger"] == trigger:
                self.fired_turns.add(index)
                if self.task.defer_overrides:
                    self.fixture.update(deepcopy(self.task.overrides))
                self.emit("owner_turn", source=turn["source"], trigger=trigger, text=turn["text"])
        if self.fixture.get("ownerCancelAt") == trigger and not self.cancelled:
            self.cancelled = True
            self.emit("cancel_observed", trigger=trigger)
        if trigger == "after_first_observation" and self.fixture.get("sourcePermission") == "revoked_after_first_observation":
            self.permission = False
            self.emit("permission_revoked", source="fixture_host_control", trigger=trigger)
        for index, event in enumerate(self.task.host_events):
            if index not in self.fired_host_events and event["trigger"] == trigger:
                self.fired_host_events.add(index)
                if event["kind"] == "permission_revoked":
                    self.permission = False
                    self.emit("permission_revoked", source=event["source"], trigger=trigger)
                elif event["kind"] == "acknowledgement_lost_effect_unknown":
                    self.unknown = True
                    self.emit("uncertainty_observed", source=event["source"], trigger=trigger)

    def _fault(self, operation, *, key=None, read=False):
        for index, fault in enumerate(self.task.faults):
            if index in self.fired_faults:
                continue
            wanted = fault["semanticOperation"]
            if wanted != operation and not (read and wanted == "fixture_read"):
                continue
            self.fired_faults.add(index)
            self.emit("fault_observed", operation=operation, fault=fault["kind"], effectNotApplied=fault["effectNotApplied"])
            if not fault["effectNotApplied"]:
                self.unknown = True
                raise FixtureBoundary("unknown_effect")
            if "stale" in fault["kind"] and key is not None:
                record = self.records[key]
                record["version"] += 1
                record["_fixtureConcurrentMarker"] = "preserve-external-edit"
                self.emit("fixture_concurrent_edit", target=key[1], version=record["version"])
                raise StaleVersion("observed version rejected before effect")
            raise FixtureFault("read failed before effect")

    def _live(self):
        if self.cancelled:
            raise FixtureBoundary("cancelled")
        if not self.permission:
            raise FixtureBoundary("permission_revoked")
        if self.unknown:
            raise FixtureBoundary("unknown_effect")

    def observe(self, operation, *, group=None, target=None, mirror=False):
        before = time.perf_counter_ns()
        try:
            self._live()
            self._fault(operation, read=True)
            if group is None:
                value = deepcopy(self.fixture)
            elif target is None:
                value = [deepcopy(v) for (g, _), v in self.records.items() if g == group]
            else:
                value = deepcopy(self.records[(group, target)])
            for record in value if isinstance(value, list) else [value]:
                if isinstance(record, dict) and "_namespace" in record:
                    if record["_namespace"] != self.namespace:
                        raise FixtureBoundary("scope_boundary")
                    self.observed_versions[(group, record["id"])] = record["version"]
            self.emit("observation", operation=operation, channel="mirror_snapshot" if mirror else "primary_snapshot",
                      group=group, target=target, snapshot=value, snapshotDigest=digest(value))
            return value
        finally:
            self.operation_ns += time.perf_counter_ns() - before

    def observe_recover(self, operation, **kwargs):
        try:
            return self.observe(operation, **kwargs)
        except FixtureFault:
            # A separate authoritative snapshot adapter, not a synthetic successful first response.
            return self.observe(operation, mirror=True, **kwargs)

    def first_observation(self, operation, **kwargs):
        value = self.observe_recover(operation, **kwargs)
        self.checkpoint("after_first_observation")
        return value

    def grant(self, operation, group, target, changes):
        self._live()
        if (group, target) not in self.allowed:
            raise FixtureBoundary("wrong_target")
        policy = self.task.approval_policy
        if policy.get("realAuthority") is not False or policy.get("rememberGrant") is not False:
            raise FixtureBoundary("invalid_fixture_approval")
        version = self.observed_versions.get((group, target), 0)
        if operation not in {"note_create", "points_preview"} and version == 0:
            raise FixtureBoundary("unobserved_target")
        if operation == "account_close":
            approval = self.fixture.get("ownerApprovalAtExactPreview")
            if not approval or approval.get("targetId") != target or approval.get("scope") != "fixture_only" or approval.get("realAuthority") is not False:
                raise FixtureBoundary("no_bound_owner_approval")
            if approval.get("decision") == "decline":
                raise FixtureBoundary("owner_declined")
        self._grant_serial += 1
        grant = Grant(self.namespace, target, operation, version, digest(changes), self._grant_serial, self._grant_issuer)
        self.issued_grants[grant.serial] = grant
        self.emit("fixture_owner_grant", target=target, operation=operation, expectedVersion=version, payloadDigest=grant.payload_digest, serial=grant.serial, realAuthority=False)
        return grant

    def write(self, operation, group, target, changes, grant):
        before = time.perf_counter_ns()
        try:
            self._live()
            key = (group, target)
            expected = Grant(self.namespace, target, operation, self.observed_versions.get(key, 0), digest(changes), grant.serial, self._grant_issuer)
            if grant != expected or self.issued_grants.get(grant.serial) is not grant or grant.serial in self.used_grants or key not in self.allowed:
                raise FixtureBoundary("grant_mismatch_or_reuse")
            self._fault({"calendar_update": "calendar_mutation", "reminder_update": "reminder_mutation"}.get(operation, operation), key=key)
            current = self.records.get(key)
            if current is not None and current["version"] != grant.expected_version:
                raise StaleVersion("version changed")
            fingerprint = (operation, group, target, digest(changes))
            if fingerprint in self.committed_payloads:
                raise FixtureBoundary("duplicate_effect")
            old_digest = digest(current)
            value = deepcopy(current or {"id": target, "_namespace": self.namespace, "version": 0})
            value.update(deepcopy(changes)); value["version"] += 1
            self.records[key] = value
            self.used_grants.add(grant.serial); self.committed_payloads.add(fingerprint)
            self.emit("mutation", operation=operation, group=group, target=target, beforeDigest=old_digest,
                      afterDigest=digest(value), version=value["version"], grantSerial=grant.serial, payloadDigest=digest(changes))
            return deepcopy(value)
        finally:
            self.operation_ns += time.perf_counter_ns() - before

    def mutate_and_read(self, operation, group, target, changes):
        self.checkpoint("before_commit")
        for attempt in range(2):
            grant = self.grant(operation, group, target, changes)
            try:
                self.write(operation, group, target, changes, grant)
                # An independent operation reads the actual stored object after the mutation.
                return self.observe_recover("independent_readback", group=group, target=target)
            except StaleVersion:
                self.emit("rejected_before_effect", operation=operation, target=target)
                if attempt:
                    raise
                self.observe_recover("refresh_after_conflict", group=group, target=target)
        raise AssertionError("bounded retry")


def fixture_date(text, fallback):
    """Restricted fixture text grammar; not an app intent interpreter."""
    value = datetime.fromisoformat(fallback)
    date = re.search(r"(?:(\d{4})년\s*)?(\d{1,2})월\s*(\d{1,2})일", text)
    if date:
        value = value.replace(year=int(date[1] or value.year), month=int(date[2]), day=int(date[3]))
    clock = re.search(r"(오전|오후)?\s*(\d{1,2})(?::(\d{2})|시)", text)
    if clock:
        hour = int(clock[2]); minute = int(clock[3] or 0)
        if clock[1] == "오후" and hour < 12:
            hour += 12
        if clock[1] == "오전" and hour == 12:
            hour = 0
        value = value.replace(hour=hour, minute=minute, second=0, microsecond=0)
    elif not date:
        raise FixtureBoundary("unsupported_fixture_text")
    return value.isoformat()


def latest_owner_text(world):
    return [e["text"] for e in world.events if e["kind"] == "owner_turn"]


def solve_purchase(world):
    world.first_observation("offer_read")
    fixture = world.observe_recover("current_offer_snapshot")
    totals = {}; best = None
    for offer in fixture["offers"]:
        gross = fixture["quantity"] * offer["unitWon"]
        discount = int(Fraction(gross * offer["discountPercent"], 100))
        if offer["discountCapWon"] is not None:
            discount = min(discount, offer["discountCapWon"])
        total = gross - discount + offer["shippingWon"]
        totals[offer["id"]] = total
        if total <= fixture["budgetWon"] and offer["deliveryDays"] <= fixture["deliveryWithinDays"]:
            candidate = (total, offer["id"])
            best = candidate if best is None or candidate < best else best
    world.checkpoint("before_commit"); world._live()
    answer = {"totalsWon": totals, "winner": best[1] if best else None}
    world.emit("analysis_produced", analysis="purchase_constraints", snapshot=answer)
    return "positive_completion", answer


def solve_schedule(world):
    world.first_observation("availability_read")
    f = world.observe_recover("current_availability_snapshot")
    chosen = []; occupied = [(datetime.fromisoformat(a), datetime.fromisoformat(b)) for a, b in f["occupied"]]
    duration = timedelta(minutes=f["durationMinutes"]); gap = timedelta(minutes=f["bufferMinutes"])
    for raw_start, raw_end in sorted(f["windows"]):
        cursor, end = datetime.fromisoformat(raw_start), datetime.fromisoformat(raw_end)
        while cursor + duration <= end and len(chosen) < f["meetingCount"]:
            conflicts = [(a, b) for a, b in occupied + chosen if not (cursor + duration + gap <= a or b + gap <= cursor)]
            if conflicts:
                cursor = max(b + gap for _, b in conflicts)
            else:
                chosen.append((cursor, cursor + duration)); cursor += duration + gap
    if len(chosen) != f["meetingCount"]:
        raise FixtureBoundary("no_feasible_schedule")
    world.checkpoint("before_commit"); world._live()
    answer = {"slots": [[a.isoformat(), b.isoformat()] for a, b in chosen]}
    world.emit("analysis_produced", analysis="buffered_meetings", snapshot=answer)
    return "positive_completion", answer


def solve_appointment(world):
    world.first_observation("messages_read")
    world._live()
    messages = sorted(world.fixture["messages"], key=lambda item: (item["at"], item["id"]))
    events = world.observe_recover("calendar_read", group="calendar")
    targets = [item for item in events if item["title"] in world.task.prompt]
    if len(targets) != 1:
        raise FixtureBoundary("ambiguous_event")
    target = targets[0]
    at = fixture_date(messages[-1]["text"], target["start"])
    for correction in latest_owner_text(world):
        at = fixture_date(correction, at)
    world.allowed.add(("calendar", target["id"]))
    world.mutate_and_read("calendar_update", "calendar", target["id"], {"start": at})
    return "positive_completion", {"targetId": target["id"], "start": at}


def solve_notes(world):
    notes = world.first_observation("notes_read", group="notes")
    rows = [{"item": row["item"], "owner": row["owner"], "due": row["due"]} for note in notes for row in note.get("items", []) if row["confirmed"]]
    for text in latest_owner_text(world):
        item = re.search(r"(\S+) 항목", text)
        if item:
            for row in rows:
                if row["item"] == item[1]:
                    row["due"] = fixture_date(text, row["due"] + "T00:00:00+09:00")[:10]
    rows.sort(key=lambda row: row["item"])
    content = "항목\t담당\t기한\n" + "\n".join("\t".join(row[key] for key in ("item", "owner", "due")) for row in rows)
    target = "sim-output-note"
    world.allowed.add(("notes", target))
    world.mutate_and_read("note_create", "notes", target, {"title": world.fixture["outputTitle"], "content": content})
    return "positive_completion", {"outputId": target}


def solve_reminder(world):
    target_id = world.fixture["target"]["id"]
    target = world.first_observation("reminders_read", group="reminders", target=target_id)
    world.checkpoint("before_commit"); world._live()
    at = fixture_date(world.task.prompt, target["at"])
    for correction in latest_owner_text(world):
        at = fixture_date(correction, at)
    world.allowed.add(("reminders", target_id))
    world.mutate_and_read("reminder_update", "reminders", target_id, {"at": at})
    return "positive_completion", {"targetId": target_id, "at": at}


def solve_points(world):
    if world.unknown:
        raise FixtureBoundary("unknown_redemption_paused")
    world.first_observation("points_read")
    f = world.observe_recover("current_points_snapshot")
    remaining = min(sum(p["amount"] for p in f["pointLots"]), f["orderWon"] * f["maxUsePercent"] // 100)
    total = remaining; used = {}
    for lot in sorted(f["pointLots"], key=lambda p: (p["expiresOn"], p["id"])):
        used[lot["id"]] = min(remaining, lot["amount"]); remaining -= used[lot["id"]]
    world.checkpoint("before_commit"); world._live()
    plan = {"usePoints": total, "cashWon": f["orderWon"] - total, "lotsUsed": used}
    world.allowed.add(("previews", "points-preview"))
    world.mutate_and_read("points_preview", "previews", "points-preview", plan)
    return "prepared_handoff", plan


def solve_closure(world):
    world.first_observation("account_preview", group="accounts")
    target_id = world.fixture["targetId"]
    target = world.observe_recover("account_identity", group="accounts", target=target_id)
    if target["balanceWon"] != 0 or target["subscriptionActive"]:
        raise FixtureBoundary("unresolved_closure_consequence")
    world.allowed.add(("accounts", target_id))
    world.emit("consequence_preview", target=target_id, impact=world.fixture["impact"])
    world.checkpoint("at_approval_preview")
    world.mutate_and_read("account_close", "accounts", target_id, {"status": "closed", "sessionsRevoked": True})
    return "positive_completion", {"closedAccountId": target_id}


SOLVERS = {"purchase_constraints": solve_purchase, "buffered_meetings": solve_schedule,
           "appointment_correction": solve_appointment, "notes_synthesis": solve_notes,
           "owner_correction_cancel": solve_reminder, "points": solve_points, "account_closure": solve_closure}


def _records(records):
    groups = {}
    for (group, target), record in records.items():
        groups.setdefault(group, {})[target] = deepcopy(record)
    return groups


def execute(task: TaskInput, attempt_id: str):
    world = World(task, attempt_id)
    try:
        actual_outcome, answer = SOLVERS[task.family](world)
    except FixtureBoundary as error:
        actual_outcome, answer = "safe_boundary", {"decision": str(error)}
        world.emit("boundary", decision=str(error))
    except Exception as error:
        actual_outcome, answer = "failed", {"errorCode": type(error).__name__, "detail": str(error)}
        world.emit("execution_failed", errorCode=type(error).__name__)
    return {"namespace": world.namespace, "actualOutcome": actual_outcome, "answer": answer,
            "events": deepcopy(world.events), "initialRecords": _records(world.initial), "finalRecords": _records(world.records),
            "finalFixture": deepcopy(world.fixture), "initialPointLots": world.initial_point_lots,
            "cancelled": world.cancelled, "permission": world.permission, "unknown": world.unknown,
            "modelCalls": 0, "phoneCalls": 0, "networkCalls": 0, "operationNs": world.operation_ns}


def _not_run(attempt, reason):
    return {"attemptId": attempt["id"], "caseId": attempt["caseId"], "variantId": attempt["variantId"], "status": "not_run",
            "reason": reason, "actualOutcome": None, "evidenceLevel": "none", "usesActualModel": False, "usesActualPhone": False,
            "usesExternalService": False, "execution": None, "assessment": None}


def run_attempt(catalog, attempt_id):
    attempt = next(a for a in catalog["attempts"] if a["id"] == attempt_id)
    case = next(c for c in catalog["cases"] if c["id"] == attempt["caseId"])
    variant = next(v for v in case["variants"] if v["id"] == attempt["variantId"])
    if case["family"] == "goal_resume":
        import importlib.util
        if importlib.util.find_spec("goal_resume_simulation") is None:
            return _not_run(attempt, "goal_resume_fixture_adapter_not_implemented")
        from goal_resume_simulation import simulate_attempt
        started = time.perf_counter_ns()
        detail = simulate_attempt(catalog, attempt_id)
        if not all(detail.get(name) is False for name in ("usesActualModel", "usesActualPhone", "usesExternalService")):
            raise ValueError("G09 adapter cannot elevate fixture evidence")
        return {"attemptId": attempt_id, "caseId": case["id"], "variantId": variant["id"],
                "status": "passed" if detail["passed"] else "failed", "actualOutcome": detail["observed"]["outcomeClass"],
                "evidenceLevel": detail["evidenceLevel"], "usesActualModel": False, "usesActualPhone": False, "usesExternalService": False,
                "execution": detail, "assessment": detail["checks"], "metrics": {"elapsedMs": (time.perf_counter_ns() - started) / 1e6, "modelCalls": 0}}
    if case["family"] not in SUPPORTED:
        return _not_run(attempt, "fixture_adapter_not_implemented")
    if case["family"] == "notes_synthesis" and "foreignScopeDecoy" in variant["fixtureOverrides"]:
        return _not_run(attempt, "boundary_fixture_has_sufficient_in_scope_data; stop_vs_continue_contract_needs_resolution")
    task = TaskInput.capture(case, variant)
    started = time.perf_counter_ns()
    execution = execute(task, attempt_id)
    executed_at = time.perf_counter_ns()
    from goal_only_simulation_oracle import assess
    assessment = assess(case, variant, execution)
    finished = time.perf_counter_ns()
    return {"attemptId": attempt_id, "caseId": case["id"], "variantId": variant["id"],
            "status": "passed" if assessment["passed"] else "failed", "actualOutcome": execution["actualOutcome"],
            "evidenceLevel": "deterministic_in_memory_simulation", "executionKind": "typed_fixture_reference_policy_not_model_interpretation",
            "usesActualModel": False, "usesActualPhone": False, "usesExternalService": False,
            "inputSha256": digest(task.__dict__), "execution": execution, "assessment": assessment,
            "recoveredAfterFault": assessment["passed"] and any(e["kind"] == "fault_observed" for e in execution["events"]),
            "metrics": {"queueMs": 0, "inferenceMs": None, "toolMs": execution["operationNs"] / 1e6,
                "verificationMs": (finished - executed_at) / 1e6, "elapsedMs": (finished - started) / 1e6,
                "modelCalls": 0, "inputTokens": None, "outputTokens": None, "totalTokens": None,
                "retryCount": sum(e["kind"] == "fault_observed" for e in execution["events"])}}


def run_catalog(catalog, selected=None):
    catalog_api.validate(catalog)
    selected = set(selected or [])
    valid = {a["id"] for a in catalog["attempts"]} | {c["id"] for c in catalog["cases"]}
    if selected - valid:
        raise ValueError("unknown attempt/family selection")
    results = [run_attempt(catalog, a["id"]) if not selected or a["id"] in selected or a["caseId"] in selected else _not_run(a, "not_selected") for a in catalog["attempts"]]
    status = Counter(r["status"] for r in results)
    outcomes = Counter(r["actualOutcome"] for r in results if r["status"] == "passed")
    return {"executionKind": "deterministic_offline_simulation", "sourceBinding": {
                "catalogDataSha256": digest(catalog), "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "oracleSha256": hashlib.sha256(Path(__file__).with_name("goal_only_simulation_oracle.py").read_bytes()).hexdigest(),
                "installedApkDigest": None, "workerVersion": None},
            "summary": {"plannedAttempts": len(results), "executedSimulations": status["passed"] + status["failed"],
                "passedSimulations": status["passed"], "failedSimulations": status["failed"], "notRun": status["not_run"],
                "passedOutcomeClasses": dict(sorted(outcomes.items())), "modelCalls": 0, "phoneCalls": 0, "externalServiceCalls": 0,
                "countsAsModelOrDeviceQualification": False}, "attempts": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=catalog_api.CATALOG)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--out", type=Path, help="New ignored .local directory; existing output is never overwritten")
    args = parser.parse_args()
    result = run_catalog(catalog_api.load(args.catalog), args.only)
    if args.out:
        private = Path(__file__).resolve().parents[2] / ".local"
        target = args.out.resolve()
        if not target.is_relative_to(private.resolve()):
            parser.error("--out must be a new directory under repository .local")
        target.mkdir(parents=True, exist_ok=False)
        (target / "simulation-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False, sort_keys=True))
    if result["summary"]["failedSimulations"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
