# RS Monitor

An Android Auto gauge app for an Audi RS5 (B8.5), read over a generic Bluetooth OBD2 adapter.
It shows three things on the car screen:

- Engine oil temperature
- Gearbox (S tronic) oil temperature
- A G-meter with a peak trace

Status: **design mockup only.** No Android code has been written yet.

## What is in this repo

| Path | What it is |
|---|---|
| `design/Main.dc.html` | Android Auto driving screen (800×480) |
| `design/Warmup.dc.html` | Same screen at cold start, with a warm-up notice |
| `design/Console.dc.html` | Phone screen for finding and checking the car's data IDs |
| `design/canvas.json` | Layout of the three screens on the design canvas |
| `tools/gen_gauges.py` | Script that draws the two Android Auto screens |
| `tools/rail.html` | The left-hand Android Auto menu bar used by the script |

The `.dc.html` files are design-canvas source. They load a `support.js` runtime that the
canvas provides, so they do not render on their own in a browser.

To rebuild the two Android Auto screens after changing the script:

```
python3 tools/gen_gauges.py design
```

## Design decisions so far

- **Layout:** G-meter in the centre and largest; engine oil on the left and gearbox oil on
  the right, each tucked behind the G-meter.
- **Engine oil scale:** 50 – 100 – 150 °C, hot zone from 130 °C.
- **Gearbox oil scale:** 50 – 100 – 130 °C, hot zone from 120 °C.
- **100 °C sits at the middle of each scale** (9 o'clock on the left gauge, 3 o'clock on the
  right). On the gearbox gauge this makes the scale uneven: 100–130 °C is stretched over the
  same arc as 50–100 °C.
- **Look:** dark grey screen, charcoal dial faces, chrome rings, white numerals, dots between
  the labelled marks (red in the hot zone), a black gloss band behind the dots on the oil
  gauges, red two-tone needle with a ribbed black cap in a chrome-ringed hub.
- **G-meter:** rings at 0.5, 1.0 and 1.5 g, crosshair, red peak trace, white dot for the
  current reading, four orange peak numbers.
- **Header:** plain "RS MONITOR" text. No manufacturer logos are used.
- **Screen size:** 800×480 is a guess at the MMI screen and has not been checked on the car.
- All readings in the mockup are sample numbers.

## Planned approach for the app

- **Android Auto screen:** Google's Car App Library, sideloaded (not a Play Store app).
- **OBD link:** Kotlin layer talking to an ELM327-type adapter over Bluetooth, sending
  requests to the engine unit (7E0) and gearbox unit (7E1).
- **G-meter:** from the phone's motion sensor, with a calibrate step for how the phone is
  mounted.
- **Phone app:** adapter pairing, plus a raw console for sending commands and checking
  decoded values against VCDS.

First milestone: Bluetooth connection, the raw console, and a test screen on Android Auto
showing live RPM.

## Open questions

1. **Protocol of the engine (01) and gearbox (02) control units: UDS or KWP2000?** VCDS shows
   this on the control unit info screen. UDS works with a generic ELM327; KWP2000 over
   VW TP2.0 is much harder on one. This decides whether the plan works as designed.
2. **The data IDs for engine oil temperature and gearbox oil temperature** are not known yet.
   They have to be found on the car and checked against VCDS.
3. **Which Bluetooth adapter** will be used (classic Bluetooth or Bluetooth Low Energy).
4. **The real hot-zone limits** are the owner's choice (130 °C / 120 °C), not Audi's
   published warning limits.
