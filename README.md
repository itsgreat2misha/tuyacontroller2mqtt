# tuyacontroller2mqtt

Android daemon for Tuya Android controllers (starting with the F5-3
entryway panel, Allwinner A133, Android 11) that turns their physical
buttons and motion sensor into Home Assistant entities over MQTT discovery.
Entity names carry no F5 prefix; the HA device name is the panel's Android
device name, and topics/unique_ids are `tuyacontroller/<device-slug>/...`.

## Why this exists

The panel is a Tuya *controller*, not a Tuya *device*: it has no Tuya local
protocol endpoint (no TCP 6668, no UDP broadcast, no device ID/local key),
and its buttons are not Android keys or DPS points. They arrive as 7-byte
frames on an internal RS485 bus, `/dev/ttyAS2` at 115200 baud, parsed by the
vendor app. This daemon reads that bus directly and publishes to MQTT, so no
vendor cloud app is needed. The panel itself runs on a no-internet VLAN.

## Protocol (decoded from captures, confirmed in vendor code)

Port: `/dev/ttyAS2`, 115200 baud raw. Frames are 7 bytes, `fc` ... `fe`.

| Frame | Meaning |
|---|---|
| `fc 00 1c 00 00 00 fe` | Presence on |
| `fc 00 0c 00 00 00 fe` | Presence off (after 30-60 s idle) |
| `fc 00 00 00 01 04 fe` | Button 4 (momentary) |
| `fc 00 00 00 01 05 fe` | Button 5 |
| `fc 00 00 00 01 06 fe` | Button 6 |
| `fc 00 00 00 01 07 fe` | Button 7 |
| `fc 00 00 00 01 08 fe` | Button 8 |
| `fc 00 00 a1 <01/00> 01 fe` | Relay 1, toggle (old L1) |
| `fc 00 00 a2 <01/00> 02 fe` | Relay 2, toggle (old L2) |
| `fc 00 00 a3 <01/00> 03 fe` | Relay 3, toggle (old L3) |

The entity number IS the frame's id/code byte: relays 1-3, then momentary
buttons 4-8 in reverse of their old top-to-bottom order (old buttons 5-1).
Hold = one frame (indistinguishable from a tap). Double-tap = two frames;
detect it by timing in HA if needed. Toggle vs momentary is fixed by the MCU
frame type, not a configurable mode.

## Motion vs presence — research note

The F5 manual calls the sensor "Induction (Radar PIR)" and describes
waving a hand to trigger it, and vendor code calls the frames
`F5MessageSensorChange`, not presence. Traditional PIR detects movement
only; true mmWave presence sensors detect a stationary person (breathing,
micro-motion). No F5 spec claims static detection, and the observed
behaviour (fires on walk-up, clears after 30-60 s idle) fits motion.
So the entity is Motion with `device_class: motion`, not Occupancy.
Decisive test not yet done: stand still in front of the panel for over
60 s — if the sensor stays on, it is static presence and the class/name
should flip to Occupancy/Presence; if it clears despite you still being
there, motion is confirmed.

## Home Assistant entities (MQTT discovery)

- `event` Button 4 .. Button 8 — press events
- `binary_sensor` Relay 1, Relay 2, Relay 3 — toggle state, retained
- `binary_sensor` Motion — `device_class: motion`, retained
- `switch` Wake Lock, Wake Screen — daemon options (below), retained state
- All entities share an availability topic with a Last Will: the daemon
  publishes retained `online` on connect, and the broker publishes
  retained `offline` if the daemon dies (keepalive 60 s, PINGREQ every
  30 s, so detection is within ~90 s). A graceful service stop also
  publishes `offline` first.

On first start with a new numbering/naming scheme, the daemon publishes
empty retained discovery for the old `f5_*` topics, so old entities are
removed from HA instead of lingering as unavailable.

Relay buttons are binary sensors, not switches: no outbound MCU command
to change their state was found, so an HA switch would have nowhere to go.
The two HA switches control daemon options, not relays.

## App behaviour

- Foreground service, holds a `PARTIAL_WAKE_LOCK` (`F5Daemon::serial`) so
  the panel can suspend its display but keep reading the serial port.
- `BootReceiver` starts the service on boot.
- `/dev/ttyAS2` is world read/write (0666) on this panel, so no root is
  needed to open it; the service sets 115200 baud on every start because
  the port defaults to 9600.
- MQTT settings (host, port, user, password) are entered in the app's
  settings screen on the panel. They are never in this repo. The password
  is stored AES/GCM-encrypted with a key in the Android Keystore
  (`PasswordStore.java`); only ciphertext is in SharedPreferences, and a
  legacy plaintext entry is migrated and deleted on first run. Honest
  limit: this panel is rooted with unauthenticated adb, so root could
  still use the Keystore key via system APIs; this stops plaintext
  exposure, it is not a defence against root.
- Device name defaults to the Android device name (`Settings.Global`
  device_name, e.g. "Entryway assistant") and can be edited in settings
  before enabling; it becomes the HA device name in discovery.
- On Save, the service connects immediately and publishes discovery, so
  entities appear without a button press first, and a 30-second reconnect
  loop covers broker restarts.
- Options, in both the app config page and HA switches (same prefs):
  - Wake Lock (on by default): holds a partial wake lock so the panel
    can turn its display off without suspending serial reading. Turning
    it off lets the panel suspend, which stops the daemon until wake.
  - Wake Screen on button press (on by default, buttons only, not
    motion): best-effort via `su -c input keyevent 224` on this rooted
    panel, because a normal app cannot hold a screen wake lock
    (DEVICE_POWER is signature-only). Without su it logs a warning and
    does nothing.
- MQTT is a dependency-free raw MQTT 3.1.1 client (`MqttPublisher.java`).
  Paho was deliberately removed: its ResourceBundle log properties are
  not in a dex, so `MqttClient` construction crashed on Android with
  `MissingResourceException`.

## Build

This project is built without Gradle, with the Android SDK command-line
tools directly (Gradle's sdkmanager choked on a proxied VM). See `build.sh`:

```sh
ANDROID_SDK=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 ./build.sh
```

It needs SDK `platforms;android-30` and `build-tools;30.0.3`, and produces
`out/f5daemon.apk` (debug-signed). Install with:

```sh
adb install -r out/f5daemon.apk
adb shell am start-foreground-service -n com.misha.f5daemon/.ButtonService
```

## UI notes

The settings screen is a ScrollView so the small panel screen + keyboard
cannot hide Save. Fields are single-line, Enter moves to the next field,
and the password field is masked with a Show password checkbox.
The activity has no orientation lock and handles rotation itself, so it
follows the system rotation: on a vertically mounted panel, set
`settings put system user_rotation 1` (or 3, for the other direction)
— the panel has no accelerometer, so sensor auto-rotate cannot work,
but the fixed system rotation persists across reboots.

## Known open items (Oct 29, 2026)

- Boot autostart did NOT fire on the first reboot test: no service or
  daemon log at 4 min uptime, though the system BOOT_COMPLETED broadcast
  finished, and a manual BOOT_COMPLETED broadcast started the service
  immediately. `BootReceiver` now logs on receipt, so the next reboot
  test will show whether delivery or the service start is at fault.
- Wake screen via su failed on first live test (`exit=1` from the app
  uid, while the same command works from adb shell as root). The service
  now logs su's output as well as its exit code, to tell a su denial
  apart from an `input` failure.
- The panel clock resets to January on reboot (RTC persistence unresolved).
- MQTT client id is per-device (`tuyacontroller-<device-slug>`), so a
  second panel does not kick the first off the broker; two panels with
  the same Android device name would still collide on unique_ids, so
  give each panel a distinct device name in settings.
