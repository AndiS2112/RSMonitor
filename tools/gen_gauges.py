"""Draws the polished RS Monitor Android Auto screens (driving + cold start).

Usage: python3 gen_polished.py <output dir>
"""
import math, os, sys

OUT = sys.argv[1]
RAIL = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rail.html')).read()

# ---- tokens ---------------------------------------------------------------
BG = "#18181A"      # screen
FACE = "#222225"    # dial faces
INSET = "#1C1C20"   # G-meter plot
LINE = "#3A3A3E"    # hairlines
INK = "#FFFFFF"
INK2 = "#D6D6D6"    # labels
INK3 = "#A8A8AE"    # secondary text
HOT = "#FF1A1A"     # hot zone, hot readout, G trace
PEAK = "#F5A623"    # G-meter peak numbers
ICON = "#D2D2D6"
F = "'Saira', 'Inter', sans-serif"
# one numeral voice for scale numbers, readouts and G peaks
NUM_SVG = 'font-family="Saira, sans-serif" font-weight="700" font-style="italic" style="font-stretch: 112.5%"'
NUM_CSS = "font-style: italic; font-weight: 700; font-stretch: 112.5%; font-variant-numeric: tabular-nums"
CAPS = "font-weight: 600; letter-spacing: 0.16em"

# ---- geometry -------------------------------------------------------------
S = 232; C = S / 2            # side gauges
G = 324; GC = G / 2; GS = 60  # centre G-meter, px per g
OV = 40                       # how far the side gauges tuck behind the G-meter
BAND = C - 35                 # inner edge of the gloss band
HUB = 24                      # hub ring radius
NUM = 21                      # scale numeral size
DIGIT = 0.66                  # numeral advance in em (Saira expanded, estimated)


def pt(cx, cy, r, th):
    a = math.radians(th)
    return cx + r * math.sin(a), cy - r * math.cos(a)


def defs(gid):
    return (f'<defs><linearGradient id="chrome{gid}" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="#F2F2F2"></stop><stop offset="0.45" stop-color="#7C7C80"></stop><stop offset="0.7" stop-color="#2A2A2C"></stop><stop offset="1" stop-color="#9A9A9E"></stop></linearGradient>'
            f'<linearGradient id="gloss{gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2E2E32"></stop><stop offset="0.22" stop-color="#050506"></stop><stop offset="0.8" stop-color="#000000"></stop><stop offset="1" stop-color="#151517"></stop></linearGradient>'
            f'<linearGradient id="silver{gid}" x1="0" y1="0" x2="0.25" y2="1"><stop offset="0" stop-color="#F0F0F4"></stop><stop offset="0.55" stop-color="#8E8E94"></stop><stop offset="1" stop-color="#C8C8CC"></stop></linearGradient></defs>')


def label_radius(th, text):
    """Largest radius that keeps the numeral 7px clear of the gloss band."""
    hw = len(text) * DIGIT * NUM / 2
    hh = NUM * 0.375
    a = math.radians(abs(th))
    r = 64.0
    while r > HUB + hw:
        x = r * math.sin(a) + hw
        y = abs(r * math.cos(a)) + hh
        if math.hypot(x, y) <= BAND - 7:
            return r
        r -= 0.5
    return r


def side_svg(lo, hi, labels, red_from, gid, side):
    c = C; mid = 100

    def ang(v):
        t = 150 - 60 * (v - lo) / (mid - lo) if v <= mid else 90 - 60 * (v - mid) / (hi - mid)
        return side * t

    s = [defs(gid)]
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-3}" fill="{FACE}" stroke="url(#chrome{gid})" stroke-width="5"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-20.5}" fill="none" stroke="url(#gloss{gid})" stroke-width="29"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{BAND}" fill="none" stroke="{LINE}" stroke-width="1"></circle>')
    v = lo
    while v <= hi + 0.001:
        th = ang(v)
        col = HOT if v >= red_from else INK
        if v in labels:
            xa, ya = pt(c, c, c - 14, th); xb, yb = pt(c, c, c - 28, th)
            s.append(f'<line x1="{xa:.1f}" y1="{ya:.1f}" x2="{xb:.1f}" y2="{yb:.1f}" stroke="{col}" stroke-width="5" stroke-linecap="round"></line>')
        else:
            x, y = pt(c, c, c - 20, th)
            r = 2.645 if (v - lo) % 10 == 0 else 1.725
            s.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{col}"></circle>')
        v += 5
    for L in labels:
        th = ang(L)
        x, y = pt(c, c, label_radius(th, str(L)), th)
        col = HOT if L >= red_from else INK
        s.append(f'<text x="{x:.1f}" y="{y+NUM*0.35:.1f}" text-anchor="middle" font-size="{NUM}" {NUM_SVG} fill="{col}">{L}</text>')
    return "\n".join(s)


def _teeth(cx, cy, r0, r1, w, n=8, off=0):
    out = []
    for i in range(n):
        d = math.radians(off + i * 360 / n)
        out.append(f'<line x1="{cx+r0*math.sin(d):.1f}" y1="{cy-r0*math.cos(d):.1f}" x2="{cx+r1*math.sin(d):.1f}" y2="{cy-r1*math.cos(d):.1f}" stroke-width="{w}" stroke-linecap="butt"></line>')
    return "".join(out)


ICONS = {
    'oil': ('Engine oil temperature',
            f'<path d="M9 20H21L29 15.5L31 17.5L22.5 28H9Z" fill="{ICON}" stroke-width="1.2"></path>'
            f'<path d="M9 23.5L4.5 21.5V18.5L9 20.5" stroke-width="2"></path>'
            f'<path d="M32.5 20.5q-1.8 2.8 0 3.9q1.8-1.1 0-3.9z" fill="{ICON}" stroke="none"></path>'
            f'<rect x="13.2" y="4.5" width="4" height="17" rx="2" fill="{ICON}" stroke="none"></rect>'
            f'<path d="M18.6 7.5h3M18.6 11h3M18.6 14.5h3" stroke-width="1.6"></path>'),
    'gear': ('Gearbox oil temperature',
             f'<circle cx="15" cy="21" r="8.2" stroke-width="3"></circle>'
             + _teeth(15, 21, 9.4, 12.8, 3.6, 8, 22.5) +
             f'<path d="M15 14.5v6" stroke-width="2.2"></path><circle cx="15" cy="22.6" r="2.7" fill="{ICON}" stroke="none"></circle>'
             f'<circle cx="28.5" cy="8.5" r="3" stroke-width="2.2"></circle>'
             + _teeth(28.5, 8.5, 3.9, 6.2, 2.2, 8, 0)),
}
ICON_SIZE = 32


def icon(key, side):
    """Sits on the hub axis, midway between the hub ring and the G-meter's edge."""
    label, body = ICONS[key]
    free = (S - OV - 6) - (C + HUB)            # hub ring edge -> G-meter separation ring
    inset = C + HUB + (free - ICON_SIZE) / 2
    left = inset if side < 0 else S - inset - ICON_SIZE
    return (f'<svg width="{ICON_SIZE}" height="{ICON_SIZE}" viewBox="0 0 36 36" fill="none" stroke="{ICON}" stroke-linecap="round" stroke-linejoin="round" '
            f'role="img" aria-label="{label}" style="position: absolute; left: {left:.0f}px; top: {C-ICON_SIZE/2:.0f}px">{body}</svg>')


def side_block(key, label, lo, hi, labels, red_from, side):
    row_dir = 'row' if side < 0 else 'row-reverse'
    num_align = 'left' if side < 0 else 'right'   # fixed-width readout: the label never shifts as digits change
    margin = '' if side < 0 else f' margin-left: -{OV}px;'
    return f'''<div style="display: flex; flex-direction: column; align-items: center; gap: 10px; width: {S}px; height: {G}px; padding-top: {(G-S)//2}px; box-sizing: border-box; flex-shrink: 0;{margin}">
<div style="position: relative; width: {S}px; height: {S}px; flex-shrink: 0">
<svg width="{S}" height="{S}" viewBox="0 0 {S} {S}" aria-hidden="true">
{side_svg(lo, hi, labels, red_from, key, side)}
</svg>
<div style="position: absolute; left: {C-24:.0f}px; top: {C-24:.0f}px; width: 48px; height: 48px; border-radius: 24px; background: linear-gradient(150deg, #4E4E54 0%, #161618 32%, #2A2A2E 52%, #F0F0F4 78%, #8C8C92 100%); box-shadow: 0 2px 5px rgba(0,0,0,0.6)"></div>
<div style="position: absolute; left: {C-21.5:.1f}px; top: {C-21.5:.1f}px; width: 43px; height: 43px; border-radius: 22px; background: radial-gradient(circle at 50% 42%, #1E1E21 0%, #0E0E10 55%, #050506 100%); box-shadow: inset 0 2px 4px rgba(0,0,0,0.9), inset 0 -1px 1px rgba(255,255,255,0.06)"></div>
<div style="position: absolute; left: {C-6:.0f}px; top: {C-95:.0f}px; width: 12px; height: 101px; transform-origin: 6px 95px; transform: rotate({{{{{key}.angle}}}}deg); filter: drop-shadow(1px 2px 2px rgba(0,0,0,0.65)) drop-shadow(0 0 5px rgba(255,30,20,0.55))">
<div style="width: 12px; height: 101px; background: linear-gradient(90deg, #FF6A58 0%, #FF2C1E 46%, #FFD0C4 50%, #C00A00 54%, #8A0000 100%); clip-path: polygon(40% 0%, 60% 0%, 100% 100%, 0% 100%)"></div>
</div>
<div style="position: absolute; left: {C-9:.0f}px; top: {C-42:.0f}px; width: 18px; height: 61px; transform-origin: 9px 42px; transform: rotate({{{{{key}.angle}}}}deg); filter: drop-shadow(0 2px 2px rgba(0,0,0,0.75))">
<div style="width: 18px; height: 61px; background: linear-gradient(90deg, #060607 0%, #45454B 10%, #111113 20%, #0B0B0C 36%, #303035 44%, #55555C 50%, #303035 56%, #0B0B0C 64%, #111113 80%, #45454B 90%, #060607 100%); clip-path: polygon(30% 0%, 70% 0%, 70% 7%, 84% 11%, 100% 66%, 100% 93%, 84% 100%, 16% 100%, 0% 93%, 0% 66%, 16% 11%, 30% 7%)"></div>
</div>
<div style="position: absolute; left: {C-2:.0f}px; top: {C-2:.0f}px; width: 4px; height: 4px; border-radius: 2px; background: #000000; box-shadow: 0 0 0 1px rgba(255,255,255,0.10)"></div>
{icon(key, side)}
</div>
<div style="display: flex; flex-direction: {row_dir}; align-items: baseline; justify-content: center; gap: 10px; white-space: nowrap; font-family: {F}">
<div style="font-size: 13px; {CAPS}; color: {INK2}">{label}</div>
<div style="min-width: 72px; text-align: {num_align}; font-size: 21px; {NUM_CSS}; color: {{{{{key}.color}}}}">{{{{{key}.value}}}}<span style="font-size: 13px; font-weight: 600; font-stretch: 100%"> °C</span></div>
</div>
</div>'''


def trace(peaks):
    """Sample peak envelope: an ellipse per quadrant with a light, slow wobble."""
    up, rt, dn, lf = peaks
    pts = []
    for i in range(0, 360, 4):
        a = math.radians(i)
        sx = rt if math.sin(a) >= 0 else lf
        sy = up if math.cos(a) >= 0 else dn
        r = 1 / math.sqrt((math.sin(a) / sx) ** 2 + (math.cos(a) / sy) ** 2)
        r *= 0.95 + 0.035 * math.sin(a * 3 + 0.6) + 0.02 * math.cos(a * 7)
        pts.append(f"{GC + r*GS*math.sin(a):.1f},{GC - r*GS*math.cos(a):.1f}")
    return " ".join(pts)


def gmeter(peaks):
    up, rt, dn, lf = peaks
    c = GC
    R = GS * 1.5                 # outer plot ring (1.5 g)
    band = R + 7                 # silver band outer edge
    rim = c - 12                 # bezel inner hairline
    mid = (band + rim) / 2       # peak numbers sit midway between band and rim
    s = [defs('g')]
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-4}" fill="{FACE}" stroke="url(#chromeg)" stroke-width="7"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{rim}" fill="none" stroke="{LINE}" stroke-width="1"></circle>')
    for d in range(0, 360, 15):
        if d % 90 == 0:
            continue
        x, y = pt(c, c, c - 20, d)
        s.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{2.53 if d % 45 == 0 else 1.61}" fill="{INK}"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{band:.1f}" fill="url(#silverg)"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{R+2:.1f}" fill="{INSET}"></circle>')
    for k in (1.5, 1.0, 0.5):
        s.append(f'<circle cx="{c}" cy="{c}" r="{GS*k:.1f}" fill="none" stroke="#5A5A60" stroke-width="1.5"></circle>')
    s.append(f'<polygon points="{trace(peaks)}" fill="{HOT}" fill-opacity="0.32" stroke="{HOT}" stroke-width="2" stroke-linejoin="round"></polygon>')
    s.append(f'<line x1="{c-R:.1f}" y1="{c}" x2="{c+R:.1f}" y2="{c}" stroke="#8E8E94" stroke-width="1"></line>')
    s.append(f'<line x1="{c}" y1="{c-R:.1f}" x2="{c}" y2="{c+R:.1f}" stroke="#8E8E94" stroke-width="1"></line>')
    s.append(f'<circle cx="{{{{g.x}}}}" cy="{{{{g.y}}}}" r="11" fill="{INK}"></circle>')
    T = f'text-anchor="middle" font-size="18" {NUM_SVG} fill="{PEAK}"'
    dy = 18 * 0.35
    s.append(f'<text x="{c}" y="{c-mid+dy:.1f}" {T}>{up:.2f}</text>')
    s.append(f'<text x="{c}" y="{c+mid+dy:.1f}" {T}>{dn:.2f}</text>')
    s.append(f'<text x="{c-mid:.1f}" y="{c+dy:.1f}" {T}>{lf:.2f}</text>')
    s.append(f'<text x="{c+mid:.1f}" y="{c+dy:.1f}" {T}>{rt:.2f}</text>')
    body = "\n".join(s)
    return f'''<div style="position: relative; z-index: 1; width: {G}px; height: {G}px; flex-shrink: 0; margin-left: -{OV}px; border-radius: {GC:.0f}px; box-shadow: 0 0 0 6px {BG}, 0 10px 24px 2px rgba(0,0,0,0.5)">
<svg width="{G}" height="{G}" viewBox="0 0 {G} {G}" role="img" aria-label="G-meter with peak trace">
{body}
</svg>
</div>'''


THERMOMETER = '<path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z"></path>'
WARNING = '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"></path><path d="M12 9v4"></path><path d="M12 17h.01"></path>'
HOT_FILL = "#C8101A"   # warning fill: white text on it passes 4.5:1


def notice_pill(kind):
    """The pill in the bottom slot. 'cold' is quiet; 'hot' is a filled warning."""
    if kind == 'cold':
        edge, fill, type_ = LINE, FACE, f"font-size: 13px; font-weight: 500; color: {INK2}"
        glyph, stroke, text = THERMOMETER, 2, "Engine and gearbox still cold — oil below 80 °C"
    elif kind == 'hot':
        edge, fill, type_ = HOT, HOT_FILL, f"font-size: 14px; font-weight: 700; letter-spacing: 0.02em; color: {INK}"
        glyph, stroke, text = WARNING, 2.4, "Engine and gearbox oil hot — above 130 °C and 120 °C"
    else:
        return ''
    return f'''<div role="status" style="display: flex; align-items: center; gap: 8px; height: 30px; box-sizing: border-box; padding: 0 16px 0 12px; border: 1px solid {edge}; border-radius: 15px; background: {fill}; {type_}; white-space: nowrap">
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="{INK}" stroke-width="{stroke}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">{glyph}</svg>
{text}
</div>'''


def page(title, oil, gear, lat, lon, peaks, state=None):
    notice = notice_pill(state)
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Saira:ital,wdth,wght@0,100..125,500..700;1,100..125,700&amp;family=Inter:wght@500&amp;display=swap" rel="stylesheet">
<style>
body{{margin:0}}
a{{color:{INK}}}a:hover{{color:{INK2}}}
::selection{{background:{HOT};color:{INK}}}
button:focus-visible,a:focus-visible{{outline:2px solid {INK};outline-offset:2px}}
</style>
</helmet>
<div style="width: 800px; height: 480px; box-sizing: border-box; display: flex; background: {BG}; color: {INK}; font-family: {F}; overflow: hidden">
{RAIL}
<main style="flex: 1; display: flex; flex-direction: column; padding: 12px 14px 14px; box-sizing: border-box; gap: 4px">
<header style="display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; height: 24px">
<div style="font-size: 14px; {CAPS}">LIVE</div>
<div style="padding-left: 0.32em; font-size: 17px; font-weight: 600; letter-spacing: 0.32em">RS MONITOR</div>
<div style="display: flex; align-items: center; justify-content: flex-end; gap: 8px; font-size: 13px; font-weight: 500; color: {INK3}; letter-spacing: 0.06em">
<span style="width: 7px; height: 7px; border-radius: 4px; background: {INK}"></span>OBD · 14 Hz
</div>
</header>
<div style="flex: 1; display: flex; align-items: center; justify-content: center">
{side_block('oil', 'ENGINE OIL', 50, 150, [50,100,150], 130, -1)}
{gmeter(peaks)}
{side_block('gear', 'GEARBOX OIL', 50, 130, [50,100,130], 120, 1)}
</div>
<div style="height: 30px; flex-shrink: 0; display: flex; align-items: center; justify-content: center">{notice}</div>
</main>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"oilTemp":{{"editor":"range","min":40,"max":155,"step":1,"unit":"°C","default":{oil},"section":"Sample readings"}},"gearTemp":{{"editor":"range","min":40,"max":135,"step":1,"unit":"°C","default":{gear},"section":"Sample readings"}},"gLat":{{"editor":"range","min":-1.5,"max":1.5,"step":0.01,"default":{lat},"section":"Sample readings"}},"gLon":{{"editor":"range","min":-1.5,"max":1.5,"step":0.01,"default":{lon},"section":"Sample readings"}},"$preview":{{"width":800,"height":480}}}}'>
class Component extends DCLogic {{
  needle(v, lo, hi, red, side) {{
    var c = Math.max(lo, Math.min(hi, v));
    var t = c <= 100 ? 150 - 60 * (c - lo) / (100 - lo) : 90 - 60 * (c - 100) / (hi - 100);
    return {{ angle: (side * t).toFixed(1), value: Math.round(v), color: v >= red ? '{HOT}' : '{INK}' }};
  }}
  renderVals() {{
    var lat = this.props.gLat ?? {lat};
    var lon = this.props.gLon ?? {lon};
    return {{
      oil: this.needle(this.props.oilTemp ?? {oil}, 50, 150, 130, -1),
      gear: this.needle(this.props.gearTemp ?? {gear}, 50, 130, 120, 1),
      g: {{ x: ({GC} + lat * {GS}).toFixed(1), y: ({GC} - lon * {GS}).toFixed(1) }}
    }};
  }}
}}
</script>
</body>
</html>
'''


open(f'{OUT}/Main.dc.html', 'w').write(page('RS Monitor — driving', 104, 88, 0.62, -0.28, (0.36, 0.88, 0.95, 0.82)))
open(f'{OUT}/Warmup.dc.html', 'w').write(page('RS Monitor — cold start', 46, 38, 0.02, 0.01, (0.08, 0.10, 0.12, 0.09), 'cold'))
open(f'{OUT}/Hot.dc.html', 'w').write(page('RS Monitor — hot', 141, 126, -0.55, 0.20, (0.36, 0.88, 0.95, 0.82), 'hot'))
