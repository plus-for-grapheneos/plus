#!/bin/bash
# Builds the code-loading overlay (overlay-dcl/ -> app asset overlay/PlusDynamicCode.apk) with the Android SDK's aapt2
# and apksigner. The signing key doesn't matter for trust: the overlay is only ever used inside the owner-signed image.
#   tools/build-dcl-overlay.sh [keystore (default: the SDK debug keystore)]
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
exe=""; [ -e "$bt/aapt2.exe" ] && exe=".exe"
apksigner="$bt/apksigner"; [ -e "$bt/apksigner.bat" ] && apksigner="$bt/apksigner.bat"
android_jar=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)/android.jar
ks=${1:-$HOME/.android/debug.keystore}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
"$bt/aapt2$exe" compile --dir overlay-dcl/res -o "$tmp/res.zip"
"$bt/aapt2$exe" link -o "$tmp/unsigned.apk" --manifest overlay-dcl/AndroidManifest.xml -I "$android_jar" \
    --min-sdk-version 34 --target-sdk-version 34 "$tmp/res.zip"
mkdir -p app/src/main/overlay-assets/overlay
"$apksigner" sign --ks "$ks" --ks-pass pass:android --out app/src/main/overlay-assets/overlay/PlusDynamicCode.apk "$tmp/unsigned.apk"
rm -f app/src/main/overlay-assets/overlay/PlusDynamicCode.apk.idsig
echo "built app/src/main/overlay-assets/overlay/PlusDynamicCode.apk"
