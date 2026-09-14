package com.example.kosherbridge

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * The platform's own hook for "this companion device just came into range".
 *
 * Registered through [CompanionBridge.startObservingPresence]. When the kosher
 * phone appears, the system binds this service and calls
 * [onDeviceAppeared] - and a bound service is a running process, which is all
 * the bridge needs to come back from the dead.
 *
 * This is deliberately the *third* recovery mechanism, not a replacement for
 * the other two. START_STICKY covers an ordinary low-memory kill;
 * [BridgeWatchdog]'s alarm covers a vendor power manager that ignores it; and
 * this covers the case neither can - the process killed overnight on a player
 * whose vendor also drops alarms, where nothing would run again until the user
 * happened to open the app. Here the *platform* starts us, on its own
 * schedule, because the device we exist to serve is nearby.
 *
 * Each mechanism is a no-op when the service is already alive, so having three
 * costs nothing.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BridgeCompanionService : CompanionDeviceService() {

  private fun wake(what: String) {
    Log.i(TAG, "companion device appeared ($what) - making sure the bridge is up")
    // Never crash this callback: it runs in the system's binding path, and a
    // vendor build can still refuse the service start.
    runCatching { BridgeService.start(this) }
  }

  @SuppressLint("MissingPermission")
  override fun onDeviceAppeared(associationInfo: AssociationInfo) {
    super.onDeviceAppeared(associationInfo)
    wake(associationInfo.deviceMacAddress?.toString() ?: "unknown")
  }

  @Deprecated("Replaced by onDeviceAppeared(AssociationInfo) on API 33+")
  override fun onDeviceAppeared(address: String) {
    wake(address)
  }

  @SuppressLint("MissingPermission")
  override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
    super.onDeviceDisappeared(associationInfo)
    // Deliberately nothing. The phone going out of range is not a reason to
    // stop the bridge: it is exactly when the raw client's reconnect ladder
    // should keep running so the link is back the moment the phone returns.
    Log.i(TAG, "companion device left range - bridge stays up to reconnect")
  }

  @Deprecated("Replaced by onDeviceDisappeared(AssociationInfo) on API 33+")
  override fun onDeviceDisappeared(address: String) {
    Log.i(TAG, "companion device left range - bridge stays up to reconnect")
  }

  private companion object {
    const val TAG = "BridgeCompanion"
  }
}
