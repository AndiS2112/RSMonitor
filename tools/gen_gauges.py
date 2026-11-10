import math, sys
OUT = sys.argv[1]
F = "'Saira', 'Inter', sans-serif"
import os
RAIL = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rail.html')).read()
S = 232; C = S/2          # side gauges
G = 324; GC = G/2; GS = 62  # centre G-meter, px per g
OV = 46                   # overlap

def pt(cx, cy, r, th):
    a = math.radians(th)
    return cx + r*math.sin(a), cy - r*math.cos(a)

def chrome(gid):
    return f'<defs><linearGradient id="chrome{gid}" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="#F2F2F2"></stop><stop offset="0.45" stop-color="#7C7C80"></stop><stop offset="0.7" stop-color="#2A2A2C"></stop><stop offset="1" stop-color="#9A9A9E"></stop></linearGradient><linearGradient id="gloss{gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2E2E32"></stop><stop offset="0.22" stop-color="#050506"></stop><stop offset="0.8" stop-color="#000000"></stop><stop offset="1" stop-color="#151517"></stop></linearGradient></defs>'

def side_svg(lo, hi, labels, red_from, gid, side):
    c = C; mid = 100
    def ang(v):
        t = 150 - 60*(v-lo)/(mid-lo) if v <= mid else 90 - 60*(v-mid)/(hi-mid)
        return side*t
    s = [chrome(gid)]
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-3}" fill="#222225" stroke="url(#chrome{gid})" stroke-width="5"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-20.5}" fill="none" stroke="url(#gloss{gid})" stroke-width="29"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-35}" fill="none" stroke="#3A3A3E" stroke-width="1"></circle>')
    v = lo
    while v <= hi + 0.001:
        th = ang(v)
        if v in labels:
            col = "#FF1A1A" if v >= red_from else "#FFFFFF"
            xa,ya = pt(c,c,c-14,th); xb,yb = pt(c,c,c-28,th)
            s.append(f'<line x1="{xa:.1f}" y1="{ya:.1f}" x2="{xb:.1f}" y2="{yb:.1f}" stroke="{col}" stroke-width="5" stroke-linecap="round"></line>')
        else:
            x,y = pt(c,c,c-20,th)
            r = 2.645 if (v-lo) % 10 == 0 else 1.725
            col = "#FF1A1A" if v >= red_from else "#FFFFFF"
            s.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{col}"></circle>')
        v += 5
    for L in labels:
        x,y = pt(c,c,c-52,ang(L))
        col = "#FF1A1A" if L >= red_from else "#FFFFFF"
        s.append(f'<text x="{x:.1f}" y="{y+8:.1f}" text-anchor="middle" font-family="Saira, sans-serif" font-weight="700" font-size="24" font-style="italic" style="font-stretch: 112.5%" fill="{col}">{L}</text>')
    return "\n".join(s)


COL = "#D2D2D6"
def _teeth(cx, cy, r0, r1, w, n=8, off=0):
    out = []
    for i in range(n):
        d = math.radians(off + i*360/n)
        out.append(f'<line x1="{cx+r0*math.sin(d):.1f}" y1="{cy-r0*math.cos(d):.1f}" x2="{cx+r1*math.sin(d):.1f}" y2="{cy-r1*math.cos(d):.1f}" stroke-width="{w}" stroke-linecap="butt"></line>')
    return "".join(out)
ICON = {
 'oil': ('Engine oil temperature',
   f'<path d="M9 20H21L29 15.5L31 17.5L22.5 28H9Z" fill="{COL}" stroke-width="1.2"></path>'
   f'<path d="M9 23.5L4.5 21.5V18.5L9 20.5" stroke-width="2"></path>'
   f'<path d="M32.5 20.5q-1.8 2.8 0 3.9q1.8-1.1 0-3.9z" fill="{COL}" stroke="none"></path>'
   f'<rect x="13.2" y="4.5" width="4" height="17" rx="2" fill="{COL}" stroke="none"></rect>'
   f'<path d="M18.6 7.5h3M18.6 11h3M18.6 14.5h3" stroke-width="1.6"></path>'),
 'gear': ('Gearbox oil temperature',
   f'<circle cx="15" cy="21" r="8.2" stroke-width="3"></circle>'
   + _teeth(15, 21, 9.4, 12.8, 3.6, 8, 22.5) +
   f'<path d="M15 14.5v6" stroke-width="2.2"></path><circle cx="15" cy="22.6" r="2.7" fill="{COL}" stroke="none"></circle>'
   f'<circle cx="28.5" cy="8.5" r="3" stroke-width="2.2"></circle>'
   + _teeth(28.5, 8.5, 3.9, 6.2, 2.2, 8, 0)),
}
def icon(key, side):
    label, body = ICON[key]
    left = 144 if side < 0 else S - 144 - 38
    return f'''<svg width="38" height="38" viewBox="0 0 36 36" fill="none" stroke="{COL}" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="{label}" style="position: absolute; left: {left}px; top: 90px">{body}</svg>'''

def side_block(key, label, lo, hi, labels, red_from, side):
    read_left = 106 if side < 0 else S - 186
    margin = '' if side < 0 else f' margin-left: -{OV}px;'
    lab_align = 'flex-start' if side < 0 else 'flex-end'
    return f'''<div style="display: flex; flex-direction: column; gap: 10px; width: {S}px; flex-shrink: 0;{margin}">
<div style="position: relative; width: {S}px; height: {S}px">
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
<div style="position: absolute; left: {read_left}px; top: 170px; width: 80px; text-align: center; font-family: {F}; font-style: italic; font-size: 24px; font-weight: 700; color: {{{{{key}.color}}}}; font-variant-numeric: tabular-nums">{{{{{key}.value}}}}<span style="font-size: 13px; font-weight: 600"> °C</span></div>
</div>
<div style="align-self: {lab_align}; width: {S-OV}px; text-align: center; font-family: {F}; font-size: 13px; font-weight: 600; letter-spacing: 0.16em; color: #D6D6D6">{label}</div>
</div>'''

def blob(peaks):
    up, rt, dn, lf = peaks
    pts = []
    for i in range(0, 360, 6):
        a = math.radians(i)
        sx = rt if math.sin(a) >= 0 else lf
        sy = up if math.cos(a) >= 0 else dn
        r = 1/math.sqrt((math.sin(a)/sx)**2 + (math.cos(a)/sy)**2)
        r *= 0.93 + 0.07*math.sin(i*0.37) + 0.04*math.cos(i*0.91)
        pts.append(f"{GC + r*GS*math.sin(a):.1f},{GC - r*GS*math.cos(a):.1f}")
    return " ".join(pts)

def gmeter(peaks):
    up, rt, dn, lf = peaks
    c = GC
    s = [chrome('g')]
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-4}" fill="#222225" stroke="url(#chromeg)" stroke-width="7"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{c-12}" fill="none" stroke="#3C3C40" stroke-width="1"></circle>')
    for d in range(0, 360, 15):
        if d % 90 == 0: continue
        x,y = pt(c,c,c-20,d)
        s.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{2.53 if d % 45 == 0 else 1.61}" fill="#FFFFFF"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{GS*1.5+7:.1f}" fill="#B9B9BC"></circle>')
    s.append(f'<circle cx="{c}" cy="{c}" r="{GS*1.5+2:.1f}" fill="#1C1C20"></circle>')
    for k in (1.5, 1.0, 0.5):
        s.append(f'<circle cx="{c}" cy="{c}" r="{GS*k:.1f}" fill="none" stroke="#5A5A60" stroke-width="1.5"></circle>')
    s.append(f'<polygon points="{blob(peaks)}" fill="#C8102E" fill-opacity="0.45" stroke="#FF2A3D" stroke-width="2" stroke-linejoin="round"></polygon>')
    R = GS*1.5
    s.append(f'<line x1="{c-R:.1f}" y1="{c}" x2="{c+R:.1f}" y2="{c}" stroke="#8E8E94" stroke-width="1"></line>')
    s.append(f'<line x1="{c}" y1="{c-R:.1f}" x2="{c}" y2="{c+R:.1f}" stroke="#8E8E94" stroke-width="1"></line>')
    s.append(f'<circle cx="{{{{g.x}}}}" cy="{{{{g.y}}}}" r="11" fill="#FFFFFF"></circle>')
    T = 'text-anchor="middle" font-family="Saira, sans-serif" font-weight="700" font-size="19" font-style="italic" fill="#F5A623"'
    rr = c - 38
    s.append(f'<text x="{c}" y="{c-rr+7:.1f}" {T}>{up:.2f}</text>')
    s.append(f'<text x="{c}" y="{c+rr+7:.1f}" {T}>{dn:.2f}</text>')
    s.append(f'<text x="{c-rr-2:.1f}" y="{c+7}" {T}>{lf:.2f}</text>')
    s.append(f'<text x="{c+rr+2:.1f}" y="{c+7}" {T}>{rt:.2f}</text>')
    body = "\n".join(s)
    return f'''<div style="position: relative; z-index: 1; width: {G}px; height: {G}px; flex-shrink: 0; margin-left: -{OV}px; border-radius: {GC:.0f}px; box-shadow: 0 0 0 6px #18181A">
<svg width="{G}" height="{G}" viewBox="0 0 {G} {G}" role="img" aria-label="G-meter with peak trace">
{body}
</svg>
</div>'''

def page(title, oil, gear, lat, lon, peaks, banner):
    ban = ''
    if banner:
        ban = f'''<div role="status" style="display: flex; align-items: center; gap: 10px; border: 1px solid #3A3A3E; border-radius: 8px; padding: 6px 14px; font-family: {F}; font-size: 13px; color: #D6D6D6">
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z"></path></svg>
Engine and gearbox still cold — oil below 80 °C
</div>'''
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
a{{color:#FFFFFF}}a:hover{{color:#D6D6D6}}
</style>
</helmet>
<div style="width: 800px; height: 480px; box-sizing: border-box; display: flex; background: #18181A; color: #FFFFFF; font-family: {F}; overflow: hidden">
{RAIL}
<main style="flex: 1; display: flex; flex-direction: column; padding: 12px 14px 14px; box-sizing: border-box; gap: 4px">
<header style="display: flex; align-items: center; justify-content: space-between; height: 24px">
<div style="width: 120px; font-size: 14px; font-weight: 600; letter-spacing: 0.14em">LIVE</div>
<div style="font-family: {F}; font-size: 17px; font-weight: 600; letter-spacing: 0.32em; color: #FFFFFF">RS MONITOR</div>
<div style="width: 120px; display: flex; align-items: center; justify-content: flex-end; gap: 8px; font-size: 12px; color: #9A9A9A; letter-spacing: 0.06em">
<span style="width: 7px; height: 7px; border-radius: 4px; background: #FFFFFF"></span>OBD · 14 Hz
</div>
</header>
{ban}
<div style="flex: 1; display: flex; align-items: center; justify-content: center">
{side_block('oil', 'ENGINE OIL', 50, 150, [50,100,150], 130, -1)}
{gmeter(peaks)}
{side_block('gear', 'GEARBOX OIL', 50, 130, [50,100,130], 120, 1)}
</div>
</main>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"oilTemp":{{"editor":"range","min":40,"max":155,"step":1,"unit":"°C","default":{oil},"section":"Sample readings"}},"gearTemp":{{"editor":"range","min":40,"max":135,"step":1,"unit":"°C","default":{gear},"section":"Sample readings"}},"gLat":{{"editor":"range","min":-1.5,"max":1.5,"step":0.01,"default":{lat},"section":"Sample readings"}},"gLon":{{"editor":"range","min":-1.5,"max":1.5,"step":0.01,"default":{lon},"section":"Sample readings"}},"$preview":{{"width":800,"height":480}}}}'>
class Component extends DCLogic {{
  needle(v, lo, hi, red, side) {{
    var c = Math.max(lo, Math.min(hi, v));
    var t = c <= 100 ? 150 - 60 * (c - lo) / (100 - lo) : 90 - 60 * (c - 100) / (hi - 100);
    return {{ angle: (side * t).toFixed(1), value: Math.round(v), color: v >= red ? '#FF1A1A' : '#FFFFFF' }};
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

open(f'{OUT}/Main.dc.html','w').write(page('RS5 gauges — driving', 104, 88, 0.62, -0.28, (0.36,0.88,0.95,0.82), False))
open(f'{OUT}/Warmup.dc.html','w').write(page('RS5 gauges — cold start', 46, 38, 0.02, 0.01, (0.08,0.10,0.12,0.09), True))
