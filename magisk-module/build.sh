#!/bin/sh
# Bundles the built KosherBridge APK into the Magisk module zip.
#
# Usage:
#   sh magisk-module/build.sh [path-to-apk] [version]
#
# Default APK path: kosherbridge/build/outputs/apk/release/kosherbridge-release.apk
# Output: kosherbridge-magisk-<version>.zip at the repo root, where <version>
# defaults to the app's own version (1.0.<commit count>) so the file name says
# which build it carries. A fixed "v1" name made every module zip ever produced
# indistinguishable from the others once downloaded.
#
# The module is installed from the Magisk app: Modules -> Install from storage.
set -e
cd "$(dirname "$0")"

# Resolve the APK path against the repo root (build.sh runs from magisk-module/).
REPO_ROOT="$(cd .. && pwd)"
APK_SRC="${1:-$REPO_ROOT/kosherbridge/build/outputs/apk/release/kosherbridge-release.apk}"
if [ ! -f "$APK_SRC" ] && [ -f "$REPO_ROOT/$APK_SRC" ]; then
  APK_SRC="$REPO_ROOT/$APK_SRC"
fi
if [ ! -f "$APK_SRC" ]; then
  echo "APK not found at: $APK_SRC"
  echo "Build it first (gradle :kosherbridge:assembleRelease) or pass the APK path as an argument."
  exit 1
fi

# The APK itself is never committed - it is copied in at build time.
DEST=system/priv-app/KosherBridge/KosherBridge.apk
mkdir -p "$(dirname "$DEST")"
cp "$APK_SRC" "$DEST"

# Version: the argument wins, otherwise derive the same value the app uses.
VERSION="${2:-}"
if [ -z "$VERSION" ]; then
  COMMITS=$(git -C "$REPO_ROOT" rev-list --count HEAD 2>/dev/null || echo 1)
  VERSION="1.0.$COMMITS"
fi

# Keep module.prop's version in step with the zip, so Magisk shows the real
# version in its module list instead of a stale hardcoded one.
STAGING=$(mktemp -d)
cp -r system "$STAGING/system"
cp module.prop customize.sh service.sh system.prop "$STAGING/"
COMMITS=$(git -C "$REPO_ROOT" rev-list --count HEAD 2>/dev/null || echo 2)
sed -i.bak \
  -e "s/^version=.*/version=v$VERSION/" \
  -e "s/^versionCode=.*/versionCode=$COMMITS/" \
  "$STAGING/module.prop" && rm -f "$STAGING/module.prop.bak"

OUT="../kosherbridge-magisk-$VERSION.zip"
rm -f "$OUT"
(cd "$STAGING" && zip -r -q "$OLDPWD/$OUT" .)
rm -rf "$STAGING"

echo "Module created: $OUT (version $VERSION)"
echo "Install it in the Magisk app (Modules -> Install from storage) and reboot."
