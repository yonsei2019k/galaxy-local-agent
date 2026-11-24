import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "gen-icons.py"
spec = importlib.util.spec_from_file_location("gen_icons", SCRIPT)
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

ANDROID = "{http://schemas.android.com/apk/res/android}"
ICONS = json.loads(gen.ICONS_SRC.read_text(encoding="utf-8"))
ILLS = json.loads(gen.ILLS_SRC.read_text(encoding="utf-8"))
# Names the web UI used before the duotone redraw; they must keep working.
LEGACY = ("compass archive person search menu plus close chat settings image tools folder history native arrow "
          "arrowDown back group refresh stop steer microphone clip file down check clock activity pause alert "
          "shield copy open robot chip").split()
ADDED = "edit calendar globe phone mail sparkle home heart bell lock download link chevronRight trash".split()
SCENES = "emptyChat welcome connections calendar web files photos computer archive contacts settings error".split()


class SourceTest(unittest.TestCase):
    def test_icon_and_scene_names(self):
        for name in LEGACY + ADDED:
            self.assertIn(name, ICONS["icons"])
        self.assertEqual(sorted(SCENES), sorted(ILLS["illustrations"]))

    def test_every_icon_has_a_stroked_line_and_no_per_icon_stroke_style(self):
        for name, spec_ in ICONS["icons"].items():
            lines = [el for el in spec_["line"] if "dot" not in el]
            self.assertTrue(lines, name)
            for el in spec_["line"] + spec_.get("fill", []):
                self.assertFalse({"stroke", "sw", "strokeWidth", "opacity"} & set(el), name)

    def test_uniform_stroke_params(self):
        meta, _ = gen.load_icons(ICONS)
        self.assertEqual(meta["stroke"], 1.8)
        self.assertTrue(0 < meta["softOpacity"] < 0.5)

    def test_icon_points_inside_viewbox(self):
        lo, hi = ICONS["meta"]["liveArea"]
        self.assertTrue(0 <= lo and hi <= 24)
        _, icons = gen.load_icons(ICONS)
        for name, ic in icons.items():
            for d in filter(None, [ic["soft"], ic["line"], ic["dots"]] + [a["d"] for a in ic["accents"]]):
                for x, y in gen.sample_path(d):
                    # stroke half-width must still land inside the 24 grid
                    self.assertTrue(0.9 <= x <= 23.1 and 0.9 <= y <= 23.1, (name, x, y))

    def test_illustration_points_inside_viewbox_and_palette_only(self):
        ills = gen.load_illustrations(ILLS)
        palette = {v.upper() for v in ILLS["meta"]["palette"].values()}
        for name, shapes in ills.items():
            self.assertLessEqual(len(shapes), 40, name)
            for s in shapes:
                for key in ("fill", "stroke"):
                    if key in s:
                        self.assertIn(s[key], palette, name)
                for x, y in gen.sample_path(s["d"]):
                    self.assertTrue(0 <= x <= 160 and 0 <= y <= 120, (name, x, y))

    def test_rejects_bad_sources(self):
        base = json.loads(json.dumps(ICONS))
        bad_stroke = json.loads(json.dumps(base))
        bad_stroke["meta"]["stroke"] = 2
        with self.assertRaises(ValueError):
            gen.load_icons(bad_stroke)
        outside = json.loads(json.dumps(base))
        outside["icons"]["plus"] = {"line": [{"d": "M0 0 L24 24"}]}
        with self.assertRaises(ValueError):
            gen.load_icons(outside)
        no_line = json.loads(json.dumps(base))
        no_line["icons"]["plus"] = {"fill": [{"circle": [12, 12, 4]}], "line": [{"dot": [12, 12, 1]}]}
        with self.assertRaises(ValueError):
            gen.load_icons(no_line)
        styled = json.loads(json.dumps(base))
        styled["icons"]["plus"]["line"][0]["sw"] = 3
        with self.assertRaises(ValueError):
            gen.load_icons(styled)
        ills = json.loads(json.dumps(ILLS))
        ills["illustrations"]["error"]["shapes"][1]["fill"] = "#123456"
        with self.assertRaises(ValueError):
            gen.load_illustrations(ills)
        with self.assertRaises(ValueError):
            gen.parse_path("M1 1 L2")
        with self.assertRaises(ValueError):
            gen.parse_path("M1 1 A2 2 0 2 1 3 3")


class OutputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.meta, cls.icons, cls.ills, cls.outputs = gen.build()

    def test_check_mode_reports_committed_outputs_current(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_generation_is_deterministic(self):
        again = gen.build()[3]
        self.assertEqual(self.outputs, again)

    def test_icon_drawables_are_tintable_duotone(self):
        for name in self.icons:
            path = gen.DRAWABLE / f"zi_{gen.snake(name)}.xml"
            root = ET.fromstring(self.outputs[path])
            self.assertEqual(root.tag, "vector")
            self.assertEqual(root.get(ANDROID + "viewportWidth"), "24")
            self.assertEqual(root.get(ANDROID + "viewportHeight"), "24")
            paths = root.findall("path")
            strokes = [p for p in paths if p.get(ANDROID + "strokeColor")]
            self.assertEqual(len(strokes), 1, name)
            self.assertEqual(strokes[0].get(ANDROID + "strokeWidth"), "1.8")
            self.assertEqual(strokes[0].get(ANDROID + "strokeLineCap"), "round")
            self.assertEqual(strokes[0].get(ANDROID + "strokeLineJoin"), "round")
            for p in paths:
                for attr in ("fillColor", "strokeColor"):
                    if p.get(ANDROID + attr):
                        self.assertEqual(p.get(ANDROID + attr), "@android:color/black", name)
                if p.get(ANDROID + "fillAlpha"):
                    self.assertEqual(float(p.get(ANDROID + "fillAlpha")), self.meta["softOpacity"])
                gen.parse_path(p.get(ANDROID + "pathData"))

    def test_illustration_drawables_use_fixed_colors(self):
        for name in self.ills:
            path = gen.DRAWABLE / f"zi_ill_{gen.snake(name)}.xml"
            root = ET.fromstring(self.outputs[path])
            self.assertEqual(root.get(ANDROID + "viewportWidth"), "160")
            self.assertEqual(root.get(ANDROID + "viewportHeight"), "120")
            for p in root.findall("path"):
                for attr in ("fillColor", "strokeColor"):
                    v = p.get(ANDROID + attr)
                    if v:
                        self.assertRegex(v, r"^#[0-9A-F]{6}$")

    def test_typescript_lists_every_icon(self):
        ts = self.outputs[gen.TS_OUT]
        for name in self.icons:
            self.assertRegex(ts, rf"\n  {name}: \{{ soft: ")
        for name in self.ills:
            self.assertIn(f"\n  {name}: [", ts)
        self.assertIn("export const ICON_STROKE = 1.8", ts)

    def test_drawable_names_are_valid_resource_names(self):
        for path in self.outputs:
            if path.parent == gen.DRAWABLE:
                self.assertRegex(path.stem, r"^zi_[a-z0-9_]+$")


if __name__ == "__main__":
    unittest.main()
