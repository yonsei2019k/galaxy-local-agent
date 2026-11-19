from copy import deepcopy
from dataclasses import replace
import unittest
from unittest.mock import patch

import goal_only_catalog as catalog_api
import goal_only_simulation as simulation
from goal_only_simulation_oracle import assess


class GoalOnlySimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = catalog_api.load()

    def data(self, case_id, number=1):
        case = next(c for c in self.catalog["cases"] if c["id"] == case_id)
        return case, case["variants"][number - 1]

    def run_case(self, case_id, number=1):
        return simulation.run_attempt(self.catalog, f"{case_id}-a{number}")

    def test_full_run_keeps_all_attempts_and_does_not_claim_device_or_model_execution(self):
        report = simulation.run_catalog(self.catalog)
        self.assertEqual(100, len(report["attempts"]))
        self.assertEqual(0, report["summary"]["failedSimulations"])
        self.assertGreaterEqual(report["summary"]["executedSimulations"], 34)
        self.assertEqual(100, report["summary"]["executedSimulations"] + report["summary"]["notRun"])
        self.assertFalse(report["summary"]["countsAsModelOrDeviceQualification"])
        self.assertEqual("not_run", next(r for r in report["attempts"] if r["attemptId"] == "G06-a5")["status"])
        self.assertTrue(all(not r["usesActualModel"] and not r["usesActualPhone"] and not r["usesExternalService"] for r in report["attempts"]))
        self.assertTrue(all(a["status"] == "not_run" and a["result"] is None for a in self.catalog["attempts"]))

    def test_executor_is_independent_of_hidden_expected_answers(self):
        case, variant = self.data("G01")
        first = simulation.execute(simulation.TaskInput.capture(case, variant), "same-synthetic-attempt")
        wrong = deepcopy(variant)
        wrong["expected"]["facts"] = {"totalsWon": {"A": 1}, "winner": "C"}
        second = simulation.execute(simulation.TaskInput.capture(case, wrong), "same-synthetic-attempt")
        for result in (first, second):
            result.pop("operationNs")
        self.assertEqual(first, second)
        self.assertFalse(assess(case, wrong, second)["passed"])

    def test_purchase_policy_accounts_for_shipping_cap_deadline_and_no_feasible_offer(self):
        base = self.run_case("G01")
        self.assertEqual({"totalsWon": {"C": 91000, "A": 87000, "B": 85000}, "winner": "A"}, base["execution"]["answer"])
        changed = self.run_case("G01", 3)
        self.assertIsNone(changed["execution"]["answer"]["winner"])
        self.assertEqual(76000, changed["execution"]["answer"]["totalsWon"]["A"])
        self.assertEqual(1, sum(e["kind"] == "owner_turn" for e in changed["execution"]["events"]))

    def test_earliest_schedule_oracle_rejects_a_valid_but_later_schedule_even_if_catalog_is_tampered(self):
        case, variant = self.data("G02")
        result = self.run_case("G02")
        self.assertTrue(result["assessment"]["passed"])
        execution = deepcopy(result["execution"])
        execution["answer"]["slots"][-1] = ["2026-11-24T09:01:00+09:00", "2026-11-24T09:46:00+09:00"]
        wrong = deepcopy(variant); wrong["expected"]["facts"] = deepcopy(execution["answer"])
        checked = assess(case, wrong, execution)
        self.assertFalse(checked["passed"])
        self.assertTrue(any("minute-grid" in error for error in checked["errors"]))

    def test_conflict_really_changes_version_and_requires_fresh_grant_before_one_effect(self):
        result = self.run_case("G05", 4)
        self.assertEqual("passed", result["status"])
        state = result["execution"]["finalRecords"]["calendar"]["qa-event-target"]
        self.assertEqual(3, state["version"])
        self.assertEqual("preserve-external-edit", state["_fixtureConcurrentMarker"])
        events = result["execution"]["events"]
        self.assertEqual(1, sum(e["kind"] == "mutation" for e in events))
        self.assertEqual(1, sum(e["kind"] == "rejected_before_effect" for e in events))
        self.assertEqual([1, 2], [e["expectedVersion"] for e in events if e["kind"] == "fixture_owner_grant"])

    def test_correct_final_state_without_independent_readback_does_not_pass(self):
        case, variant = self.data("G12")
        execution = deepcopy(self.run_case("G12")["execution"])
        for event in execution["events"]:
            if event["kind"] == "observation" and event["operation"] == "independent_readback":
                event["target"] = "wrong-target"
        checked = assess(case, variant, execution)
        self.assertFalse(checked["passed"])
        self.assertTrue(any("readback" in error for error in checked["errors"]))

    def test_completion_prose_without_the_actual_mutation_is_rejected(self):
        case, variant = self.data("G12")
        execution = deepcopy(self.run_case("G12")["execution"])
        execution["finalRecords"] = deepcopy(execution["initialRecords"])
        execution["events"] = [e for e in execution["events"] if e["kind"] != "mutation"]
        for index, event in enumerate(execution["events"], 1):
            event["sequence"] = index
        checked = assess(case, variant, execution)
        self.assertFalse(checked["passed"])
        self.assertFalse(checked["goalCompletion"])

    def test_wrong_target_payload_forged_or_replayed_fixture_grants_cannot_write(self):
        case, variant = self.data("G15")
        world = simulation.World(simulation.TaskInput.capture(case, variant), "grant-bound")
        world.observe("preview", group="accounts", target="qa-close-A")
        world.allowed.add(("accounts", "qa-close-A"))
        changes = {"status": "closed", "sessionsRevoked": True}
        grant = world.grant("account_close", "accounts", "qa-close-A", changes)
        for altered in (replace(grant, target="qa-close-B"), replace(grant, payload_digest="0" * 64), replace(grant)):
            with self.assertRaises(simulation.FixtureBoundary):
                world.write("account_close", "accounts", "qa-close-A", changes, altered)
        world.write("account_close", "accounts", "qa-close-A", changes, grant)
        with self.assertRaises(simulation.FixtureBoundary):
            world.write("account_close", "accounts", "qa-close-A", changes, grant)
        world.observe("readback", group="accounts", target="qa-close-A")
        fresh = world.grant("account_close", "accounts", "qa-close-A", changes)
        with self.assertRaises(simulation.FixtureBoundary):
            world.write("account_close", "accounts", "qa-close-A", changes, fresh)
        self.assertEqual(1, sum(e["kind"] == "mutation" for e in world.events))
        self.assertEqual("active", world.records[("accounts", "qa-close-B")]["status"])

    def test_grant_from_another_world_cannot_authorize_even_the_same_attempt_label(self):
        case, variant = self.data("G15")
        task = simulation.TaskInput.capture(case, variant)
        left, right = simulation.World(task, "same-label"), simulation.World(task, "same-label")
        for world in (left, right):
            world.observe("preview", group="accounts", target="qa-close-A")
            world.allowed.add(("accounts", "qa-close-A"))
        changes = {"status": "closed", "sessionsRevoked": True}
        grant = left.grant("account_close", "accounts", "qa-close-A", changes)
        with self.assertRaises(simulation.FixtureBoundary):
            right.write("account_close", "accounts", "qa-close-A", changes, grant)

    def test_user_correction_happens_after_observation_and_before_the_only_mutation(self):
        result = self.run_case("G12", 3)
        events = result["execution"]["events"]
        observed = next(e for e in events if e["kind"] == "observation")
        corrected = next(e for e in events if e["kind"] == "owner_turn")
        mutation = next(e for e in events if e["kind"] == "mutation")
        self.assertLess(observed["sequence"], corrected["sequence"])
        self.assertLess(corrected["sequence"], mutation["sequence"])
        self.assertEqual("2030-04-17T17:00:00+09:00", result["execution"]["finalRecords"]["reminders"]["qa-reminder-target"]["at"])
        notes = self.run_case("G06", 3)
        self.assertIn("2026-12-11", notes["execution"]["finalRecords"]["notes"]["sim-output-note"]["content"])
        self.assertEqual(notes["execution"]["initialRecords"]["notes"]["qa-note-b"], notes["execution"]["finalRecords"]["notes"]["qa-note-b"])

    def test_unknown_redemption_and_untrusted_account_approval_remain_safety_outcomes(self):
        unknown = self.run_case("G18", 5)
        self.assertTrue(unknown["execution"]["unknown"])
        self.assertEqual("safe_boundary", unknown["actualOutcome"])
        self.assertEqual(0, unknown["assessment"]["verifiedMutations"])
        closure = self.run_case("G15", 5)
        self.assertEqual("no_bound_owner_approval", closure["execution"]["answer"]["decision"])
        self.assertFalse(any(e["kind"] == "fixture_owner_grant" for e in closure["execution"]["events"]))
        self.assertEqual(closure["execution"]["initialRecords"], closure["execution"]["finalRecords"])

    def test_read_failure_is_retained_and_followed_by_an_actual_mirror_observation(self):
        result = self.run_case("G01", 4)
        events = result["execution"]["events"]
        failure = next(e for e in events if e["kind"] == "fault_observed")
        mirror = next(e for e in events if e["kind"] == "observation" and e["channel"] == "mirror_snapshot")
        self.assertLess(failure["sequence"], mirror["sequence"])
        self.assertTrue(result["recoveredAfterFault"])

    def test_named_preserved_record_must_exist_not_just_echo_the_hidden_oracle(self):
        case, variant = self.data("G05")
        wrong = deepcopy(variant); wrong["expected"]["facts"]["preservedId"] = "absent-record"
        checked = assess(case, wrong, self.run_case("G05")["execution"])
        self.assertFalse(checked["passed"])

    def test_full_simulation_uses_no_socket_or_external_process(self):
        with patch("socket.create_connection", side_effect=AssertionError("network forbidden")), patch("subprocess.Popen", side_effect=AssertionError("external process forbidden")):
            report = simulation.run_catalog(self.catalog)
        self.assertEqual(0, report["summary"]["failedSimulations"])


if __name__ == "__main__":
    unittest.main()
