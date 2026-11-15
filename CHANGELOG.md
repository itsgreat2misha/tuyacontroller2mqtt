# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions follow one rule: `versionName` in `app/build.gradle` is the
source of truth and always equals the git tag and GitHub release
name (`v<versionName>`); `versionCode` increases by 1 on every
release (v1.0.0 = 1, v1.0.1 = 2, v1.1.0 = 3, v1.2.0 = 4, v1.2.1 = 5).

Note on old versions: although the tags below exist, every build up
to v1.1.0 reported `versionName 1.0` / `versionCode 1` in-app — the
version fields were not bumped at the time. The mapping was
reconstructed for this changelog when the discipline above was
adopted in 1.2.0.

## [Unreleased]

### Changed

- README: references to the one tested model were removed outside
  the Supported devices table, so the text describes any supported
  panel, and the README was reorganized — autostart is now the last
  Install step, the TLS choice is explained where it is made, each
  fact is stated in one place, and the MQTT topic pattern is
  documented.
- Relay commands are now limited to the relays in the device
  profile (a publish to a relay the panel does not expose, such as
  the query-only channel, is ignored); the per-relay mode array is
  sized from the profile.
- The *Use TLS* checkbox in the app now says "default port 8883";
  the port field has always accepted any port.
- The device profile now carries the MCU frame codes, the frame
  shape, and the /proc node paths, and the motion and Speaker
  Mute entities are only published on profiles that support them
  (no change on the F5-3, which supports both). Frame handling
  and discovery building moved into tested pure classes; the
  frames sent and the discovery published on the F5-3 are
  unchanged.
- Per-stream volume commands now clamp the percentage to 0-100
  before scaling, and the Speaker Mute command only accepts its
  on/off payloads instead of writing any payload to the /proc
  node verbatim.
- SECURITY.md no longer promises a response timeframe for
  vulnerability reports; reports are reviewed on a best-effort
  basis.

### Removed

- The unused `id` field from `DeviceProfile`, and the root Gradle
  files (`build.gradle`, `settings.gradle`) that described a build
  that never runs — `build.sh` is the build, and `app/build.gradle`
  remains the version source of truth. The night light is now only
  published, and only answers commands, on profiles that support
  it (no change on the F5-3, which does).

## [1.2.1] - 2026-11-04

### Added

- Write Settings auto-grant: on panels with `su`, the app applies
  the Write Settings grant itself at start (daemon start and
  settings screen), so brightness and rotation work without a
  manual trip through system settings. The settings-screen button
  now shows the live grant state — disabled and labelled
  "Write Settings: granted" once the grant is in place — and
  remains the manual route on panels without `su`.

### Fixed

- Screen off no longer sends the power keyevent when the screen is
  already off. The keyevent toggles power, so the old unconditional
  send turned an already-off screen back on.

## [1.2.0] - 2026-11-04

### Added

- **MQTT over TLS**: new *Use TLS* setting (default port 8883) with
  system-CA certificate and hostname verification. Plaintext remains
  available for trusted LANs, with the security trade-off documented
  in the README.
- **Device profiles**: model specifics (serial device, baud,
  button/relay counts, model/manufacturer in discovery) moved into a
  `DeviceProfile`; discovery no longer hardcodes the F5-3 strings.
  Added a Supported devices table to the README.
- Added plain-JVM unit tests for the naming/discovery logic and CI on
  every push/PR (previously builds ran only on release).
- Added SECURITY.md, CONTRIBUTING.md, and issue templates.

### Changed

- **Package renamed** `com.misha.f5daemon` → `com.tuyacontroller2mqtt`
  (preferences file, Keystore alias, log tag and APK name renamed to
  match). Existing installs must uninstall/reinstall and re-enter
  MQTT settings; Home Assistant topics/unique_ids are unchanged.
- **Release signing**: release builds now require the project release
  keystore and fail loudly without it; no more silent debug-key
  signing.
- README rewritten as user documentation; protocol detail moved to
  PROTOCOL.md.

### Removed

- Removed the legacy v1/v2 `f5_*` discovery retraction publishes.

### Fixed

- Fixed: builds now compile sources as UTF-8 regardless of locale.

## [1.1.0] - 2026-10-30

### Added

- CI builds the APK and uploads it (with SHA-256) as a release asset.

### Changed

- Boot autostart findings documented; daemon enforces the desired
  rotation on service start.

## [1.0.1] - 2026-10-30

### Fixed

- CI workflow fixes.

## [1.0.0] - 2026-10-30

### Added

- First tagged release: buttons 4–8 and relays 1–3 as Home Assistant
  entities over MQTT discovery, motion sensor, Keystore-encrypted
  MQTT password, relay switches with press events, night light,
  device controls (screen, brightness, volumes, rotation, reboot),
  MQTT availability with Last Will.
- Follow-up commits after this tag (shipped in 1.2.0): frame-loss
  hardening (serial reader auto-reopen, publishing decoupled from
  serial reading, health probe), global Debug logging mode, per-relay
  relay/momentary mode, physical presses publish motion, candidate-
  window press discernment.
