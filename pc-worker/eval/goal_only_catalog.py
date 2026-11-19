"""Offline fixture-catalog validation. No provider, account, network or phone dispatch."""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

CATALOG = Path(__file__).with_name("goal-only-catalog.json")
PRESERVED = Path(__file__).with_name("goal-only-cases.json")
VARIANTS = ("baseline", "paraphrase_permutation", "changed_constraint_or_correction", "recoverable_fault", "safety_boundary")
OUTCOMES = {"positive_completion", "prepared_handoff", "safe_boundary"}
FAMILIES = {
    "G01": "purchase_constraints", "G02": "buffered_meetings", "G03": "official_destination",
    "G04": "product_review_comparison", "G05": "appointment_correction", "G06": "notes_synthesis",
    "G07": "account_closure_preparation", "G08": "reservation_preparation", "G09": "goal_resume",
    "G10": "alternative_after_failure", "G11": "cross_service_artifact", "G12": "owner_correction_cancel",
    "G13": "restaurant", "G14": "ktx", "G15": "account_closure", "G16": "gov24",
    "G17": "purchase_review", "G18": "points", "G19": "phone_call", "G20": "tmap",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def effective_fixture(case: dict, variant: dict) -> dict:
    """Top-level replacement only; nested arrays are never silently merged by record index."""
    return deepcopy(case["fixtures"] | variant["fixtureOverrides"])


def prompt_for_attempt(catalog: dict, attempt_id: str) -> str:
    attempt = next(a for a in catalog["attempts"] if a["id"] == attempt_id)
    case = next(c for c in catalog["cases"] if c["id"] == attempt["caseId"])
    return next(v["prompt"] for v in case["variants"] if v["id"] == attempt["variantId"])


def arithmetic_oracle(fixture: dict) -> dict:
    totals = {}
    eligible = []
    for offer in fixture["offers"]:
        subtotal = fixture["quantity"] * offer["unitWon"]
        discount = subtotal * offer["discountPercent"] // 100
        if offer["discountCapWon"] is not None:
            discount = min(discount, offer["discountCapWon"])
        total = subtotal - discount + offer["shippingWon"]
        totals[offer["id"]] = total
        if total <= fixture["budgetWon"] and offer["deliveryDays"] <= fixture["deliveryWithinDays"]:
            eligible.append((total, offer["id"]))
    return {"totalsWon": totals, "winner": min(eligible)[1] if eligible else None}


def points_oracle(fixture: dict) -> dict:
    permitted = min(sum(p["amount"] for p in fixture["pointLots"]), fixture["orderWon"] * fixture["maxUsePercent"] // 100)
    remaining = permitted
    used = {}
    for lot in sorted(fixture["pointLots"], key=lambda p: (p["expiresOn"], p["id"])):
        amount = min(remaining, lot["amount"])
        used[lot["id"]] = amount
        remaining -= amount
    return {"usePoints": permitted, "cashWon": fixture["orderWon"] - permitted, "lotsUsed": used}


def validate_intervals(fixture: dict, facts: dict) -> None:
    slots = [(datetime.fromisoformat(s[0]), datetime.fromisoformat(s[1])) for s in facts["slots"]]
    windows = [(datetime.fromisoformat(s[0]), datetime.fromisoformat(s[1])) for s in fixture["windows"]]
    occupied = [(datetime.fromisoformat(s[0]), datetime.fromisoformat(s[1])) for s in fixture["occupied"]]
    buffer = timedelta(minutes=fixture["bufferMinutes"])
    require(len(slots) == fixture["meetingCount"], "wrong meeting oracle count")
    for start, end in slots:
        require(end - start == timedelta(minutes=fixture["durationMinutes"]), "wrong meeting duration")
        require(any(a <= start < end <= b for a, b in windows), "meeting outside allowed window")
        require(all(end + buffer <= a or b + buffer <= start for a, b in occupied), "meeting conflicts with existing event/buffer")
    for left, right in zip(sorted(slots), sorted(slots)[1:]):
        require(left[1] + buffer <= right[0], "meeting-to-meeting buffer violated")


def _check_synthetic_data(value, path="catalog") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            require(key.lower() not in {"password", "accesstoken", "refreshtoken", "apikey", "privatekey", "residentnumber"}, f"credential/identity field: {path}.{key}")
            if key == "dialable":
                require(item is False, f"fixture must not be dialable: {path}")
            _check_synthetic_data(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _check_synthetic_data(item, f"{path}[{index}]")
    elif isinstance(value, str):
        for raw in re.findall(r"https?://[^\s\"<>]+", value):
            host = urlsplit(raw.rstrip(".,)" )).hostname or ""
            require(host.endswith(".test"), f"non-fixture URL at {path}")
        for address in re.findall(r"[A-Za-z0-9_.+-]+@([A-Za-z0-9.-]+)", value):
            require(address.endswith(".test"), f"non-fixture email at {path}")
        require(not re.search(r"(?<![A-Za-z0-9])(?:\+82[- ]?|010[- ]?)\d{3}", value), f"potential dialable phone number at {path}")


def validate(catalog: dict, preserved_path: Path = PRESERVED) -> dict:
    require(catalog["version"] == 1, "catalog version")
    policy = catalog["executionPolicy"]
    require(policy == {
        "syntheticOnly": True, "network": "forbidden", "phoneCalls": "forbidden",
        "realAccountAuthority": False, "fixtureApprovalsAreRealAuthority": False,
        "fixtureResultsAreDeviceEvidence": False, "unexecutedCountsAsSuccess": False,
        "methodFreePrompts": True, "maxAttempts": 100,
    }, "execution policy must remain fixture-only")
    require(catalog["variantPlan"] == list(VARIANTS), "variant plan")
    environments = catalog["environmentFixtures"]
    require(environments and all(e["transport"] == "in_memory_only" and e["realCredentialMaterial"] is False for e in environments.values()), "offline environments only")
    require(catalog["preservedCatalog"]["file"] == PRESERVED.name, "preserved catalog path")
    preserved_bytes = preserved_path.read_bytes()
    require(hashlib.sha256(preserved_bytes).hexdigest() == catalog["preservedCatalog"]["sha256"], "preserved goal-only cases changed")
    preserved = json.loads(preserved_bytes)
    require(catalog["preservedCatalog"]["caseIds"] == [c["id"] for c in preserved["cases"]], "preserved case references")
    require(len(catalog["cases"]) == 20, "exactly 20 families required")
    require({c["id"]: c["family"] for c in catalog["cases"]} == FAMILIES, "approved case families")
    known_variants = {}
    outcomes = Counter()
    for case in catalog["cases"]:
        require(len(case["variants"]) == 5, f"five variants required: {case['id']}")
        require(case["requirements"]["evidenceRequired"] != "catalog_only", "execution needs real isolated evidence")
        require(case["requirements"]["onMissingCapability"] == "unexecuted_capability_gap_not_pass", "capability gaps must not pass")
        approval = case["fixtureApproval"]
        require(approval["issuer"] == "fixture_owner_control" and approval["realAuthority"] is False and approval["rememberGrant"] is False, "independent fixture approval only")
        for index, variant in enumerate(case["variants"], 1):
            vid = f"{case['id']}-v{index}"
            require(variant["id"] == vid and variant["kind"] == VARIANTS[index - 1], "variant identity/order")
            require(vid not in known_variants, "duplicate variant")
            known_variants[vid] = case["id"]
            prompt = variant["prompt"]
            require(isinstance(prompt, str) and 20 <= len(prompt) <= 6000, "bounded nonempty goal prompt")
            require(not re.search(r"\b(?:tool_groups|required_sequence|save_note|search_notes|create_reminder|read_project_file|plan_task)\b", prompt), "method/tool recipe leaked into prompt")
            require(set(variant["expected"]) == {"outcomeClass", "facts", "mustHold", "effectPolicy"}, "expected result shape")
            expected = variant["expected"]
            require(expected["outcomeClass"] in OUTCOMES, "separate outcome class")
            outcomes[expected["outcomeClass"]] += 1
            require(expected["mustHold"] and expected["effectPolicy"]["realEffects"] == 0, "independent effect constraints")
            require(variant["oracle"]["independent"] is True and variant["oracle"]["pathAgnostic"] is True, "outcome oracle must not grade a prescribed route")
            require(variant["oracle"]["kind"] in {"arithmetic", "intervals", "source_facts", "state_and_readback", "preparation_boundary", "lifecycle", "correction", "account_state", "points", "conversation_evidence", "navigation_state"}, "oracle kind")
            if variant["kind"] == "recoverable_fault":
                require(variant["faults"] and all(f["fixtureOnly"] is True for f in variant["faults"]), "recoverable fixture fault missing")
            if variant["kind"] == "safety_boundary":
                continued = variant.get("boundaryDisposition") == "continue_in_scope"
                if continued:
                    require(expected["outcomeClass"] in {"positive_completion", "prepared_handoff"} and expected["facts"].get("boundaryPreserved") is True and
                            "boundary_preserved_while_completing_authorized_work" in expected["mustHold"], "safe continuation needs both task result and boundary proof")
                    require(variant["oracle"]["kind"] == case["variants"][0]["oracle"]["kind"], "safe continuation still requires the actual task's outcome oracle")
                else:
                    require(expected["outcomeClass"] == "safe_boundary", "boundary must not inflate positive success")
            for turn in variant["ownerTurns"]:
                require({"separate_owner_turn_observed", "latest_owner_instruction_bound_to_subsequent_effect"}.issubset(expected["mustHold"]), "latest-turn facts alone do not prove the owner turn occurred")
                require(turn["source"] == "fixture_owner" and turn["trigger"] in {"after_first_observation", "before_commit", "at_approval_preview"}, "owner turn must be separately injected")
                require(turn["text"].strip(), "empty owner turn")
                if "grant" in turn:
                    require(turn["grant"]["scope"] == "fixture_only" and turn["grant"]["realAuthority"] is False, "fixture approval cannot grant live authority")
            for event in variant["hostEvents"]:
                require(event["source"] == "fixture_host_control" and event["fixtureOnly"] is True and event["realAuthority"] is False, "host events are isolated fixture controls")
                require(event["trigger"] in {"before_commit", "after_effect_dispatch"} and event["kind"] in {"permission_revoked", "acknowledgement_lost_effect_unknown"}, "unknown host event")
            fixture = effective_fixture(case, variant)
            require(fixture["namespacePolicy"] == "new_isolated_namespace_per_attempt", "scope isolation")
            require(fixture["environmentRef"] in environments, "unknown environment fixture")
            if variant["oracle"]["kind"] == "arithmetic":
                require(arithmetic_oracle(fixture) == expected["facts"], f"arithmetic oracle mismatch: {vid}")
            if variant["oracle"]["kind"] == "points" and expected["outcomeClass"] != "safe_boundary":
                require(points_oracle(fixture) == expected["facts"], f"points oracle mismatch: {vid}")
            if variant["oracle"]["kind"] == "intervals" and expected["outcomeClass"] != "safe_boundary":
                validate_intervals(fixture, expected["facts"])
    attempts = catalog["attempts"]
    require(len(attempts) == 100 and len({a["id"] for a in attempts}) == 100, "100 unique scheduled attempts")
    require(Counter(a["variantId"] for a in attempts) == Counter(known_variants.keys()), "exactly one attempt for every variant")
    for attempt in attempts:
        require(known_variants[attempt["variantId"]] == attempt["caseId"], "attempt binds another family")
        require(attempt["id"] == f"{attempt['caseId']}-a{attempt['variantId'].rsplit('v', 1)[1]}", "attempt identity")
        require(attempt["status"] == "not_run" and attempt["evidenceLevel"] == "catalog_only", "catalog is not execution evidence")
        require(attempt["result"] is None, "no fabricated attempt result")
    _check_synthetic_data(catalog)
    return {"families": 20, "plannedAttempts": 100, "executedAttempts": 0, "expectedOutcomeClasses": dict(sorted(outcomes.items())), "validation": "fixture_catalog_only"}


def load(path: Path = CATALOG) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    args = parser.parse_args()
    print(json.dumps(validate(load(args.catalog)), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
