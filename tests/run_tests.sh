#!/bin/sh
# Runs the plain-JVM unit tests for the pure logic (MqttTopics,
# DeviceProfile). Needs only a JDK: no Android SDK, no Gradle.
set -e
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" \
  app/src/main/java/com/tuyacontroller2mqtt/MqttTopics.java \
  app/src/main/java/com/tuyacontroller2mqtt/DeviceProfile.java \
  tests/com/tuyacontroller2mqtt/MqttTopicsTest.java
java -cp "$OUT" com.tuyacontroller2mqtt.MqttTopicsTest
