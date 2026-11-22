import unittest
from model_orchestration import LUNA, TERRA, SOL, agent_profile, briefing_profile, claude_profile, model_flags, skill_body


class ModelOrchestrationTests(unittest.TestCase):
    catalog = {LUNA: {"low"}, TERRA: {"medium"}, SOL: {"medium", "high"}}

    def test_fixed_short_batch_and_settled_read_use_luna(self):
        self.assertEqual((LUNA, "low"), briefing_profile(self.catalog, 4, 2))
        self.assertEqual((SOL, "medium"), briefing_profile(self.catalog, 4, 5))
        terminal = {"readObservationsComplete": True, "observedReads": [{}]}
        self.assertEqual((LUNA, "low"), agent_profile(self.catalog, recovery=terminal))
        self.assertEqual((SOL, "medium"), agent_profile(self.catalog, recovery={**terminal, "observationsOmitted": True}))

    def test_visual_scope_uses_terra_and_mixed_scope_uses_sol(self):
        self.assertEqual((TERRA, "medium"), agent_profile(self.catalog, {"plan_task", "read_screen", "screen_action"}))
        self.assertEqual((SOL, "medium"), agent_profile(self.catalog, {"read_screen", "patch_note"}))
        self.assertEqual((SOL, "high"), agent_profile(self.catalog, recovery={"acceptedTools": ["search_memory", "search_notes"]}))

    def test_claude_profiles_follow_verified_work(self):
        self.assertEqual(("sonnet", "medium"), claude_profile({"read_screen", "search_notes"}))
        self.assertEqual(("sonnet", "medium"), claude_profile(recovery={"acceptedTools": ["read_screen"]}))
        self.assertEqual(("sonnet", "high"), claude_profile(recovery={"acceptedTools": ["read_screen", "search_notes"]}))
        self.assertEqual(("opus", "high"), claude_profile(recovery={"acceptedTools": ["read_screen", "search_notes", "search_memory"]}))
        settled = {"readObservationsComplete": True, "observedReads": [{}]}
        self.assertEqual(("haiku", None), claude_profile(recovery=settled))
        self.assertEqual(("sonnet", "medium"), claude_profile(recovery={**settled, "observationsOmitted": True}))

    def test_profiles_require_catalog_effort_and_skill_is_loaded_from_package(self):
        self.assertIsNone(agent_profile({TERRA: {"high"}}))
        self.assertEqual((SOL, "medium"), briefing_profile({SOL: {"medium"}}, 1, 0))
        self.assertEqual(["--model", LUNA, "-c", 'model_reasoning_effort="low"'], model_flags((LUNA, "low")))
        self.assertIn("Never repeat an action with an unknown effect", skill_body())
