#!/usr/bin/env python3
"""Convert the restricted SVGs in art/svg/ to Android VectorDrawables in res/drawable/ic_<name>.xml.

Supported: <svg viewBox>, <g>, <path>, <rect> (with rx/ry), <circle>, <line>; fill, stroke, stroke-width,
stroke-linecap, stroke-linejoin, fill-rule, opacity attributes (inherited through <g>); transform on any
element as translate / rotate(a [cx cy]) / scale (becomes nested <group>).  Anything else raises.
Usage: python svg2vd.py [svg_dir] [out_res_drawable_dir]
"""
import os, re, sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"
INHERIT = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "opacity")


def num(v):
    s = ("%.3f" % float(v)).rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def color(v):
    v = v.strip()
    if v == "none":
        return None
    if re.fullmatch(r"#[0-9a-fA-F]{6}", v):
        return "#FF" + v[1:].upper()
    if re.fullmatch(r"#[0-9a-fA-F]{3}", v):
        return "#FF" + "".join(c * 2 for c in v[1:]).upper()
    raise ValueError("unsupported colour " + v)


def shape_path(el):
    tag = el.tag.replace(NS, "")
    f = lambda k, d=0.0: float(el.get(k, d))
    if tag == "path":
        return el.get("d")
    if tag == "line":
        return "M%s,%s L%s,%s" % (num(f("x1")), num(f("y1")), num(f("x2")), num(f("y2")))
    if tag == "circle":
        cx, cy, r = f("cx"), f("cy"), f("r")
        return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z" % (num(cx - r), num(cy), num(r), num(r), num(2 * r), num(r), num(r), num(-2 * r))
    if tag == "rect":
        x, y, w, h = f("x"), f("y"), f("width"), f("height")
        rx = f("rx", el.get("ry", 0)); ry = f("ry", rx) if el.get("ry") else rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx <= 0:
            return "M%s,%s h%s v%s h%s Z" % (num(x), num(y), num(w), num(h), num(-w))
        return ("M%s,%s h%s a%s,%s 0 0,1 %s,%s v%s a%s,%s 0 0,1 %s,%s h%s a%s,%s 0 0,1 %s,%s v%s a%s,%s 0 0,1 %s,%s Z" % (
            num(x + rx), num(y), num(w - 2 * rx), num(rx), num(ry), num(rx), num(ry), num(h - 2 * ry),
            num(rx), num(ry), num(-rx), num(ry), num(-(w - 2 * rx)), num(rx), num(ry), num(-rx), num(-ry),
            num(-(h - 2 * ry)), num(rx), num(ry), num(rx), num(-ry)))
    raise ValueError("unsupported element " + tag)


def parse_transform(t):
    """-> list of dicts of <group> attributes, outermost first."""
    out = []
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", t or ""):
        a = [float(x) for x in re.split(r"[\s,]+", args.strip()) if x]
        if name == "translate":
            out.append({"android:translateX": num(a[0]), "android:translateY": num(a[1] if len(a) > 1 else 0)})
        elif name == "rotate":
            g = {"android:rotation": num(a[0])}
            if len(a) == 3:
                g["android:pivotX"] = num(a[1]); g["android:pivotY"] = num(a[2])
            out.append(g)
        elif name == "scale":
            out.append({"android:scaleX": num(a[0]), "android:scaleY": num(a[-1])})
        else:
            raise ValueError("unsupported transform " + name)
    return out


def emit(el, style, depth, lines):
    style = dict(style)
    for k in INHERIT:
        if el.get(k) is not None:
            style[k] = el.get(k)
    pad = "    " * depth
    groups = parse_transform(el.get("transform"))
    tag = el.tag.replace(NS, "")
    for g in groups:
        lines.append(pad + "<group " + " ".join('%s="%s"' % kv for kv in g.items()) + ">")
        pad += "    "; depth += 1
    if tag == "g":
        for c in el:
            emit(c, style, depth, lines)
    else:
        attrs = ['android:pathData="%s"' % shape_path(el)]
        fill = color(style.get("fill", "#000000")); stroke = color(style.get("stroke", "none"))
        if fill: attrs.append('android:fillColor="%s"' % fill)
        if style.get("fill-rule") == "evenodd": attrs.append('android:fillType="evenOdd"')
        if stroke:
            attrs.append('android:strokeColor="%s"' % stroke)
            attrs.append('android:strokeWidth="%s"' % num(style.get("stroke-width", 1)))
            attrs.append('android:strokeLineCap="%s"' % style.get("stroke-linecap", "butt"))
            attrs.append('android:strokeLineJoin="%s"' % style.get("stroke-linejoin", "miter"))
        if style.get("opacity"): attrs.append('android:fillAlpha="%s" android:strokeAlpha="%s"' % ((style["opacity"],) * 2))
        lines.append(pad + "<path\n" + "".join(pad + "    " + a + "\n" for a in attrs).rstrip("\n") + "/>")
    for _ in groups:
        depth -= 1
        lines.append("    " * depth + "</group>")


def convert(src):
    root = ET.parse(src).getroot()
    vb = [float(x) for x in root.get("viewBox").split()]
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="%sdp" android:height="%sdp"' % (num(vb[2]), num(vb[3])),
             '    android:viewportWidth="%s" android:viewportHeight="%s">' % (num(vb[2]), num(vb[3]))]
    for c in root:
        emit(c, {}, 1, lines)
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    svg_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "svg")
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "..", "res", "drawable")
    os.makedirs(out_dir, exist_ok=True)
    n = 0
    for fn in sorted(os.listdir(svg_dir)):
        if fn.endswith(".svg"):
            with open(os.path.join(out_dir, "ic_" + fn[:-4] + ".xml"), "w", newline="\n") as f:
                f.write(convert(os.path.join(svg_dir, fn)))
            n += 1
    print("converted %d icons -> %s" % (n, os.path.normpath(out_dir)))
