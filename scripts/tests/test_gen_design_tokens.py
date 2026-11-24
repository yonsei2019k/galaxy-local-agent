import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "gen-design-tokens.py"
spec = importlib.util.spec_from_file_location("gen_design_tokens", SCRIPT)
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

SAMPLE = {
    "color": {"bg": "#F7F8FC", "cobalt": "#3355F0", "cobalt-text": "#2E4BE0"},
    "space": {"1": 4, "2": 8},
    "fs": {"caption": 12, "body": 15},
    "radius": {"card": 16, "pill": 999},
}


class GenDesignTokensTest(unittest.TestCase):
    def test_generates_css_and_kotlin_from_json(self):
        css, kt = gen.generate(SAMPLE)
        self.assertIn("--color-cobalt: #3355F0;", css)
        self.assertIn("--space-1: 4px;", css)
        self.assertIn("--fs-caption: 12px;", css)
        self.assertIn("--radius-pill: 999px;", css)
        self.assertIn("val Cobalt = Color(0xFF3355F0)", kt)
        self.assertIn("val CobaltText = Color(0xFF2E4BE0)", kt)
        self.assertIn("val Space1 = 4.dp", kt)
        self.assertIn("val FsCaption = 12.sp", kt)
        self.assertIn("val RadiusCard = 16.dp", kt)

    def test_rejects_bad_hex(self):
        bad = {**SAMPLE, "color": {"cobalt": "#33F"}}
        with self.assertRaises(ValueError):
            gen.generate(bad)

    def test_output_is_stable(self):
        self.assertEqual(gen.generate(SAMPLE), gen.generate(SAMPLE))
        self.assertEqual(
            [s.encode() for s in gen.generate(SAMPLE)],
            [s.encode() for s in gen.generate(SAMPLE)],
        )


if __name__ == "__main__":
    unittest.main()
