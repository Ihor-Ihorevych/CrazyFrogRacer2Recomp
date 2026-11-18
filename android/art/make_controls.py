#!/usr/bin/env python3
"""Draws the on-screen control faces as SVG into art/svg/ (then: python svg2vd.py svg ../res/drawable).

Every control is a whole button face: a dark glass base (so it reads on bright and dark scenes), a coloured ring and a coloured symbol
(PlayStation colours for the face buttons). Round buttons are 96x96, the shoulder / Start / Select pills 160x100. Letters are stroked paths
(svg2vd.py has no text). TouchOverlay draws these full size and only adds the pressed glow.
"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "svg")
BASE = "#0E1218"
WHITE = "#FFFFFF"
GREEN, RED, BLUE, PINK, TEAL, AMBER, CYAN = "#4DE38E", "#FF5A5F", "#7FB2FF", "#FF8FD8", "#3DDBA8", "#FFC23D", "#5CC8FF"


def svg(w, h, body):
    return '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">\n%s\n</svg>\n' % (w, h, w, h, body)


def disc(accent):
    return ('<circle cx="48" cy="48" r="44" fill="%s" opacity="0.62"/>\n'
            '<circle cx="48" cy="48" r="37" fill="none" stroke="%s" stroke-width="2" opacity="0.22"/>\n'
            '<circle cx="48" cy="48" r="44" fill="none" stroke="%s" stroke-width="4"/>' % (BASE, WHITE, accent))


def pill(accent):
    return ('<rect x="4" y="6" width="152" height="88" rx="32" fill="%s" opacity="0.62"/>\n'
            '<rect x="11" y="13" width="138" height="74" rx="26" fill="none" stroke="%s" stroke-width="2" opacity="0.22"/>\n'
            '<rect x="4" y="6" width="152" height="88" rx="32" fill="none" stroke="%s" stroke-width="4"/>' % (BASE, WHITE, accent))


# stroked letters, top-left x, y, height 30 (width ~17)
def letter(ch, x, y):
    b = y + 30
    if ch == "L":
        return "M%d %dV%dH%d" % (x, y, b, x + 16)
    if ch == "R":
        return "M%d %dV%dH%dQ%d %d %d %dQ%d %d %d %dH%dM%d %dL%d %d" % (x, b, y, x + 9, x + 18, y, x + 18, y + 8, x + 18, y + 15, x + 9, y + 15, x, x + 8, y + 15, x + 17, b)
    if ch == "1":
        return "M%d %dL%d %dV%d" % (x + 2, y + 6, x + 10, y, b)
    if ch == "2":
        return "M%d %dQ%d %d %d %dQ%d %d %d %dQ%d %d %d %dL%d %dH%d" % (x, y + 7, x + 1, y, x + 9, y, x + 18, y, x + 18, y + 8, x + 18, y + 14, x + 11, y + 19, x, b, x + 18)
    raise ValueError(ch)


def word(text, cx, y, colour, width=6):
    total = len(text) * 18 + (len(text) - 1) * 8
    x = cx - total // 2
    paths = []
    for ch in text:
        paths.append(letter(ch, x, y))
        x += 26
    return '<path d="%s" fill="none" stroke="%s" stroke-width="%d" stroke-linecap="round" stroke-linejoin="round"/>' % (" ".join(paths), colour, width)


def badge(kind, colour):
    """A small face-button mark in the lower right of a round button (which PS2 button it is)."""
    s = '<circle cx="74" cy="74" r="14" fill="%s"/>\n<circle cx="74" cy="74" r="14" fill="none" stroke="%s" stroke-width="2.5"/>\n' % (BASE, colour)
    if kind == "cross":
        s += '<path d="M68 68L80 80M80 68L68 80" fill="none" stroke="%s" stroke-width="3.5" stroke-linecap="round"/>' % colour
    else:
        s += '<rect x="68" y="68" width="12" height="12" rx="1.5" fill="none" stroke="%s" stroke-width="3.5"/>' % colour
    return s


icons = {}

# face buttons
icons["triangle"] = (96, 96, disc(TEAL) + '\n<path d="M48 25L70 64H26Z" fill="%s" opacity="0.18"/>\n<path d="M48 25L70 64H26Z" fill="none" stroke="%s" stroke-width="7" stroke-linejoin="round"/>' % (TEAL, TEAL))
icons["circle"] = (96, 96, disc(RED) + '\n<circle cx="48" cy="48" r="19" fill="%s" opacity="0.18"/>\n<circle cx="48" cy="48" r="19" fill="none" stroke="%s" stroke-width="7"/>' % (RED, RED))
icons["cross"] = (96, 96, disc(BLUE) + '\n<path d="M32 32L64 64M64 32L32 64" fill="none" stroke="%s" stroke-width="8" stroke-linecap="round"/>' % BLUE)
icons["square"] = (96, 96, disc(PINK) + '\n<rect x="30" y="30" width="36" height="36" rx="4" fill="%s" opacity="0.18"/>\n<rect x="30" y="30" width="36" height="36" rx="4" fill="none" stroke="%s" stroke-width="7"/>' % (PINK, PINK))

# gas: a pedal with speed lines, green; the Cross mark in the corner
icons["accelerate"] = (96, 96, disc(GREEN) + """
<path d="M40 18H58Q64 18 63.5 24L60.5 62Q60 70 52 70H46Q38 70 37.5 62L34.5 24Q34 18 40 18Z" fill="%s" opacity="0.25"/>
<path d="M40 18H58Q64 18 63.5 24L60.5 62Q60 70 52 70H46Q38 70 37.5 62L34.5 24Q34 18 40 18Z" fill="none" stroke="%s" stroke-width="5" stroke-linejoin="round"/>
<path d="M40 32H58M40 44H58M41 56H57" fill="none" stroke="%s" stroke-width="3.5" stroke-linecap="round"/>
<path d="M18 36H27M15 48H27M18 60H27" fill="none" stroke="%s" stroke-width="4" stroke-linecap="round"/>
""" % (GREEN, GREEN, WHITE, GREEN) + badge("cross", BLUE))

# brake: a brake disc with its caliper, red; the Square mark in the corner
icons["brake"] = (96, 96, disc(RED) + """
<circle cx="44" cy="48" r="21" fill="%s" opacity="0.2"/>
<circle cx="44" cy="48" r="21" fill="none" stroke="%s" stroke-width="5"/>
<circle cx="44" cy="48" r="7" fill="%s"/>
<circle cx="44" cy="35" r="2.5" fill="%s"/><circle cx="31" cy="48" r="2.5" fill="%s"/><circle cx="44" cy="61" r="2.5" fill="%s"/>
<path d="M52 24Q66 30 68 44" fill="none" stroke="%s" stroke-width="9" stroke-linecap="round"/>
""" % (RED, RED, WHITE, WHITE, WHITE, WHITE, RED) + badge("square", PINK))

# R1 as a round button beside the gas: amber letters and a hop arc
# R1 (items: the rocket) beside the gas: a rocket flying up and right, amber, with an R1 mark in the corner
ROCKET = """<g transform="rotate(45 48 46)">
<path d="M40 60Q48 80 56 60Z" fill="#FF7A2E"/>
<path d="M43 60Q48 72 53 60Z" fill="#FFE07A"/>
<path d="M38 48L27 63L38 60Z" fill="%s"/>
<path d="M58 48L69 63L58 60Z" fill="%s"/>
<path d="M48 13Q61 25 60 46V61H36V46Q35 25 48 13Z" fill="%s" opacity="0.25"/>
<path d="M48 13Q61 25 60 46V61H36V46Q35 25 48 13Z" fill="none" stroke="%s" stroke-width="5" stroke-linejoin="round"/>
<circle cx="48" cy="37" r="6" fill="none" stroke="%s" stroke-width="3.5"/>
</g>""" % (AMBER, AMBER, AMBER, AMBER, WHITE)
R1_MARK = ('<circle cx="74" cy="74" r="14" fill="%s"/>\n<circle cx="74" cy="74" r="14" fill="none" stroke="%s" stroke-width="2.5"/>\n'
           '<g transform="translate(74 74) scale(0.42) translate(-48 -15)">%s</g>' % (BASE, AMBER, word("R1", 48, 0, AMBER, 7)))
icons["r1"] = (96, 96, disc(AMBER) + "\n" + ROCKET + "\n" + R1_MARK)

# shoulder pills
for name, text in (("l1", "L1"), ("l2", "L2"), ("r2", "R2")):
    icons[name] = (160, 100, pill(CYAN) + "\n" + word(text, 80, 35, WHITE, 6))

# Start: play mark; Select: two bars
icons["start"] = (160, 100, pill(WHITE) + '\n<path d="M68 32L98 50L68 68Z" fill="%s" opacity="0.9"/>' % WHITE)
icons["select"] = (160, 100, pill(WHITE) + '\n<path d="M60 40H100M60 60H100" fill="none" stroke="%s" stroke-width="7" stroke-linecap="round"/>' % WHITE)

# stick: base ring with direction marks, and the thumb
icons["stick_base"] = (96, 96, """<circle cx="48" cy="48" r="45" fill="%s" opacity="0.5"/>
<circle cx="48" cy="48" r="45" fill="none" stroke="%s" stroke-width="3" opacity="0.75"/>
<circle cx="48" cy="48" r="30" fill="none" stroke="%s" stroke-width="1.5" opacity="0.2"/>
<path d="M42 13L48 7L54 13M42 83L48 89L54 83M13 42L7 48L13 54M83 42L89 48L83 54" fill="none" stroke="%s" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" opacity="0.8"/>""" % (BASE, WHITE, WHITE, CYAN))
icons["stick_thumb"] = (96, 96, """<circle cx="48" cy="48" r="44" fill="#2A3442" opacity="0.92"/>
<circle cx="48" cy="48" r="44" fill="none" stroke="%s" stroke-width="5"/>
<circle cx="48" cy="48" r="30" fill="none" stroke="%s" stroke-width="2.5" opacity="0.35"/>
<circle cx="48" cy="48" r="16" fill="%s" opacity="0.25"/>""" % (CYAN, WHITE, CYAN))

# D-pad (the stick / D-pad switch shows it)
icons["dpad"] = (96, 96, """<path d="M36 6H60V36H90V60H60V90H36V60H6V36H36Z" fill="%s" opacity="0.62"/>
<path d="M36 6H60V36H90V60H60V90H36V60H6V36H36Z" fill="none" stroke="%s" stroke-width="4" stroke-linejoin="round"/>
<path d="M48 14L55 24H41ZM48 82L55 72H41ZM14 48L24 41V55ZM82 48L72 41V55Z" fill="%s"/>""" % (BASE, WHITE, CYAN))

os.makedirs(OUT, exist_ok=True)
for name, (w, h, body) in icons.items():
    with open(os.path.join(OUT, name + ".svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(svg(w, h, body))
print("wrote", len(icons), "icons:", " ".join(sorted(icons)))
