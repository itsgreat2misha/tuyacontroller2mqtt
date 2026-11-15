#!/bin/sh
# Runs the plain-JVM unit tests for the pure logic (MqttTopics,
# DeviceProfile, AccessLogic, FrameCodec, Discovery,
# InputValidation). Needs only a JDK: no Android SDK,
# no Gradle.
set -e
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" \
  app/src/main/java/com/tuyacontroller2mqtt/MqttTopics.java \
  app/src/main/java/com/tuyacontroller2mqtt/DeviceProfile.java \
  app/src/main/java/com/tuyacontroller2mqtt/AccessLogic.java \
  app/src/main/java/com/tuyacontroller2mqtt/FrameCodec.java \
  app/src/main/java/com/tuyacontroller2mqtt/Discovery.java \
  app/src/main/java/com/tuyacontroller2mqtt/InputValidation.java \
  tests/com/tuyacontroller2mqtt/MqttTopicsTest.java \
  tests/com/tuyacontroller2mqtt/AccessLogicTest.java \
  tests/com/tuyacontroller2mqtt/ProfileBehaviorTest.java
java -cp "$OUT" com.tuyacontroller2mqtt.MqttTopicsTest
java -cp "$OUT" com.tuyacontroller2mqtt.AccessLogicTest
java -cp "$OUT" com.tuyacontroller2mqtt.ProfileBehaviorTest
