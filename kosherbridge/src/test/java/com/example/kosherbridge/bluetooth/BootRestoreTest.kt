package com.example.kosherbridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot-restore decision, in the two halves that can be tested on the JVM:
 * "is this a new boot" and "does the verdict admit that the channel in force
 * ignores the profile".
 *
 * Both guard behaviour whose failure is silent. A broken boot marker either
 * restarts Bluetooth every few minutes or never restores the profile at all,
 * and neither reports anything; a verdict that forgets the channel tells a user
 * who has just done everything right that the audio should be working when the
 * app is actively preventing it.
 */
class BootRestoreTest {

  /** A plausible wall-clock moment, so the numbers read like real readings. */
  private val bootedAt = 1_760_000_000_000L

  @Test
  fun `the same boot instant is the same boot`() {
    assertFalse(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt))
  }

  @Test
  fun `a later check within the same boot is still the same boot`() {
    // THE REGRESSION THIS REPLACED. With an elapsedRealtime comparison, a
    // marker written a minute into boot N and checked ten minutes into boot
    // N+1 read as "later in the same boot", and the repair was skipped for the
    // rest of the day. The boot instant does not move within a boot at all, so
    // when the check happens is irrelevant.
    assertFalse(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt + 40))
  }

  @Test
  fun `a reboot moves the boot instant forward by the time spent off`() {
    // Switched off for two minutes: the RTC kept counting, elapsedRealtime
    // restarted, so the difference jumped.
    assertTrue(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt + 120_000L))
  }

  @Test
  fun `a check made later in the new boot than the marker was written is still a reboot`() {
    // The case that has to keep working however late the service starts: the
    // marker was written one minute into the previous boot, and this check is
    // ten minutes into the next one. Only the boot instant answers this.
    assertTrue(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt + 3_600_000L))
  }

  @Test
  fun `ordinary clock drift does not look like a reboot`() {
    // A small NTP correction shifts the wall clock and with it this value.
    assertFalse(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt + 1_200L))
    assertFalse(BootMarker.isNewBoot(stored = bootedAt, current = bootedAt - 1_200L))
  }

  @Test
  fun `a large clock correction errs towards repairing`() {
    // A player with no RTC battery starts at 1970 and jumps to the real date
    // when the clock is set. That reads as a reboot - deliberately the safe
    // direction: the caller's first check is whether the profile is already
    // running, so a needless attempt becomes a no-op, while a missed reboot
    // costs the user call audio all day and says nothing.
    assertTrue(BootMarker.isNewBoot(stored = 0L, current = bootedAt))
  }

  @Test
  fun `a negative boot instant is an ordinary value`() {
    // currentTimeMillis() - elapsedRealtime() really is negative while the
    // clock is still at 1970 and the player has been up for a while, so it
    // must never be mistaken for "nothing recorded".
    assertFalse(BootMarker.isNewBoot(stored = -90_000L, current = -90_000L))
    assertTrue(BootMarker.isNewBoot(stored = -90_000L, current = 90_000L))
  }

  @Test
  fun `the verdict says the profile is on when a profile channel is in force`() {
    val caps = capabilities(profileEnabled = true, channel = "SHIZUKU")
    assertFalse(caps.channelBypassesProfile)
    assertTrue(caps.verdict.contains("הקול אמור לעבור"))
  }

  @Test
  fun `the verdict warns when the auto channel is bypassing an enabled profile`() {
    for (channel in listOf("AUTO", "RAW")) {
      val caps = capabilities(profileEnabled = true, channel = channel)
      assertTrue("$channel should count as bypassing", caps.channelBypassesProfile)
      // The point of the message is the instruction, not the diagnosis.
      assertTrue("$channel verdict must name the setting", caps.verdict.contains("ערוץ חיבור"))
      assertFalse(
        "$channel verdict must not promise audio",
        caps.verdict.contains("הקול אמור לעבור"),
      )
    }
  }

  @Test
  fun `an unknown channel is never reported as a mismatch`() {
    // Claiming a mismatch we cannot prove would send the user to change a
    // setting that may already be correct.
    assertFalse(capabilities(profileEnabled = true, channel = null).channelBypassesProfile)
  }

  @Test
  fun `the channel does not change the verdict when the profile is off`() {
    // A disabled profile has its own diagnosis, and the channel is irrelevant
    // to it - fixing the channel first would be wasted effort.
    val auto = capabilities(profileEnabled = false, channel = "AUTO")
    val shizuku = capabilities(profileEnabled = false, channel = "SHIZUKU")
    assertEquals(auto.verdict, shizuku.verdict)
  }

  private fun capabilities(profileEnabled: Boolean?, channel: String?) = PlayerCapabilities(
    device = "test",
    profileEnabled = profileEnabled,
    profilePresent = true,
    profileFlag = "",
    audioRouteFlag = "",
    selinuxMode = "Enforcing",
    hiddenApiReachable = false,
    enabledProfiles = if (profileEnabled == true) listOf(16) else emptyList(),
    activeChannel = channel,
    sdkInt = 33,
  )
}
