#!/system/bin/sh
# Runs at boot (late_start service stage). The in-process direct path needs
# two things, both provided by this module:
#   1. BLUETOOTH_PRIVILEGED  -> granted because the app is a priv-app and is
#      whitelisted in system/etc/permissions/privapp-permissions-*.xml.
#   2. Access to the hidden BluetoothHeadsetClient API -> hidden_api_policy=1
#      makes the system allow non-SDK reflection instead of blocking it.
# The setting persists across reboots; re-applying it at boot covers factory
# resets and ROMs that reset globals.

# Wait for boot to complete, bounded: a device where sys.boot_completed
# never flips must not spin this loop forever.
i=0
until [ "$(getprop sys.boot_completed)" = "1" ] || [ "$i" -ge 120 ]; do
  sleep 1
  i=$((i + 1))
done
if [ "$i" -ge 120 ]; then
  echo "KosherBridge module: boot did not complete after 120s - skipping hidden_api_policy" > /dev/stderr
  exit 0
fi

# NOTE: hidden_api_policy=1 is a DEVICE-GLOBAL setting. It relaxes Android's
# non-SDK API restrictions for EVERY application on this player, not only
# KosherBridge. On a dedicated player that trade is reasonable; reconsider on
# a device used for anything else.
settings put global hidden_api_policy 1

# Re-assert the profile flag at boot as a belt-and-braces measure. system.prop
# is the mechanism that actually works (Magisk applies it with resetprop at
# post-fs-data, before the Bluetooth process starts and reads it); this line
# only catches the case where a vendor init script overwrote it afterwards,
# and it is harmless when the value is already correct.
if [ "$(getprop bluetooth.profile.hfp.hf.enabled)" != "true" ]; then
  resetprop bluetooth.profile.hfp.hf.enabled true
  # The stack has already read the old value by now, so it needs a restart to
  # pick this up. Only done when we actually had to change something.
  svc bluetooth disable
  sleep 2
  svc bluetooth enable
fi
