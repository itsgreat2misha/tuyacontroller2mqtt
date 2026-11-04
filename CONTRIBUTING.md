# Contributing

Thanks for helping. This project is a small dependency-free Android
daemon; the build is a shell script, not Gradle (see the README's
Build section).

## Before you start

- Run `tests/run_tests.sh` (plain JVM, needs only a JDK) and make
  sure the full build compiles:
  `ANDROID_SDK=... JAVA_HOME=... ALLOW_DEBUG_SIGNING=1 ./build.sh`
- CI runs the tests and a build check on every push and pull request;
  release builds additionally require the project signing keystore.

## Compatibility rules

These are load-bearing for existing users; changing them needs a
migration story in the PR:

- MQTT topic root `tuyacontroller/<device-slug>/`, all `unique_id`s,
  entity names, and the device slug derived from the device name.
- The Home Assistant discovery payload shape (additive changes are
  fine; renames are not).
- `versionName` = git tag = release name; `versionCode` only ever
  increases.

## Contributing a device profile (new panel model)

1. Capture the panel's serial traffic (with the Debug mode's serial
   log, or a sniffer on the bus) and decode the frame set: motion
   on/off, button presses, relay states/sets, query batch shape, any
   extra channels.
2. Add a `DeviceProfile` entry in
   `app/src/main/java/com/tuyacontroller2mqtt/DeviceProfile.java`
   (serial device, baud, model/manufacturer strings as the vendor
   reports them, button range, relay count, night-light support).
   If your model's frame codes differ, put them behind the profile
   rather than branching at call sites.
3. Add a row to the README's Supported devices table with status
   **Tested** only if you ran it on the real hardware; otherwise
   mark it untested.
4. Note any model-specific findings in PROTOCOL.md.

## Style

- No new dependencies without discussion: the MQTT client is raw
  MQTT 3.1.1 on purpose (an earlier library dependency crashed on
  Android).
- Comments should explain the current design timelessly; incident
  history belongs in the changelog / git log, not in dated comment
  diaries.

## AI involvement

This project's development uses AI-agent assistance. Every change is reviewed by the repository owner, who remains the responsible human for the project.
