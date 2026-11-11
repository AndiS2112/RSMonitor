# RS Monitor

An Android Auto gauge app for an Audi RS5 (B8.5), read over a generic Bluetooth OBD2 adapter.
It shows three things on the car screen:

- Engine oil temperature
- Gearbox (S tronic) oil temperature
- A G-meter with a peak trace
- A five-stage shift light

Status: **design done, app partly built.** In the car, the app connects to the adapter,
reaches the engine and gearbox, and shows on Android Auto with live engine speed. The gauge
screen from the design is drawn, but the oil temperatures and the G-meter have no data yet.

## The design

| Cold start | Driving | Hot |
|---|---|---|
| ![Cold start](design/preview/cold-start.png) | ![Driving](design/preview/driving.png) | ![Hot](design/preview/hot.png) |

The phone screen for finding and checking the car's data IDs:

<img src="design/preview/console.png" alt="Discovery console" width="300">

The previews are rendered without the web font, so the lettering falls back to a local font.
On the design canvas the numbers and labels are set in Saira.

## The app (milestone 1)

**What it does**

- Finds the OBD adapter, over classic Bluetooth or Bluetooth LE, and sets it up for the car.
- Discovery console on the phone: type a command, see the reply, switch between the engine
  unit (7E0) and the gearbox unit (7E1).
- Live engine speed, read from the engine unit as fast as the adapter allows.
- Android Auto gauge screen, drawn from the design: the three gauges, the shift lights and
  the notice. Only the shift lights have live data so far. The oil gauges show a dash until
  their data IDs are found, and the G-meter shows dashes until the motion sensor is wired in.

**Getting the APK**

Every push that touches the app is built by GitHub Actions. Open the repo's Actions tab, pick
the latest "Build APK" run, and download the `rs-monitor-debug-apk` artifact. Unzip it and
install `app-debug.apk` on the phone. Each build is signed with a fresh debug key, so uninstall
the previous build before installing a new one.

To build on a computer instead, open the repo in Android Studio.

**First run**

1. Plug the adapter into the car, switch it on, and turn the ignition on.
2. Open RS Monitor on the phone, allow Bluetooth, and pick the adapter.
3. In the console, send `0105`. A reply containing `41 05` means the engine unit answers
   (that value is coolant temperature).
4. Switch to Gearbox and send `22 F1 90`. Any reply starting `7E9` means the adapter can aim
   a request at the gearbox unit, which is what the oil-temperature gauges depend on.
5. Turn on live engine speed and check the number against the tachometer.

**Finding the oil-temperature IDs**

The "Find IDs" tab asks the selected control unit for every data ID in a range and lists the
ones that answer, with the raw bytes and what they would mean as a temperature under the two
formulas VW-group units most often use. It only reads values. To pin an ID down, scan once
cold and once warm and look for the value that tracks the oil temperature on the dash (for
the gearbox, against a VCDS reading).

**On the car screen**

Installing the APK the normal way is not enough. Android Auto only shows an app built with the
Car App Library if Android has it recorded as installed from Google Play, and the "Unknown
sources" developer setting does not change that for this kind of app.

- **What works on this phone:** installing the APK with the UnknownAuto tool
  (github.com/GitMag/UnknownAuto), which uses Shizuku to record Google Play as the installer.
- **Not tried:** from a computer, `adb install -i com.android.vending app-debug.apk`.
- **The route Google documents:** a Play Console internal test track.

After installing, reconnect the phone to the car. If RS Monitor is missing, check "Customize
launcher" in Android Auto's settings on the phone.

**Code layout**

| Path | What it is |
|---|---|
| `app/src/main/java/com/rsmonitor/app/obd/` | Bluetooth links, the ELM327 command layer, reply parsing, and the shared connection |
| `app/src/main/java/com/rsmonitor/app/ui/` | The phone screen (discovery console) |
| `app/src/main/java/com/rsmonitor/app/car/` | The Android Auto screen and the shift-light rule |
| `app/src/test/` | Unit tests for reply parsing and the shift-light rule, and a test that draws the gauge screen to PNG files |
| `.github/workflows/build.yml` | Builds and tests the app on every push |

## What is in this repo

| Path | What it is |
|---|---|
| `design/Warmup.dc.html` | Android Auto screen at cold start, with a warm-up notice |
| `design/Main.dc.html` | Android Auto screen while driving at normal temperatures |
| `design/Hot.dc.html` | Android Auto screen with both oils in the hot zone |
| `design/Console.dc.html` | Phone screen for finding and checking the car's data IDs |
| `design/canvas.json` | Layout of the four screens on the design canvas |
| `design/preview/` | PNG previews of the four screens |
| `tools/gen_gauges.py` | Script that draws the three Android Auto screens |
| `tools/rail.html` | The left-hand Android Auto menu bar used by the script |
| `tools/render_preview.py` | Script that renders the PNG previews |

The `.dc.html` files are design-canvas source. They load a `support.js` runtime that the
canvas provides, so they do not render on their own in a browser. Use the previews to look
at the design here.

To rebuild after changing the gauge script (the previews need Google Chrome installed):

```
python3 tools/gen_gauges.py design
python3 tools/render_preview.py design design/preview
```

## Design decisions

**Layout**

- Screen is 800×480. This is a guess at the MMI screen and has not been checked on the car.
- G-meter in the centre and largest (324 px). Engine oil on the left and gearbox oil on the
  right (232 px each), tucked 40 px behind the G-meter, all three on one centre line.
- Each oil gauge has its name and precise temperature on one line underneath: reading to the
  right of "ENGINE OIL", to the left of "GEARBOX OIL". The reading has a fixed width so the
  label does not move as digits change.
- A row of five shift lights sits under the title, above the G-meter.
- A slot at the bottom is reserved for notices, so the gauges do not move when one appears.

**States**

- **Cold start:** notice "Engine and gearbox still cold — oil below 80 °C".
- **Driving:** no notice.
- **Hot:** the reading turns red once the oil is in its hot zone, and a filled red warning
  notice appears: "Engine and gearbox oil hot — above 130 °C and 120 °C". It is deliberately
  bolder than the cold notice. The wording for only one oil being hot is not written yet.

**Shift light**

| Light | Colour | Comes on at |
|---|---|---|
| 1 | Green | 4,000 rpm |
| 2 | Yellow | 5,000 rpm |
| 3 | Yellow | 6,000 rpm |
| 4 | Yellow | 7,000 rpm |
| 5 | Red | 8,000 rpm |

- From 8,200 rpm all five flash red: upshift.
- Lights stay on once passed, so they fill from left to right. Unlit lights show as dark sockets.
- The flash is four times a second and stops if the device asks for reduced motion.
- The sample screens show the three cases: cold start at 1,500 rpm (all off), driving at
  6,300 rpm (green and two yellow), hot at 8,300 rpm (flashing; the preview image shows all
  five lit, since a still image cannot flash).

**Scales**

- Engine oil: 50 – 100 – 150 °C, hot zone from 130 °C.
- Gearbox oil: 50 – 100 – 130 °C, hot zone from 120 °C.
- 100 °C sits at the middle of each scale (9 o'clock on the left gauge, 3 o'clock on the
  right). On the gearbox gauge this makes the scale uneven: 100–130 °C is stretched over the
  same arc as 50–100 °C.
- The hot-zone limits are the owner's choice, not Audi's published warning limits.

**Look**

- Dark grey screen, charcoal dial faces, chrome rings, a black gloss band behind the dots on
  the oil gauges.
- White numerals; dots between the labelled marks, red in the hot zone.
- Red two-tone needle with a ribbed black cap in a chrome-ringed hub.
- An icon beside each hub: oil can with thermometer, gear with thermometer.
- G-meter: rings at 0.5, 1.0 and 1.5 g, crosshair, red peak trace, white dot for the current
  reading, four orange peak numbers.
- Header is plain "RS MONITOR" text. No manufacturer logos are used.
- All readings in the design are sample numbers.

**Palette and type**

| Use | Value |
|---|---|
| Screen | `#18181A` |
| Dial faces, panels | `#222225` |
| G-meter plot | `#1C1C20` |
| Hairlines | `#3A3A3E` |
| Text | `#FFFFFF`, labels `#D6D6D6`, secondary `#A8A8AE` |
| Hot zone, hot reading, G trace | `#FF1A1A` |
| Hot notice fill | `#C8101A` |
| G-meter peak numbers, "waiting" in the console | `#F5A623` |
| Shift lights | green `#2FD158`, yellow `#FFD60A`, red `#FF1A1A`, unlit `#0B0B0C` |

Saira throughout; numbers are bold italic at 112.5% width. The console log uses JetBrains Mono.

## Planned approach for the app

- **Android Auto screen:** Google's Car App Library, sideloaded (not a Play Store app).
- **OBD link:** Kotlin layer talking to an ELM327-type adapter over Bluetooth, sending
  requests to the engine unit (7E0) and gearbox unit (7E1).
- **Shift light:** from engine speed, which is a standard OBD2 value (PID 0C), so it needs no
  Audi-specific data ID. It has to be polled far more often than the two temperatures.
- **G-meter:** from the phone's motion sensor, with a calibrate step for how the phone is
  mounted.
- **Phone app:** adapter pairing, plus the discovery console for sending commands and checking
  decoded values against VCDS.

First milestone: Bluetooth connection, the discovery console, and a test screen on Android
Auto showing live RPM.

## Known about the car and the adapter

- **Both control units use UDS.** Checked in VCDS for engine (01) and gearbox (02). A generic
  ELM327 can therefore read both: a `22` request plus a two-byte data ID, sent to 7E0 for the
  engine and 7E1 for the gearbox.
- **Adapter:** a generic ELM327 "mini" with a power button (sold as Kungfuren OBD2 4.0). It
  must be switched on to be seen.
- **The adapter can reach both control units.** Tested in the car: the engine unit answers
  `0105` from `7E8`, and with the gearbox selected a `22 F1 90` request is answered from `7E9`.
- **Engine speed updates about 13 times a second** on this adapter, with only engine speed
  being polled.
- **The app shows on the car screen** once it is installed so that Android records Google Play
  as the installer.
- **The car gives the app a 1180×630 px surface at 166 dpi, of which 1156×606 is usable.**
  Android Auto's menu bar runs along the bottom, outside that area. The panel itself is the
  800×480 MMI 3G+ screen; the Android Auto box scales the picture down to it. The gauge screen
  scales the design to whatever area it is given, so it fits by height.

## Open questions

1. **The data IDs for engine oil temperature and gearbox oil temperature** are not known yet.
   They have to be found on the car and checked against the dash display and VCDS.
3. **Whether the adapter is classic Bluetooth or Bluetooth LE.** The app handles both, so this
   only matters for which entry to pick in the adapter list.
4. **Hot notice wording when only one oil is hot** (engine only, gearbox only).
5. **How far the shift light lags the engine.** Each reading goes adapter → Bluetooth → phone →
   Android Auto → car screen. In a low gear the revs can climb several hundred rpm in that
   time, so the lights may need to come on earlier than the figures above. This has to be
   measured on the car.
