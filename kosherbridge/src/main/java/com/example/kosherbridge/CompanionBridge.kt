package com.example.kosherbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android's own answer to "keep this app alive for this Bluetooth device".
 *
 * Everything else the bridge does to survive is a workaround: a foreground
 * service the vendor kills anyway, an alarm that re-starts it fifteen minutes
 * later, a wake lock. [CompanionDeviceManager] is the supported API for
 * exactly this situation - an app that exists to serve one specific companion
 * device - and associating with the kosher phone buys three things no
 * workaround can:
 *
 *  - **The system restarts us.** With [startObservingPresence], the platform
 *    binds [BridgeCompanionService] the moment the phone comes into range and
 *    unbinds it when it leaves. That is a system-driven wake-up, not an alarm
 *    a vendor "power manager" can drop, and it is the one mechanism that
 *    recovers a bridge killed while the user was asleep.
 *  - **Foreground services from the background.** Android 12+ refuses
 *    `startForegroundService` from the background, which is precisely what a
 *    notification's "ענה" button does when the app is not on screen -
 *    BridgeService already carries a fallback for that refusal.
 *    REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND removes the
 *    refusal instead of coping with it.
 *  - **Background execution.** REQUEST_COMPANION_RUN_IN_BACKGROUND exempts an
 *    associated app from the ordinary background limits.
 *
 * All three are normal permissions granted at install; none of them needs
 * root, Shizuku, or a privileged identity. The association itself is a system
 * dialog the user confirms once, naming the phone they already chose.
 */
object CompanionBridge {

  private const val TAG = "CompanionBridge"

  /** True when this player has the companion-device subsystem at all. */
  fun isSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

  private fun manager(context: Context): CompanionDeviceManager? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return runCatching {
      context.getSystemService(CompanionDeviceManager::class.java)
    }.getOrNull()
  }

  /**
   * MAC addresses this app is already associated with. Read defensively: the
   * API throws on some vendor builds that report the feature but ship a stub.
   */
  fun associations(context: Context): List<String> {
    val cdm = manager(context) ?: return emptyList()
    return runCatching { cdm.associations.orEmpty() }.getOrDefault(emptyList())
  }

  /** True when [address] is already associated (case-insensitive). */
  fun isAssociated(context: Context, address: String?): Boolean {
    if (address.isNullOrBlank()) return false
    return associations(context).any { it.equals(address, ignoreCase = true) }
  }

  /**
   * Asks the system to associate with one specific device.
   *
   * The result is an [IntentSender] the caller launches to show the system's
   * confirmation dialog; there is no way to associate without the user seeing
   * and confirming it, which is the point. [onFailure] is called with a
   * human-readable reason so the setup screen can say what went wrong instead
   * of leaving a button that does nothing.
   */
  @SuppressLint("MissingPermission")
  fun requestAssociation(
    context: Context,
    address: String,
    onReady: (IntentSender) -> Unit,
    onFailure: (String) -> Unit,
  ) {
    val cdm = manager(context)
    if (cdm == null) {
      onFailure("הנגן הזה לא תומך בשיוך מכשיר נלווה")
      return
    }
    val request = runCatching {
      AssociationRequest.Builder()
        .addDeviceFilter(
          BluetoothDeviceFilter.Builder().setAddress(address).build(),
        )
        .setSingleDevice(true)
        .build()
    }.getOrElse {
      onFailure("לא ניתן לבנות בקשת שיוך: ${it.message ?: "שגיאה לא ידועה"}")
      return
    }

    // The callback shape changed in API 33: the older one hands back an
    // IntentSender through onDeviceFound, the newer one through
    // onAssociationPending. Both are answered so the feature is not silently
    // dead on either side of that line.
    val callback = object : CompanionDeviceManager.Callback() {
      override fun onAssociationPending(intentSender: IntentSender) {
        onReady(intentSender)
      }

      @Deprecated("Replaced by onAssociationPending on API 33+")
      override fun onDeviceFound(intentSender: IntentSender) {
        onReady(intentSender)
      }

      override fun onFailure(error: CharSequence?) {
        onFailure(
          "השיוך נכשל: ${error?.toString()?.takeIf { it.isNotBlank() } ?: "הטלפון לא נמצא בטווח"}",
        )
      }
    }
    runCatching {
      cdm.associate(request, callback, Handler(Looper.getMainLooper()))
    }.onFailure {
      onFailure("לא ניתן לפתוח את דיאלוג השיוך: ${it.message ?: "שגיאה לא ידועה"}")
    }
  }

  /**
   * Asks the platform to watch for this device and bind
   * [BridgeCompanionService] whenever it appears.
   *
   * Safe and cheap to call repeatedly - re-registering an existing observation
   * is a no-op - so the service calls it on every start, which is what makes
   * it survive a reboot without any extra bookkeeping.
   */
  @SuppressLint("MissingPermission")
  fun startObservingPresence(context: Context, address: String?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    if (address.isNullOrBlank()) return
    if (!isAssociated(context, address)) return
    val cdm = manager(context) ?: return
    runCatching {
      @Suppress("DEPRECATION")
      cdm.startObservingDevicePresence(address)
      Log.i(TAG, "observing presence of $address")
    }.onFailure { Log.w(TAG, "could not observe device presence", it) }
  }

  /** Drops the association for one device (used when the phone is unpaired). */
  @SuppressLint("MissingPermission")
  fun forget(context: Context, address: String?) {
    if (address.isNullOrBlank()) return
    val cdm = manager(context) ?: return
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        @Suppress("DEPRECATION")
        cdm.stopObservingDevicePresence(address)
      }
    }
    runCatching { cdm.disassociate(address) }
      .onFailure { Log.w(TAG, "could not disassociate $address", it) }
  }

  /** The address of [device], for callers that hold the Bluetooth object. */
  fun addressOf(device: BluetoothDevice?): String? = device?.address
}
