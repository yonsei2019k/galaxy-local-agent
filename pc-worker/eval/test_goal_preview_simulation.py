from copy import deepcopy
import unittest

from goal_only_catalog import CATALOG, load
from goal_preview_simulation import FAMILIES, simulate_attempt


class GoalPreviewSimulationTests(unittest.TestCase):
    def test_all_owned_fixtures_execute(self):
        catalog = load(CATALOG)
        supported = {case["id"] for case in catalog["cases"] if case["family"] in FAMILIES}
        attempts = [attempt for attempt in catalog["attempts"] if attempt["caseId"] in supported]
        self.assertEqual(len(attempts), 20)
        for attempt in attempts:
            with self.subTest(attempt=attempt["id"]):
                result = simulate_attempt(catalog, attempt["id"])
                self.assertTrue(result["passed"], result["checks"])
                self.assertFalse(result["usesExternalService"])

    def test_wrong_expected_delivery_does_not_change_the_observed_call_result(self):
        catalog = load(CATALOG)
        before = simulate_attempt(catalog, "G19-a1")
        edited = deepcopy(catalog)
        case = next(row for row in edited["cases"] if row["id"] == "G19")
        case["variants"][0]["expected"]["facts"]["deliveryAt"] = "wrong"
        after = simulate_attempt(edited, "G19-a1")
        self.assertEqual(before["observed"], after["observed"])
        self.assertFalse(after["passed"])

    def test_logical_phone_target_cannot_be_replaced_with_a_dialable_contact(self):
        catalog = load(CATALOG)
        case = next(row for row in catalog["cases"] if row["id"] == "G19")
        case["fixtures"]["contacts"][0]["dialable"] = True
        with self.assertRaises(ValueError):
            simulate_attempt(catalog, "G19-a1")
