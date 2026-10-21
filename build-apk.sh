#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
PLATFORM=${ANDROID_PLATFORM:-android-35}
BUILD_TOOLS_VERSION=${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_DIR/platforms/$PLATFORM/android.jar"
OUT_DIR="$PROJECT_DIR/build/manual"

for tool in aapt d8 zipalign apksigner; do
    if [ ! -x "$BUILD_TOOLS/$tool" ]; then
        echo "Missing Android SDK tool: $BUILD_TOOLS/$tool" >&2
        exit 1
    fi
done
if [ ! -f "$ANDROID_JAR" ]; then
    echo "Missing Android platform: $ANDROID_JAR" >&2
    exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes" "$OUT_DIR/dex" "$OUT_DIR/native"

find "$PROJECT_DIR/app/src/main/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 \
        -classpath "$ANDROID_JAR" \
        -d "$OUT_DIR/classes"

jar --create --file "$OUT_DIR/classes.jar" -C "$OUT_DIR/classes" .
mkdir -p "$OUT_DIR/test-classes"
find "$PROJECT_DIR/app/src/test/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 \
        -classpath "$OUT_DIR/classes:$ANDROID_JAR" \
        -d "$OUT_DIR/test-classes"
java -cp "$OUT_DIR/test-classes:$OUT_DIR/classes" \
    cn.autoplay.replacement.ProtocolFrameSelfTest

"$BUILD_TOOLS/aapt" package -f \
    -M "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
    -S "$PROJECT_DIR/app/src/main/res" \
    -A "$PROJECT_DIR/app/src/main/assets" \
    -I "$ANDROID_JAR" \
    --min-sdk-version 19 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 0.1.0 \
    --rename-manifest-package cn.autoplay.replacement \
    -F "$OUT_DIR/base.apk"

"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 19 \
    --output "$OUT_DIR/dex" "$OUT_DIR/classes.jar"
(
    cd "$OUT_DIR/dex"
    zip -q -u "$OUT_DIR/base.apk" classes.dex
)
if [ -d "$PROJECT_DIR/app/src/main/jniLibs" ]; then
    cp -R "$PROJECT_DIR/app/src/main/jniLibs" "$OUT_DIR/native/lib"
    (
        cd "$OUT_DIR/native"
        zip -q -u "$OUT_DIR/base.apk" -r lib
    )
fi
"$BUILD_TOOLS/zipalign" -f 4 "$OUT_DIR/base.apk" "$OUT_DIR/aligned.apk"

KEYSTORE="$PROJECT_DIR/build/debug.keystore"
mkdir -p "$PROJECT_DIR/build"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT_DIR/AutoPlayReplacement-debug.apk" "$OUT_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUT_DIR/AutoPlayReplacement-debug.apk"
printf 'Built: %s\n' "$OUT_DIR/AutoPlayReplacement-debug.apk"
