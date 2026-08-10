package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Client side of the privileged HFP bridge.
 *
 * In the normal app process two barriers block HFP call control on modern
 * Android: hidden-API enforcement (the whole BluetoothHeadsetClient class is
 * a non-SDK interface) and the missing BLUETOOTH_PRIVILEGED signature
 * permission. Shizuku runs [HfpUserService] in a separate process under the
 * `shell` (adb) or root UID, where neither restriction applies. This class
 * binds that user service and proxies every operation over the [IHfpBridge]
 * AIDL interface. See HfpClientManager.bindShizuku() for the wiring.
 */
class ShizukuBridge(private val context: Context) {

  private val tag = "ShizukuBridge"

  @Volatile private var remote: IHfpBridge? = null
  @Volatile private var bindRequested = false
  private var args: Shizuku.UserServiceArgs? = null
  private var conn: ServiceConnection? = null

  val isBound: Boolean get() = remote != null

  /** True when the Shizuku server (started via adb / root) is reachable. */
  val isAvailable: Boolean
    get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

  /** True when the user granted this app access inside the Shizuku app (Android 11+). */
  val permissionGranted: Boolean
    get() = runCatching {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

  /**
   * Asks Shizuku to start [HfpUserService] in its privileged process.
   * The connection arrives asynchronously via [ServiceConnection] (main thread).
   */
  fun bind(): Boolean {
    if (remote != null || bindRequested) return true
    synchronized(this) {
      // Binding is asynchronous. A second caller can arrive before
      // onServiceConnected and otherwise create a second remote process.
      if (remote != null || bindRequested) return true
      if (!isAvailable) return false
      if (!permissionGranted) {
        Log.w(tag, "Shizuku permission not granted for this app")
        return false
      }
      bindRequested = true
    }
    val component = ComponentName(context, HfpUserService::class.java)
    val a = Shizuku.UserServiceArgs(component)
      .daemon(false)
      .processNameSuffix("shizuku")
      .debuggable(false)
      .version(1)
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        remote = IHfpBridge.Stub.asInterface(binder)
        bindRequested = true
        Log.i(tag, "user service connected")
      }

      override fun onServiceDisconnected(name: ComponentName) {
        Log.w(tag, "user service disconnected")
        remote = null
        bindRequested = false
      }
    }
    args = a
    conn = connection
    Log.i(tag, "requesting user service bind (${component.flattenToString()})")
    val started = runCatching {
      Shizuku.bindUserService(a, connection)
      true
    }.getOrDefault(false)
    if (!started) bindRequested = false
    return started
  }

  /** Stops and removes the remote user service (triggers HfpUserService.destroy()). */
  fun unbind() {
    val a = args
    val c = conn
    if (a != null && c != null) {
      runCatching { Shizuku.unbindUserService(a, c, true) }
    }
    conn = null
    args = null
    remote = null
    bindRequested = false
  }

  // ------------------------------------------------------------- remote calls

  fun registerProfile(): Boolean =
    runCatching { remote?.registerProfile() ?: false }.getOrDefault(false)

  fun isProfileReady(): Boolean =
    runCatching { remote?.isProfileReady() ?: false }.getOrDefault(false)

  fun bondedDevices(): List<PairedDeviceInfo> = runCatching {
    (remote?.bondedDevices() ?: emptyArray()).mapNotNull { entry ->
      val sep = entry.indexOf('|')
      if (sep > 0) PairedDeviceInfo(entry.substring(0, sep), entry.substring(sep + 1)) else null
    }
  }.getOrDefault(emptyList())

  fun connect(address: String): Boolean =
    runCatching { remote?.connect(address) ?: false }.getOrDefault(false)

  fun disconnect(address: String): Boolean =
    runCatching { remote?.disconnect(address) ?: false }.getOrDefault(false)

  fun connectionState(address: String): Int =
    runCatching { remote?.connectionState(address) ?: BluetoothProfile.STATE_DISCONNECTED }
      .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)

  fun audioState(address: String): Int =
    runCatching { remote?.audioState(address) ?: 0 }.getOrDefault(0)

  fun connectAudio(): Boolean =
    runCatching { remote?.connectAudio() ?: false }.getOrDefault(false)

  fun disconnectAudio(): Boolean =
    runCatching { remote?.disconnectAudio() ?: false }.getOrDefault(false)

  fun dial(number: String): Boolean =
    runCatching { remote?.dial(number) ?: false }.getOrDefault(false)

  fun redial(): Boolean =
    runCatching { remote?.redial() ?: false }.getOrDefault(false)

  fun accept(): Boolean =
    runCatching { remote?.accept() ?: false }.getOrDefault(false)

  fun reject(): Boolean =
    runCatching { remote?.reject() ?: false }.getOrDefault(false)

  fun hangup(): Boolean =
    runCatching { remote?.hangup() ?: false }.getOrDefault(false)

  fun currentCallSnapshot(): String =
    runCatching { remote?.currentCallSnapshot() ?: "" }.getOrDefault("")
}
