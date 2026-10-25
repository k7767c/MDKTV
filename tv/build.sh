#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
bt="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
out="$PWD/artifacts/tv"
mkdir -p "$out/classes" "$out/dex"
"$bt/aapt2" compile --dir tv/res -o "$out/resources.zip"
"$bt/aapt2" link -o "$out/base.apk" -I "$platform" --manifest tv/AndroidManifest.xml -A tv/assets "$out/resources.zip"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -classpath "$platform" -d "$out/classes" tv/src/org/mdktv/tv/MainActivity.java
find "$out/classes" -name '*.class' | sort > "$out/classes.list"
JAVA_HOME="$JAVA_HOME" "$bt/d8" --min-api 23 --lib "$platform" --output "$out/dex" @"$out/classes.list"
python3 - "$out" <<'PY'
import pathlib, sys, zipfile
out = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out / 'base.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    for dex in sorted((out / 'dex').glob('*.dex')):
        apk.write(dex, dex.name)
PY
"$bt/zipalign" -f -p 4 "$out/base.apk" "$out/aligned.apk"
key="$out/debug.keystore"
if [[ ! -f "$key" ]]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$key" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=MDKTV Debug,O=Development,C=CN'
fi
JAVA_HOME="$JAVA_HOME" "$bt/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$out/MDKTV-TV-0.1.0-debug.apk" "$out/aligned.apk"
JAVA_HOME="$JAVA_HOME" "$bt/apksigner" verify --verbose "$out/MDKTV-TV-0.1.0-debug.apk"
"$bt/aapt2" dump badging "$out/MDKTV-TV-0.1.0-debug.apk"
