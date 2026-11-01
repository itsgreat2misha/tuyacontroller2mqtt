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

Outbound, found in vendor `McuTestActivity` and proven live Oct 30, 2026
(note the last data byte is `00` outbound, unlike the relay number
inbound):

| Frame | Meaning |
|---|---|
| `fc 00 00 a1 01 01 fe` / `fc 00 00 a1 00 01 fe` | Relay 1 on / off (real switch frame, last byte = relay id) |
| `fc 00 00 a2 01 02 fe` / `fc 00 00 a2 00 02 fe` | Relay 2 on / off |
| `fc 00 00 a3 01 03 fe` / `fc 00 00 a3 00 03 fe` | Relay 3 on / off |
| `fc 00 00 aa ff 00 fe` | Query relay states -> batch `a1/a2/a3/a4` |
| `fc 00 1d 01 00 00 fe` | Night light on |
| `fc 00 1d 00 00 00 fe` | Night light off |
| `fc 00 1d 02 <00-64 hex> 00 fe` | Night light brightness 0-100 |

McuTest frames (`...00FE`) are TEST frames: they change the MCU's reported/query state but NOT the button LED or the actual relay -- that was the v5/v6 bug. The real set frame is vendor A485McuUtil.send() for an unbound local switch, identical to an inbound press frame (last byte = relay id).

A virtual relay set produces NO immediate frame; the new state is only
visible via a subsequent query batch. A physical press produces an
immediate unsolicited single frame. The daemon therefore publishes relay
state on every frame, but a press event only for frames that are not part
of a query batch and do not match a just-sent set. HA toggle automations
must trigger on the press event, not on state, otherwise a lights->relay
sync (external light change -> relay switch) re-triggers the toggle and
loops. Relay 4 appears in every query batch (`a4`, code `00`) but has no
physical button and no `/proc/vendor/relay_four` file; it is state-only,
not exposed as a switch. `/proc/vendor/relay_first/second/third` do NOT
reflect MCU relay state (verified live: they stayed `off` while the MCU
reported Relay 1 on).

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
- `switch` Relay 1, Relay 2, Relay 3 — toggle state, retained, command
  topic writes the outbound MCU frame above, then queries to confirm.
  v6: serial writes are spaced 200 ms apart (back-to-back nightlight
  ON->brightness frames were not reliably acted on in v5); relay-4
  query marker fixed (its inbound code byte is 0, f[3]=0xa4 is the
  marker, not f[5])
- `event` Relay 1 press, Relay 2 press, Relay 3 press — physical presses
  only (see press vs virtual note above); toggle automations should
  trigger on these, not on relay state
- `binary_sensor` Motion — `device_class: motion`, retained
- `light` Night Light — side-edge LED bar, brightness 0-100 only, no
  colour (vendor code has no colour API); state is optimistic, the MCU
  sends no status frame for it
- `switch` Screen — on/off via su keyevents (see below)
- `number` Screen Brightness — 0-100%, forces manual mode
- `number` Media Volume (STREAM_MUSIC, renamed from Music Volume in v6)
  — 0-100%, plus System, Notification, Alarm, Ring, and Voice Call
  volume numbers (all % of their stream max; Android has no single
  master volume, MUSIC is what volume buttons / media use). Music Mute,
  Audio Source, and Horn were removed in v6 (unused).
- `select` Rotation — 0/90/180/270 degrees (system rotation, panel has
  no accelerometer)
- `button` Reboot — su reboot, no state
- `switch` Speaker Mute, `select` Audio Source (local/aux), `switch`
  Horn — direct writes to world-writable `/proc/vendor` nodes, no su
- `switch` Wake Lock, Wake Screen with Button — daemon options (below),
  retained state. All tracked states (screen, brightness, volumes,
  rotation, relays, motion, wake options, nightlight) are re-published
  every 5 s in v6, so a lost QoS 0 message heals within one poll tick
  instead of leaving HA stale until the next change.
- All entities share an availability topic with a Last Will: the daemon
  publishes retained `online` on connect, and the broker publishes
  retained `offline` if the daemon dies (keepalive 60 s, PINGREQ every
  30 s, so detection is within ~90 s). A graceful service stop also
  publishes `offline` first.

On first start with a new numbering/naming scheme, the daemon publishes
empty retained discovery for the old `f5_*` topics, and v5 publishes
empty discovery for the old relay `binary_sensor` topics, so old entities
are removed from HA instead of lingering as unavailable. Note: relay
entity ids change from `binary_sensor.*_relay_*` to `switch.*_relay_*`
in v5; automations referencing the old ids need updating.

Brightness, volume, screen state, rotation, and `/proc` audio states are
polled every 5 seconds and published on change, so changes made from
Android Settings or Kiosk update HA, not just changes made from HA.

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
    motion): screen wake lock first, then su on this rooted panel.
  - Debug (off by default, HA switch "Debug" or app checkbox): one
    global toggle, no auto-off, safe to leave on indefinitely.
    Writes three rotating log files in the daemon's private
    storage (`/data/data/com.misha.f5daemon/files/diag/`, pull
    with adb): `serial.log` (every frame in/out, hex + decoded,
    plus dropped/bad frames), `metrics.log` (a snapshot every
    30 s plus event-triggered snapshots on motion frames, MQTT
    connect/disconnect and serial reopen), and `events.log`
    (lifecycle and errors). Hard total cap 30 MB (serial 5 MB,
    metrics 20 MB, events 5 MB, oldest files deleted on rotation),
    plus a pause if /data free space drops below 100 MB, so it
    cannot eat panel storage. Frame-loss fixes in the same
    version: the serial reader reopens ttyAS2 with backoff
    instead of dying silently on an error (a dead reader used to
    leave HA showing a live but frozen motion entity), and a
    stray 0xfc mid-frame resyncs immediately instead of losing
    the following good frame.
    su syntax on this ROM is `su 0 <command...>` — NOT `su -c ...`.
    The old `su -c input keyevent 224` failed with exit 1 for that
    reason alone; the app UID can escalate silently via `su 0 ...`
    (proven Oct 30, 2026, including screen off/on keyevents).
- Screen on/off and reboot use that su path. Brightness and rotation
  use `Settings.System` and need the special Write Settings grant,
  granted once from the app's settings screen (or via appops for
  testing); without it, HA commands for those two are refused and HA
  reverts to the polled actual value. Volume uses AudioManager
  (`MODIFY_AUDIO_SETTINGS`, no special grant). `/proc/vendor` audio
  nodes are world-writable, so the daemon writes them directly.
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
— the panel has no accelerometer, so sensor auto-rotate cannot work.
Note: `user_rotation` does NOT persist across reboots on this panel
(observed Oct 30, 2026: it reverted to 0 after reboot). Since Oct 30,
2026 the daemon enforces the desired rotation itself on every service
start (see `enforceDesiredRotation` in `ButtonService`): it reads the
persisted desired rotation (default 90, updated whenever a
`/rotation/set` MQTT command succeeds) and, if WRITE_SETTINGS is
granted, sets `accelerometer_rotation` to 0 and `user_rotation` to the
desired value. This runs once per start only, never in the poll loop,
so a deliberate rotation change is not fought. The Autostart plugin
rotation commands below are therefore optional legacy config, not
required — but the plugin's daemon-start command is still required,
because the daemon only enforces rotation once it is actually started.

## Autostart on this panel (working, verified Oct 30, 2026)

The daemon's `BootReceiver` (BOOT_COMPLETED + QUICKBOOT_POWERON +
LOCKED_BOOT_COMPLETED, directBootAware) is retained for other ROMs, but
on this vendor ROM its delivery is broken: after three reboot tests
plus battery-whitelist / ACTIVE-bucket / explicit re-enable, the
receiver still never logged, and on Oct 30 an explicit broadcast to
it — even with a custom, non-protected action — was skipped by
ActivityManager (no delivery, dispatch time epoch 0), while the same
mechanism delivered to Kiosk Satellite's own manifest receiver. So
do not keep tweaking BOOT_COMPLETED on this panel.

The working autostart path is the Kiosk Satellite Autostart plugin
(`kiosk-autostart`, Once per boot, backend Auto = Root first):
- `Start daemon service`: `/system/bin/am`,
  `start-foreground-service -n com.misha.f5daemon/.ButtonService`
  (direct service start, proven live; the old BOOT_COMPLETED broadcast
  command "succeeded" with exit 0 while the receiver never ran, so
  exit 0 from a broadcast proves nothing here).
- `Rotation 90`: `/system/bin/settings`, `put system user_rotation 1`
- `Rotation auto off`: `/system/bin/settings`,
  `put system accelerometer_rotation 0`

Verified on a real reboot Oct 30, 2026 (new boot ID
ab277fec-1a47-45df-a59d-f5cbd882db5f): Kiosk pid 1015, daemon service
up with a fresh ServiceRecord, `user_rotation=1`, and the plugin's
runState for the new boot marked all four commands (daemon service,
both rotation commands, Shizuku).

## Known open items (Oct 30, 2026)
- Wake screen via su: root cause found Oct 30, 2026 — wrong su syntax
  (`su -c ...` instead of `su 0 ...`), not a permission denial. v5
  uses the correct syntax, plus a screen wake lock fallback. Daemon's
  own screen on/off via HA not yet live-tested at time of v5 push;
  the underlying su keyevents were proven from the app UID via adb.
- Reboot button (v5) live-tested Oct 30, 2026 (HA Reboot button):
  the panel reboots and the daemon + rotation come back via the
  Autostart plugin (see above), so a reboot no longer strands the
  daemon.
- The panel clock resets to January on reboot (RTC persistence unresolved
  in the daemon; a manual `hwclock -u -w` after setting the time was
  applied Oct 30, 2026, proof on next natural reboot deferred).
- MQTT client id is per-device (`tuyacontroller-<device-slug>`), so a
  second panel does not kick the first off the broker; two panels with
  the same Android device name would still collide on unique_ids, so
  give each panel a distinct device name in settings.
