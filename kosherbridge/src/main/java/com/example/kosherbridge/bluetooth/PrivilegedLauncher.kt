package com.example.kosherbridge.bluetooth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How [SpawnedBridge] gets its privileged process off the ground.
 *
 * Everything after the spawn is identical whichever way it happened - the same
 * app_process command line, the same binder handoff, the same AIDL surface - so
 * this is deliberately the whole of the difference between "the bridge runs as
 * root" and "the bridge runs as shell".
 */
interface PrivilegedLauncher {

  /** Short human-readable name, for the journal. */
  val label: String

  /** Appended to the spawned process's `--nice-name`, so `ps` says which is which. */
  val processSuffix: String

  /**
   * True when this launcher can actually start something right now.
   *
   * Deliberately allowed to be expensive and to have side effects: for `su`
   * this is the call that may raise the root manager's grant prompt, so it is
   * only ever called on a path the user chose.
   */
  suspend fun available(): Boolean

  /**
   * Starts [command] so that it outlives the call. Returns false when the
   * command could not be handed over at all - which is not the same as the
   * spawned process failing later, and the caller distinguishes them by
   * whether a binder ever arrives.
   */
  suspend fun runDetached(command: String): Boolean

  /** Best-effort kill of a process this launcher started. */
  suspend fun kill(pid: Int)

  /**
   * What to tell the user when [available] says no.
   *
   * Each channel fails for its own reason and has its own fix, and "the
   * channel is unavailable" helps nobody: root needs a grant that may never
   * have been asked for, ADB needs wireless debugging and a pairing.
   */
  fun unavailableMessage(): String
}

/**
 * Runs the privileged process through `su`, as uid 0.
 *
 * Exempt from hidden-API enforcement and past every permission check - the most
 * capable channel, and the only one that can write the Bluetooth profile
 * properties on a player whose SELinux is Enforcing.
 */
class SuLauncher : PrivilegedLauncher {

  override val label = "רוט"
  override val processSuffix = "root"

  /**
   * True when `su` actually grants this app uid 0.
   *
   * On the first call the root manager (Magisk / KernelSU) may show its grant
   * prompt, so this runs only when the user picked the root channel. Use
   * [hasBinary] for the passive question.
   */
  override suspend fun available(): Boolean = withContext(Dispatchers.IO) {
    val r = exec(arrayOf("su", "-c", "id"), 3_000)
    r.exited && r.exitCode == 0 && r.output.contains("uid=0")
  }

  override suspend fun runDetached(command: String): Boolean = withContext(Dispatchers.IO) {
    val r = exec(arrayOf("su", "-c", command), 5_000)
    r.exited && r.exitCode == 0
  }

  override suspend fun kill(pid: Int) {
    withContext(Dispatchers.IO) {
      runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $pid")) }
    }
  }

  override fun unavailableMessage(): String =
    "לא הוענקה הרשאת רוט לאפליקציה - אשרו את בקשת ההרשאה (Magisk) ובחרו שוב 'דרך הרשאת רוט'"


  /**
   * True when a `su` binary merely EXISTS. Does not trigger the grant prompt,
   * so it is safe from diagnostics at boot - but a binary can exist while this
   * app was never granted root, which is what [available] proves.
   */
  suspend fun hasBinary(): Boolean = withContext(Dispatchers.IO) {
    val r = exec(arrayOf("sh", "-c", "command -v su"), 3_000)
    r.exited && r.exitCode == 0 && r.output.isNotBlank()
  }

  private data class CmdResult(val exited: Boolean, val exitCode: Int, val output: String)

  /**
   * Runs a command with a hard timeout, minSdk-safe (Process.waitFor(long,
   * TimeUnit) is API 26+). A hanging `su` prompt - root not granted yet - must
   * not block the app, so the wait happens on a watchdog thread and the process
   * is destroyed if it overruns.
   */
  private fun exec(cmd: Array<String>, timeoutMs: Long): CmdResult {
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
}

/**
 * Runs the privileged process through the player's own ADB daemon, as uid 2000
 * (`shell`).
 *
 * Less capable than root and deliberately so: `shell` holds
 * BLUETOOTH_PRIVILEGED and WRITE_SECURE_SETTINGS - enough for the audio gate,
 * the connection-policy repair, and the profile property on a player whose
 * SELinux is Permissive - but it cannot write an init-only property, so an
 * Enforcing player still needs root for that one step. What it buys instead is
 * that nobody has to root anything, install anything, or own a PC.
 *
 * The [AdbShell] it runs on only exists in the "plus" product flavour; in the
 * standard build this launcher is never constructed.
 */
class AdbLauncher(private val shell: AdbShell) : PrivilegedLauncher {

  override val label = "ADB מקומי"
  override val processSuffix = "adb"

  override suspend fun available(): Boolean = shell.state == AdbShell.State.CONNECTED

  override suspend fun runDetached(command: String): Boolean {
    if (!available()) {
      Log.w(TAG, "no live ADB connection - cannot spawn")
      return false
    }
    return shell.runDetached(command)
  }

  override suspend fun kill(pid: Int) {
    if (!available()) return
    shell.exec("kill -9 $pid")
  }

  override fun unavailableMessage(): String = when (shell.state) {
    AdbShell.State.NEEDS_PAIRING ->
      "צריך להתאים את האפליקציה ל-ADB של הנגן: הגדרות ← כל הגדרות החיבור ← " +
        "'ערוץ ADB מקומי', והזן שם את קוד ההתאמה בן שש הספרות"
    AdbShell.State.PAIRED ->
      "מותאם אך לא מחובר. ודא שניפוי באגים אלחוטי דלוק בנגן, ולחץ 'התחבר' " +
        "במסך 'ערוץ ADB מקומי'"
    AdbShell.State.CONNECTED -> "החיבור ל-ADB אבד - נסה להתחבר שוב"
  }

  private companion object {
    const val TAG = "AdbLauncher"
  }
}
