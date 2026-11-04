# Changelog

Versions follow one rule: `versionName` in `app/build.gradle` is the
source of truth and always equals the git tag and GitHub release
name (`v<versionName>`); `versionCode` increases by 1 on every
release (v1.0.0 = 1, v1.0.1 = 2, v1.1.0 = 3, v1.2.0 = 4).

Note on old versions: although the tags below exist, every build up
to v1.1.0 reported `versionName 1.0` / `versionCode 1` in-app — the
version fields were not bumped at the time. The mapping was
reconstructed for this changelog when the discipline above was
adopted in 1.2.0.

## Unreleased

Nothing yet.

## 1.2.0 — unreleased (pending first signed release)

- **Package renamed** `com.misha.f5daemon` → `com.tuyacontroller2mqtt`
  (preferences file, Keystore alias, log tag and APK name renamed to
  match). Existing installs must uninstall/reinstall and re-enter
  MQTT settings; Home Assistant topics/unique_ids are unchanged.
- **MQTT over TLS**: new *Use TLS* setting (default port 8883) with
  system-CA certificate and hostname verification. Plaintext remains
  available for trusted LANs, with the security trade-off documented
  in the README.
- **Device profiles**: model specifics (serial device, baud,
  button/relay counts, model/manufacturer in discovery) moved into a
  `DeviceProfile`; discovery no longer hardcodes the F5-3 strings.
  Added a Supported devices table to the README.
- **Release signing**: release builds now require the project release
  keystore and fail loudly without it; no more silent debug-key
  signing. A dedicated keystore secret must be configured before the
  first 1.2.0 release can be published.
- Removed the legacy v1/v2 `f5_*` discovery retraction publishes.
- Added plain-JVM unit tests for the naming/discovery logic and CI on
  every push/PR (previously builds ran only on release).
- README rewritten as user documentation; protocol detail moved to
  PROTOCOL.md; added SECURITY.md, CONTRIBUTING.md, issue templates.
- Fixed: builds now compile sources as UTF-8 regardless of locale.

## 1.1.0 — 2026-10-30 (tag)

- CI builds the APK and uploads it (with SHA-256) as a release asset.
- Boot autostart findings documented; daemon enforces the desired
  rotation on service start.

## 1.0.1 — 2026-10-30 (tag)

- CI workflow fixes.

## 1.0.0 — 2026-10-30 (tag)

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
