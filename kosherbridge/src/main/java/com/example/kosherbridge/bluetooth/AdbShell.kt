package com.example.kosherbridge.bluetooth

/**
 * A shell identity obtained from the player's own ADB daemon, over loopback.
 *
 * Why this exists at all: the bridge's remaining no-root lever is a shell
 * identity. `com.android.shell` holds BLUETOOTH_PRIVILEGED and
 * WRITE_SECURE_SETTINGS, which is exactly what the audio gate, the connection
 * policy repair and the profile-enable ladder need - and on a player whose
 * SELinux is Permissive (common on the cheap ones this app targets) a shell can
 * write the Bluetooth profile property too.
 *
 * Until now the only way to get one was Shizuku: a second app, installed,
 * paired over wireless debugging, started, and granted - five steps before this
 * app can do anything. Android 11+ lets any app speak the ADB protocol to
 * 127.0.0.1 and get the same identity, with the pairing code the user already
 * has on screen. One step, in this app, no PC and no root.
 *
 * The implementation lives in the "plus" product flavour only, because it needs
 * android.permission.INTERNET to open the loopback socket and that permission
 * is not something to add to every install of an app for kosher phones. See the
 * productFlavors block in build.gradle.kts. [AdbShellFactory] is defined once
 * per flavour and returns null in the standard build.
 */
interface AdbShell {

  /** Where the channel currently stands, for the UI to render honestly. */
  enum class State {
    /** Never paired on this player, or the pairing was forgotten. */
    NEEDS_PAIRING,

    /** Paired, but no live connection to adbd right now. */
    PAIRED,

    /** Connected: [exec] and [runDetached] work. */
    CONNECTED,
  }

  val state: State

  /** True when wireless debugging exists on this Android version at all. */
  val supportedHere: Boolean

  /**
   * Pairs with the daemon using the six-digit code the player shows under
   * Wireless debugging -> "Pair device with pairing code".
   *
   * @param port the PAIRING port from that dialog, which is not the port the
   *   same screen shows for connecting - a confusion worth spelling out in the
   *   UI, because entering the wrong one fails with no useful error.
   * @return a human-readable result, Hebrew, for the screen and the journal.
   */
  suspend fun pair(port: Int, code: String): String

  /**
   * Connects to adbd. Discovers the port itself where the platform supports
   * it (it is randomised per boot on Android 11+), falling back to a port the
   * user read off the wireless-debugging screen.
   */
  suspend fun connect(port: Int? = null): String

  /**
   * Makes sure the channel is usable right now, reconnecting if it is not.
   *
   * This is what turns the ADB route from a demo into something that survives
   * ordinary use. The pairing is permanent - the daemon stores our key - but
   * the CONNECTION is not: it dies when the player is switched off, when
   * wireless debugging is toggled, and when adbd restarts. Worse, the port it
   * listens on is randomised at every boot, so "reconnect to the port that
   * worked yesterday" is not a thing.
   *
   * Without this, everything the privileged channel achieved stopped working
   * at the first reboot and stayed broken until the user happened to open the
   * ADB screen and press Connect - for a bridge whose whole job is to sit in a
   * drawer and answer calls, that is the same as not working.
   *
   * Callers may invoke it freely: it returns immediately when already
   * connected, serialises concurrent attempts, and rate-limits failures so a
   * player with wireless debugging switched off is not probed in a loop.
   *
   * @return true when [exec] and [runDetached] can be used on return.
   */
  suspend fun ensureConnected(): Boolean

  /** Runs one command and returns its combined output. */
  suspend fun exec(command: String): String

  /**
   * Starts a command that outlives the call, for spawning the privileged
   * process. Returns false when the command could not be sent at all.
   */
  suspend fun runDetached(command: String): Boolean

  /** Drops the connection. The pairing survives - it is stored by the daemon. */
  fun disconnect()
}
