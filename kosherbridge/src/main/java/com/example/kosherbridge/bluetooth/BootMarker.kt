package com.example.kosherbridge.bluetooth

import android.os.SystemClock
import kotlin.math.abs

/**
 * Answers "has the player rebooted since we last did this?".
 *
 * The question matters because the thing it guards - re-applying the profile
 * property and restarting Bluetooth - must happen once per boot and never
 * twice. The watchdog restarts the bridge service on its own schedule, so
 * "once per process" would have meant restarting Bluetooth every few minutes.
 *
 * The answer is the BOOT INSTANT: the wall-clock moment the player started,
 * computed as `currentTimeMillis() - elapsedRealtime()`. Within one boot both
 * clocks advance together so the difference is constant; a reboot resets
 * `elapsedRealtime` to zero while the RTC keeps counting, so the difference
 * jumps by the time the player spent off. Comparing two readings of it is
 * therefore a direct comparison of "which boot is this".
 *
 * A plain `elapsedRealtime` comparison - is the current reading SMALLER than
 * the stored one - looks like it answers the same question and does not. It is
 * only sound when the stored reading came from late in the previous boot. This
 * marker is written EARLY in a boot (the repair runs within a minute of the
 * service starting), so after the next reboot any check made later than that
 * first minute - a delayed BOOT_COMPLETED, or the user simply opening the app
 * ten minutes in - reads as "later in the same boot" and the repair is skipped
 * for the rest of the day, silently.
 *
 * The tolerance absorbs clock corrections. Getting it wrong in that direction
 * is safe: a false "new boot" costs one repair attempt, and the caller's first
 * check is whether the profile is already running - which after a successful
 * repair it is, so the attempt turns into a no-op. A false "same boot" costs
 * the user call audio for the rest of the day and says nothing. The asymmetry
 * is the whole design.
 */
internal object BootMarker {

  /**
   * How far two readings may differ and still count as the same boot.
   *
   * An NTP correction, or a player with no RTC battery jumping from 1970 to
   * the real date, shifts the wall clock and with it this value. A minute
   * covers ordinary drift; a larger jump reads as a reboot, which by the
   * argument above is the harmless direction.
   */
  const val TOLERANCE_MS = 60_000L

  /** This boot's identity. Android-only; [isNewBoot] is the testable half. */
  fun currentBootInstant(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

  /**
   * @param stored the boot instant recorded at the last attempt.
   * @param current the boot instant now.
   */
  fun isNewBoot(stored: Long, current: Long): Boolean = abs(current - stored) > TOLERANCE_MS
}
