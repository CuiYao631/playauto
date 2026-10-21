#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
ANDROID_JAR="$SDK_DIR/platforms/${ANDROID_PLATFORM:-android-35}/android.jar"
TOOLS="$SDK_DIR/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
OUT="$PROJECT_DIR/build/connection-tests"
MAIN="$PROJECT_DIR/build/manual"
SERIAL=${ANDROID_SERIAL:-emulator-5554}
if [ "${COMPATIBILITY_TEST:-0}" = 1 ]; then
    RUNNER=CompatibilityInstrumentation
    RESULT=compatibility_passed
else
    RUNNER=ConnectionServiceInstrumentation
    RESULT=lifecycle_passed
fi
mkdir -p "$OUT/classes" "$OUT/dex"

# Run build-apk.sh first; use the exact compiled production classes and signing key.
find "$PROJECT_DIR/app/src/androidTest/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 -classpath "$ANDROID_JAR:$MAIN/classes.jar" -d "$OUT/classes"
jar --create --file "$OUT/tests.jar" -C "$OUT/classes" .
"$TOOLS/aapt" package -f -M "$PROJECT_DIR/app/src/androidTest/AndroidManifest.xml" \
    -I "$ANDROID_JAR" --min-sdk-version 19 --target-sdk-version 35 -F "$OUT/base.apk"
"$TOOLS/d8" --lib "$ANDROID_JAR" --classpath "$MAIN/classes.jar" --min-api 19 \
    --output "$OUT/dex" "$OUT/tests.jar"
(
    cd "$OUT/dex"
    zip -q -u "$OUT/base.apk" classes.dex
)
"$TOOLS/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$PROJECT_DIR/build/debug.keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$OUT/tests.apk" "$OUT/aligned.apk"
adb -s "$SERIAL" install -r "$MAIN/AutoPlayReplacement-debug.apk"
adb -s "$SERIAL" install -r "$OUT/tests.apk"
adb -s "$SERIAL" shell am instrument -w \
    "cn.autoplay.replacement.tests/cn.autoplay.replacement.$RUNNER" > "$OUT/result.txt"
cat "$OUT/result.txt"
adb -s "$SERIAL" shell am start -W -n cn.autoplay.replacement/.MainActivity
grep -q "$RESULT=true" "$OUT/result.txt"
