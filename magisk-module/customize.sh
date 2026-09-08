#!/system/bin/sh
# Runs during module installation (Magisk app). Verifies the APK is actually
# bundled - build.sh packages it - so a broken module never flashes.

if [ ! -f "$MODPATH/system/priv-app/KosherBridge/KosherBridge.apk" ]; then
  ui_print "KosherBridge: APK missing - run magisk-module/build.sh first"
  abort "APK not found in module"
fi
ui_print "KosherBridge: installing app as system app with privileged Bluetooth"

# The module ships the app to /system/priv-app. If a copy is ALREADY installed
# from the normal APK with a different signature, Android refuses to treat the
# system copy as the same package and the app breaks in confusing ways (it
# neither updates nor gains the privileged permission). Detect it here and say
# so plainly instead of letting the user debug it after a reboot.
if pm path com.example.kosherbridge >/dev/null 2>&1; then
  ui_print "- KosherBridge is already installed on this player."
  ui_print "  If it was installed from a DIFFERENT build than this module,"
  ui_print "  uninstall it first (Settings -> Apps), then reboot."
fi

for required in system.prop service.sh \
  system/etc/permissions/privapp-permissions-com.example.kosherbridge.xml; do
  if [ ! -f "$MODPATH/$required" ]; then
    abort "KosherBridge: module is incomplete - missing $required"
  fi
done

ui_print "- Enables bluetooth.profile.hfp.hf.enabled (call audio)"
ui_print "- Grants BLUETOOTH_PRIVILEGED to KosherBridge only"
ui_print "- Sets hidden_api_policy=1 (DEVICE-GLOBAL) at every boot"
ui_print "REBOOT REQUIRED: the profile flag is read when Bluetooth starts."
