#!/usr/bin/env python3
"""Generate ZipHap icons, illustrations, stickers and metal art from design/icons.json, design/illustrations.json,
design/stickers.json and design/metal.json.

Outputs:
  web-ui/src/icons/generated.ts                    path data for the React Icon, Illustration and Sticker parts
  app/src/main/res/drawable/zi_<snake>.xml          duotone icon vector drawables (tintable; icons marked "web": true are web only)
  app/src/main/res/drawable/zi_ill_<snake>.xml      full-color illustration vector drawables
  app/src/main/res/drawable/zi_ill_metal_<snake>.xml metallic security art (gradients) for approval, payment and vault
Stickers (24x24 full-color glyphs that replace emoji inside model text) are web only.

Usage: python scripts/gen-icons.py [--check] [--sheet OUT.html]
--check  exits 1 when a committed output differs from what the sources would generate,
         or when a stale zi_*.xml drawable has no source.
--sheet  also writes a static proof sheet (all icons and illustrations) to OUT.html.
Standard library only.
"""
import html
import json
import math
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ICONS_SRC = ROOT / "design" / "icons.json"
ILLS_SRC = ROOT / "design" / "illustrations.json"
STICKERS_SRC = ROOT / "design" / "stickers.json"
METAL_SRC = ROOT / "design" / "metal.json"
TS_OUT = ROOT / "web-ui" / "src" / "icons" / "generated.ts"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"
HEADER = "Generated from design/icons.json, design/illustrations.json, design/stickers.json and design/metal.json by scripts/gen-icons.py. Do not edit."
# Android drawables come only from icons and illustrations, so their header stays as before.
DRAWABLE_HEADER = "Generated from design/icons.json and design/illustrations.json by scripts/gen-icons.py. Do not edit."
METAL_HEADER = "Generated from design/metal.json by scripts/gen-icons.py. Do not edit."

NAME = re.compile(r"^[a-z][a-zA-Z0-9]*$")
HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
ICON_KEYS = {"fill", "line", "note", "web"}
SHAPE_KEYS = {"d", "circle", "rect", "ellipse"}


def snake(name):
    return re.sub(r"([A-Z])", r"_\1", name).lower()


def fmt(v):
    v = round(float(v), 3)
    if v == 0:
        v = 0.0
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s or "0"


# ---------------------------------------------------------------- path geometry

def parse_path(d):
    """Return [(command, [numbers])] with implicit repeats expanded. Raises on malformed data."""
    if not isinstance(d, str) or not d.strip():
        raise ValueError("empty path data")
    leftover = TOKEN.sub(" ", d).replace(",", " ").strip()
    if leftover:
        raise ValueError(f"unexpected characters in path data: {leftover!r}")
    tokens = TOKEN.findall(d)
    out, i, cmd = [], 0, None
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append((cmd, []))
                continue
        elif cmd is None or cmd in "Zz":
            raise ValueError(f"number without command in {d!r}")
        n = ARITY[cmd.upper()]
        args = tokens[i:i + n]
        if len(args) != n or any(a.isalpha() for a in args):
            raise ValueError(f"wrong argument count for {cmd} in {d!r}")
        nums = [float(a) for a in args]
        if cmd in "Aa" and (nums[3] not in (0, 1) or nums[4] not in (0, 1)):
            raise ValueError(f"arc flags must be 0 or 1 in {d!r}")
        out.append((cmd, nums))
        i += n
        if cmd == "M":
            cmd = "L"
        elif cmd == "m":
            cmd = "l"
    if not out or out[0][0] not in "Mm":
        raise ValueError(f"path must start with M: {d!r}")
    return out


def serialize(cmds):
    parts = []
    for c, nums in cmds:
        parts.append(c + (" ".join(fmt(n) for n in nums) if nums else ""))
    return " ".join(parts)


def _arc_points(x1, y1, rx, ry, phi, fa, fs, x2, y2, steps=24):
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x2, y2)]
    rx, ry = abs(rx), abs(ry)
    p = math.radians(phi)
    cp, sp = math.cos(p), math.sin(p)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        co = -co
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    pts = []
    for k in range(1, steps + 1):
        t = t1 + dt * k / steps
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        pts.append((cp * ex - sp * ey + cx, sp * ex + cp * ey + cy))
    return pts


def _bez(p0, p1, p2, p3, steps=16):
    pts = []
    for k in range(1, steps + 1):
        t = k / steps
        u = 1 - t
        pts.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return pts


def sample_path(d):
    """Points along the drawn outline (curves sampled), used for bounds checks."""
    pts = []
    x = y = sx = sy = 0.0
    last_c = last_q = None
    for c, n in parse_path(d):
        rel = c.islower()
        C = c.upper()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if C == "M":
            x, y = ox + n[0], oy + n[1]
            sx, sy = x, y
            pts.append((x, y))
            last_c = last_q = None
        elif C == "L":
            x, y = ox + n[0], oy + n[1]
            pts.append((x, y))
            last_c = last_q = None
        elif C == "H":
            x = (x if rel else 0) + n[0]
            pts.append((x, y))
            last_c = last_q = None
        elif C == "V":
            y = (y if rel else 0) + n[0]
            pts.append((x, y))
            last_c = last_q = None
        elif C in "CS":
            if C == "C":
                p1 = (ox + n[0], oy + n[1])
                p2, p3 = (ox + n[2], oy + n[3]), (ox + n[4], oy + n[5])
            else:
                p1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
                p2, p3 = (ox + n[0], oy + n[1]), (ox + n[2], oy + n[3])
            pts += _bez((x, y), p1, p2, p3)
            last_c, last_q = p2, None
            x, y = p3
        elif C in "QT":
            if C == "Q":
                q = (ox + n[0], oy + n[1])
                p3 = (ox + n[2], oy + n[3])
            else:
                q = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
                p3 = (ox + n[0], oy + n[1])
            p1 = (x + 2 / 3 * (q[0] - x), y + 2 / 3 * (q[1] - y))
            p2 = (p3[0] + 2 / 3 * (q[0] - p3[0]), p3[1] + 2 / 3 * (q[1] - p3[1]))
            pts += _bez((x, y), p1, p2, p3)
            last_q, last_c = q, None
            x, y = p3
        elif C == "A":
            ex, ey = ox + n[5], oy + n[6]
            pts += _arc_points(x, y, n[0], n[1], n[2], int(n[3]), int(n[4]), ex, ey)
            x, y = ex, ey
            last_c = last_q = None
        elif C == "Z":
            x, y = sx, sy
            pts.append((x, y))
            last_c = last_q = None
    return pts


# ---------------------------------------------------------------- shapes

def shape_to_d(el, where):
    kinds = [k for k in SHAPE_KEYS | {"dot"} if k in el]
    if len(kinds) != 1:
        raise ValueError(f"{where}: element needs exactly one of d/circle/rect/ellipse/dot, got {sorted(el)}")
    kind = kinds[0]
    v = el[kind]
    if kind == "d":
        return serialize(parse_path(v))
    if not isinstance(v, list) or any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in v):
        raise ValueError(f"{where}: {kind} needs a list of numbers")
    if kind in ("circle", "dot"):
        if len(v) != 3 or v[2] <= 0:
            raise ValueError(f"{where}: {kind} is [cx, cy, r]")
        cx, cy, r = v
        return ellipse_d(cx, cy, r, r)
    if kind == "ellipse":
        if len(v) != 4 or v[2] <= 0 or v[3] <= 0:
            raise ValueError(f"{where}: ellipse is [cx, cy, rx, ry]")
        return ellipse_d(*v)
    if len(v) != 5 or v[2] <= 0 or v[3] <= 0 or v[4] < 0 or v[4] * 2 > min(v[2], v[3]) + 1e-9:
        raise ValueError(f"{where}: rect is [x, y, w, h, r] with 2r <= min(w, h)")
    x, y, w, h, r = v
    if r == 0:
        return f"M{fmt(x)} {fmt(y)} H{fmt(x + w)} V{fmt(y + h)} H{fmt(x)} Z"
    return (f"M{fmt(x + r)} {fmt(y)} H{fmt(x + w - r)} A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x + w)} {fmt(y + r)} "
            f"V{fmt(y + h - r)} A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x + w - r)} {fmt(y + h)} "
            f"H{fmt(x + r)} A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x)} {fmt(y + h - r)} "
            f"V{fmt(y + r)} A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x + r)} {fmt(y)} Z")


def translate(d, dx, dy):
    """Shift path data by (dx, dy); relative segments move with their absolute start."""
    if not dx and not dy:
        return d
    out = []
    for i, (c, n) in enumerate(parse_path(d)):
        n = list(n)
        if c in "MLCSQT" or (i == 0 and c == "m"):
            for k in range(0, len(n), 2):
                n[k] += dx
                n[k + 1] += dy
        elif c == "H":
            n[0] += dx
        elif c == "V":
            n[0] += dy
        elif c == "A":
            n[5] += dx
            n[6] += dy
        out.append((c, n))
    return serialize(out)


def ellipse_d(cx, cy, rx, ry):
    return (f"M{fmt(cx - rx)} {fmt(cy)} A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx + rx)} {fmt(cy)} "
            f"A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx - rx)} {fmt(cy)} Z")


# ---------------------------------------------------------------- icons

def load_icons(src):
    meta = src.get("meta", {})
    grid = meta.get("grid")
    stroke = meta.get("stroke")
    soft = meta.get("softOpacity")
    tints = meta.get("tints", {})
    if grid != 24 or stroke != 1.8 or not isinstance(soft, (int, float)) or not 0 < soft < 1:
        raise ValueError("icons meta must set grid 24, stroke 1.8 and 0 < softOpacity < 1")
    for t, hexv in tints.items():
        if not NAME.fullmatch(t) or not isinstance(hexv, str) or not HEX.fullmatch(hexv):
            raise ValueError(f"bad tint {t!r}")
    lo, hi = meta.get("liveArea", [2, 22])
    off = meta.get("fillOffset", [0, 0])
    if (not isinstance(off, list) or len(off) != 2 or any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in off)
            or any(abs(v) > 1.5 for v in off)):
        raise ValueError("fillOffset must be [dx, dy] within 1.5")
    icons = {}
    for name, spec in src.get("icons", {}).items():
        if not NAME.fullmatch(name):
            raise ValueError(f"bad icon name {name!r}")
        if not isinstance(spec, dict) or set(spec) - ICON_KEYS:
            raise ValueError(f"icon {name}: unknown keys {sorted(set(spec) - ICON_KEYS) if isinstance(spec, dict) else spec}")
        line = spec.get("line")
        if not isinstance(line, list) or not line:
            raise ValueError(f"icon {name}: needs at least one line element")
        soft_d, accents, line_d, dots_d = [], [], [], []
        for i, el in enumerate(spec.get("fill", [])):
            where = f"icon {name} fill[{i}]"
            if not isinstance(el, dict) or set(el) - SHAPE_KEYS - {"tone"}:
                raise ValueError(f"{where}: unknown keys")
            d = translate(shape_to_d(el, where), *off)
            tone = el.get("tone", "soft")
            if tone == "soft":
                soft_d.append(d)
            elif tone in tints:
                accents.append({"d": d, "color": tints[tone].upper()})
            else:
                raise ValueError(f"{where}: unknown tone {tone!r}")
        for i, el in enumerate(line):
            where = f"icon {name} line[{i}]"
            if not isinstance(el, dict) or set(el) - SHAPE_KEYS - {"dot"}:
                raise ValueError(f"{where}: unknown keys (stroke style is global)")
            d = shape_to_d(el, where)
            (dots_d if "dot" in el else line_d).append(d)
        if not line_d:
            raise ValueError(f"icon {name}: needs at least one stroked line element")
        for d in soft_d + [a["d"] for a in accents] + line_d + dots_d:
            for px, py in sample_path(d):
                if not (lo - 1e-6 <= px <= hi + 1e-6 and lo - 1e-6 <= py <= hi + 1e-6):
                    raise ValueError(f"icon {name}: point ({px:.2f}, {py:.2f}) outside live area {lo}-{hi}")
        if "web" in spec and spec["web"] is not True:
            raise ValueError(f"icon {name}: web must be true when present")
        icons[name] = {"soft": " ".join(soft_d), "accents": accents, "line": " ".join(line_d), "dots": " ".join(dots_d),
                       "web": spec.get("web") is True}
    if not icons:
        raise ValueError("no icons")
    return {"stroke": stroke, "softOpacity": soft}, icons


# ---------------------------------------------------------------- illustrations

def load_illustrations(src, group="illustrations", size=(160, 120), extra_keys=frozenset()):
    meta = src.get("meta", {})
    w, h = meta.get("viewBox", [0, 0])
    palette = meta.get("palette", {})
    widths = meta.get("strokeWidths", [])
    max_paths = meta.get("maxPaths", 40)
    if (w, h) != size:
        raise ValueError(f"{group} viewBox must be {size[0]}x{size[1]}")
    for k, v in palette.items():
        if not NAME.fullmatch(k) or not isinstance(v, str) or not HEX.fullmatch(v):
            raise ValueError(f"bad palette entry {k!r}")
    ills = {}
    for name, spec in src.get(group, {}).items():
        if not NAME.fullmatch(name):
            raise ValueError(f"bad {group} name {name!r}")
        if isinstance(spec, dict) and set(spec) - {"shapes"} - extra_keys:
            raise ValueError(f"{group} {name}: unknown keys {sorted(set(spec) - {'shapes'} - extra_keys)}")
        shapes = spec.get("shapes") if isinstance(spec, dict) else None
        if not isinstance(shapes, list) or not shapes:
            raise ValueError(f"illustration {name}: needs shapes")
        if len(shapes) > max_paths:
            raise ValueError(f"illustration {name}: {len(shapes)} shapes > {max_paths}")
        out = []
        for i, el in enumerate(shapes):
            where = f"illustration {name}[{i}]"
            allowed = SHAPE_KEYS | {"fill", "stroke", "sw", "opacity"}
            if not isinstance(el, dict) or set(el) - allowed:
                raise ValueError(f"{where}: unknown keys")
            d = shape_to_d(el, where)
            item = {"d": d}
            fill = el.get("fill", "none")
            stroke = el.get("stroke", "none")
            for role, val in (("fill", fill), ("stroke", stroke)):
                if val != "none" and val not in palette:
                    raise ValueError(f"{where}: {role} {val!r} is not a palette name")
            if fill == "none" and stroke == "none":
                raise ValueError(f"{where}: needs fill or stroke")
            if fill != "none":
                item["fill"] = palette[fill].upper()
            if stroke != "none":
                sw = el.get("sw", widths[0] if widths else None)
                if sw not in widths:
                    raise ValueError(f"{where}: stroke width {sw!r} not in {widths}")
                item["stroke"] = palette[stroke].upper()
                item["sw"] = sw
            elif "sw" in el:
                raise ValueError(f"{where}: sw without stroke")
            if "opacity" in el:
                op = el["opacity"]
                if isinstance(op, bool) or not isinstance(op, (int, float)) or not 0 < op < 1:
                    raise ValueError(f"{where}: opacity must be between 0 and 1")
                item["opacity"] = op
            margin = item.get("sw", 0) / 2
            for px, py in sample_path(d):
                if not (margin - 1e-6 <= px <= w - margin + 1e-6 and margin - 1e-6 <= py <= h - margin + 1e-6):
                    raise ValueError(f"{where}: point ({px:.1f}, {py:.1f}) outside viewBox")
            out.append(item)
        ills[name] = out
    if not ills:
        raise ValueError(f"no {group}")
    return ills


def load_stickers(src):
    stickers = load_illustrations(src, "stickers", (24, 24), frozenset({"label"}))
    labels = {}
    for name, spec in src["stickers"].items():
        label = spec.get("label")
        if not isinstance(label, str) or not label.strip():
            raise ValueError(f"sticker {name}: needs a Korean label for screen readers")
        labels[name] = label
    return {name: {"label": labels[name], "shapes": shapes} for name, shapes in stickers.items()}


# ---------------------------------------------------------------- metal art

GRAD = re.compile(r"^grad:([a-z][a-zA-Z0-9]*)$")


def _num(v, where):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError(f"{where}: needs a number")
    return v


def load_metal(src):
    """Metallic art with linear/radial gradients in viewBox coordinates (userSpaceOnUse on the web)."""
    meta = src.get("meta", {})
    w, h = meta.get("viewBox", [0, 0])
    if (w, h) != (160, 160):
        raise ValueError("metal viewBox must be 160x160")
    arts = {}
    for name, spec in src.get("metal", {}).items():
        if not NAME.fullmatch(name) or not isinstance(spec, dict) or set(spec) - {"gradients", "shapes"}:
            raise ValueError(f"bad metal art {name!r}")
        grads = {}
        for gname, g in spec.get("gradients", {}).items():
            where = f"metal {name}.{gname}"
            if not NAME.fullmatch(gname) or not isinstance(g, dict):
                raise ValueError(f"{where}: bad gradient")
            kind = g.get("type")
            keys = {"linear": {"x1", "y1", "x2", "y2"}, "radial": {"cx", "cy", "r"}}.get(kind)
            if keys is None or set(g) != keys | {"type", "stops"}:
                raise ValueError(f"{where}: linear needs x1 y1 x2 y2, radial needs cx cy r, both need stops")
            stops = g["stops"]
            if not isinstance(stops, list) or len(stops) < 2:
                raise ValueError(f"{where}: needs two or more stops")
            last = -1
            for off, color in stops:
                if _num(off, where) < last or not 0 <= off <= 1 or not isinstance(color, str) or not HEX.fullmatch(color):
                    raise ValueError(f"{where}: stops are [offset 0..1 ascending, #RRGGBB]")
                last = off
            grads[gname] = {"type": kind, **{k: _num(g[k], where) for k in sorted(keys)},
                            "stops": [[off, color.upper()] for off, color in stops]}
        shapes = spec.get("shapes")
        if not isinstance(shapes, list) or not shapes:
            raise ValueError(f"metal {name}: needs shapes")
        used = set()
        out = []
        for i, el in enumerate(shapes):
            where = f"metal {name}[{i}]"
            if not isinstance(el, dict) or set(el) - (SHAPE_KEYS | {"fill", "stroke", "sw", "opacity"}):
                raise ValueError(f"{where}: unknown keys")
            item = {"d": shape_to_d(el, where)}
            for role in ("fill", "stroke"):
                val = el.get(role)
                if val is None:
                    continue
                m = GRAD.fullmatch(val) if isinstance(val, str) else None
                if m:
                    if m.group(1) not in grads:
                        raise ValueError(f"{where}: unknown gradient {val!r}")
                    used.add(m.group(1))
                    item[role] = val
                elif isinstance(val, str) and HEX.fullmatch(val):
                    item[role] = val.upper()
                else:
                    raise ValueError(f"{where}: {role} must be #RRGGBB or grad:<name>")
            if "fill" not in item and "stroke" not in item:
                raise ValueError(f"{where}: needs fill or stroke")
            if "stroke" in item:
                item["sw"] = _num(el.get("sw"), where)
            elif "sw" in el:
                raise ValueError(f"{where}: sw without stroke")
            if "opacity" in el:
                op = _num(el["opacity"], where)
                if not 0 < op < 1:
                    raise ValueError(f"{where}: opacity must be between 0 and 1")
                item["opacity"] = op
            margin = item.get("sw", 0) / 2
            for px, py in sample_path(item["d"]):
                if not (margin - 1e-6 <= px <= w - margin + 1e-6 and margin - 1e-6 <= py <= h - margin + 1e-6):
                    raise ValueError(f"{where}: point ({px:.1f}, {py:.1f}) outside viewBox")
            out.append(item)
        if set(grads) - used:
            raise ValueError(f"metal {name}: unused gradients {sorted(set(grads) - used)}")
        arts[name] = {"gradients": grads, "shapes": out}
    if not arts:
        raise ValueError("no metal art")
    return arts


def gen_metal_xml(art):
    def gradient(g):
        if g["type"] == "linear":
            attrs = (f'android:type="linear" android:startX="{fmt(g["x1"])}" android:startY="{fmt(g["y1"])}" '
                     f'android:endX="{fmt(g["x2"])}" android:endY="{fmt(g["y2"])}"')
        else:
            attrs = (f'android:type="radial" android:centerX="{fmt(g["cx"])}" android:centerY="{fmt(g["cy"])}" '
                     f'android:gradientRadius="{fmt(g["r"])}"')
        items = [f'                <item android:offset="{fmt(off)}" android:color="#FF{color[1:]}" />' for off, color in g["stops"]]
        return [f"            <gradient {attrs}>", *items, "            </gradient>"]
    body = []
    for s in art["shapes"]:
        attrs = [f'android:pathData="{s["d"]}"']
        children = []
        for role, android_name in (("fill", "android:fillColor"), ("stroke", "android:strokeColor")):
            val = s.get(role)
            if val is None:
                continue
            if val.startswith("grad:"):
                children += [f'        <aapt:attr name="{android_name}">', *gradient(art["gradients"][val[5:]]), "        </aapt:attr>"]
            else:
                attrs.append(f'{android_name}="{val}"')
        if "stroke" in s:
            attrs += [f'android:strokeWidth="{fmt(s["sw"])}"', 'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        if "opacity" in s:
            attrs.append(f'android:{"fillAlpha" if "fill" in s else "strokeAlpha"}="{fmt(s["opacity"])}"')
        if children:
            body += ["    <path " + " ".join(attrs) + ">", *children, "    </path>"]
        else:
            body.append("    <path " + " ".join(attrs) + " />")
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {METAL_HEADER} -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        '    android:width="160dp"',
        '    android:height="160dp"',
        '    android:viewportWidth="160"',
        '    android:viewportHeight="160">',
        *body,
        "</vector>",
        "",
    ])


# ---------------------------------------------------------------- outputs

def _shape_ts(s):
    fields = [f"d: {json.dumps(s['d'])}"]
    for k in ("fill", "stroke"):
        if k in s:
            fields.append(f"{k}: {json.dumps(s[k])}")
    if "sw" in s:
        fields.append(f"sw: {fmt(s['sw'])}")
    if "opacity" in s:
        fields.append(f"opacity: {fmt(s['opacity'])}")
    return "    { " + ", ".join(fields) + " },"


def gen_ts(meta, icons, ills, stickers, metal):
    lines = [f"// {HEADER}", "",
             "export interface IconLayers { soft: string; accents: readonly { d: string; color: string }[]; line: string; dots: string }",
             "export interface IllustrationShape { d: string; fill?: string; stroke?: string; sw?: number; opacity?: number }",
             "",
             f"export const ICON_STROKE = {fmt(meta['stroke'])}",
             f"export const ICON_SOFT_OPACITY = {fmt(meta['softOpacity'])}",
             "",
             "export const ICONS = {"]
    for name, ic in icons.items():
        acc = ", ".join("{ d: " + json.dumps(a["d"]) + ", color: " + json.dumps(a["color"]) + " }" for a in ic["accents"])
        lines.append(f"  {name}: {{ soft: {json.dumps(ic['soft'])}, accents: [{acc}], line: {json.dumps(ic['line'])}, dots: {json.dumps(ic['dots'])} }},")
    lines += ["} satisfies Record<string, IconLayers>", "", "export const ILLUSTRATIONS = {"]
    for name, shapes in ills.items():
        lines.append(f"  {name}: [")
        lines += [_shape_ts(s) for s in shapes]
        lines.append("  ],")
    lines += ["} satisfies Record<string, readonly IllustrationShape[]>", "",
              "export interface StickerArt { label: string; shapes: readonly IllustrationShape[] }", "",
              "export const STICKERS = {"]
    for name, st in stickers.items():
        lines.append(f"  {name}: {{ label: {json.dumps(st['label'], ensure_ascii=False)}, shapes: [")
        lines += ["  " + _shape_ts(s) for s in st["shapes"]]
        lines.append("  ] },")
    lines += ["} satisfies Record<string, StickerArt>", "",
              "export type MetalGradient =",
              "  | { type: 'linear'; x1: number; y1: number; x2: number; y2: number; stops: readonly (readonly [number, string])[] }",
              "  | { type: 'radial'; cx: number; cy: number; r: number; stops: readonly (readonly [number, string])[] }",
              "export interface MetalShape { d: string; fill?: string; stroke?: string; sw?: number; opacity?: number }",
              "export interface MetalArt { gradients: Readonly<Record<string, MetalGradient>>; shapes: readonly MetalShape[] }", "",
              "export const METAL = {"]
    for name, art in metal.items():
        lines.append(f"  {name}: {{")
        lines.append("    gradients: {")
        for gname, g in art["gradients"].items():
            keys = ("x1", "y1", "x2", "y2") if g["type"] == "linear" else ("cx", "cy", "r")
            coords = ", ".join(f"{k}: {fmt(g[k])}" for k in keys)
            stops = ", ".join(f"[{fmt(off)}, {json.dumps(color)}]" for off, color in g["stops"])
            lines.append(f"      {gname}: {{ type: '{g['type']}', {coords}, stops: [{stops}] }},")
        lines.append("    },")
        lines.append("    shapes: [")
        lines += ["    " + _shape_ts(s) for s in art["shapes"]]
        lines.append("    ],")
        lines.append("  },")
    lines += ["} satisfies Record<string, MetalArt>", ""]
    return "\n".join(lines)


def _vector(w, h, body):
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {DRAWABLE_HEADER} -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{w}dp"',
        f'    android:height="{h}dp"',
        f'    android:viewportWidth="{w}"',
        f'    android:viewportHeight="{h}">',
        *body,
        "</vector>",
        "",
    ])


def gen_icon_xml(meta, ic):
    # Every layer is black so Compose Icon(tint = ...) / View tint (SrcIn) recolors all of them;
    # the soft layer keeps its fillAlpha because SrcIn preserves the destination alpha.
    # Accent tones are fixed colors on the web only; on Android they become soft layers so tinting stays uniform.
    body = []
    soft = " ".join([ic["soft"]] + [a["d"] for a in ic["accents"]]).strip()
    if soft:
        body.append(f'    <path android:fillColor="@android:color/black" android:fillAlpha="{fmt(meta["softOpacity"])}" android:pathData="{soft}" />')
    body.append(f'    <path android:strokeColor="@android:color/black" android:strokeWidth="{fmt(meta["stroke"])}" '
                f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{ic["line"]}" />')
    if ic["dots"]:
        body.append(f'    <path android:fillColor="@android:color/black" android:pathData="{ic["dots"]}" />')
    return _vector(24, 24, body)


def gen_ill_xml(shapes):
    body = []
    for s in shapes:
        attrs = []
        if "fill" in s:
            attrs.append(f'android:fillColor="{s["fill"]}"')
            if "opacity" in s:
                attrs.append(f'android:fillAlpha="{fmt(s["opacity"])}"')
        if "stroke" in s:
            attrs += [f'android:strokeColor="{s["stroke"]}"', f'android:strokeWidth="{fmt(s["sw"])}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if "opacity" in s:
                attrs.append(f'android:strokeAlpha="{fmt(s["opacity"])}"')
        attrs.append(f'android:pathData="{s["d"]}"')
        body.append("    <path " + " ".join(attrs) + " />")
    return _vector(160, 120, body)


def build():
    meta, icons = load_icons(json.loads(ICONS_SRC.read_text(encoding="utf-8")))
    ills = load_illustrations(json.loads(ILLS_SRC.read_text(encoding="utf-8")))
    stickers = load_stickers(json.loads(STICKERS_SRC.read_text(encoding="utf-8")))
    metal = load_metal(json.loads(METAL_SRC.read_text(encoding="utf-8")))
    outputs = {TS_OUT: gen_ts(meta, icons, ills, stickers, metal)}
    for name, ic in icons.items():
        if not ic["web"]:
            outputs[DRAWABLE / f"zi_{snake(name)}.xml"] = gen_icon_xml(meta, ic)
    for name, shapes in ills.items():
        outputs[DRAWABLE / f"zi_ill_{snake(name)}.xml"] = gen_ill_xml(shapes)
    for name, art in metal.items():
        outputs[DRAWABLE / f"zi_ill_metal_{snake(name)}.xml"] = gen_metal_xml(art)
    return meta, icons, ills, stickers, outputs


# ---------------------------------------------------------------- proof sheet

def icon_svg(meta, ic, size):
    parts = [f'<svg viewBox="0 0 24 24" width="{size}" height="{size}" fill="none" aria-hidden="true">']
    if ic["soft"]:
        parts.append(f'<path d="{ic["soft"]}" fill="currentColor" fill-opacity="{fmt(meta["softOpacity"])}"/>')
    for a in ic["accents"]:
        parts.append(f'<path d="{a["d"]}" fill="{a["color"]}"/>')
    parts.append(f'<path d="{ic["line"]}" stroke="currentColor" stroke-width="{fmt(meta["stroke"])}" stroke-linecap="round" stroke-linejoin="round"/>')
    if ic["dots"]:
        parts.append(f'<path d="{ic["dots"]}" fill="currentColor"/>')
    parts.append("</svg>")
    return "".join(parts)


def ill_svg(shapes, width, box=(160, 120)):
    parts = [f'<svg viewBox="0 0 {box[0]} {box[1]}" width="{width}" height="{width * box[1] // box[0]}" aria-hidden="true">']
    for s in shapes:
        a = [f'd="{s["d"]}"', f'fill="{s.get("fill", "none")}"']
        if "stroke" in s:
            a += [f'stroke="{s["stroke"]}"', f'stroke-width="{fmt(s["sw"])}"', 'stroke-linecap="round"', 'stroke-linejoin="round"']
        if "opacity" in s:
            a.append(f'opacity="{fmt(s["opacity"])}"')
        parts.append("<path " + " ".join(a) + "/>")
    parts.append("</svg>")
    return "".join(parts)


def gen_sheet(meta, icons, ills, stickers):
    rows = []
    variants = [("#FFFFFF", "#3B2F3A"), ("#F3F4F8", "#3B2F3A"), ("#FFFFFF", "#3355F0"), ("#F3F4F8", "#3355F0")]
    for name, ic in icons.items():
        cells = "".join(
            f'<div class="v" style="background:{bg};color:{fg}">{icon_svg(meta, ic, 24)}{icon_svg(meta, ic, 20)}</div>'
            for bg, fg in variants)
        rows.append(f'<div class="ic"><div class="big" style="color:#3B2F3A">{icon_svg(meta, ic, 48)}</div>'
                    f'<div class="vs">{cells}</div><div class="n">{html.escape(name)}</div></div>')
    tiles = "".join(
        f'<div class="tile" style="background:{bg}"><span style="color:{fg}">{icon_svg(meta, icons[n], 22)}</span></div>'
        for n, (bg, fg) in zip(list(icons)[:15], [("#E6EBFF", "#2E4BE0"), ("#FFE9E2", "#C2401F"), ("#DDF7EE", "#0B7A5B"),
                                                   ("#FFF3D6", "#8A5A00"), ("#F0E9FF", "#6D3FE0")] * 3))
    names = list(icons)
    half = (len(names) + 1) // 2
    zooms = "".join(
        f'<div class="zoom" id="zoom{k}">' + "".join(
            f'<div class="z"><span style="color:#3B2F3A">{icon_svg(meta, icons[n], 64)}</span>'
            f'<span style="color:#3355F0;background:#E6EBFF">{icon_svg(meta, icons[n], 64)}</span><b>{html.escape(n)}</b></div>'
            for n in part) + "</div>"
        for k, part in enumerate((names[:half], names[half:])))
    sticker_cells = "".join(
        f'<figure>{ill_svg(st["shapes"], 64, (24, 24))}<p>글 {ill_svg(st["shapes"], 20, (24, 24))} 글</p>'
        f'<figcaption>{html.escape(name)} · {html.escape(st["label"])}</figcaption></figure>'
        for name, st in stickers.items())
    ill_cells = "".join(
        f'<figure>{ill_svg(shapes, 240)}<figcaption>{html.escape(name)} ({len(shapes)})</figcaption></figure>'
        for name, shapes in ills.items())
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>ZipHap icon sheet</title>
<style>
body{{margin:0;padding:24px;background:#F3F4F8;font:13px system-ui,sans-serif;color:#171B33}}
h2{{margin:8px 0 12px;font-size:15px}}
.grid{{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}}
.ic{{background:#fff;border-radius:16px;padding:10px;display:flex;flex-direction:column;align-items:center;gap:8px}}
.big{{display:grid;place-items:center;width:64px;height:56px}}
.vs{{display:grid;grid-template-columns:1fr 1fr;gap:4px}}
.v{{display:flex;gap:6px;align-items:center;padding:6px;border-radius:8px}}
.n{{font-weight:600}}
.zoom{{display:grid;grid-template-columns:repeat(7,1fr);gap:8px;width:1100px;margin-top:16px}}
.z{{background:#fff;border-radius:12px;padding:6px;display:flex;flex-wrap:wrap;gap:4px;justify-content:center}}
.z span{{border-radius:10px;display:grid;place-items:center;padding:2px}} .z b{{width:100%;text-align:center;font-size:11px}}
.tiles{{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px}}
.tile{{width:40px;height:40px;border-radius:12px;display:grid;place-items:center}}
.ills{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}}
figure{{margin:0;background:#fff;border-radius:20px;padding:12px;text-align:center}}
figcaption{{margin-top:6px;font-weight:600}}
.stickers{{display:grid;grid-template-columns:repeat(7,1fr);gap:10px}} .stickers p{{margin:4px 0 0;font-size:15px}}
.stickers p svg{{vertical-align:-4px}}
</style></head><body>
<h2>Icons ({len(icons)}) — 48px ink · 24/20px on white &amp; #F3F4F8 in ink &amp; cobalt</h2>
<div class="tiles">{tiles}</div>
<div class="grid">{''.join(rows)}</div>
{zooms}
<h2 style="margin-top:24px">Illustrations ({len(ills)})</h2>
<div class="ills">{ill_cells}</div>
<h2 style="margin-top:24px">Stickers ({len(stickers)}) — 64px and 20px inside a line of text</h2>
<div class="stickers">{sticker_cells}</div>
</body></html>
"""


def main(argv):
    meta, icons, ills, stickers, outputs = build()
    if "--sheet" in argv:
        target = pathlib.Path(argv[argv.index("--sheet") + 1]).resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(gen_sheet(meta, icons, ills, stickers), encoding="utf-8")
        print(f"wrote {target}")
    expected = {p.name for p in outputs if p.parent == DRAWABLE}
    if "--check" in argv:
        stale = [p for p, text in outputs.items()
                 if not p.exists() or p.read_bytes().replace(b"\r\n", b"\n") != text.encode("utf-8")]
        orphans = sorted(p for p in DRAWABLE.glob("zi_*.xml") if p.name not in expected)
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}", file=sys.stderr)
        for p in orphans:
            print(f"orphan: {p.relative_to(ROOT)}", file=sys.stderr)
        return 1 if stale or orphans else 0
    for p in DRAWABLE.glob("zi_*.xml"):
        if p.name not in expected:
            p.unlink()
            print(f"removed {p.relative_to(ROOT)}")
    for path, text in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.read_bytes() != text.encode("utf-8"):
            path.write_bytes(text.encode("utf-8"))
    print(f"wrote {len(outputs)} files ({len(icons)} icons, {len(ills)} illustrations, {len(stickers)} stickers)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
