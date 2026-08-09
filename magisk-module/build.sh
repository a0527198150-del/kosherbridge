#!/bin/sh
# Bundles the built KosherBridge APK into the Magisk module zip.
#
# Usage:
#   sh magisk-module/build.sh [path-to-apk]
#
# Default APK path: kosherbridge/build/outputs/apk/debug/kosherbridge-debug.apk
# Output: kosherbridge-magisk-v1.zip at the repo root.
#
# The module is installed from the Magisk app: Modules -> Install from storage.
set -e
cd "$(dirname "$0")"

APK_SRC="${1:-../kosherbridge/build/outputs/apk/debug/kosherbridge-debug.apk}"
if [ ! -f "$APK_SRC" ]; then
  echo "APK not found at: $APK_SRC"
  echo "Build it first (gradle :kosherbridge:assembleDebug) or pass the APK path as an argument."
  exit 1
fi

# The APK itself is never committed - it is copied in at build time.
DEST=system/priv-app/KosherBridge/KosherBridge.apk
mkdir -p "$(dirname "$DEST")"
cp "$APK_SRC" "$DEST"

STAGING=$(mktemp -d)
cp -r system "$STAGING/system"
cp module.prop customize.sh service.sh "$STAGING/"
OUT="../kosherbridge-magisk-v1.zip"
rm -f "$OUT"
(cd "$STAGING" && zip -r -q "$OLDPWD/$OUT" .)
rm -rf "$STAGING"

echo "Module created: $OUT"
echo "Install it in the Magisk app (Modules -> Install from storage) and reboot."
