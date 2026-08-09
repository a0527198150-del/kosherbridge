#!/sbin/sh
# Runs during module installation (Magisk app). Verifies the APK is actually
# bundled - build.sh packages it - so a broken module never flashes.

if [ ! -f "$MODPATH/system/priv-app/KosherBridge/KosherBridge.apk" ]; then
  ui_print "KosherBridge: APK missing - run magisk-module/build.sh first"
  abort "APK not found in module"
fi
ui_print "KosherBridge: installing app as system app with privileged Bluetooth"
