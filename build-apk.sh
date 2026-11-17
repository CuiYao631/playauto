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

BUILD_VERSION=$(TZ=Asia/Shanghai date '+%Y.%m.%d.%H%M')
# versionCode 必须单调递增，供系统升级与在线更新判断使用。
# 8 位 YYMMDDHH，既小于 int 上限（2147483647），又保证到 2099 年都不回绕；
# 小时级精度意味着同一小时内多次构建会得到相同 code，仅影响覆盖安装识别。
BUILD_VERSION_CODE=$(TZ=Asia/Shanghai date '+%y%m%d%H')

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes" "$OUT_DIR/dex" "$OUT_DIR/native"

mkdir -p "$OUT_DIR/mcu-api"
find "$PROJECT_DIR/app/src/mcuApi/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 -d "$OUT_DIR/mcu-api"

find "$PROJECT_DIR/app/src/main/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 \
        -classpath "$ANDROID_JAR:$OUT_DIR/mcu-api" \
        -d "$OUT_DIR/classes"

jar --create --file "$OUT_DIR/classes.jar" -C "$OUT_DIR/classes" .
mkdir -p "$OUT_DIR/test-classes"
find "$PROJECT_DIR/app/src/test/java" -name '*.java' -print0 |
    xargs -0 javac -source 17 -target 17 \
        -classpath "$OUT_DIR/classes:$ANDROID_JAR" \
        -d "$OUT_DIR/test-classes"
java -cp "$OUT_DIR/test-classes:$OUT_DIR/classes:$ANDROID_JAR" \
    cn.autoplay.replacement.ProtocolFrameSelfTest

"$BUILD_TOOLS/aapt" package -f \
    -M "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
    -S "$PROJECT_DIR/app/src/main/res" \
    -A "$PROJECT_DIR/app/src/main/assets" \
    -I "$ANDROID_JAR" \
    --min-sdk-version 19 \
    --target-sdk-version 35 \
    --version-code "$BUILD_VERSION_CODE" \
    --version-name "$BUILD_VERSION" \
    --rename-manifest-package cn.autoplay.replacement \
    -F "$OUT_DIR/base.apk"

"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --classpath "$OUT_DIR/mcu-api" --min-api 19 \
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

# 签名密钥必须固定，否则每次构建的证书都不同，Android 会拒绝覆盖安装
# （INSTALL_FAILED_UPDATE_INCOMPATIBLE，即应用内更新显示“签名不一致”）。
# 默认使用仓库内 keys/playauto.keystore；CI 可通过环境变量指向自己的密钥。
KEYSTORE=${PLAYAUTO_KEYSTORE:-"$PROJECT_DIR/keys/playauto.keystore"}
KEY_ALIAS=${PLAYAUTO_KEY_ALIAS:-androiddebugkey}
KS_PASS=${PLAYAUTO_KS_PASS:-android}
KEY_PASS=${PLAYAUTO_KEY_PASS:-android}

if [ ! -f "$KEYSTORE" ]; then
    echo "缺少签名密钥：$KEYSTORE" >&2
    echo "请先执行：" >&2
    echo "  keytool -genkeypair -keystore \"$KEYSTORE\" -storepass android -keypass android \\" >&2
    echo "      -alias $KEY_ALIAS -dname \"CN=PlayAuto,O=PlayAuto,C=CN\" \\" >&2
    echo "      -keyalg RSA -keysize 2048 -validity 10000" >&2
    echo "生成的密钥请妥善备份：一旦更换，已安装的应用必须卸载后才能重装。" >&2
    exit 1
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$OUT_DIR/AutoPlayReplacement-debug.apk" "$OUT_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUT_DIR/AutoPlayReplacement-debug.apk"
printf 'Built: %s\n' "$OUT_DIR/AutoPlayReplacement-debug.apk"
