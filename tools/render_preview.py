"""Renders static PNG previews of the design screens.

The .dc.html files need the design canvas to run, so this script fills in their
sample values itself and screenshots the result with headless Google Chrome.

Usage: python3 tools/render_preview.py design design/preview

Web fonts are not loaded, so the lettering in the previews falls back to a
local font and differs from the canvas (which uses Saira).
"""
import json, os, re, subprocess, sys

HOT, INK = '#FF1A1A', '#FFFFFF'
# keep in step with gen_gauges.py
SHIFT_ON = [4000, 5000, 6000, 7000, 8000]
SHIFT_FLASH = 8200
SHIFT_COL = ['#2FD158', '#FFD60A', '#FFD60A', '#FFD60A', HOT]
SOCKET, LINE = '#0B0B0C', '#3A3A3E'
GC, GS = 162.0, 60.0   # G-meter centre and px per g; keep in step with gen_gauges.py


def shot(html, out, w, h, scale=2):
    html = html.replace('<script src="./support.js"></script>', '')
    html = re.sub(r'<link[^>]*fonts\.g[^>]*>', '', html)
    html = html.replace('<helmet>', '').replace('</helmet>', '')
    html = re.sub(r'<script type="text/x-dc".*?</script>', '', html, flags=re.S)
    tmp = out + '.tmp.html'
    with open(tmp, 'w') as f:
        f.write(html)
    try:
        subprocess.run(['google-chrome', '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
                        f'--force-device-scale-factor={scale}', f'--window-size={w},{h}',
                        f'--screenshot={out}', 'file://' + os.path.abspath(tmp)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120, check=True)
    finally:
        os.remove(tmp)


def needle(v, lo, hi, red, side):
    c = max(lo, min(hi, v))
    t = 150 - 60 * (c - lo) / (100 - lo) if c <= 100 else 90 - 60 * (c - 100) / (hi - 100)
    return {'angle': f'{side*t:.1f}', 'value': str(round(v)), 'color': HOT if v >= red else INK}


def shift(rpm):
    """Same rule as the screens, but a flash is drawn as all five lit (a still image cannot flash)."""
    flash = rpm >= SHIFT_FLASH
    out = []
    for at, col in zip(SHIFT_ON, SHIFT_COL):
        lit = flash or rpm >= at
        c = HOT if flash else col
        out.append({'fill': c if lit else SOCKET, 'edge': c if lit else LINE, 'glow': f'0 0 10px 1px {c}' if lit else 'none', 'anim': 'none'})
    return out


def gauges(src, out):
    html = open(src).read()
    props = json.loads(re.search(r"data-props='(.*?)'", html, flags=re.S).group(1))
    d = lambda k: props[k]['default']
    vals = {}
    for key, n in (('oil', needle(d('oilTemp'), 50, 150, 130, -1)), ('gear', needle(d('gearTemp'), 50, 130, 120, 1))):
        for k, v in n.items():
            vals[f'{key}.{k}'] = v
    vals['g.x'] = f"{GC + d('gLat') * GS:.1f}"
    vals['g.y'] = f"{GC - d('gLon') * GS:.1f}"
    lights = shift(d('rpm'))
    html = re.sub(r'<sc-for list="\{\{shift\}\}"[^>]*>(.*?)</sc-for>',
                  lambda m: ''.join(re.sub(r'\{\{l\.(\w+)\}\}', lambda k: l[k.group(1)], m.group(1)) for l in lights), html, flags=re.S)
    html = re.sub(r'\{\{\s*([\w.]+)\s*\}\}', lambda m: vals.get(m.group(1), m.group(0)), html)
    shot(html, out, 800, 480)


def console(src, out):
    html = open(src).read()
    colours = dict(re.findall(r"(\w+) = '(#[0-9A-Fa-f]{6})'", html))
    rows = re.findall(r"\{ cmd: '(.*?)', reply: '(.*?)', color: (\w+) \}", html)

    def expand(m):
        return ''.join(m.group(1).replace('{{row.cmd}}', a).replace('{{row.reply}}', b).replace('{{row.color}}', colours[c])
                       for a, b, c in rows)

    html = re.sub(r'<sc-for[^>]*>(.*?)</sc-for>', expand, html, flags=re.S)
    shot(html, out, 390, 844)


if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    gauges(f'{src}/Warmup.dc.html', f'{dst}/cold-start.png')
    gauges(f'{src}/Main.dc.html', f'{dst}/driving.png')
    gauges(f'{src}/Hot.dc.html', f'{dst}/hot.png')
    console(f'{src}/Console.dc.html', f'{dst}/console.png')
    print('previews written to', dst)
