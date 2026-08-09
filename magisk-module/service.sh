#!/sbin/sh
# Runs at boot (late_start service stage). The in-process direct path needs
# two things, both provided by this module:
#   1. BLUETOOTH_PRIVILEGED  -> granted because the app is a priv-app and is
#      whitelisted in system/etc/permissions/privapp-permissions-*.xml.
#   2. Access to the hidden BluetoothHeadsetClient API -> hidden_api_policy=1
#      makes the system allow non-SDK reflection instead of blocking it.
# The setting persists across reboots; re-applying it at boot covers factory
# resets and ROMs that reset globals.

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 1
done

settings put global hidden_api_policy 1
