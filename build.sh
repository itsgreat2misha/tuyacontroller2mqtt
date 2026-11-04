#!/bin/sh
# Manual build for tuyacontroller2mqtt, no Gradle.
# Requires: ANDROID_SDK with platforms/android-30 and build-tools/30.0.3,
# JAVA_HOME pointing at JDK 17. Produces out/tuyacontroller2mqtt.apk.
#
# Signing: release builds MUST be signed with the project's release
# keystore. Point RELEASE_KEYSTORE at the keystore file and set
# RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS / RELEASE_KEY_PASS.
# There is deliberately no silent fallback: without a release keystore
# this script fails, because a build signed with a fresh throwaway key
# cannot upgrade any previous install and must never be published.
# For a local throwaway build only, set ALLOW_DEBUG_SIGNING=1 to sign
# with a locally generated debug keystore (never publish that APK).
set -e
: "${ANDROID_SDK:?set ANDROID_SDK}"
: "${JAVA_HOME:?set JAVA_HOME}"
BT="$ANDROID_SDK/build-tools/30.0.3"
AJ="$ANDROID_SDK/platforms/android-30/android.jar"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
rm -rf out/classes out/classes.jar out/classes.dex out/base.apk out/tuyacontroller2mqtt-aligned.apk out/tuyacontroller2mqtt.apk
mkdir -p out/classes
javac -encoding UTF-8 -source 8 -target 8 -cp "$AJ" -d out/classes $(find app/src/main/java -name '*.java')
jar cf out/classes.jar -C out/classes .
d8 --lib "$AJ" --output out/ out/classes.jar
# Version comes from app/build.gradle, the single source of truth
# (see the version discipline note there); the manifest carries none.
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
VERSION_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
: "${VERSION_CODE:?could not read versionCode from app/build.gradle}"
: "${VERSION_NAME:?could not read versionName from app/build.gradle}"
echo "Building version $VERSION_NAME (versionCode $VERSION_CODE)"
aapt2 link -o out/base.apk --manifest app/src/main/AndroidManifest.xml -I "$AJ" --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
python3 -c "import zipfile; z=zipfile.ZipFile('out/base.apk','a'); z.write('out/classes.dex','classes.dex'); z.close()"
zipalign -f 4 out/base.apk out/tuyacontroller2mqtt-aligned.apk
if [ -n "$RELEASE_KEYSTORE" ]; then
  : "${RELEASE_KEYSTORE_PASS:?set RELEASE_KEYSTORE_PASS}"
  : "${RELEASE_KEY_ALIAS:?set RELEASE_KEY_ALIAS}"
  : "${RELEASE_KEY_PASS:?set RELEASE_KEY_PASS}"
  apksigner sign --ks "$RELEASE_KEYSTORE" --ks-pass "pass:$RELEASE_KEYSTORE_PASS" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass "pass:$RELEASE_KEY_PASS" \
    --out out/tuyacontroller2mqtt.apk out/tuyacontroller2mqtt-aligned.apk
  echo "Signed with release keystore $RELEASE_KEYSTORE"
elif [ "$ALLOW_DEBUG_SIGNING" = "1" ]; then
  if [ ! -f out/debug.keystore ]; then
    keytool -genkeypair -keystore out/debug.keystore -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" -noprompt
  fi
  apksigner sign --ks out/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out out/tuyacontroller2mqtt.apk out/tuyacontroller2mqtt-aligned.apk
  echo "WARNING: signed with a local DEBUG keystore (ALLOW_DEBUG_SIGNING=1). Do not publish this APK."
else
  echo "ERROR: no release keystore configured." >&2
  echo "Set RELEASE_KEYSTORE (+ RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS / RELEASE_KEY_PASS)," >&2
  echo "or set ALLOW_DEBUG_SIGNING=1 for a local-only debug build." >&2
  exit 1
fi
apksigner verify out/tuyacontroller2mqtt.apk
ls -lh out/tuyacontroller2mqtt.apk
