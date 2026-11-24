import importlib.util
import json
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "gen-design-tokens.py"
spec = importlib.util.spec_from_file_location("gen_design_tokens", SCRIPT)
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

SAMPLE = {
    "color": {"bg": "#F7F8FC", "cobalt": "#3355F0", "cobalt-text": "#2E4BE0"},
    "space": {"1": 4, "2": 8, "section": 24},
    "layout": {"gutter": 16},
    "fs": {"caption": 12, "body": 15, "hero": 28},
    "radius": {"card": 16, "pill": 999},
    "control": {"control-height": 52, "touch-min": 44},
    "shadow": {
        "card": [
            {"y": 1, "blur": 2, "rgb": "#171B33", "alpha": 0.04},
            {"y": 6, "blur": 20, "rgb": "#171B33", "alpha": 0.05},
        ],
        "sheet": [{"y": -8, "blur": 32, "rgb": "#171B33", "alpha": 0.12}],
    },
    "font": {"ui": "'SUIT Variable', system-ui, sans-serif"},
    "fw": {"bold": 700},
    "lh": {"body": 1.6},
    "ls": {"tight": -0.01},
}


class GenDesignTokensTest(unittest.TestCase):
    def test_generates_css_and_kotlin_from_json(self):
        css, kt = gen.generate(SAMPLE)
        self.assertIn("--color-cobalt: #3355F0;", css)
        self.assertIn("--space-1: 4px;", css)
        self.assertIn("--space-section: 24px;", css)
        self.assertIn("--gutter: 16px;", css)
        self.assertIn("--fs-hero: 28px;", css)
        self.assertIn("--radius-pill: 999px;", css)
        self.assertIn("--control-height: 52px;", css)
        self.assertIn("--touch-min: 44px;", css)
        self.assertIn("val Cobalt = Color(0xFF3355F0)", kt)
        self.assertIn("val CobaltText = Color(0xFF2E4BE0)", kt)
        self.assertIn("val Space1 = 4.dp", kt)
        self.assertIn("val SpaceSection = 24.dp", kt)
        self.assertIn("val Gutter = 16.dp", kt)
        self.assertIn("val FsCaption = 12.sp", kt)
        self.assertIn("val RadiusCard = 16.dp", kt)
        self.assertIn("val ControlHeight = 52.dp", kt)
        self.assertIn("val TouchMin = 44.dp", kt)

    def test_shadow_font_weight_line_height_letter_spacing(self):
        css, kt = gen.generate(SAMPLE)
        self.assertIn("--shadow-card: 0 1px 2px rgba(23,27,51,0.04), 0 6px 20px rgba(23,27,51,0.05);", css)
        self.assertIn("--shadow-sheet: 0 -8px 32px rgba(23,27,51,0.12);", css)
        self.assertIn("--font-ui: 'SUIT Variable', system-ui, sans-serif;", css)
        self.assertIn("--fw-bold: 700;", css)
        self.assertIn("--lh-body: 1.6;", css)
        self.assertIn("--ls-tight: -0.01em;", css)
        self.assertIn("val ShadowCard = listOf(ZipShadow(1.dp, 2.dp, Color(0x0A171B33)), ZipShadow(6.dp, 20.dp, Color(0x0D171B33)))", kt)
        self.assertIn("val ShadowSheet = listOf(ZipShadow(-8.dp, 32.dp, Color(0x1F171B33)))", kt)
        self.assertIn("const val FwBold = 700", kt)
        self.assertIn("const val LhBody = 1.6f", kt)
        self.assertIn("const val LsTight = -0.01f", kt)

    def test_rejects_bad_hex(self):
        bad = {**SAMPLE, "color": {"cobalt": "#33F"}}
        with self.assertRaises(ValueError):
            gen.generate(bad)

    def test_rejects_unknown_top_level_group(self):
        with self.assertRaisesRegex(ValueError, "unknown token group"):
            gen.generate({**SAMPLE, "colour": {"x": "#FFFFFF"}})

    def test_rejects_bad_shadow_font_and_weight(self):
        with self.assertRaises(ValueError):
            gen.generate({"shadow": {"card": [{"y": 1, "blur": -2, "rgb": "#171B33", "alpha": 0.1}]}})
        with self.assertRaises(ValueError):
            gen.generate({"shadow": {"card": [{"y": 1, "blur": 2, "rgb": "#171B33", "alpha": 2}]}})
        with self.assertRaises(ValueError):
            gen.generate({"shadow": {"card": []}})
        with self.assertRaises(ValueError):
            gen.generate({"font": {"ui": "x; } body { color: red"}})
        with self.assertRaises(ValueError):
            gen.generate({"fw": {"bold": 70}})
        with self.assertRaises(ValueError):
            gen.generate({"control": {"touch-min": -1}})

    def test_output_is_stable(self):
        self.assertEqual(gen.generate(SAMPLE), gen.generate(SAMPLE))
        self.assertEqual(
            [s.encode() for s in gen.generate(SAMPLE)],
            [s.encode() for s in gen.generate(SAMPLE)],
        )

    def test_committed_tokens_have_2_0_values(self):
        tokens = json.loads(gen.SOURCE.read_text(encoding="utf-8"))
        self.assertEqual(tokens["color"]["bg"], "#F3F4F8")
        for name in ("surface-sunken", "divider", "text-sub", "text-faint"):
            self.assertIn(name, tokens["color"])
        for group in ("shadow", "font", "fw", "lh", "ls", "control", "layout"):
            self.assertIn(group, tokens)

    def test_text_sub_passes_aa_on_surface_and_bg(self):
        def lum(h):
            c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
            c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
            return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

        def ratio(a, b):
            hi, lo = sorted((lum(a), lum(b)), reverse=True)
            return (hi + 0.05) / (lo + 0.05)

        colors = json.loads(gen.SOURCE.read_text(encoding="utf-8"))["color"]
        for bg in ("surface", "bg", "surface-sunken"):
            self.assertGreaterEqual(ratio(colors["text-sub"], colors[bg]), 4.5)
            self.assertGreaterEqual(ratio(colors["text-faint"], colors[bg]), 3.0)


class CheckModeTest(unittest.TestCase):
    def run_check(self, css_bytes, kt_bytes):
        with tempfile.TemporaryDirectory() as d:
            d = pathlib.Path(d)
            tokens = d / "tokens.json"
            tokens.write_text(json.dumps(SAMPLE), encoding="utf-8")
            css_out, kt_out = d / "tokens.css", d / "ZipHapTokens.kt"
            if css_bytes is not None:
                css_out.write_bytes(css_bytes)
            if kt_bytes is not None:
                kt_out.write_bytes(kt_bytes)
            saved = (gen.SOURCE, gen.CSS_OUT, gen.KT_OUT, gen.ROOT)
            gen.SOURCE, gen.CSS_OUT, gen.KT_OUT, gen.ROOT = tokens, css_out, kt_out, d
            try:
                return gen.main(["--check"])
            finally:
                gen.SOURCE, gen.CSS_OUT, gen.KT_OUT, gen.ROOT = saved

    def setUp(self):
        css, kt = gen.generate(SAMPLE)
        self.css, self.kt = css.encode(), kt.encode()

    def test_check_passes_when_current(self):
        self.assertEqual(self.run_check(self.css, self.kt), 0)

    def test_check_accepts_crlf_checkout(self):
        self.assertEqual(self.run_check(self.css.replace(b"\n", b"\r\n"), self.kt.replace(b"\n", b"\r\n")), 0)

    def test_check_fails_when_stale(self):
        self.assertEqual(self.run_check(self.css + b"/* edited */\n", self.kt), 1)

    def test_check_fails_when_missing(self):
        self.assertEqual(self.run_check(None, self.kt), 1)


if __name__ == "__main__":
    unittest.main()
