from copy import deepcopy
from dataclasses import replace
import json
import unittest

import goal_only_catalog
import goal_resume_simulation as simulation


class GoalResumeSimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = goal_only_catalog.load()

    def variant(self, index=0):
        case = next(c for c in self.catalog["cases"] if c["id"] == "G09")
        variant = case["variants"][index]
        return goal_only_catalog.effective_fixture(case, variant), variant

    def run_variant(self, index=0):
        fixture, variant = self.variant(index)
        capture = simulation.execute_fixture(fixture, owner_turns=variant["ownerTurns"], faults=variant["faults"], host_events=variant["hostEvents"])
        return capture, simulation.compare_observed(capture, variant["expected"])

    def test_all_five_variants_have_real_fixture_transitions_and_correct_outcome_classes(self):
        results = [simulation.simulate_attempt(self.catalog, "G09-a" + str(i)) for i in range(1, 6)]
        self.assertTrue(all(r["passed"] for r in results), results)
        self.assertEqual(["positive_completion"] * 4 + ["safe_boundary"], [r["observed"]["outcomeClass"] for r in results])
        for result in results:
            self.assertEqual("offline_fixture_state_machine", result["evidenceLevel"])
            self.assertFalse(result["usesActualModel"])
            self.assertFalse(result["usesActualPhone"])
            self.assertFalse(result["usesExternalService"])
            self.assertEqual(0, result["realEffects"])

    def test_checkpoint_is_serialized_and_session_is_recreated_before_completion(self):
        capture, result = self.run_variant()
        self.assertTrue(result["passed"])
        self.assertGreaterEqual(capture["checkpoints"], 3)
        self.assertGreaterEqual(capture["restores"], 3)
        kinds = [event["kind"] for event in capture["trace"]]
        self.assertEqual(2, kinds.count("segment_started"))
        self.assertIn("segment_checkpointed", kinds)
        self.assertEqual("completed", kinds[-1])

    def test_same_target_repeated_delivery_uses_journal_and_readback_without_duplicate_effect(self):
        fixture, _ = self.variant()
        world = simulation.FixtureWorld(fixture)
        session = simulation.ResumeSession(world)
        self.assertTrue(session.resume())
        identifier = world.preserved[0]
        payload = deepcopy(world.artifacts[identifier]["payload"])
        world.create(session.binding, identifier, payload)
        world.create(session.binding, identifier, payload)
        self.assertEqual(1, world.effect_counts[identifier])
        self.assertEqual(2, world.suppressed_replays)
        capture, result = self.run_variant()
        self.assertTrue(result["passed"])
        self.assertEqual(1, capture["effectCounts"]["qa-part-B"])
        self.assertEqual(1, capture["suppressedReplays"])

    def test_fault_has_observed_not_applied_receipt_before_recovery_and_one_actual_B_effect(self):
        capture, result = self.run_variant(3)
        self.assertTrue(result["passed"])
        self.assertEqual(["NOT_APPLIED", "VERIFIED"], [r["state"] for r in capture["journal"] if r["artifactId"] == "qa-part-B"])
        self.assertEqual(1, capture["effectCounts"]["qa-part-B"])
        self.assertEqual(3, capture["goal"]["segments"])
        events = capture["trace"]
        fault = next(e for e in events if e["kind"] == "fault_not_applied")
        applied = next(e for e in events if e["kind"] == "effect_applied")
        self.assertLess(fault["sequence"], applied["sequence"])

    def test_open_uncertain_native_source_overrides_a_clean_verified_summary(self):
        capture, result = self.run_variant(4)
        self.assertTrue(result["passed"])
        self.assertEqual("UNKNOWN_EFFECT_PAUSED", capture["goal"]["state"])
        self.assertEqual({"qa-part-A": 1}, capture["effectCounts"])
        self.assertEqual("VERIFIED", capture["journal"][0]["state"])
        self.assertFalse(capture["native"]["closed"])
        self.assertTrue(capture["native"]["uncertain"])
        self.assertNotIn("effect_applied", [e["kind"] for e in capture["trace"]])

    def test_unknown_journal_receipt_also_prevents_replay_even_with_closed_native_summary(self):
        fixture, _ = self.variant()
        world = simulation.FixtureWorld(fixture)
        world.journal.append({"operation": "qa-unknown", "artifactId": "qa-part-B", "state": "UNKNOWN", "binding": simulation.asdict(world.binding), "sha256": None})
        session = simulation.ResumeSession(world)
        self.assertFalse(session.resume())
        with self.assertRaisesRegex(simulation.SimulationError, "UNKNOWN_EFFECT"):
            world.create(session.binding, world.remaining_id, {"part": world.remaining_id, "due": None})
        self.assertEqual({"qa-part-A": 1}, world.effect_counts)

    def test_current_owner_correction_is_a_separate_event_before_the_effect(self):
        capture, result = self.run_variant(2)
        self.assertTrue(result["passed"])
        events = capture["trace"]
        observed = next(e for e in events if e["kind"] == "observation")
        correction = next(e for e in events if e["kind"] == "owner_correction")
        applied = next(e for e in events if e["kind"] == "effect_applied")
        self.assertLess(observed["sequence"], correction["sequence"])
        self.assertLess(correction["sequence"], applied["sequence"])
        self.assertEqual(correction["instructionRevision"], applied["instructionRevision"])
        self.assertEqual("2030-04-17", capture["artifacts"]["qa-part-B"]["payload"]["due"])

    def test_other_scope_goal_grant_and_namespace_cannot_resume_or_create(self):
        fixture, _ = self.variant()
        for field, value in (("scope", "qa-other"), ("goal_id", "qa-other-goal"), ("authorization_revision", 2), ("namespace", "qa-resume-other")):
            world = simulation.FixtureWorld(fixture)
            session = simulation.ResumeSession(world)
            world.authority = replace(world.binding, **{field: value})
            with self.assertRaisesRegex(simulation.SimulationError, "SCOPE_OR_GRANT_CHANGED"):
                session.resume()
            with self.assertRaisesRegex(simulation.SimulationError, "SCOPE_OR_GRANT_CHANGED"):
                world.create(session.binding, world.remaining_id, {"part": world.remaining_id, "due": None})
            self.assertEqual({"qa-part-A": 1}, world.effect_counts)

    def test_checkpoint_from_another_namespace_or_forged_completed_target_is_rejected(self):
        fixture, _ = self.variant()
        first, second = simulation.FixtureWorld(fixture), simulation.FixtureWorld(fixture)
        encoded = simulation.ResumeSession(first).checkpoint()
        with self.assertRaisesRegex(simulation.SimulationError, "CHECKPOINT_SCOPE"):
            simulation.ResumeSession.restore(second, encoded)
        changed = json.loads(encoded)
        changed["completed"] = [first.remaining_id]
        with self.assertRaisesRegex(simulation.SimulationError, "READBACK_TARGET_MISSING"):
            simulation.ResumeSession.restore(first, json.dumps(changed).encode())

    def test_checkpoint_duplicate_fields_and_stale_owner_instruction_are_rejected(self):
        fixture, _ = self.variant()
        world = simulation.FixtureWorld(fixture)
        session = simulation.ResumeSession(world)
        encoded = session.checkpoint()
        with self.assertRaises(simulation.SimulationError):
            simulation.ResumeSession.restore(world, encoded[:-1] + b',"schema":1}')
        self.assertTrue(session.resume())
        session.correct_due({"source": "fixture_owner", "trigger": "after_first_observation", "text": "synthetic correction"}, "2030-04-17")
        with self.assertRaisesRegex(simulation.SimulationError, "CHECKPOINT_STALE_INSTRUCTION"):
            simulation.ResumeSession.restore(world, encoded)

    def test_checkpoint_cannot_change_owner_due_with_an_unchanged_instruction_revision(self):
        fixture, _ = self.variant()
        world = simulation.FixtureWorld(fixture)
        session = simulation.ResumeSession(world)
        self.assertTrue(session.resume())
        session.correct_due({"source": "fixture_owner", "trigger": "after_first_observation", "text": "synthetic correction"}, "2030-04-17")
        checkpoint = json.loads(session.checkpoint())
        checkpoint["due"] = "2030-04-18"
        with self.assertRaisesRegex(simulation.SimulationError, "CHECKPOINT_OWNER_CONSTRAINT_CHANGED"):
            simulation.ResumeSession.restore(world, json.dumps(checkpoint).encode())

    def test_readback_does_not_trust_a_verified_label_or_an_existing_artifact(self):
        fixture, _ = self.variant()
        world = simulation.FixtureWorld(fixture)
        session = simulation.ResumeSession(world)
        self.assertTrue(session.resume())
        identifier = world.preserved[0]
        world.artifacts[identifier]["payload"]["part"] = "changed"
        with self.assertRaisesRegex(simulation.SimulationError, "READBACK_MISMATCH"):
            world.create(session.binding, identifier, world.artifacts[identifier]["payload"])
        self.assertEqual(1, world.effect_counts[identifier])

    def test_independent_expected_or_observed_tampering_cannot_make_a_false_pass(self):
        capture, result = self.run_variant()
        self.assertTrue(result["passed"])
        _, variant = self.variant()
        changed_expected = deepcopy(variant["expected"])
        changed_expected["facts"]["BEffectCount"] = 2
        self.assertFalse(simulation.compare_observed(capture, changed_expected)["passed"])
        for change in ("duplicate", "payload", "scope", "unrelated", "receipt"):
            corrupted = deepcopy(capture)
            if change == "duplicate":
                corrupted["effectCounts"]["qa-part-B"] = 2
            elif change == "payload":
                corrupted["artifacts"]["qa-part-A"]["payload"]["part"] = "wrong"
            elif change == "scope":
                corrupted["artifacts"]["qa-part-B"]["binding"]["scope"] = "qa-other"
            elif change == "unrelated":
                corrupted["unrelated"]["content"] = "wrong"
            else:
                corrupted["journal"][-1]["state"] = "UNKNOWN"
            self.assertFalse(simulation.compare_observed(corrupted, variant["expected"])["passed"], change)

    def test_execution_does_not_consume_or_modify_expected_or_catalog(self):
        before = deepcopy(self.catalog)
        for index in range(5):
            self.assertTrue(simulation.simulate_attempt(self.catalog, "G09-a" + str(index + 1))["passed"])
        self.assertEqual(before, self.catalog)
        self.assertEqual({"not_run"}, {a["status"] for a in self.catalog["attempts"]})
        self.assertTrue(all(a["result"] is None for a in self.catalog["attempts"]))

    def test_budget_boundary_and_unsupported_real_fault_fail_closed(self):
        fixture, _ = self.variant()
        fixture["goal"]["maxSegments"] = 1
        with self.assertRaisesRegex(simulation.SimulationError, "SEGMENT_BUDGET"):
            simulation.execute_fixture(fixture)
        fixture, _ = self.variant()
        with self.assertRaisesRegex(simulation.SimulationError, "UNSUPPORTED_FAULT"):
            simulation.execute_fixture(fixture, faults=[{"semanticOperation": "remaining_mutation", "fixtureOnly": False}])


if __name__ == "__main__":
    unittest.main()
