#!/usr/bin/env python3
"""Generate CSS, Compose and Android View resources from design/tokens.json.

Usage: python scripts/gen-design-tokens.py [--check]
--check exits 1 when the committed outputs differ from what the source would generate.
Unknown top-level groups are an error.
"""
import json
import math
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "tokens.json"
CSS_OUT = ROOT / "web-ui" / "src" / "tokens.css"
KT_OUT = ROOT / "app" / "src" / "main" / "java" / "com" / "user" / "localagent" / "ui" / "ZipHapTokens.kt"

COLORS_OUT = ROOT / "app" / "src" / "main" / "res" / "values" / "ziphap_view_colors.xml"

HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
NAME = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
FONT = re.compile(r"^[A-Za-z0-9 ',_-]+$")
HEADER = "Generated from design/tokens.json by scripts/gen-design-tokens.py. Do not edit."

# group -> (css prefix, css unit, kotlin unit) for non-negative integer groups.
INT_GROUPS = {
    "space": ("space-", "px", "dp"),
    "layout": ("", "px", "dp"),
    "fs": ("fs-", "px", "sp"),
    "radius": ("radius-", "px", "dp"),
    "control": ("", "px", "dp"),
}
KNOWN = {"color", "shadow", "font", "fw", "lh", "ls", "motion", "ease"} | set(INT_GROUPS)


def pascal(*parts):
    return "".join(p.capitalize() for part in parts for p in part.split("-"))


def check_name(name):
    if not isinstance(name, str) or not NAME.fullmatch(name):
        raise ValueError(f"bad token name: {name!r}")


def num(value):
    return format(value, "g")


def generate(tokens):
    unknown = sorted(set(tokens) - KNOWN)
    if unknown:
        raise ValueError(f"unknown token group: {', '.join(unknown)}")
    css = [f"/* {HEADER} */", ":root {"]
    kt = [
        f"// {HEADER}",
        "package com.user.localagent.ui",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.Dp",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.sp",
        "",
        "/** One layer of a drop shadow: vertical offset, blur radius and ARGB color (alpha baked in). */",
        "data class ZipShadow(val offsetY: Dp, val blur: Dp, val color: Color)",
        "",
        "object ZipHapTokens {",
    ]
    for name, value in tokens.get("color", {}).items():
        check_name(name)
        if not isinstance(value, str) or not HEX.match(value):
            raise ValueError(f"color {name!r} must be #RRGGBB, got {value!r}")
        hexv = value.upper()
        css.append(f"  --color-{name}: {hexv};")
        kt.append(f"    val {pascal(name)} = Color(0xFF{hexv[1:]})")
    for name, layers in tokens.get("shadow", {}).items():
        check_name(name)
        if not isinstance(layers, list) or not layers:
            raise ValueError(f"shadow {name!r} must be a non-empty list of layers")
        parts, kparts = [], []
        for layer in layers:
            if (not isinstance(layer, dict) or set(layer) != {"y", "blur", "rgb", "alpha"}
                    or type(layer["y"]) is not int or type(layer["blur"]) is not int or layer["blur"] < 0
                    or not isinstance(layer["rgb"], str) or not HEX.match(layer["rgb"])
                    or type(layer["alpha"]) not in (int, float) or not 0 <= layer["alpha"] <= 1):
                raise ValueError(f"invalid shadow layer in {name!r}: {layer!r}")
            rgb = layer["rgb"].upper()
            r, g, b = (int(rgb[i:i + 2], 16) for i in (1, 3, 5))
            parts.append(f"0 {layer['y']}px {layer['blur']}px rgba({r},{g},{b},{num(layer['alpha'])})")
            argb = f"{round(layer['alpha'] * 255):02X}{rgb[1:]}"
            kparts.append(f"ZipShadow({layer['y']}.dp, {layer['blur']}.dp, Color(0x{argb}))")
        css.append(f"  --shadow-{name}: {', '.join(parts)};")
        kt.append(f"    val {pascal('shadow', name)} = listOf({', '.join(kparts)})")
    for name, value in tokens.get("font", {}).items():
        check_name(name)
        if not isinstance(value, str) or not FONT.fullmatch(value):
            raise ValueError(f"invalid font stack: {name!r}")
        css.append(f"  --font-{name}: {value};")
        kt.append(f"    const val {pascal('font', name)} = \"{value}\"")
    for group, (prefix, unit_css, unit_kt) in INT_GROUPS.items():
        for name, value in tokens.get(group, {}).items():
            check_name(name)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ValueError(f"{group} {name!r} must be a non-negative integer, got {value!r}")
            css.append(f"  --{prefix}{name}: {value}{unit_css};")
            ident = pascal(prefix, name) if prefix else pascal(name)
            kt.append(f"    val {ident} = {value}.{unit_kt}")
    for name, value in tokens.get("fw", {}).items():
        check_name(name)
        if type(value) is not int or not 100 <= value <= 900:
            raise ValueError(f"fw {name!r} must be an integer 100-900, got {value!r}")
        css.append(f"  --fw-{name}: {value};")
        kt.append(f"    const val {pascal('fw', name)} = {value}")
    for group, unit in (("lh", ""), ("ls", "em")):
        for name, value in tokens.get(group, {}).items():
            check_name(name)
            if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 5:
                raise ValueError(f"{group} {name!r} must be a small finite number, got {value!r}")
            css.append(f"  --{group}-{name}: {num(value)}{unit};")
            kt.append(f"    const val {pascal(group, name)} = {num(value)}f")
    for name, ms in tokens.get("motion", {}).items():
        if not NAME.fullmatch(name) or type(ms) is not int or ms < 0:
            raise ValueError(f"invalid motion duration: {name!r}")
        css.append(f"  --motion-{name}: {ms}ms;")
        kt.append(f"    const val {pascal('motion', name, 'ms')} = {ms}")
    for name, points in tokens.get("ease", {}).items():
        if (not NAME.fullmatch(name) or not isinstance(points, list) or len(points) != 4
                or any(type(p) not in (float, int) or not math.isfinite(p) for p in points)
                or not (0 <= points[0] <= 1 and 0 <= points[2] <= 1)):
            raise ValueError(f"invalid easing curve: {name!r}")
        css.append(f"  --ease-{name}: cubic-bezier({', '.join(map(str, points))});")
    css.append("}")
    kt.append("}")
    return "\n".join(css) + "\n", "\n".join(kt) + "\n"


def view_colors(tokens):
    colors = [f"<!-- {HEADER} -->", "<resources>"]
    for name, value in tokens.get("color", {}).items():
        resource = name.replace("-", "_")
        colors.append(f'    <color name="zip_token_{resource}">{value}</color>')
    colors.append("</resources>")
    return "\n".join(colors) + "\n"


def main(argv):
    tokens = json.loads(SOURCE.read_text(encoding="utf-8"))
    css, kt = generate(tokens)
    outputs = ((CSS_OUT, css), (KT_OUT, kt), (COLORS_OUT, view_colors(tokens)))
    if "--check" in argv:
        stale = [p for p, text in outputs if not p.exists() or p.read_bytes().replace(b"\r\n", b"\n") != text.encode("utf-8")]
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}", file=sys.stderr)
        return 1 if stale else 0
    for path, text in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))
        print(f"wrote {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
