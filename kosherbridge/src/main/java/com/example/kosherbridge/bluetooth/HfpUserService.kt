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
 * The actual privileged worker. Two paths load this same APK into a second
 * process with a privileged identity and instantiate this class there:
 *  - Shizuku (see HfpClientManager.bindShizuku): the Shizuku server spawns
 *    the process under the `shell` UID.
 *  - Root (see HfpClientManager.bindRoot / RootBridgeMain): the app spawns
 *    the process itself via `su` under uid 0 - no Shizuku app needed.
 * It reuses HiddenHfp directly - it's the same reflection code, just executed
 * under an identity that isn't blocked by the two barriers: hidden-API
 * enforcement and the BLUETOOTH_PRIVILEGED permission check.
 *
 * Never instantiate this directly from normal app code - only the privileged
 * spawn paths (Shizuku bindUserService / RootBridgeMain) should create it,
 * in the remote process.
 */
class HfpUserService(private val context: Context) : IHfpBridge.Stub() {

  /**
   * Fallback for Shizuku servers older than v13, which instantiate the user
   * service with the no-arg constructor. The privileged process does NOT run
   * BridgeApp: Shizuku's starter calls LoadedApk.makeApplication(true, null),
   * which forces the default android.app.Application class. The starter does
   * set ActivityThread.mInitialApplication, so currentApplication() resolves.
   */
  constructor() : this(resolveFallbackContext())

  companion object {
    private fun resolveFallbackContext(): Context {
      val fromActivityThread = runCatching {
        Class.forName("android.app.ActivityThread")
          .getMethod("currentApplication")
          .invoke(null) as? Context
      }.getOrNull()
      if (fromActivityThread != null) return fromActivityThread
      return BridgeApp.instance
    }
  }

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

  override fun connectAudio(): Boolean = HiddenHfp.connectAudio(client, connectedDevice())
  override fun disconnectAudio(): Boolean = HiddenHfp.disconnectAudio(client, connectedDevice())

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

  override fun setConnectionAllowed(address: String): Boolean {
    val d = deviceFor(address) ?: return false
    // The privileged process holds BLUETOOTH_PRIVILEGED, so this write —
    // which the app process cannot perform — succeeds here. Restoring ALLOW
    // before connect() undoes the FORBIDDEN policy a previous raw/AUTO
    // session persisted for this device, which otherwise makes the stack
    // refuse (or tear down seconds later) every hands-free connection.
    return HiddenHfp.setProfilePriority(context, d, HiddenHfp.PROFILE_ID, HiddenHfp.POLICY_ALLOWED)
  }

  override fun connectionPolicy(address: String): Int {
    val d = deviceFor(address) ?: return HiddenHfp.POLICY_UNREADABLE
    return HiddenHfp.profilePolicy(context, d, HiddenHfp.PROFILE_ID)
  }

  override fun setProfilePolicy(address: String, profileId: Int, policy: Int): Boolean {
    val d = deviceFor(address) ?: return false
    // The privileged process holds BLUETOOTH_PRIVILEGED, so this write - which
    // the app process cannot perform on a stock player - succeeds here. Lets
    // the repair action restore any guarded profile, not just HFP-client.
    return HiddenHfp.setProfilePriority(context, d, profileId, policy)
  }

  override fun setAudioRouteAllowed(address: String, allowed: Boolean): Boolean {
    val c = client ?: return false
    val d = deviceFor(address) ?: connectedDevice() ?: return false
    val result = HiddenHfp.setAudioRouteAllowed(c, d, allowed)
    return result == HiddenHfp.AudioRoutePermission.ALLOWED ||
      result == HiddenHfp.AudioRoutePermission.ALREADY_ALLOWED
  }

  override fun audioRouteAllowed(address: String): Int {
    val c = client ?: return -1
    val d = deviceFor(address) ?: connectedDevice()
    return when (HiddenHfp.getAudioRouteAllowed(c, d)) {
      true -> 1
      false -> 0
      null -> -1
    }
  }

  // ------------------------------------------------- device preparation
  //
  // Everything below runs under the privileged identity (Shizuku's `shell`, or
  // uid 0 for the root channel). None of it needs root *specifically*: whether
  // the property write below succeeds is decided by the player's SELinux
  // policy, not by which of the two identities is running it. On a stock
  // Android 13+ build `bluetooth.profile.*` lives in the bluetooth_config_prop
  // context that only init may write, and the attempt fails cleanly; on the
  // lax policies common to cheap players it can succeed, which is the whole
  // reason to try it before telling a user to root the device.

  override fun getSystemProperty(key: String): String = readProperty(key)

  override fun setSystemProperty(key: String, value: String): String? {
    val viaApi = runCatching {
      Class.forName("android.os.SystemProperties")
        .getMethod("set", String::class.java, String::class.java)
        .invoke(null, key, value)
    }.isSuccess
    // SystemProperties.set throws when property_service refuses the write.
    // `setprop` goes through the same service, but some vendor images ship a
    // setuid helper that behaves differently, so it is worth a second try.
    if (!viaApi) runCatching { exec(arrayOf("setprop", key, value)) }
    val readBack = readProperty(key)
    return readBack.ifBlank { null }
  }

  override fun restartBluetooth(): Boolean {
    val a = adapter ?: return false
    // The profile flags are read when the Bluetooth process starts, so a
    // property write is invisible until the stack comes back up.
    val disabled = runCatching { disableAdapter(a) }.getOrDefault(false)
    if (!disabled) return false
    // Give the stack time to tear down before asking it back up; enabling too
    // early is rejected while the adapter is still TURNING_OFF.
    for (i in 0 until 40) {
      if (!a.isEnabled) break
      runCatching { Thread.sleep(250) }
    }
    return runCatching { enableAdapter(a) }.getOrDefault(false)
  }

  @Suppress("DEPRECATION")
  private fun disableAdapter(a: BluetoothAdapter): Boolean =
    if (a.disable()) true else exec(arrayOf("svc", "bluetooth", "disable"))

  @Suppress("DEPRECATION")
  private fun enableAdapter(a: BluetoothAdapter): Boolean =
    if (a.enable()) true else exec(arrayOf("svc", "bluetooth", "enable"))

  override fun setHiddenApiPolicy(policy: Int): Boolean = runCatching {
    android.provider.Settings.Global.putInt(
      context.contentResolver, "hidden_api_policy", policy,
    )
  }.getOrElse {
    // WRITE_SECURE_SETTINGS is held by `shell`, but a vendor build can still
    // refuse; the settings binary is the same write by another route.
    exec(arrayOf("settings", "put", "global", "hidden_api_policy", policy.toString()))
  }

  override fun selinuxMode(): String =
    runCatching { execOutput(arrayOf("getenforce")).trim() }.getOrDefault("")

  override fun enabledProfiles(): IntArray {
    val a = adapter ?: return IntArray(0)
    // getSupportedProfiles() reports the profiles the stack actually STARTED,
    // which is what decides whether call audio is possible - unlike the
    // profile proxy, which binds happily to a service that never ran.
    val list = runCatching {
      @Suppress("UNCHECKED_CAST")
      a.javaClass.getMethod("getSupportedProfiles").invoke(a) as? List<Int>
    }.getOrNull() ?: return IntArray(0)
    return list.filterNotNull().toIntArray()
  }

  private fun readProperty(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties")
      .getMethod("get", String::class.java, String::class.java)
      .invoke(null, key, "") as? String ?: ""
  }.getOrElse { runCatching { execOutput(arrayOf("getprop", key)).trim() }.getOrDefault("") }

  /** Runs a command, returns true on a zero exit code. */
  private fun exec(cmd: Array<String>): Boolean = runCatching {
    val p = Runtime.getRuntime().exec(cmd)
    p.waitFor()
    p.exitValue() == 0
  }.getOrDefault(false)

  private fun execOutput(cmd: Array<String>): String = runCatching {
    val p = Runtime.getRuntime().exec(cmd)
    val out = p.inputStream.bufferedReader().use { it.readText() }
    p.waitFor()
    out
  }.getOrDefault("")

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
    // Shizuku convention (shared by the root channel): the process is not
    // killed automatically, so the service must terminate itself after the
    // cleanup.
    System.exit(0)
  }
}
