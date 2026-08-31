package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.IBinder
import android.util.Log
import java.util.UUID

/**
 * Client side of the privileged HFP bridge running through **root** (`su`).
 *
 * [ShizukuBridge] needs the separate Shizuku app + server; this class needs
 * nothing but a rooted device (Magisk / KernelSU / SuperSU - any `su` that
 * grants this app uid 0). The app spawns its own privileged process:
 *
 *     su -c "CLASSPATH=<our apk> app_process ... RootBridgeMain ..."
 *
 * That process runs under uid 0 - exempt from hidden-API enforcement and
 * granted BLUETOOTH_PRIVILEGED - so the same reflection calls that are
 * blocked in the normal app process succeed there (see [RootBridgeMain]).
 * [HfpUserService] is instantiated in that process, and its binder is handed
 * back into this process through [RootBridgeProvider] (a ContentProvider.call
 * with a Bundle.putBinder extra - the same handoff mechanism Shizuku's server
 * uses, minus the Shizuku app). Every operation is then proxied over the
 * [IHfpBridge] AIDL interface, exactly like [ShizukuBridge].
 */
class RootBridge(private val context: Context) {

  companion object {
    private const val TAG = "RootBridge"

    const val AUTHORITIES = "com.example.kosherbridge.rootbridge"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PID = "pid"

    private const val ENTRY_CLASS = "com.example.kosherbridge.bluetooth.RootBridgeMain"
    private const val SERVICE_CLASS = "com.example.kosherbridge.bluetooth.HfpUserService"

    /** A spawn older than this without delivering its binder is dead - re-spawn. */
    private const val SPAWN_RETRY_AFTER_MS = 45_000L

    // Bridge between RootBridgeProvider (running in this process on a binder
    // thread) and the manager's RootBridge instance. The provider only lets
    // uid-0 callers through, and the one-time token ties the handoff to the
    // process start() actually launched.
    @Volatile private var active: RootBridge? = null
    @Volatile private var expectedToken: String? = null

    /** Called by [RootBridgeProvider] when the root process hands over its binder. */
    fun accept(binder: IBinder, pid: Int, token: String?) {
      val b = active ?: return
      if (expectedToken == null || token != expectedToken) return
      b.onRemoteDelivered(binder, pid)
    }
  }

  init {
    active = this
  }

  @Volatile private var remote: IHfpBridge? = null
  @Volatile private var rootPid = -1
  @Volatile private var started = false
  /** When the last spawn was launched - used to un-stick a spawn that never
   * delivered its binder (silent app_process crash, bad CLASSPATH, ...).
   * Without it `started` stayed true forever and the root channel was bricked
   * until the app restarted. */
  @Volatile private var startedAt = 0L
  private var remoteDied: (() -> Unit)? = null

  val isBound: Boolean get() = remote != null

  /** Fired when the root process dies (binder death). */
  fun onRemoteDied(callback: () -> Unit) {
    remoteDied = callback
  }

  /**
   * True when a `su` binary exists on this device. Does NOT trigger the root
   * grant prompt (safe to call from diagnostics at boot). A binary can exist
   * while the app was never granted root - [isRootAvailable] proves the grant.
   */
  fun hasRootBinary(): Boolean {
    val r = execWithTimeout(arrayOf("sh", "-c", "command -v su"), 3_000)
    return r.exited && r.exitCode == 0 && r.output.isNotBlank()
  }

  /**
   * True when `su` actually grants this app root (uid 0). On the first call
   * the root manager (Magisk/KernelSU) may show its grant prompt - call only
   * when the user opted into the root path.
   */
  fun isRootAvailable(): Boolean {
    val r = execWithTimeout(arrayOf("su", "-c", "id"), 3_000)
    return r.exited && r.exitCode == 0 && r.output.contains("uid=0")
  }

  private data class CmdResult(val exited: Boolean, val exitCode: Int, val output: String)

  /**
   * Runs a command with a hard timeout, minSdk-safe (Process.waitFor(long,
   * TimeUnit) is API 26+). A hanging su prompt (root not granted yet) must not
   * block the app, so the wait runs on a watchdog thread and the process is
   * destroyed if it does not finish in time.
   */
  private fun execWithTimeout(cmd: Array<String>, timeoutMs: Long): CmdResult {
    val p = try {
      Runtime.getRuntime().exec(cmd)
    } catch (t: Throwable) {
      return CmdResult(false, -1, "")
    }
    val output = StringBuilder()
    val reader = Thread {
      try {
        p.inputStream.bufferedReader().use { output.append(it.readText()) }
      } catch (_: Throwable) {
        // best effort - the output is only used for detection
      }
    }
    reader.start()
    val waiter = Thread {
      try {
        p.waitFor()
        reader.join()
      } catch (_: Throwable) {
        // best effort
      }
    }
    waiter.start()
    try {
      waiter.join(timeoutMs)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    if (waiter.isAlive) {
      runCatching { p.destroy() }
      return CmdResult(false, -1, output.toString())
    }
    return CmdResult(true, p.exitValue(), output.toString())
  }

  /**
   * Spawns the privileged root process. The command backgrounds a subshell
   * (like Shizuku's start.sh) so `su` returns immediately; the app_process
   * child keeps running under uid 0. The binder handoff arrives asynchronously
   * via [RootBridgeProvider] - poll [isBound] or wait in the caller.
   *
   * Synchronized so two concurrent bindRoot() coroutines cannot both spawn a
   * second process (each spawn replaces expectedToken, silently rejecting the
   * other's delivery and leaking a booted-but-idle app_process). A spawn that
   * never delivers its binder within [SPAWN_RETRY_AFTER_MS] is treated as
   * dead and re-spawned on the next call instead of sticking forever.
   */
  @Synchronized
  fun start(): Boolean {
    if (remote != null) return true
    if (started && System.currentTimeMillis() - startedAt < SPAWN_RETRY_AFTER_MS) return true
    val token = UUID.randomUUID().toString()
    expectedToken = token
    val apk = context.packageCodePath
    val cmd = buildString {
      append("( CLASSPATH='").append(apk).append("' /system/bin/app_process /system/bin --nice-name='")
      append(context.packageName).append(":root' ")
      append(ENTRY_CLASS)
      append(" --package=").append(context.packageName)
      append(" --class=").append(SERVICE_CLASS)
      append(" --token=").append(token)
      append(" ) >/dev/null 2>&1 &")
    }
    val r = execWithTimeout(arrayOf("su", "-c", cmd), 5_000)
    val ok = r.exited && r.exitCode == 0
    started = ok
    startedAt = if (ok) System.currentTimeMillis() else 0L
    if (!ok) expectedToken = null
    Log.i(TAG, if (ok) "root process spawned" else "failed to spawn root process")
    return ok
  }

  /** Stops the root process: asks it to destroy itself, then kills by pid. */
  @Synchronized
  fun stop() {
    runCatching { remote?.destroy() }
    if (rootPid > 0) {
      runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $rootPid")) }
    }
    remote = null
    rootPid = -1
    started = false
    startedAt = 0L
    expectedToken = null
  }

  internal fun onRemoteDelivered(binder: IBinder, pid: Int) {
    rootPid = pid
    runCatching {
      binder.linkToDeath({
        Log.w(TAG, "root process died")
        remote = null
        rootPid = -1
        started = false
        startedAt = 0L
        remoteDied?.invoke()
      }, 0)
    }
    remote = IHfpBridge.Stub.asInterface(binder)
    Log.i(TAG, "root bridge bound (pid=$pid)")
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

  /**
   * Asks the privileged root process to set the HFP-client connection policy
   * for one device back to ALLOWED before connect() (mirror of ShizukuBridge).
   */
  fun setConnectionAllowed(address: String): Boolean =
    runCatching { remote?.setConnectionAllowed(address) ?: false }.getOrDefault(false)

  /** Reads the HFP-client connection policy for one device (diagnostics). */
  fun connectionPolicy(address: String): Int =
    runCatching { remote?.connectionPolicy(address) ?: -1000 }.getOrDefault(-1000)

  /**
   * Asks the privileged root process to set the connection policy for one
   * device and guarded profile (generalization of [setConnectionAllowed]). Lets
   * the repair action restore every guarded profile, not just HFP-client.
   */
  fun setProfilePolicy(address: String, profileId: Int, policy: Int): Boolean =
    runCatching { remote?.setProfilePolicy(address, profileId, policy) ?: false }.getOrDefault(false)
}
