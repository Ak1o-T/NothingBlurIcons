#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=== Building NothingBlurIcons LSPosed Module ==="

BUILD_ENV="${BUILD_ENV:-$DIR/../build_env}"
ANDROID_JAR="${ANDROID_JAR:-$BUILD_ENV/android.jar}"
R8_JAR="${R8_JAR:-$BUILD_ENV/r8.jar}"
SIGNER_JAR="${SIGNER_JAR:-$BUILD_ENV/uber-apk-signer.jar}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVAC_BIN="${JAVAC_BIN:-javac}"

if [ -f "$BUILD_ENV/jdk/bin/java" ]; then
    JAVA_BIN="$BUILD_ENV/jdk/bin/java"
    JAVAC_BIN="$BUILD_ENV/jdk/bin/javac"
fi

rm -rf bin/classes bin/dex
mkdir -p bin/classes bin/dex

echo "[1/4] Compiling Java sources..."
"$JAVAC_BIN" -cp "$ANDROID_JAR" -d bin/classes $(find src -name "*.java")

echo "[2/4] Desugaring & compiling to DEX (D8)..."
"$JAVA_BIN" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --classpath bin/classes \
    --lib "$ANDROID_JAR" \
    --output bin/dex \
    $(find bin/classes/com -name "*.class")

echo "[3/4] Packaging APK..."
cd bin
cp app-unsigned.apk unaligned.apk
"$JAVA_BIN" -jar "$R8_JAR" 2>/dev/null || true
if command -v jar >/dev/null 2>&1; then
    jar uf unaligned.apk -C dex classes.dex
else
    (cd dex && zip -u ../unaligned.apk classes.dex)
fi

echo "[4/4] Aligning & Signing APK..."
"$JAVA_BIN" -jar "$SIGNER_JAR" -a unaligned.apk --allowResign -o .

mv unaligned-aligned-debugSigned.apk NothingBlurIcons.apk
echo "=== Success! Output: bin/NothingBlurIcons.apk ==="
