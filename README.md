# tuyacontroller2mqtt

An Android daemon for **Tuya smart home central-control panels** — the
wall-mounted Android controllers whose physical
buttons, relays and sensors are wired to an internal MCU rather than to
Android itself. The daemon reads the MCU's serial bus directly and
bridges the panel to **MQTT**, with Home Assistant MQTT discovery, so
the panel's buttons and relays become ordinary Home Assistant entities.
No Tuya cloud account, vendor app, or internet connection is involved.
The MCU protocol was reverse-engineered from the panel's own traffic;
no vendor code is included.

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

"Momentary" buttons only register a press; they are not wired to a
relay. The "+1 query-only channel" is a fourth relay channel that
appears only in the panel's status replies: it has no physical
button and is not exposed as an entity.

Hardware differences live in a single `DeviceProfile` in the code, so
supporting another panel is mostly a matter of capturing its frames
and adding a profile. Contributions are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Requirements

- A supported panel (see Supported devices).
- An MQTT broker. Home Assistant is not required to use the bridge —
  state and commands are plain MQTT topics (see MQTT topics below).
  For the Home Assistant entities, Home Assistant with the MQTT
  integration and discovery enabled.
- Optional, feature-dependent:
  - **root (su)** for screen off and reboot commands. Turning the
    screen on first tries a wake lock, which needs no root, and
    uses `su` only as a fallback;
  - the **Write Settings** grant for brightness and rotation
    commands; without it those commands are refused and Home
    Assistant reverts to the actual polled value. On a panel with
    `su` the app applies this grant itself at start; otherwise use
    the button in the app's settings screen (it shows whether the
    grant is in place).

## Install

1. Download `tuyacontroller2mqtt.apk` (and its `.sha256`) from the
   [latest release](../../releases/latest) and verify the checksum:
   run `sha256sum tuyacontroller2mqtt.apk` and check that the
   output matches the contents of the `.sha256` file.
2. Install it on the panel, e.g. `adb install tuyacontroller2mqtt.apk`
   (this needs USB debugging, under developer options, enabled on
   the panel), or copy the APK to the panel and open it there,
   allowing the install if Android asks.
3. Open the app, enter your MQTT broker host, port, username and
   password, set the device name (this becomes the Home Assistant
   device name; give every panel a distinct name), choose TLS or
   plaintext, and tap **Save and start daemon**. The short version
   of the TLS choice: without TLS the broker password and every
   command travel in cleartext, and anyone who can publish to the
   broker can operate the panel — enable TLS unless the broker is
   on a fully trusted, isolated LAN (see Security).
4. Check that it connected: the daemon connects immediately and
   publishes discovery, so the panel's device appears in Home
   Assistant with its entities available, without any button press.
   A 30-second reconnect loop covers broker restarts.
5. Make it survive a reboot: the daemon registers a boot receiver
   and starts itself after boot on ROMs that deliver
   `BOOT_COMPLETED`. Some vendor ROMs do not deliver it to this app;
   on those, start the service after boot with whatever
   launcher/kiosk tooling you use:
   `am start-foreground-service -n com.tuyacontroller2mqtt/.ButtonService`.

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
  Security). If the port field still holds the other mode's
  default, enabling or disabling TLS swaps it (1883 ↔ 8883).

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

One setting lives only in Home Assistant: **Relay N Mode** — see
Entities below. There is no control for it in the app; the chosen
mode is stored on-device.

The remaining Home Assistant entities are controls and state, not
settings; they are listed under Home Assistant entities below.

### MQTT topics

All topics sit under `tuyacontroller/<device-slug>/`, where the slug
is the device name lowercased, with runs of non-alphanumeric
characters replaced by `_` and leading/trailing underscores dropped
(for example, a panel named "Entryway assistant" publishes under
`tuyacontroller/entryway_assistant/`). State appears on topics such
as `…/motion`, `…/relay/1` and `…/screen/state`; each control has a
command topic of the same shape — for a relay it is the state topic
with `/set` appended (publish `ON` to `…/relay/1/set`), for the
screen it is `…/screen/set` — and reboot is `…/reboot/press` with
payload `PRESS`. The daemon also publishes its availability to
`…/availability` (see Entities).

## Home Assistant entities (MQTT discovery)

On connect, the daemon publishes MQTT discovery configuration for
one device, named after the panel's device name. The daemon
publishes the following entities. The button and relay numbering
comes from the device profile — see Supported devices for the
numbering and counts on each model. Entity IDs follow the device
name: for a panel named "Entryway assistant", Relay 1 appears as
`switch.entryway_assistant_relay_1`.

- `event` Button N — physical press events (the momentary-only
  buttons).
- `switch` Relay N — relay state, with `event` Relay N press for
  physical presses. Automations should trigger on the **press event**,
  not on relay state (see PROTOCOL.md for why).
- `select` Relay N Mode — `relay` (default) or `momentary`. In
  relay mode the relay is an ordinary switch that stays as it is
  set. In momentary mode the switch is retracted and only the press
  event remains; a press that turns the relay on is snapped back
  off by the daemon. Use momentary mode for a relay that should
  only pulse, never stay on.
- `binary_sensor` Motion — also published on any physical button
  press (a press can land before the sensor's own trigger); the
  panel's clear frame ends the episode.
- `light` Night Light — brightness only, no colour; state is
  optimistic (the MCU sends no status frame for it).
- `switch` Screen.
- `number` Screen Brightness.
- `number` Media Volume.
- `number` System Volume.
- `number` Notification Volume.
- `number` Alarm Volume.
- `number` Ring Volume.
- `number` Voice Call Volume.
- `select` Rotation (0/90/180/270).
- `button` Reboot.
- `switch` Speaker Mute.
- `switch` Wake Lock / Wake Screen with Button / Debug — the daemon
  options above.

All entities share an availability topic: the daemon publishes
retained `online` to it on connect, and if the daemon stops
responding the broker publishes retained `offline` (the daemon
checks in every 60 s, so this is detected within about 90 s).
Tracked states are re-published every 5 s, so a lost message heals
on its own.

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
- Screen off and reboot run through `su` where the ROM provides
  it: on a rooted panel this daemon is a root-capable agent. Install
  it only on panels you control.

## Troubleshooting

- **No entities appear in Home Assistant.** Check that the MQTT
  broker is reachable from the panel, that the broker host, port,
  username and password entered in the app are correct, and that
  Home Assistant has the MQTT integration with discovery enabled.
  If the panel's device is in Home Assistant but shows unavailable,
  the daemon is not connected — see the availability topic under
  Home Assistant entities.
- **TLS connection fails.** Check that the broker's certificate
  chains to a CA in the Android system trust store and that its
  hostname matches the name you entered; self-signed certificates
  are not supported (see Security).
- **Brightness or rotation commands are refused or revert.** Those
  commands require the **Write Settings** grant. With `su` the app
  grants it to itself at start; without `su`, grant it from the
  button in the app's settings screen — if the button reads
  "Write Settings: granted" the grant is in place and something
  else is wrong.
- **Screen off or reboot commands do not work.** Those commands
  run through `su`, so they require root (see Requirements).
  Turning the screen on does not: it uses a wake lock first.
- **The daemon does not start after boot.** Start the service with
  the command in Install step 5, using whatever launcher/kiosk
  tooling you use.
- **A relay press does not behave like a switch toggle.** Check
  the relay's mode: in *momentary* mode there is no switch to
  toggle, only the press event (see Entities).

## Limitations

- **Only the models marked as Tested in Supported devices are
  tested.** Any other model is untested and needs a device profile
  before it can be supported (see Supported devices and
  CONTRIBUTING.md).
- **The night light is brightness only**, with no colour control,
  and its state is optimistic because the MCU sends no status frame
  for it.

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
