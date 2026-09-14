package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.IBinder
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client side of a privileged HFP bridge running in a process this app spawned
 * for itself.
 *
 * [ShizukuBridge] needs the separate Shizuku app and its server. This class
 * needs neither: it starts its own privileged process directly,
 *
 *     <launcher> "CLASSPATH=<our apk> app_process ... SpawnedBridgeMain ..."
 *
 * and [PrivilegedLauncher] is the only thing that differs between the two ways
 * of doing that:
 *
 *  - [SuLauncher] runs it through `su`, so the process is uid 0 - exempt from
 *    hidden-API enforcement and past every permission check.
 *  - [AdbLauncher] runs it through the player's own ADB daemon, so the process
 *    is uid 2000 (`shell`) - which holds BLUETOOTH_PRIVILEGED and
 *    WRITE_SECURE_SETTINGS, and is exactly the identity Shizuku hands out. No
 *    root, no second app, no PC.
 *
 * Everything after the spawn is identical for both, which is the point of the
 * split: [HfpUserService] is instantiated in that process and its binder is
 * handed back here through [SpawnedBridgeProvider] (a ContentProvider.call with
 * a Bundle.putBinder extra - the same handoff Shizuku's server performs), and
 * every operation is then proxied over the [IHfpBridge] AIDL interface.
 */
class SpawnedBridge(
  private val context: Context,
  private val launcher: PrivilegedLauncher,
) {

  companion object {
    private const val TAG = "SpawnedBridge"

    const val AUTHORITIES = "com.example.kosherbridge.spawnedbridge"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PID = "pid"

    private const val ENTRY_CLASS = "com.example.kosherbridge.bluetooth.SpawnedBridgeMain"
    private const val SERVICE_CLASS = "com.example.kosherbridge.bluetooth.HfpUserService"

    /** A spawn older than this without delivering its binder is dead - re-spawn. */
    private const val SPAWN_RETRY_AFTER_MS = 45_000L

    /** The uids a process this app spawned can legitimately run as. */
    val SPAWNABLE_UIDS = setOf(0, 2000)

    /**
     * Spawns waiting for their binder, keyed by the one-time token each was
     * started with.
     *
     * This used to be a single `active` field plus a single `expectedToken`,
     * which quietly assumed only one bridge could ever exist. With two
     * launchers that assumption is false: constructing the second bridge
     * replaced the first as `active`, so the first one's binder - when it
     * finally arrived - was delivered to the wrong instance or dropped. Keying
     * by token makes each handoff find its own spawn, and consuming the entry
     * on delivery means a replayed token is ignored.
     */
    private val pending = ConcurrentHashMap<String, SpawnedBridge>()

    /** Called by [SpawnedBridgeProvider] when a spawned process hands over its binder. */
    fun accept(binder: IBinder, pid: Int, token: String?) {
      val bridge = token?.let { pending.remove(it) } ?: return
      bridge.onRemoteDelivered(binder, pid)
    }
  }

  @Volatile private var remote: IHfpBridge? = null
  @Volatile private var spawnedPid = -1
  private val startMutex = Mutex()
  @Volatile private var started = false
  /** When the last spawn was launched - used to un-stick a spawn that never
   * delivered its binder (silent app_process crash, bad CLASSPATH, ...).
   * Without it `started` stayed true forever and the root channel was bricked
   * until the app restarted. */
  @Volatile private var startedAt = 0L
  private var remoteDied: (() -> Unit)? = null

  val isBound: Boolean get() = remote != null

  /** Fired when the spawned process dies (binder death). */
  fun onRemoteDied(callback: () -> Unit) {
    remoteDied = callback
  }

  /**
   * Spawns the privileged process. The command backgrounds a subshell (like
   * Shizuku's start.sh) so the launcher returns immediately while the
   * app_process child keeps running; the binder handoff then arrives
   * asynchronously via [SpawnedBridgeProvider] - poll [isBound] or wait in the
   * caller.
   *
   * A spawn that never delivers its binder within [SPAWN_RETRY_AFTER_MS] is
   * treated as dead and re-spawned on the next call rather than sticking for
   * ever (a silent app_process crash, a bad CLASSPATH).
   *
   * The mutex is not decoration: two concurrent bind calls would otherwise
   * both spawn, and the second would leak a booted-but-idle app_process whose
   * binder nothing is waiting for.
   */
  suspend fun start(): Boolean = startMutex.withLock {
    if (remote != null) return@withLock true
    if (started && System.currentTimeMillis() - startedAt < SPAWN_RETRY_AFTER_MS) {
      return@withLock true
    }
    val token = UUID.randomUUID().toString()
    pending[token] = this
    // packageCodePath is only the BASE apk. An app installed as an app bundle
    // keeps its Kotlin/AndroidX classes in split apks, and a CLASSPATH missing
    // them makes the spawned process die with NoClassDefFoundError before it
    // can report anything - the channel just silently never binds.
    val info = context.applicationInfo
    val apk = buildList {
      add(info.sourceDir ?: context.packageCodePath)
      info.splitSourceDirs?.let { addAll(it) }
    }.filter { it.isNotBlank() }.distinct().joinToString(":")
    val cmd = buildString {
      append("( CLASSPATH='").append(apk).append("' /system/bin/app_process /system/bin --nice-name='")
      append(context.packageName).append(':').append(launcher.processSuffix).append("' ")
      append(ENTRY_CLASS)
      append(" --package=").append(context.packageName)
      append(" --class=").append(SERVICE_CLASS)
      append(" --token=").append(token)
      append(" ) >/dev/null 2>&1 &")
    }
    val ok = launcher.runDetached(cmd)
    started = ok
    startedAt = if (ok) System.currentTimeMillis() else 0L
    // A spawn that could not even be sent will never deliver anything, so its
    // entry would otherwise sit in the map for the life of the process.
    if (!ok) pending.remove(token)
    Log.i(
      TAG,
      if (ok) "${launcher.label} process spawned" else "failed to spawn ${launcher.label} process",
    )
    ok
  }

  /** Stops the spawned process: asks it to destroy itself, then kills by pid. */
  suspend fun stop(): Unit = startMutex.withLock {
    runCatching { remote?.destroy() }
    if (spawnedPid > 0) launcher.kill(spawnedPid)
    remote = null
    spawnedPid = -1
    started = false
    startedAt = 0L
    // Anything still waiting for THIS bridge is now stale.
    pending.entries.removeAll { it.value === this }
  }

  internal fun onRemoteDelivered(binder: IBinder, pid: Int) {
    spawnedPid = pid
    runCatching {
      binder.linkToDeath({
        Log.w(TAG, "root process died")
        remote = null
        spawnedPid = -1
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

  /**
   * Flips the HFP-client audio-route gate for one device in the privileged
   * process. On Android 13+ that call needs BLUETOOTH_PRIVILEGED, so this is
   * the only way to open it - and without it the stack rejects the phone's
   * SCO (voice) link and the call is audible nowhere.
   */
  fun setAudioRouteAllowed(address: String, allowed: Boolean): Boolean =
    runCatching { remote?.setAudioRouteAllowed(address, allowed) ?: false }.getOrDefault(false)

  /** Reads the same gate: true, false, or null when it cannot be read. */
  fun audioRouteAllowed(address: String): Boolean? =
    runCatching { remote?.audioRouteAllowed(address) }.getOrNull()
      ?.let { if (it < 0) null else it == 1 }

  // ---------------------------------------------------- device preparation

  /** Reads a system property in the privileged process. */
  fun systemProperty(key: String): String =
    runCatching { remote?.getSystemProperty(key) ?: "" }.getOrDefault("")

  /**
   * Attempts to write a system property, returning the value read back
   * afterwards (null when the write was refused). Used to try enabling the
   * HFP-client profile flag without root - it succeeds only where the
   * player's SELinux policy permits it.
   */
  fun writeSystemProperty(key: String, value: String): String? =
    runCatching { remote?.setSystemProperty(key, value) }.getOrNull()

  /** Restarts the Bluetooth stack so it re-reads the profile flags. */
  fun restartBluetooth(): Boolean =
    runCatching { remote?.restartBluetooth() ?: false }.getOrDefault(false)

  /** Sets Android's non-SDK interface policy. DEVICE-GLOBAL. */
  fun setHiddenApiPolicy(policy: Int): Boolean =
    runCatching { remote?.setHiddenApiPolicy(policy) ?: false }.getOrDefault(false)

  /** "Enforcing" / "Permissive" / "" when unknown. */
  fun selinuxMode(): String =
    runCatching { remote?.selinuxMode() ?: "" }.getOrDefault("")

  /** Profile IDs the stack currently has enabled (HEADSET_CLIENT is 16). */
  fun enabledProfiles(): List<Int> =
    runCatching { remote?.enabledProfiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())

  /** Last-resort attempt to start the dormant HFP-client profile service.
   * Returns "OK" or an "ERR:..." reason. */
  fun startHeadsetClientService(): String =
    runCatching { remote?.startHeadsetClientService() ?: "ERR:אין חיבור לערוץ המורשה" }
      .getOrElse { "ERR:${it.message ?: "שגיאה"}" }

  /** Re-enables the HFP-client service component at the package-manager level. */
  fun enableProfileComponent(): String =
    runCatching { remote?.enableProfileComponent() ?: "ERR:אין חיבור לערוץ המורשה" }
      .getOrElse { "ERR:${it.message ?: "שגיאה"}" }

  /** Current bluetooth_disabled_profiles bitmask ("" when the setting is absent). */
  fun disabledProfilesSetting(): String =
    runCatching { remote?.disabledProfilesSetting() ?: "" }.getOrDefault("")

  /** Clears that bitmask. Returns "OK" or "ERR:...". */
  fun clearDisabledProfilesSetting(): String =
    runCatching { remote?.clearDisabledProfilesSetting() ?: "ERR:אין חיבור לערוץ המורשה" }
      .getOrElse { "ERR:${it.message ?: "שגיאה"}" }
}
