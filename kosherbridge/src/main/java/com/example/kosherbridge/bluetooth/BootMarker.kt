package com.example.kosherbridge.bluetooth

/**
 * Answers "has the player rebooted since we last did this?" from nothing but
 * two readings of `SystemClock.elapsedRealtime()`.
 *
 * That clock counts from boot and is reset by it, which makes the whole
 * question a comparison: a stored reading LARGER than the current one cannot
 * have come from this boot, so a boot happened in between. No wall clock is
 * involved, so changing the time zone, an NTP correction or a user setting the
 * date cannot fool it; and nothing depends on BOOT_COMPLETED being delivered,
 * which on the cheap players this app targets is not something to rely on.
 *
 * It matters because the thing it guards - re-applying the profile property and
 * restarting Bluetooth - must happen once per boot and never twice. The
 * watchdog restarts the bridge service on its own schedule, so "once per
 * process" would have meant restarting Bluetooth every few minutes.
 *
 * Pulled out of the manager as a pure function so it can be tested: it is one
 * line of arithmetic whose failure mode is invisible in ordinary use and
 * obvious in a test.
 */
internal object BootMarker {

  /**
   * @param stored the reading taken at the last attempt, or a negative number
   *   when no attempt was ever made - which counts as a new boot, since the
   *   work has certainly not been done.
   * @param now the current reading.
   */
  fun isNewBoot(stored: Long, now: Long): Boolean {
    if (stored < 0L) return true
    return now < stored
  }
}
