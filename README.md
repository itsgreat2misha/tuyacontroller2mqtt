# tuyacontroller2mqtt

Android daemon for the Tuya F5-3 entryway panel (Allwinner A133, Android 11)
that turns its 8 below-screen physical buttons and its presence sensor into
Home Assistant entities over MQTT discovery.

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

## Home Assistant entities (MQTT discovery)

- `event.f5_button_4` .. `event.f5_button_8` — press events
- `binary_sensor.f5_relay_1`, `f5_relay_2`, `f5_relay_3` — toggle state, retained
- `binary_sensor.f5_presence` — motion, retained

On first start with the v2 numbering, the daemon publishes empty retained
discovery for the old `f5_button_1`..`5` and `f5_l1`..`l3` topics, so the
old entities are removed from HA instead of lingering as unavailable.

Relay buttons are binary sensors, not switches: no outbound MCU command
to change their state was found, so an HA switch would have nowhere to go.

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
