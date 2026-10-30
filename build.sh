#!/bin/sh
# Manual build for tuyacontroller2mqtt, no Gradle.
# Requires: ANDROID_SDK with platforms/android-30 and build-tools/30.0.3,
# JAVA_HOME pointing at JDK 17. Produces out/f5daemon.apk (debug-signed).
set -e
: "${ANDROID_SDK:?set ANDROID_SDK}"
: "${JAVA_HOME:?set JAVA_HOME}"
BT="$ANDROID_SDK/build-tools/30.0.3"
AJ="$ANDROID_SDK/platforms/android-30/android.jar"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
rm -rf out/classes out/classes.jar out/classes.dex out/base.apk out/f5daemon-aligned.apk out/f5daemon.apk
mkdir -p out/classes
javac -source 8 -target 8 -cp "$AJ" -d out/classes $(find app/src/main/java -name '*.java')
jar cf out/classes.jar -C out/classes .
d8 --lib "$AJ" --output out/ out/classes.jar
aapt2 link -o out/base.apk --manifest app/src/main/AndroidManifest.xml -I "$AJ"
python3 -c "import zipfile; z=zipfile.ZipFile('out/base.apk','a'); z.write('out/classes.dex','classes.dex'); z.close()"
zipalign -f 4 out/base.apk out/f5daemon-aligned.apk
if [ ! -f out/debug.keystore ]; then
  keytool -genkeypair -keystore out/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
apksigner sign --ks out/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/f5daemon.apk out/f5daemon-aligned.apk
apksigner verify out/f5daemon.apk
ls -lh out/f5daemon.apk
