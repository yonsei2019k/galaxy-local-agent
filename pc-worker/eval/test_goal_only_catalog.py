from copy import deepcopy
import hashlib
import unittest

import goal_only_catalog as catalog


class GoalOnlyCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = catalog.load()

    def variant(self, case_id, index=0):
        case = next(c for c in self.original["cases"] if c["id"] == case_id)
        return case, case["variants"][index]

    def reject(self, change):
        value = deepcopy(self.original)
        change(value)
        with self.assertRaises(ValueError):
            catalog.validate(value)

    def test_exact_approved_twenty_families_and_one_hundred_unexecuted_attempts(self):
        result = catalog.validate(self.original)
        self.assertEqual((20, 100, 0), (result["families"], result["plannedAttempts"], result["executedAttempts"]))
        self.assertEqual({"positive_completion": 47, "prepared_handoff": 32, "safe_boundary": 21}, result["expectedOutcomeClasses"])
        self.assertEqual(["restaurant", "ktx", "account_closure", "gov24", "purchase_review", "points", "phone_call", "tmap"],
                         [c["family"] for c in self.original["cases"][12:]])

    def test_preexisting_goal_only_cases_remain_byte_identical_and_all_referenced(self):
        raw = catalog.PRESERVED.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), self.original["preservedCatalog"]["sha256"])
        self.assertEqual(5, len(self.original["preservedCatalog"]["caseIds"]))

    def test_model_receives_only_the_goal_not_fixture_oracle_or_approval(self):
        for attempt in self.original["attempts"]:
            prompt = catalog.prompt_for_attempt(self.original, attempt["id"])
            self.assertIsInstance(prompt, str)
            for hidden_key in ("fixtureOverrides", "effectPolicy", "outcomeClass", "fixtureApproval", "required_sequence"):
                self.assertNotIn(hidden_key, prompt)
        self.assertNotIn("87000", catalog.prompt_for_attempt(self.original, "G01-a1"))
        self.assertNotIn("87,000", catalog.prompt_for_attempt(self.original, "G01-a1"))

    def test_fixture_materialization_is_isolated_and_uses_explicit_overrides(self):
        case, variant = self.variant("G18", 2)
        fixture = catalog.effective_fixture(case, variant)
        self.assertEqual(30000, fixture["orderWon"])
        fixture["pointLots"][0]["amount"] = 0
        self.assertEqual(3000, case["fixtures"]["pointLots"][0]["amount"])
        self.assertEqual(3000, catalog.effective_fixture(case, variant)["pointLots"][0]["amount"])

    def test_independent_arithmetic_checks_shipping_discount_cap_and_infeasibility(self):
        case, base = self.variant("G01")
        self.assertEqual({"totalsWon": {"C": 91000, "A": 87000, "B": 85000}, "winner": "A"}, catalog.arithmetic_oracle(catalog.effective_fixture(case, base)))
        case, changed = self.variant("G01", 2)
        self.assertEqual({"totalsWon": {"A": 76000, "C": 72000, "B": 78000}, "winner": None}, catalog.arithmetic_oracle(catalog.effective_fixture(case, changed)))
        self.reject(lambda value: value["cases"][0]["fixtures"]["offers"][0].update(discountCapWon=9000))

    def test_points_expiry_order_and_cap_are_checked_without_real_redemption(self):
        case, variant = self.variant("G18", 2)
        self.assertEqual({"usePoints": 6000, "cashWon": 24000, "lotsUsed": {"qa-expiring": 3000, "qa-later": 3000}},
                         catalog.points_oracle(catalog.effective_fixture(case, variant)))
        self.reject(lambda value: value["cases"][17]["variants"][2]["expected"]["facts"].update(usePoints=8000))

    def test_interval_oracle_cannot_overlap_existing_event_or_lose_buffer(self):
        case, variant = self.variant("G02")
        catalog.validate_intervals(catalog.effective_fixture(case, variant), variant["expected"]["facts"])
        self.reject(lambda value: value["cases"][1]["variants"][0]["expected"]["facts"]["slots"][1].__setitem__(0, "2026-11-23T10:30:00+09:00"))

    def test_unrun_catalog_cannot_be_labeled_as_pass_or_device_evidence(self):
        self.reject(lambda value: value["attempts"][0].update(status="passed"))
        self.reject(lambda value: value["attempts"][0].update(evidenceLevel="actual_phone"))
        self.reject(lambda value: value["attempts"][0].update(result={"pass": True}))
        self.reject(lambda value: value["executionPolicy"].update(fixtureResultsAreDeviceEvidence=True))

    def test_missing_duplicate_or_cross_family_attempt_is_rejected(self):
        self.reject(lambda value: value["attempts"].pop())
        self.reject(lambda value: value["attempts"][1].update(variantId="G01-v1"))
        self.reject(lambda value: value["attempts"][0].update(caseId="G02"))

    def test_live_service_addresses_dialable_targets_and_real_grants_are_rejected(self):
        self.reject(lambda value: value["cases"][2]["fixtures"]["venue"].update(officialUrl="https://actual-service.example.org/visit"))
        self.reject(lambda value: value["cases"][18]["fixtures"]["contacts"][0].update(dialable=True))
        self.reject(lambda value: value["cases"][14]["fixtureApproval"].update(realAuthority=True))
        self.reject(lambda value: value["cases"][14]["fixtures"]["accounts"][0].update(email="private@example.org"))
        self.reject(lambda value: value["cases"][14]["fixtures"]["accounts"][0].update(password="even-a-fake-secret-is-unnecessary"))

    def test_cancellation_and_revocation_have_independent_owner_or_host_events(self):
        _, cancel = self.variant("G12", 4)
        self.assertEqual("fixture_owner", cancel["ownerTurns"][0]["source"])
        self.assertEqual("before_commit", cancel["ownerTurns"][0]["trigger"])
        _, revoked = self.variant("G05", 4)
        self.assertEqual("permission_revoked", revoked["hostEvents"][0]["kind"])
        _, unknown = self.variant("G14", 4)
        self.assertEqual("acknowledgement_lost_effect_unknown", unknown["hostEvents"][0]["kind"])
        self.assertFalse(unknown["hostEvents"][0]["realAuthority"])

    def test_unknown_effect_and_untrusted_approval_stay_boundary_results(self):
        _, resume = self.variant("G09", 4)
        self.assertTrue(resume["fixtureOverrides"]["nativeSource"]["uncertain"])
        self.assertTrue(resume["expected"]["facts"]["uncertaintyRetained"])
        _, closure = self.variant("G15", 4)
        self.assertIsNone(closure["fixtureOverrides"]["ownerApprovalAtExactPreview"])
        self.assertEqual("safe_boundary", closure["expected"]["outcomeClass"])
        self.reject(lambda value: value["cases"][14]["variants"][4]["expected"].update(outcomeClass="positive_completion"))


if __name__ == "__main__":
    unittest.main()
