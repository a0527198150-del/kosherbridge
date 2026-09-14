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

  @Test
  fun `a first run counts as a new boot`() {
    assertTrue(BootMarker.isNewBoot(stored = -1L, now = 0L))
    assertTrue(BootMarker.isNewBoot(stored = -1L, now = 5_000_000L))
  }

  @Test
  fun `a later reading in the same boot is not a new boot`() {
    // The restore ran 30 seconds after boot; it is now ten minutes in.
    assertFalse(BootMarker.isNewBoot(stored = 30_000L, now = 600_000L))
  }

  @Test
  fun `the same reading twice is not a new boot`() {
    // The service was restarted so fast the clock did not move. Doing the work
    // again here is exactly the loop the marker exists to stop.
    assertFalse(BootMarker.isNewBoot(stored = 600_000L, now = 600_000L))
  }

  @Test
  fun `a smaller reading than the stored one proves a reboot`() {
    // elapsedRealtime is reset by the reboot, so the new reading is small.
    assertTrue(BootMarker.isNewBoot(stored = 86_400_000L, now = 12_000L))
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
