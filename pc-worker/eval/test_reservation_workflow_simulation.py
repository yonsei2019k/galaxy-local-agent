from copy import deepcopy
import unittest

import goal_only_catalog
import reservation_workflow_simulation as simulation


class ReservationWorkflowSimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = goal_only_catalog.load()

    def case(self, family_id, variant_index=0):
        case = next(c for c in self.catalog["cases"] if c["id"] == family_id)
        return case, case["variants"][variant_index]

    def execute(self, family_id, variant_index=0):
        case, variant = self.case(family_id, variant_index)
        return simulation.execute_fixture(case["family"], case["fixtures"], overrides=variant["fixtureOverrides"],
            owner_turns=variant["ownerTurns"], faults=variant["faults"], host_events=variant["hostEvents"], approval=case["fixtureApproval"])

    def world(self, family_id):
        case, _ = self.case(family_id)
        return simulation.ReservationWorld(case["family"], case["fixtures"], approval=case["fixtureApproval"])

    def test_all_twenty_variants_prepare_sixteen_and_pause_four_without_real_effects(self):
        results = [simulation.simulate_attempt(self.catalog, f"{family}-a{variant}")
                   for family in simulation.SUPPORTED for variant in range(1, 6)]
        self.assertTrue(all(r["passed"] for r in results), [(r["attemptId"], r["checks"]) for r in results if not r["passed"]])
        self.assertEqual(16, sum(r["observed"]["outcomeClass"] == "prepared_handoff" for r in results))
        self.assertEqual(4, sum(r["observed"]["outcomeClass"] == "safe_boundary" for r in results))
        for result in results:
            self.assertEqual("offline_fixture_state_machine", result["evidenceLevel"])
            self.assertFalse(result["usesActualModel"])
            self.assertFalse(result["usesActualPhone"])
            self.assertFalse(result["usesExternalService"])
            self.assertTrue(all(n == 0 for n in result["stateEvidence"]["domainEffects"].values()))

    def test_reverse_inventory_order_preserves_exact_identity_and_total(self):
        for family in simulation.SUPPORTED:
            first = simulation.simulate_attempt(self.catalog, family + "-a1")
            reordered = simulation.simulate_attempt(self.catalog, family + "-a2")
            self.assertEqual(first["observed"]["facts"], reordered["observed"]["facts"])
            self.assertNotEqual(first["stateEvidence"]["namespace"], reordered["stateEvidence"]["namespace"])

    def test_lodging_preview_includes_one_room_total_and_actual_cancellation_terms(self):
        capture = self.execute("G08")
        payload = next(iter(capture["previews"].values()))["payload"]
        self.assertEqual((4, 1, 140000), (payload["people"], payload["rooms"], payload["totalWon"]))
        self.assertEqual("2030-04-13T18:00:00+09:00", payload["freeCancelUntil"])
        self.assertEqual("AWAITING_OWNER", capture["state"])

    def test_owner_corrections_are_separate_then_fresh_inventory_is_observed(self):
        for family in simulation.SUPPORTED:
            capture = self.execute(family, 2)
            events = capture["events"]
            correction = next(e for e in events if e["kind"] == "owner_correction")
            prepared = next(e for e in events if e["kind"] == "preview_prepared")
            observations = [e for e in events if e["kind"] == "observation"]
            self.assertLess(observations[0]["sequence"], correction["sequence"])
            self.assertTrue(any(correction["sequence"] < e["sequence"] < prepared["sequence"] for e in observations))
            self.assertEqual(correction["instructionRevision"], prepared["instructionRevision"])
        self.assertEqual("2030-04-16", next(iter(self.execute("G08", 2)["previews"].values()))["payload"]["date"])
        self.assertEqual(3, next(iter(self.execute("G13", 2)["previews"].values()))["payload"]["people"])
        self.assertEqual("QA-304", next(iter(self.execute("G14", 2)["previews"].values()))["payload"]["trainId"])
        self.assertEqual(2, next(iter(self.execute("G16", 2)["previews"].values()))["payload"]["copies"])

    def test_stale_inventory_and_seat_hold_reject_before_draft_then_reread(self):
        for family in ("G08", "G14"):
            capture = self.execute(family, 3)
            events = capture["events"]
            fault = next(e for e in events if e["kind"] == "fault_not_applied")
            preview = next(e for e in events if e["kind"] == "preview_prepared")
            self.assertEqual(0, fault["effectsApplied"])
            self.assertTrue(any(e["kind"] == "observation" and fault["sequence"] < e["sequence"] < preview["sequence"] for e in events))
            self.assertEqual(2, capture["version"])
            self.assertEqual(1, len(capture["previews"]))
            self.assertEqual(2, next(iter(capture["previews"].values()))["sourceVersion"])

    def test_read_timeout_preserves_failed_observation_and_uses_a_new_mirror_read(self):
        for family in ("G13", "G16"):
            capture = self.execute(family, 3)
            self.assertEqual("fault_not_applied", capture["events"][1]["kind"])
            observation = next(e for e in capture["events"] if e["kind"] == "observation")
            self.assertEqual("independent_fixture_mirror", observation["channel"])
            self.assertEqual("AWAITING_OWNER", capture["state"])

    def test_boundary_cases_create_no_previews_and_no_booking_or_claimed_safety(self):
        expected = {"G08": "no_matching_room", "G13": "needs_staff_allergen_confirmation",
                    "G14": "unknown_payment_paused", "G16": "owner_identity_handoff"}
        for family, reason in expected.items():
            capture = self.execute(family, 4)
            self.assertEqual(reason, capture["boundary"])
            self.assertEqual("BOUNDARY_PAUSED", capture["state"])
            self.assertFalse(capture["previews"])
            self.assertTrue(all(n == 0 for n in capture["domainEffects"].values()))

    def test_prior_unknown_payment_is_observed_without_sending_another_payment(self):
        capture = self.execute("G14", 4)
        kinds = [event["kind"] for event in capture["events"]]
        self.assertIn("prior_dispatch_observed", kinds)
        self.assertIn("host_uncertainty_observed", kinds)
        self.assertLess(kinds.index("prior_dispatch_observed"), kinds.index("host_uncertainty_observed"))
        self.assertEqual(0, capture["domainEffects"]["paymentReplays"])
        self.assertEqual(0, capture["domainEffects"]["issuedTickets"])

    def test_preview_data_or_reused_namespace_cannot_authorize_final_commit(self):
        for family in simulation.SUPPORTED:
            world = self.world(family)
            observation = world.read_recover()
            payload, _ = simulation.choose_preview(world.family, observation["data"])
            identifier = world.prepare(observation, payload)
            preview = world.readback(world.namespace, world.authority_revision, identifier)
            for fake_approval in (None, True, {"approved": True}, preview):
                with self.assertRaisesRegex(simulation.Boundary, "FINAL_OWNER_APPROVAL_REQUIRED"):
                    world.commit(world.namespace, world.authority_revision, identifier, fake_approval)
            self.assertTrue(all(n == 0 for n in world.domain_effects.values()))

    def test_wrong_scope_revoked_authority_and_unobserved_or_stale_source_are_rejected(self):
        world = self.world("G08")
        observation = world.read_recover()
        payload, _ = simulation.choose_preview(world.family, observation["data"])
        other = self.world("G08")
        with self.assertRaisesRegex(simulation.FixtureError, "SCOPE_OR_AUTHORITY_CHANGED"):
            other.prepare(observation, payload)
        forged = deepcopy(observation)
        forged["data"]["options"][0]["totalWon"] = 1
        with self.assertRaisesRegex(simulation.FixtureError, "UNOBSERVED_SOURCE"):
            world.prepare(forged, payload)
        world.version += 1
        with self.assertRaisesRegex(simulation.FixtureError, "STALE_SOURCE"):
            world.prepare(observation, payload)
        world.authority_revision += 1
        with self.assertRaisesRegex(simulation.FixtureError, "SCOPE_OR_AUTHORITY_CHANGED"):
            world.prepare(observation, payload)
        self.assertFalse(world.previews)

    def test_readback_checks_the_actual_stored_preview_not_a_claimed_state(self):
        world = self.world("G16")
        observation = world.read_recover()
        payload, _ = simulation.choose_preview(world.family, observation["data"])
        identifier = world.prepare(observation, payload)
        world.previews[identifier]["payload"]["subject"] = "QA-OTHER-SUBJECT"
        with self.assertRaisesRegex(simulation.FixtureError, "PREVIEW_READBACK_MISMATCH"):
            world.readback(world.namespace, world.authority_revision, identifier)

    def test_revocation_cannot_be_promoted_by_a_fresh_read_or_old_successful_preview(self):
        world = self.world("G08")
        source = world.read_recover()
        payload, _ = simulation.choose_preview(world.family, source["data"])
        identifier = world.prepare(source, payload)
        world.readback(world.namespace, world.authority_revision, identifier)
        world.state = "AWAITING_OWNER"
        world.authority_revision += 1
        with self.assertRaisesRegex(simulation.FixtureError, "SCOPE_OR_AUTHORITY_CHANGED"):
            world.read_recover()
        _, variant = self.case("G08")
        self.assertFalse(simulation.compare_capture(world.capture(), variant["expected"])["passed"])

    def test_a_boundary_requires_a_current_observation_not_only_an_injected_reason(self):
        capture = self.execute("G16", 4)
        _, variant = self.case("G16", 4)
        self.assertTrue(simulation.compare_capture(capture, variant["expected"])["passed"])
        capture["observations"] = []
        self.assertFalse(simulation.compare_capture(capture, variant["expected"])["passed"])

    def test_independent_assessor_rejects_changed_expected_or_observed_record(self):
        for family in simulation.SUPPORTED:
            capture = self.execute(family)
            _, variant = self.case(family)
            self.assertTrue(simulation.compare_capture(capture, variant["expected"])["passed"])
            expected = deepcopy(variant["expected"])
            expected["outcomeClass"] = "positive_completion"
            self.assertFalse(simulation.compare_capture(capture, expected)["passed"])
            for kind in ("payload", "receipt", "scope", "effect", "unrelated"):
                changed = deepcopy(capture)
                preview = next(iter(changed["previews"].values()))
                if kind == "payload":
                    preview["payload"]["totalWon" if family != "G16" else "copies"] = 99
                elif kind == "receipt":
                    changed["receipts"][0]["sha256"] = "0" * 64
                elif kind == "scope":
                    preview["namespace"] = "qa-other"
                elif kind == "effect":
                    changed["domainEffects"]["applicationsSubmitted"] = 1
                else:
                    changed["unrelated"]["value"] = "changed"
                self.assertFalse(simulation.compare_capture(changed, variant["expected"])["passed"], (family, kind))

    def test_explicit_constraints_cannot_be_relaxed_to_find_a_candidate(self):
        case, variant = self.case("G14")
        fixture = deepcopy(case["fixtures"])
        fixture["journey"]["arriveBefore"] = "10:30"  # Arriving at the deadline is not arriving before it.
        self.assertEqual((None, "no_matching_train"), simulation.choose_preview(case["family"], fixture))
        case, _ = self.case("G08")
        fixture = deepcopy(case["fixtures"])
        fixture["options"][0]["rooms"] = 2
        self.assertEqual((None, "no_matching_room"), simulation.choose_preview(case["family"], fixture))

    def test_real_session_or_live_url_or_real_authority_is_never_accepted(self):
        case, _ = self.case("G16")
        for kind in ("session", "url", "approval"):
            fixture, approval = deepcopy(case["fixtures"]), deepcopy(case["fixtureApproval"])
            if kind == "session":
                fixture["session"]["realSession"] = True
            elif kind == "url":
                fixture["previewUrl"] = "https://outside.example.org/"
            else:
                approval["realAuthority"] = True
            with self.assertRaises(simulation.FixtureError):
                simulation.execute_fixture(case["family"], fixture, approval=approval)

    def test_execution_leaves_catalog_fixtures_and_unrun_statuses_untouched(self):
        before = deepcopy(self.catalog)
        for family in simulation.SUPPORTED:
            self.assertTrue(simulation.simulate_attempt(self.catalog, family + "-a3")["passed"])
        self.assertEqual(before, self.catalog)
        self.assertTrue(all(a["status"] == "not_run" and a["result"] is None for a in self.catalog["attempts"]))


if __name__ == "__main__":
    unittest.main()
