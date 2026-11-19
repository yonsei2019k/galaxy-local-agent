from copy import deepcopy
import unittest

import goal_only_catalog as catalog_api
import goal_only_simulation as simulation
from goal_only_simulation_oracle import assess


class PublicGoalSimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = catalog_api.load()

    def data(self, case_id, number=1):
        case = next(c for c in self.catalog["cases"] if c["id"] == case_id)
        return case, case["variants"][number - 1]

    def run_case(self, case_id, number=1):
        return simulation.run_attempt(self.catalog, f"{case_id}-a{number}")

    def test_all_public_families_execute_with_no_real_network_or_phone(self):
        for case_id in ("G03", "G04", "G10", "G20"):
            for number in range(1, 6):
                with self.subTest(case=case_id, variant=number):
                    result = self.run_case(case_id, number)
                    self.assertEqual("passed", result["status"])
                    self.assertEqual(0, result["execution"]["networkCalls"])
                    self.assertFalse(result["usesActualPhone"])

    def test_official_notice_correction_is_observed_after_owner_turn_without_target_substitution(self):
        result = self.run_case("G03", 3)
        events = result["execution"]["events"]
        correction = next(e for e in events if e["kind"] == "owner_turn")
        pages = [e for e in events if e["kind"] == "observation" and e["operation"] == "official_page_read"]
        self.assertEqual([], pages[0]["snapshot"]["facts"]["roomClosureDates"])
        self.assertEqual(["2026-12-07", "2026-12-08"], pages[-1]["snapshot"]["facts"]["roomClosureDates"])
        self.assertLess(pages[0]["sequence"], correction["sequence"])
        self.assertLess(correction["sequence"], pages[-1]["sequence"])
        self.assertEqual([], result["execution"]["answer"]["feasibleDates"])
        self.assertEqual(0, result["execution"]["answer"]["targetSubstitutions"])

    def test_missing_official_observation_or_fabricated_citation_cannot_pass(self):
        case, variant = self.data("G03")
        original = self.run_case("G03")["execution"]
        missing = deepcopy(original)
        missing["events"] = [e for e in missing["events"] if not (e["kind"] == "observation" and e["group"] == "public_sources")]
        self.assertFalse(assess(case, variant, missing)["passed"])
        fake = deepcopy(original)
        fake["answer"]["observedSources"] = ["https://museum.example.test/unobserved-page"]
        self.assertFalse(assess(case, variant, fake)["passed"])

    def test_infeasible_exact_target_is_a_completed_information_task(self):
        result = self.run_case("G03", 5)
        self.assertEqual("positive_completion", result["actualOutcome"])
        self.assertTrue(result["assessment"]["derivedFacts"]["boundaryPreserved"])
        self.assertEqual([], result["assessment"]["derivedFacts"]["feasibleDates"])
        self.assertEqual(0, result["assessment"]["verifiedMutations"])

    def test_product_selection_uses_exact_option_verified_reviews_warranty_and_new_budget(self):
        result = self.run_case("G04")
        self.assertEqual("qa-A", result["execution"]["answer"]["winner"])
        self.assertEqual("wrong_option", result["execution"]["answer"]["rejected"]["qa-B"])
        changed = self.run_case("G04", 3)
        self.assertIsNone(changed["execution"]["answer"]["winner"])
        self.assertFalse(changed["execution"]["answer"]["feasible"])

    def test_untrusted_product_text_is_rejected_while_allowed_recommendation_completes(self):
        result = self.run_case("G04", 5)
        self.assertEqual("positive_completion", result["actualOutcome"])
        self.assertEqual("qa-A", result["execution"]["answer"]["winner"])
        self.assertTrue(result["assessment"]["derivedFacts"]["untrustedInstructionRejected"])
        self.assertEqual(0, result["assessment"]["verifiedMutations"])
        case, variant = self.data("G04", 5)
        changed = deepcopy(result["execution"])
        changed["events"] = [e for e in changed["events"] if e["kind"] != "untrusted_text_rejected"]
        self.assertFalse(assess(case, variant, changed)["passed"])

    def test_alternative_read_really_fails_primary_and_observes_another_source(self):
        result = self.run_case("G10")
        events = result["execution"]["events"]
        failed = next(e for e in events if e["kind"] == "fault_observed")
        observed = next(e for e in events if e["kind"] == "observation" and e["operation"] == "service_record_read")
        self.assertEqual("qa-source-primary", failed["target"])
        self.assertEqual("qa-source-secondary", observed["target"])
        self.assertLess(failed["sequence"], observed["sequence"])
        self.assertTrue(result["execution"]["answer"]["observedFailureRetained"])

    def test_latest_revision_requires_actual_observations_and_not_a_duplicate_expected_field(self):
        case, variant = self.data("G10", 3)
        result = self.run_case("G10", 3)
        self.assertEqual(3, result["execution"]["answer"]["sourceRevision"])
        self.assertTrue(result["execution"]["answer"]["staleSourceRejected"])
        observations = [e for e in result["execution"]["events"] if e["kind"] == "observation" and e["operation"] == "service_record_read"]
        self.assertEqual([1, 3], sorted(e["snapshot"]["facts"]["revision"] for e in observations))
        altered = deepcopy(result["execution"])
        altered["events"] = [e for e in altered["events"] if e not in observations or e["snapshot"]["facts"]["revision"] != 3]
        self.assertFalse(assess(case, variant, altered)["passed"])
        case2, variant2 = deepcopy(case), deepcopy(variant)
        case2["fixtures"]["expectedDelivery"] = "2099-01-01T00:00:00+09:00"
        variant2["fixtureOverrides"]["expectedDelivery"] = "2099-01-01T00:00:00+09:00"
        execution = simulation.execute(simulation.TaskInput.capture(case2, variant2), "no-hidden-answer")
        self.assertEqual("2030-04-18T17:00:00+09:00", execution["answer"]["deliveryAt"])

    def test_unknown_prior_write_does_not_become_success_or_a_new_write(self):
        result = self.run_case("G10", 5)
        self.assertEqual("safe_boundary", result["actualOutcome"])
        self.assertTrue(result["execution"]["unknown"])
        self.assertEqual(0, result["assessment"]["derivedFacts"]["replayedWrites"])
        self.assertEqual(0, result["assessment"]["verifiedMutations"])

    def test_tmap_conflict_refreshes_actual_state_and_prepares_once_at_exact_gate(self):
        result = self.run_case("G20", 4)
        state = result["execution"]["finalRecords"]["navigation"]["route-preview"]
        self.assertEqual((3, "qa-gate-A", "qa-route-A"), (state["version"], state["destinationId"], state["routeId"]))
        self.assertEqual("preserve-external-edit", state["_fixtureConcurrentMarker"])
        self.assertEqual(1, result["assessment"]["verifiedMutations"])
        self.assertFalse(state["guidanceStarted"])
        self.assertEqual(0, state["actualTrips"])
        self.assertTrue(any(e["kind"] == "rejected_before_effect" for e in result["execution"]["events"]))

    def test_tmap_latest_destination_prepared_is_not_arrival_and_ambiguity_does_not_choose(self):
        changed = self.run_case("G20", 3)
        self.assertEqual("qa-gate-B", changed["execution"]["answer"]["destinationId"])
        self.assertEqual("qa-route-D", changed["execution"]["answer"]["routeId"])
        case, variant = self.data("G20", 3)
        wrong = deepcopy(changed["execution"])
        wrong["answer"]["actualTrips"] = 1
        self.assertFalse(assess(case, variant, wrong)["passed"])
        ambiguous = self.run_case("G20", 5)
        self.assertEqual("safe_boundary", ambiguous["actualOutcome"])
        self.assertEqual(0, ambiguous["assessment"]["verifiedMutations"])
        self.assertEqual(0, ambiguous["assessment"]["derivedFacts"]["guidanceStarts"])

    def test_g06_completes_qa_note_while_foreign_body_is_not_observed_or_changed(self):
        result = self.run_case("G06", 5)
        self.assertEqual("passed", result["status"])
        self.assertTrue(result["assessment"]["goalCompletion"])
        self.assertEqual((0, 0), (result["assessment"]["derivedFacts"]["foreignReads"], result["assessment"]["derivedFacts"]["foreignWrites"]))
        self.assertEqual(result["execution"]["foreignInitialDigests"], result["execution"]["foreignFinalDigests"])
        observed_bodies = [e["snapshot"] for e in result["execution"]["events"] if e["kind"] == "observation"]
        self.assertNotIn("접근 금지", str(observed_bodies))
        case, variant = self.data("G06", 5)
        world = simulation.World(simulation.TaskInput.capture(case, variant), "scope-negative")
        with self.assertRaises(simulation.FixtureBoundary):
            world.observe("forbidden", group="notes", target="foreign-note")
        world.allowed.add(("notes", "foreign-note"))
        with self.assertRaises(simulation.FixtureBoundary):
            world.grant("note_create", "notes", "foreign-note", {"content": "bad"})

    def test_five_catalog_corrections_preserve_old_classifications_and_require_boundary_proof(self):
        amendment = self.catalog["amendments"][-1]
        rows = amendment["classificationChanges"]
        self.assertEqual({"G03-v5", "G04-v5", "G06-v5", "G11-v5", "G17-v5"}, {r["variantId"] for r in rows})
        self.assertTrue(all(r["priorExpected"]["outcomeClass"] == "safe_boundary" for r in rows))
        broken = deepcopy(self.catalog)
        next(c for c in broken["cases"] if c["id"] == "G06")["variants"][4]["expected"]["facts"].pop("boundaryPreserved")
        with self.assertRaises(ValueError):
            catalog_api.validate(broken)


if __name__ == "__main__":
    unittest.main()
