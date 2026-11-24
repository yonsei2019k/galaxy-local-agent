#!/usr/bin/env python3
"""Generate web-ui/src/tokens.css and ZipHapTokens.kt from design/tokens.json.

Usage: python scripts/gen-design-tokens.py [--check]
--check exits 1 when the committed outputs differ from what the source would generate.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "tokens.json"
CSS_OUT = ROOT / "web-ui" / "src" / "tokens.css"
KT_OUT = ROOT / "app" / "src" / "main" / "java" / "com" / "user" / "localagent" / "ui" / "ZipHapTokens.kt"

HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
NAME = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
HEADER = "Generated from design/tokens.json by scripts/gen-design-tokens.py. Do not edit."


def pascal(*parts):
    return "".join(p.capitalize() for part in parts for p in part.split("-"))


def generate(tokens):
    css = [f"/* {HEADER} */", ":root {"]
    kt = [
        f"// {HEADER}",
        "package com.user.localagent.ui",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.sp",
        "",
        "object ZipHapTokens {",
    ]
    for name, value in tokens.get("color", {}).items():
        if not NAME.match(name):
            raise ValueError(f"bad token name: {name!r}")
        if not isinstance(value, str) or not HEX.match(value):
            raise ValueError(f"color {name!r} must be #RRGGBB, got {value!r}")
        hexv = value.upper()
        css.append(f"  --color-{name}: {hexv};")
        kt.append(f"    val {pascal(name)} = Color(0xFF{hexv[1:]})")
    for group, unit_css, unit_kt in (("space", "px", "dp"), ("fs", "px", "sp"), ("radius", "px", "dp")):
        for name, value in tokens.get(group, {}).items():
            if not NAME.match(name):
                raise ValueError(f"bad token name: {name!r}")
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ValueError(f"{group} {name!r} must be a non-negative integer, got {value!r}")
            css.append(f"  --{group}-{name}: {value}{unit_css};")
            kt.append(f"    val {pascal(group, name)} = {value}.{unit_kt}")
    css.append("}")
    kt.append("}")
    return "\n".join(css) + "\n", "\n".join(kt) + "\n"


def main(argv):
    css, kt = generate(json.loads(SOURCE.read_text(encoding="utf-8")))
    outputs = ((CSS_OUT, css), (KT_OUT, kt))
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
