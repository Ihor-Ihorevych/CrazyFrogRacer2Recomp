#!/usr/bin/env bash
# Throwaway spike: packages libs/arm64-v8a/*.so + Java into a debug-signed APK without Gradle.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
SDK="$(cygpath "$LOCALAPPDATA")/Android/Sdk"
BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-35/android.jar"
out="$here/out"; rm -rf "$out"; mkdir -p "$out/classes" "$out/apk"
cd "$here"
"$BT/aapt2.exe" compile --dir res -o "$out/res.zip"
"$BT/aapt2.exe" link -o "$out/base.apk" -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 35 "$out/res.zip"
javac -encoding UTF-8 -nowarn -source 8 -target 8 -cp "$JAR" -d "$out/classes" $(find src -name '*.java') 2>&1 | grep -v "^Note:\|warning" || true
"$BT/d8.bat" --lib "$JAR" --min-api 29 --output "$out" $(find "$out/classes" -name '*.class')
cp "$out/base.apk" "$out/apk.zip"
mkdir -p "$out/stage/lib/arm64-v8a"; cp libs/arm64-v8a/*.so "$out/stage/lib/arm64-v8a/"; cp "$out/classes.dex" "$out/stage/"
(cd "$out/stage" && 7z a -tzip -mx=1 ../apk.zip classes.dex lib > /dev/null)
# DISC=<image.iso>: the self-contained build. The image goes in uncompressed (assets/disc.iso, -mx=0) so that zipalign aligns
# it and the game reads it straight out of the installed APK (AssetManager.openFd: offset and length inside the file).
name=CrazyFrogRacer2.apk
if [ -n "${DISC:-}" ]; then
    mkdir -p "$out/disc/assets"; cp "$DISC" "$out/disc/assets/disc.iso"
    (cd "$out/disc" && 7z a -tzip -mx=0 ../apk.zip assets/disc.iso > /dev/null)
    name=CrazyFrogRacer2-full.apk
fi
"$BT/zipalign.exe" -f -P 16 4 "$out/apk.zip" "$out/aligned.apk"
ks="$here/debug.keystore"
[ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -storepass android -keypass android -alias spike -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=spike" > /dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$ks" --ks-pass pass:android --out "$here/$name" "$out/aligned.apk"
ls -la "$here/$name"
