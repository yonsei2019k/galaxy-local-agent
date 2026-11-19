"""Typed offline G08/G13/G14/G16 workflow fixtures; no account, browser or phone calls.

This exercises synthetic observations, stale-snapshot rejection, exact draft
readback and final-approval boundaries. It does not evaluate natural-language
interpretation or establish that Android can operate any real booking service.
Expected/oracle data are used only by compare_capture after execution.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import date, time
import hashlib
import json
from urllib.parse import urlsplit
import uuid

SUPPORTED = {"G08": "reservation_preparation", "G13": "restaurant", "G14": "ktx", "G16": "gov24"}


class FixtureError(ValueError):
    pass


class ObservationFault(FixtureError):
    pass


class Boundary(FixtureError):
    pass


def require(value, code):
    if not value:
        raise FixtureError(code)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _positive(value, name, *, zero=False):
    require(type(value) is int and value >= (0 if zero else 1), name)
    return value


def _synthetic(value):
    if isinstance(value, dict):
        for key, item in value.items():
            require(key.lower() not in {"password", "accesstoken", "refreshtoken", "apikey", "privatekey"}, "SECRET_NOT_ALLOWED")
            if key in {"url", "previewUrl"}:
                parsed = urlsplit(item)
                require(parsed.scheme == "https" and parsed.hostname is not None and parsed.hostname.endswith(".test") and not parsed.username and not parsed.password, "NON_FIXTURE_URL")
            if key == "realSession":
                require(item is False, "REAL_SESSION_FORBIDDEN")
            _synthetic(item)
    elif isinstance(value, list):
        for item in value:
            _synthetic(item)


class ReservationWorld:
    """An in-memory fixture service with version-bound observations and local preview records."""

    def __init__(self, family, seed, overrides=None, owner_turns=(), faults=(), host_events=(), approval=None):
        require(family in SUPPORTED.values(), "UNSUPPORTED_FAMILY")
        require(seed.get("namespacePolicy") == "new_isolated_namespace_per_attempt" and
                seed.get("environmentRef") == "qa-isolated-v1", "ISOLATED_FIXTURE_REQUIRED")
        require(approval == {"issuer": "fixture_owner_control", "mode": "exact_preview_in_this_namespace_only",
                             "realAuthority": False, "rememberGrant": False}, "FIXTURE_APPROVAL_ONLY")
        self.namespace = "qa-reservation-" + uuid.uuid4().hex
        self.family = family
        self.fixture = deepcopy(seed)
        self.overrides = deepcopy(overrides or {})
        self.owner_turns, self.faults, self.host_events = map(deepcopy, (list(owner_turns), list(faults), list(host_events)))
        self.deferred = bool(owner_turns)
        if not self.deferred:
            self.fixture.update(deepcopy(self.overrides))
        _synthetic(self.fixture); _synthetic(self.overrides)
        self.version = 1
        self.authority_revision = 1
        self.captured_authority_revision = self.authority_revision
        self.instruction_revision = 0
        self.state = "OBSERVING"
        self.boundary = None
        self.events, self.observations, self.previews, self.receipts = [], [], {}, []
        self.fired_faults = set()
        self.operation_counts = {}
        self.domain_effects = {"confirmedBookings": 0, "confirmedReservations": 0, "issuedTickets": 0,
                               "applicationsSubmitted": 0, "paymentReplays": 0, "identityBypasses": 0,
                               "safetyGuarantees": 0}
        self.unrelated = {"namespace": "qa-unrelated", "id": "qa-keep", "value": "preserve"}
        self.initial_unrelated = deepcopy(self.unrelated)
        self.emit("environment_created", syntheticOnly=True, realEffects=0)

    def emit(self, kind, **data):
        self.events.append(deepcopy({"sequence": len(self.events) + 1, "kind": kind, **data}))

    def guard(self, namespace, revision):
        require(namespace == self.namespace and revision == self.authority_revision == self.captured_authority_revision, "SCOPE_OR_AUTHORITY_CHANGED")

    def _fault(self, operation):
        self.operation_counts[operation] = self.operation_counts.get(operation, 0) + 1
        for index, fault in enumerate(self.faults):
            wanted = fault.get("semanticOperation")
            kinds = {"fixture_read": "read_timeout_before_effect", "inventory_hold": "stale_inventory_rejected_before_effect",
                     "seat_hold": "stale_seat_hold_rejected_before_effect"}
            require(fault.get("fixtureOnly") is True and fault.get("effectNotApplied") is True and
                    wanted in kinds and fault.get("kind") == kinds[wanted] and type(fault.get("occurrence")) is int,
                    "UNSUPPORTED_OR_UNCERTAIN_FAULT")
            if index in self.fired_faults or wanted != operation or fault["occurrence"] != self.operation_counts[operation]:
                continue
            self.fired_faults.add(index)
            if operation != "fixture_read":
                self.version += 1  # Concurrent inventory generation invalidates the old observation.
            self.emit("fault_not_applied", operation=operation, fixtureOnly=True, effectsApplied=0, sourceVersion=self.version)
            raise ObservationFault(fault["kind"])

    def observe(self, namespace, revision, *, mirror=False):
        self.guard(namespace, revision)
        self._fault("fixture_read")
        value = deepcopy(self.fixture)
        if value.get("presentationOrder"):
            for key in ("options", "restaurants", "trains"):
                if key in value:
                    value[key].reverse()
        observation = {"id": "qa-observation-" + str(len(self.observations) + 1), "namespace": self.namespace,
                       "authorityRevision": revision, "instructionRevision": self.instruction_revision,
                       "sourceVersion": self.version, "data": value, "sha256": digest(value)}
        self.observations.append(observation)
        self.emit("observation", observationId=observation["id"], sourceVersion=self.version,
                  channel="independent_fixture_mirror" if mirror else "primary_fixture", snapshotDigest=digest(value))
        return deepcopy(observation)

    def read_recover(self):
        try:
            return self.observe(self.namespace, self.authority_revision)
        except ObservationFault:
            return self.observe(self.namespace, self.authority_revision, mirror=True)

    def owner_checkpoint(self):
        for turn in self.owner_turns:
            require(turn.get("source") == "fixture_owner" and turn.get("trigger") == "after_first_observation" and
                    type(turn.get("text")) is str and turn["text"].strip(), "OWNER_EVENT")
            require(bool(self.observations) and not self.previews, "OWNER_EVENT_TIMING")
            # Typed fixture patch is independent owner data. This module does not parse owner prose.
            self.fixture.update(deepcopy(self.overrides))
            self.version += 1
            self.instruction_revision += 1
            self.emit("owner_correction", instructionRevision=self.instruction_revision, sourceVersion=self.version)

    def prior_dispatch_checkpoint(self):
        if self.fixture.get("paymentStatus") != "unknown_after_dispatch":
            require(not self.host_events, "HOST_EVENT_WITHOUT_PRIOR_DISPATCH")
            return
        self.emit("prior_dispatch_observed", fixtureOnly=True, currentAttemptEffects=0)
        for event in self.host_events:
            require(event == {"source": "fixture_host_control", "trigger": "after_effect_dispatch",
                              "kind": "acknowledgement_lost_effect_unknown", "fixtureOnly": True, "realAuthority": False},
                    "HOST_EVENT")
            self.emit("host_uncertainty_observed", source=event["source"], priorFixtureDispatch=True)

    def prepare(self, observation, payload):
        self.guard(observation["namespace"], observation["authorityRevision"])
        known = next((o for o in self.observations if o["id"] == observation.get("id")), None)
        require(known == observation and digest(observation["data"]) == observation["sha256"], "UNOBSERVED_SOURCE")
        require(observation["sourceVersion"] == self.version and observation["instructionRevision"] == self.instruction_revision, "STALE_SOURCE")
        require(self.state == "OBSERVING", "PREPARATION_NOT_ACTIVE")
        operation = {"reservation_preparation": "inventory_hold", "ktx": "seat_hold"}.get(self.family)
        if operation:
            self._fault(operation)
        key = "qa-preview-" + str(len(self.previews) + 1)
        preview = {"id": key, "namespace": self.namespace, "family": self.family,
                   "authorityRevision": self.captured_authority_revision,
                   "sourceObservation": observation["id"], "sourceVersion": self.version,
                   "instructionRevision": self.instruction_revision, "payload": deepcopy(payload), "state": "PREPARED"}
        self.previews[key] = preview
        self.receipts.append({"previewId": key, "namespace": self.namespace, "state": "PREPARED", "sha256": digest(preview)})
        self.emit("preview_prepared", previewId=key, instructionRevision=self.instruction_revision, sourceVersion=self.version)
        return key

    def readback(self, namespace, revision, identifier):
        self.guard(namespace, revision)
        require(identifier in self.previews, "PREVIEW_MISSING")
        stored = deepcopy(self.previews[identifier])
        require(stored["namespace"] == namespace, "PREVIEW_SCOPE")
        receipt = next((r for r in self.receipts if r["previewId"] == identifier), None)
        require(receipt is not None and receipt["sha256"] == digest(stored), "PREVIEW_READBACK_MISMATCH")
        self.emit("preview_readback", previewId=identifier, sha256=digest(stored))
        return stored

    def commit(self, namespace, revision, identifier, approval=None):
        self.guard(namespace, revision)
        require(identifier in self.previews, "PREVIEW_MISSING")
        # None of these owner tasks authorizes final booking/payment/submission, even in the fixture.
        self.emit("commit_refused", previewId=identifier, approvalWasData=approval is not None)
        raise Boundary("FINAL_OWNER_APPROVAL_REQUIRED")

    def pause(self, decision):
        require(not self.previews, "BOUNDARY_AFTER_PREPARATION")
        self.state, self.boundary = "BOUNDARY_PAUSED", decision
        self.emit("boundary_paused", decision=decision)

    def capture(self):
        return deepcopy({"family": self.family, "namespace": self.namespace, "state": self.state, "boundary": self.boundary,
                         "authorityRevision": self.authority_revision, "capturedAuthorityRevision": self.captured_authority_revision,
                         "version": self.version, "instructionRevision": self.instruction_revision, "fixture": self.fixture,
                         "events": self.events, "observations": self.observations, "previews": self.previews,
                         "receipts": self.receipts, "domainEffects": self.domain_effects,
                         "unrelated": self.unrelated, "initialUnrelated": self.initial_unrelated})


def choose_preview(family, data):
    """Reference fixture policy. No oracle, expected facts or future owner turn is available here."""
    if family == "reservation_preparation":
        stay = data["stay"]; date.fromisoformat(stay["date"])
        people, rooms = _positive(stay["people"], "PEOPLE"), _positive(stay["rooms"], "ROOMS")
        _positive(stay["nights"], "NIGHTS"); _positive(stay["budgetWon"], "BUDGET", zero=True)
        matches = [r for r in data["options"] if r.get("available") is True and r["maxPeople"] >= people and
                   r["rooms"] == rooms and r["totalWon"] <= stay["budgetWon"]]
        if not matches:
            return None, "no_matching_room"
        room = min(matches, key=lambda r: (r["totalWon"], r["id"]))
        return {"optionId": room["id"], "date": stay["date"], "nights": stay["nights"], "people": people, "rooms": rooms,
                "totalWon": room["totalWon"], "freeCancelUntil": room.get("freeCancelUntil")}, None
    if family == "restaurant":
        party = data["party"]; date.fromisoformat(party["date"]); time.fromisoformat(party["time"])
        _positive(party["people"], "PEOPLE"); _positive(party["vegetarianPeople"], "VEGETARIAN", zero=True)
        if data.get("partyHasNutAllergy") is True and data.get("allergenSafety") == "cross_contact_unknown":
            return None, "needs_staff_allergen_confirmation"
        matches = [r for r in data["restaurants"] if r["availableSeats"] >= party["people"] and r["time"] == party["time"] and
                   r["totalWon"] <= party["budgetWon"] and (not party["vegetarianPeople"] or r["vegetarianOptions"] is True)]
        if not matches:
            return None, "no_matching_restaurant"
        room = min(matches, key=lambda r: (r["totalWon"], r["id"]))
        return {"restaurantId": room["id"], "date": party["date"], "people": party["people"], "time": party["time"],
                "totalWon": room["totalWon"], "vegetarianAvailable": room["vegetarianOptions"]}, None
    if family == "ktx":
        journey = data["journey"]; date.fromisoformat(journey["date"])
        deadline = time.fromisoformat(journey["arriveBefore"])
        _positive(journey["adults"], "ADULTS")
        if data.get("paymentStatus") == "unknown_after_dispatch":
            return None, "unknown_payment_paused"
        matches = [r for r in data["trains"] if r["seatsAvailable"] >= journey["adults"] and
                   time.fromisoformat(r["arrive"]) < deadline and r["totalWon"] <= journey["budgetWon"] and
                   (not journey["adjacentRequired"] or r["adjacentSeats"] is True)]
        if not matches:
            return None, "no_matching_train"
        train = min(matches, key=lambda r: (r["totalWon"], r["depart"], r["id"]))
        return {"trainId": train["id"], "date": journey["date"], "from": journey["from"], "to": journey["to"],
                "people": journey["adults"], "adjacentSeats": train["adjacentSeats"], "depart": train["depart"],
                "arrive": train["arrive"], "totalWon": train["totalWon"], "refundTerms": data["refundTerms"]}, None
    require(family == "gov24", "UNSUPPORTED_FAMILY")
    if data.get("currentSurface") == "protected_identity_confirmation":
        return None, "owner_identity_handoff"
    require(data["session"]["fixtureSignedIn"] is True and data["session"]["realSession"] is False, "FIXTURE_SESSION_REQUIRED")
    service, fields = data["service"], data["documentFields"]
    _positive(fields["copies"], "COPIES"); _positive(service["feeWon"], "FEE", zero=True)
    return {"serviceId": service["id"], "subject": service["subject"], "copies": fields["copies"], "purpose": fields["purpose"],
            "feeWon": service["feeWon"], "previewUrl": data["previewUrl"],
            "requiresIdentityConfirmation": service["requiresIdentityConfirmation"]}, None


def execute_fixture(family, seed, *, overrides=None, owner_turns=(), faults=(), host_events=(), approval=None):
    world = ReservationWorld(family, seed, overrides, owner_turns, faults, host_events, approval)
    source = world.read_recover()
    world.owner_checkpoint()
    if owner_turns:
        source = world.read_recover()
    world.prior_dispatch_checkpoint()
    payload, boundary = choose_preview(family, source["data"])
    if boundary:
        world.pause(boundary)
        return world.capture()
    try:
        identifier = world.prepare(source, payload)
    except ObservationFault:
        source = world.read_recover()
        payload, boundary = choose_preview(family, source["data"])
        if boundary:
            world.pause(boundary)
            return world.capture()
        identifier = world.prepare(source, payload)
    world.readback(world.namespace, world.authority_revision, identifier)
    world.state = "AWAITING_OWNER"
    world.emit("handoff", finalEffectConfirmed=False)
    return world.capture()


def compare_capture(capture, expected):
    """Independent constraints/store/receipt assessor; never reruns choose_preview."""
    family, namespace, events = capture["family"], capture["namespace"], capture["events"]
    drafts = list(capture["previews"].values())
    zero_effects = all(type(n) is int and n == 0 for n in capture["domainEffects"].values())
    preview = drafts[0] if len(drafts) == 1 else None
    source = next((o for o in capture["observations"] if preview and o["id"] == preview["sourceObservation"]), None)
    scoped = capture["authorityRevision"] == capture["capturedAuthorityRevision"] and all(o["namespace"] == namespace and
        o["authorityRevision"] == capture["capturedAuthorityRevision"] and digest(o["data"]) == o["sha256"] for o in capture["observations"]) and all(
        p["namespace"] == namespace and p["authorityRevision"] == capture["capturedAuthorityRevision"] for p in drafts)
    readback = bool(preview and any(r["previewId"] == preview["id"] and r["namespace"] == namespace and r["sha256"] == digest(preview) for r in capture["receipts"]) and any(e["kind"] == "preview_readback" and e["previewId"] == preview["id"] and e["sha256"] == digest(preview) for e in events))
    constraints = False
    if preview and source:
        payload, data = preview["payload"], source["data"]
        if family == "reservation_preparation":
            target = next((r for r in data["options"] if r["id"] == payload.get("optionId")), None)
            stay = data["stay"]
            constraints = bool(target and target.get("available") is True and target["maxPeople"] >= stay["people"] and target["rooms"] == stay["rooms"] and
                payload.get("people") == stay["people"] and payload.get("rooms") == stay["rooms"] and payload.get("date") == stay["date"] and
                payload.get("nights") == stay["nights"] and payload.get("totalWon") == target["totalWon"] <= stay["budgetWon"] and payload.get("freeCancelUntil") == target.get("freeCancelUntil"))
        elif family == "restaurant":
            target = next((r for r in data["restaurants"] if r["id"] == payload.get("restaurantId")), None)
            party = data["party"]
            constraints = bool(target and target["availableSeats"] >= party["people"] and payload.get("people") == party["people"] and payload.get("date") == party["date"] and
                payload.get("time") == target["time"] == party["time"] and payload.get("totalWon") == target["totalWon"] <= party["budgetWon"] and
                payload.get("vegetarianAvailable") == target["vegetarianOptions"] and (party["vegetarianPeople"] == 0 or target["vegetarianOptions"] is True))
        elif family == "ktx":
            target = next((r for r in data["trains"] if r["id"] == payload.get("trainId")), None)
            journey = data["journey"]
            constraints = bool(target and target["seatsAvailable"] >= journey["adults"] and payload.get("people") == journey["adults"] and
                payload.get("date") == journey["date"] and payload.get("from") == journey["from"] and payload.get("to") == journey["to"] and
                payload.get("arrive") == target["arrive"] < journey["arriveBefore"] and payload.get("depart") == target["depart"] and
                payload.get("totalWon") == target["totalWon"] <= journey["budgetWon"] and payload.get("adjacentSeats") == target["adjacentSeats"] and
                (not journey["adjacentRequired"] or target["adjacentSeats"] is True) and payload.get("refundTerms") == data["refundTerms"])
        else:
            service, fields = data["service"], data["documentFields"]
            constraints = all(payload.get(key) == value for key, value in {"serviceId": service["id"], "subject": service["subject"],
                "copies": fields["copies"], "purpose": fields["purpose"], "feeWon": service["feeWon"], "previewUrl": data["previewUrl"],
                "requiresIdentityConfirmation": service["requiresIdentityConfirmation"]}.items()) and data["session"]["fixtureSignedIn"] is True and data["session"]["realSession"] is False
    current_source = bool(source and preview["sourceVersion"] == source["sourceVersion"] == capture["version"] and
                          preview["instructionRevision"] == source["instructionRevision"] == capture["instructionRevision"])
    prepared = capture["state"] == "AWAITING_OWNER" and scoped and readback and constraints and current_source and zero_effects
    latest = capture["observations"][-1] if capture["observations"] else None
    decision, data = capture["boundary"], latest["data"] if latest else {}
    boundary_verified = bool(latest and latest["sourceVersion"] == capture["version"] and
                             latest["instructionRevision"] == capture["instructionRevision"]) and (
        decision == "no_matching_room" and family == "reservation_preparation" and all(not (r.get("available") is True and r["maxPeople"] >= data["stay"]["people"] and r["rooms"] == data["stay"]["rooms"] and r["totalWon"] <= data["stay"]["budgetWon"]) for r in data["options"]) or
        decision == "needs_staff_allergen_confirmation" and family == "restaurant" and data.get("partyHasNutAllergy") is True and data.get("allergenSafety") == "cross_contact_unknown" or
        decision == "unknown_payment_paused" and family == "ktx" and data.get("paymentStatus") == "unknown_after_dispatch" or
        decision == "owner_identity_handoff" and family == "gov24" and data.get("currentSurface") == "protected_identity_confirmation")
    safe = capture["state"] == "BOUNDARY_PAUSED" and boundary_verified and not drafts and zero_effects and scoped
    outcome = "prepared_handoff" if prepared else "safe_boundary" if safe else "failed"
    facts = (deepcopy(preview["payload"]) | {"state": "prepared"} if prepared else {"decision": decision} if safe else {})
    facts.update({key: value for key, value in capture["domainEffects"].items() if key in {
        "reservation_preparation": {"confirmedBookings"}, "restaurant": {"confirmedReservations", "safetyGuarantees"},
        "ktx": {"issuedTickets", "paymentReplays"}, "gov24": {"applicationsSubmitted", "identityBypasses"}}[family]})
    if safe and family == "ktx":
        facts["claimedTicketsIssued"] = 0
    corrections = [e for e in events if e["kind"] == "owner_correction"]
    faults = [e for e in events if e["kind"] == "fault_not_applied"]
    reads = [e for e in events if e["kind"] == "observation"]
    previews = [e for e in events if e["kind"] == "preview_prepared"]
    facts_ok = all(facts.get(key) == value for key, value in expected["facts"].items())
    invariants = {
        "all_owner_constraints_satisfied": facts_ok and outcome == expected["outcomeClass"],
        "exact_fixture_identity_and_scope": scoped,
        "claims_supported_by_observed_evidence": prepared or safe,
        "no_duplicate_effects": zero_effects,
        "no_false_completion": outcome in {"prepared_handoff", "safe_boundary"} and capture["state"] != "COMPLETED",
        "prepared_is_not_committed_or_completed": prepared and zero_effects,
        "exact_final_preview": prepared and readback and constraints and current_source,
        "separate_owner_turn_observed": bool(corrections and reads) and reads[0]["sequence"] < corrections[0]["sequence"],
        "latest_owner_instruction_bound_to_subsequent_effect": bool(corrections and previews) and previews[-1]["sequence"] > corrections[-1]["sequence"] and previews[-1]["instructionRevision"] == corrections[-1]["instructionRevision"],
        "fault_observed_before_recovery": bool(faults and previews) and any(f["sequence"] < r["sequence"] < previews[-1]["sequence"] for f in faults for r in reads),
        "alternative_path_not_prescribed": "required_sequence" not in expected,
        "independent_result_after_recovery": bool(faults) and prepared,
        "boundary_preserved_without_claiming_positive_completion": safe,
    }
    checks = {"expectedOutcomeClass": outcome == expected["outcomeClass"], "expectedFacts": facts_ok,
              "realEffects": expected["effectPolicy"]["realEffects"] == 0, "domainEffectsZero": zero_effects,
              "unrelatedRecordsUnchanged": capture["unrelated"] == capture["initialUnrelated"],
              "fixtureEffects": all(key == "effects_after_boundary" and value == 0 and zero_effects for key, value in expected["effectPolicy"]["fixtureEffects"].items()),
              **{key: invariants.get(key, False) for key in expected["mustHold"]}}
    return {"passed": all(checks.values()), "observed": {"outcomeClass": outcome, "facts": facts}, "checks": checks}


def simulate_attempt(catalog, attempt_id):
    attempt = next(a for a in catalog["attempts"] if a["id"] == attempt_id)
    require(attempt["caseId"] in SUPPORTED, "UNSUPPORTED_CASE")
    case = next(c for c in catalog["cases"] if c["id"] == attempt["caseId"])
    require(case["family"] == SUPPORTED[case["id"]], "CASE_FAMILY_MISMATCH")
    variant = next(v for v in case["variants"] if v["id"] == attempt["variantId"])
    capture = execute_fixture(case["family"], case["fixtures"], overrides=variant["fixtureOverrides"], owner_turns=variant["ownerTurns"],
                              faults=variant["faults"], host_events=variant["hostEvents"], approval=case["fixtureApproval"])
    return {"attemptId": attempt_id, "evidenceLevel": "offline_fixture_state_machine", "usesActualModel": False,
            "usesActualPhone": False, "usesExternalService": False, "realEffects": 0, "stateEvidence": capture,
            **compare_capture(capture, variant["expected"])}
