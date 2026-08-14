package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.example.kosherbridge.BridgeApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The actual privileged worker. Shizuku loads this same APK into a second
 * process that runs under the `shell` UID and instantiates this class there
 * (see HfpClientManager.bindShizuku). It reuses HiddenHfp directly - it's
 * the same reflection code, just executed under an identity that isn't
 * blocked by the two barriers described in chat: hidden-API enforcement and
 * the BLUETOOTH_PRIVILEGED permission check.
 *
 * Never instantiate this directly from normal app code - only Shizuku's
 * bindUserService() should create it, in the remote process.
 */
class HfpUserService(private val context: Context) : IHfpBridge.Stub() {

  // Some Shizuku runtime versions instantiate user services via a no-arg
  // constructor rather than passing a Context in. BridgeApp.onCreate() runs
  // first in *every* process this APK is loaded into - including this one -
  // so BridgeApp.instance is always safe to fall back to.
  constructor() : this(BridgeApp.instance)

  private val adapter: BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  @Volatile private var client: Any? = null
  @Volatile private var ready = false

  override fun isAvailable(): Boolean {
    HiddenHfp.init()
    return HiddenHfp.isAvailable
  }

  override fun registerProfile(): Boolean {
    HiddenHfp.init()
    if (!HiddenHfp.isAvailable) return false
    if (client != null) return true
    val a = adapter ?: return false

    val latch = CountDownLatch(1)
    var ok = false
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        if (profile != HiddenHfp.PROFILE_ID) return
        client = HiddenHfp.castClient(proxy)
        ok = client != null
        ready = ok
        latch.countDown()
      }
      override fun onServiceDisconnected(profile: Int) {
        if (profile != HiddenHfp.PROFILE_ID) return
        client = null
        ready = false
      }
    }
    val started = runCatching { a.getProfileProxy(context, listener, HiddenHfp.PROFILE_ID) }.getOrDefault(false)
    if (!started) return false
    latch.await(5, TimeUnit.SECONDS)
    return ok
  }

  override fun isProfileReady(): Boolean = ready

  override fun bondedDevices(): Array<String> =
    adapter?.bondedDevices?.map { "${it.name ?: it.address}|${it.address}" }?.toTypedArray()
      ?: emptyArray()

  private fun deviceFor(address: String): BluetoothDevice? =
    runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

  override fun connect(address: String): Boolean {
    val c = client ?: return false
    val d = deviceFor(address) ?: return false
    return HiddenHfp.connect(c, d)
  }

  override fun disconnect(address: String): Boolean {
    val c = client ?: return false
    val d = deviceFor(address) ?: return false
    return HiddenHfp.disconnect(c, d)
  }

  override fun connectionState(address: String): Int {
    val c = client ?: return BluetoothProfile.STATE_DISCONNECTED
    val d = deviceFor(address) ?: return BluetoothProfile.STATE_DISCONNECTED
    return HiddenHfp.connectionState(c, d)
  }

  override fun audioState(address: String): Int {
    val c = client ?: return 0
    val d = deviceFor(address) ?: return 0
    return HiddenHfp.audioState(c, d)
  }

  override fun connectAudio(): Boolean = HiddenHfp.connectAudio(client)
  override fun disconnectAudio(): Boolean = HiddenHfp.disconnectAudio(client)

  private fun connectedDevice(): BluetoothDevice? =
    client?.let { HiddenHfp.connectedDevices(it).firstOrNull() as? BluetoothDevice }

  override fun dial(number: String): Boolean {
    val c = client ?: return false
    val d = connectedDevice() ?: return false
    return HiddenHfp.dial(c, d, number)
  }

  override fun redial(): Boolean {
    val c = client ?: return false
    val d = connectedDevice() ?: return false
    return HiddenHfp.redial(c, d)
  }

  override fun accept(): Boolean {
    val c = client ?: return false
    val d = connectedDevice() ?: return false
    return HiddenHfp.accept(c, d)
  }

  override fun reject(): Boolean {
    val c = client ?: return false
    val d = connectedDevice() ?: return false
    return HiddenHfp.reject(c, d)
  }

  override fun hangup(): Boolean {
    val c = client ?: return false
    val d = connectedDevice() ?: return false
    return HiddenHfp.hangup(c, d)
  }

  override fun currentCallSnapshot(): String {
    val c = client ?: return ""
    val d = connectedDevice() ?: return ""
    val calls = HiddenHfp.currentCalls(c, d)
    val first = calls.firstOrNull() ?: return ""
    val state = HiddenHfp.callState(first)
    val number = HiddenHfp.callNumber(first) ?: ""
    val direction = HiddenHfp.callDirection(first)
    return "$state|$number|$direction"
  }

  override fun destroy() {
    client?.let { c -> runCatching { adapter?.closeProfileProxy(HiddenHfp.PROFILE_ID, c as BluetoothProfile) } }
    client = null
    ready = false
    // Shizuku convention: the process is not killed automatically, so the
    // service must terminate itself after the cleanup.
    System.exit(0)
  }
}
