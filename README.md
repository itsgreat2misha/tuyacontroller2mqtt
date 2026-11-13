# tuyacontroller2mqtt

An Android daemon for **Tuya smart home central-control panels** — the
wall-mounted Android controllers whose physical
buttons, relays and sensors are wired to an internal MCU rather than to
Android itself. The daemon reads the MCU's serial bus directly and
bridges the panel to **MQTT**, with Home Assistant MQTT discovery, so
the panel's buttons and relays become ordinary Home Assistant entities.
No Tuya cloud account, vendor app, or internet connection is involved.

This is **not** a Tuya cloud or Tuya-local-protocol integration: it
does not talk to Tuya end devices (bulbs, plugs, …), does not use
device IDs or local keys, and does not connect to any Tuya service. It
is also not affiliated with Tuya Inc.; "Tuya" is used only to describe
the class of hardware it runs on.

## Supported devices

Only one model is currently supported, because support means "the
MCU protocol has been captured and a device profile written":

| Model | SoC | Android | Serial device | Buttons | Relays | Sensor | Night light | Status |
|---|---|---|---|---|---|---|---|---|
| F5-3 | Allwinner A133 | 11 | /dev/ttyAS2 @ 115200 | 8 (1–3 relay, 4–8 momentary) | 3 (+1 query-only channel) | Motion | Yes | **Tested** |
| Any other model | — | — | — | — | — | — | — | Untested — needs a device profile |

Hardware differences live in a single `DeviceProfile` in the code, so
supporting another panel is mostly a matter of capturing its frames
and adding a profile. Contributions are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Requirements

- A supported panel (see Supported devices).
- An MQTT broker, and for the Home Assistant entities, Home
  Assistant with the MQTT integration and discovery enabled.
- Optional, feature-dependent:
  - **root (su)** for screen on/off and reboot commands;
  - the **Write Settings** grant for brightness and rotation
    commands; without it those commands are refused and Home
    Assistant reverts to the actual polled value. On a panel with
    `su` the app applies this grant itself at start; otherwise use
    the button in the app's settings screen (it shows whether the
    grant is in place).

## Install

1. Download `tuyacontroller2mqtt.apk` (and its `.sha256`) from the
   [latest release](../../releases/latest) and verify the checksum.
2. Install it on the panel, e.g. `adb install tuyacontroller2mqtt.apk`
   or copy it over and open it.
3. Open the app, enter your MQTT broker host, port, username and
   password, choose TLS or plaintext (see Security), set the device
   name (this becomes the Home Assistant device name; give every
   panel a distinct name), and tap **Save and start daemon**.
4. The daemon connects immediately and publishes discovery, so the
   entities appear in Home Assistant without any button press.
   A 30-second reconnect loop covers broker restarts.

## Configuration

Settings are stored in the app's on-device preferences. They fall
into three groups, by where each one can be set:

### Setup — in the app only

These are entered on the app's settings screen and are not exposed
to Home Assistant or MQTT:

- **Device name** — becomes the Home Assistant device name and the
  MQTT topic slug.
- **MQTT host**, **MQTT port**, **MQTT username**, **MQTT password**.
- **Use TLS** — MQTT over TLS with certificate verification (see
  Security). Enabling it swaps a port field still holding the other
  mode's well-known default (1883 ↔ 8883).

The MQTT password is stored AES/GCM-encrypted with a key in the
Android Keystore; only ciphertext is kept in the app's preferences
(note that on a rooted panel this protects against casual exposure,
not against root itself).

### Options — in the app and in Home Assistant

These three checkboxes on the app's settings screen are the same
settings as the corresponding Home Assistant switches: changing one
in either place updates the same on-device preference, and the
daemon publishes the current state to Home Assistant.

- **Wake Lock** (on by default) — holds a partial wake lock so the
  panel can turn its display off without suspending serial reading.
- **Wake Screen on button press** (on by default; buttons only, not
  motion).
- **Debug** (off by default) — writes rotating diagnostic logs
  (serial frames, metrics, events) to the app's private storage,
  hard-capped at 30 MB total.

### Runtime configuration — in Home Assistant only

- **Relay N Mode** — a per-relay `select` in Home Assistant
  (`relay` or `momentary`; see "Entities" below). There is no
  control for it in the app; the chosen mode is stored on-device.

The remaining Home Assistant entities are controls and state, not
settings; they are listed under Home Assistant entities below.

Autostart: the daemon registers a boot receiver and starts itself
after boot on ROMs that deliver `BOOT_COMPLETED`. Some vendor ROMs
do not deliver it to this app; on those, start the service after
boot with whatever launcher/kiosk tooling you use:
`am start-foreground-service -n com.tuyacontroller2mqtt/.ButtonService`.

## Home Assistant entities (MQTT discovery)

On connect, the daemon publishes MQTT discovery configuration for
one device, named after the panel's device name. All entities share
an availability topic with a Last Will: the daemon publishes
retained `online` on connect, the broker publishes retained
`offline` if it dies (keepalive 60 s, so detection is within ~90 s),
and tracked states are re-published every 5 s so a lost message heals
within one poll tick.

### Entities

The daemon publishes the following entities. The button and relay
numbering comes from the device profile — see Supported devices
for the numbering and counts on each model.

- `event` Button N — physical press events (the momentary-only
  buttons).
- `switch` Relay N — relay state, with `event` Relay N press for
  physical presses. Automations should trigger on the **press event**,
  not on relay state (see PROTOCOL.md for why).
- `select` Relay N Mode — `relay` (default) or `momentary`. In
  momentary mode the switch is retracted and only the press event
  remains; a press that turns the relay on is snapped back off by the
  daemon.
- `binary_sensor` Motion — also published on any physical button
  press (a press can land before the sensor's own trigger); the
  panel's clear frame ends the episode.
- `light` Night Light — brightness only, no colour; state is
  optimistic (the MCU sends no status frame for it).
- `switch` Screen, `number` Screen Brightness, `number` Media Volume
  plus System / Notification / Alarm / Ring / Voice Call volumes,
  `select` Rotation (0/90/180/270), `button` Reboot,
  `switch` Speaker Mute.
- `switch` Wake Lock / Wake Screen with Button / Debug — the daemon
  options above.

## Security

Read this before exposing the daemon beyond a trusted network:

- **Without TLS, MQTT is plaintext.** The broker password and every
  command travel in cleartext, readable by anyone who can observe the
  network path.
- **Anyone who can publish to the broker can operate the panel** —
  toggle its relays, turn its screen on/off, and reboot it. The
  daemon authenticates to the broker, but it has no per-command
  authorization of its own; access control is your broker's job
  (broker accounts and ACLs), in both plaintext and TLS modes.
- **Enable TLS** (the *Use TLS* setting) whenever the broker is not
  on a fully trusted, isolated LAN. TLS uses the standard port 8883
  by default, verifies the broker's certificate against the Android
  system trust store, and checks the hostname. Brokers with
  self-signed certificates are not supported; use a certificate from
  a CA your panel trusts (public CA, or a local CA installed into the
  system store), or keep the panel on a trusted LAN with plaintext.
- Screen on/off and reboot run through `su` where the ROM provides
  it: on a rooted panel this daemon is a root-capable agent. Install
  it only on panels you control.

## Troubleshooting

- **No entities appear in Home Assistant.** Check that the MQTT
  broker is reachable from the panel, that the broker host, port,
  username and password entered in the app are correct, and that
  Home Assistant has the MQTT integration with discovery enabled.
  The daemon publishes discovery when it connects, and a 30-second
  reconnect loop covers broker restarts. All entities share an
  availability topic with a Last Will: the daemon publishes retained
  `online` on connect, and the broker publishes retained `offline`
  if it dies (keepalive 60 s, so detection is within ~90 s).
- **TLS connection fails.** TLS verifies the broker's certificate
  against the Android system trust store and checks the hostname,
  and uses port 8883 by default. Brokers with self-signed
  certificates are not supported; use a certificate from a CA your
  panel trusts (public CA, or a local CA installed into the system
  store), or keep the panel on a trusted LAN with plaintext.
- **Brightness or rotation commands are refused or revert.** Those
  commands require the **Write Settings** grant. With `su` the app
  grants it to itself at start; without `su`, grant it from the
  button in the app's settings screen — if the button reads
  "Write Settings: granted" the grant is in place and something
  else is wrong.
- **Screen on/off or reboot commands do not work.** Those commands
  run through `su` where the ROM provides it, so they require root
  (see Requirements).
- **The daemon does not start after boot.** The daemon registers a
  boot receiver, but some vendor ROMs do not deliver
  `BOOT_COMPLETED` to this app. On those ROMs, start the service
  after boot with whatever launcher/kiosk tooling you use:
  `am start-foreground-service -n com.tuyacontroller2mqtt/.ButtonService`.
- **A relay press does not behave like a switch toggle.** In
  *momentary* relay mode the switch is retracted and only the press
  event remains; a press that turns the relay on is snapped back off
  by the daemon. Automations should trigger on the **press event**,
  not on relay state.

## Limitations

- **Only the models marked as Tested in Supported devices are
  tested.** Any other model is untested and needs a device profile
  before it can be supported (see Supported devices and
  CONTRIBUTING.md).
- **The night light is brightness only**, with no colour control,
  and its state is optimistic because the MCU sends no status frame
  for it.
- **Feature-dependent permissions:** screen on/off and reboot
  require root (`su`); brightness and rotation require the Write
  Settings grant (applied by the app itself when `su` is present).
  Without them, those commands do not work.
- **MQTT authorization is the broker's job.** The daemon
  authenticates to the broker but has no per-command authorization
  of its own, and without TLS MQTT is plaintext (see Security).
- **Self-signed broker certificates are not supported** for TLS
  (see Security).
- **Autostart depends on the ROM** delivering `BOOT_COMPLETED`;
  ROMs that do not deliver it need an external launcher/kiosk tool
  to start the service after boot (see Configuration).

## Build from source

The project builds without Gradle, using the Android SDK
command-line tools directly (see `build.sh`):

```sh
tests/run_tests.sh   # plain-JVM unit tests for the naming/profile logic
ANDROID_SDK=/path/to/android-sdk JAVA_HOME=/path/to/jdk17 ALLOW_DEBUG_SIGNING=1 ./build.sh
```

It needs SDK `platforms;android-30` and `build-tools;30.0.3` and
produces `out/tuyacontroller2mqtt.apk`. **Release builds are signed
with the project's release keystore** (`RELEASE_KEYSTORE` and related
environment variables, see `build.sh`); `ALLOW_DEBUG_SIGNING=1`
exists only for local build checks and produces an APK that must not
be published — an APK signed with any other key cannot upgrade an
existing install.

Release versions follow one rule: `versionName` in
`app/build.gradle` is the source of truth and always equals the git
tag and release name; `versionCode` increases by 1 every release.
See [CHANGELOG.md](CHANGELOG.md).

## More documentation

- [PROTOCOL.md](PROTOCOL.md) — the decoded serial protocol and
  press vs query-batch discernment.
- [SECURITY.md](SECURITY.md) — how to report a vulnerability.
- [CONTRIBUTING.md](CONTRIBUTING.md) — including how to contribute a
  device profile for another panel model.

## License

MIT.
