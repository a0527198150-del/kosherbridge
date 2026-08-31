package com.example.kosherbridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the code that actually fixes the reported field failure:
 * [ConnectionPolicyGuard]'s `channelMode == "RAW"` gate (profile 16 must never
 * be FORBIDDEN in AUTO / privileged modes) and the record/restore/repair
 * mechanism. The read and write are fakes, so no Bluetooth stack is involved.
 */
class ConnectionPolicyGuardTest {

  private val ADDR = "AA:BB:CC:DD:EE:FF"

  /** A fake policy write that records calls and can be made to fail. */
  private class FakeWrite {
    val calls = mutableListOf<Pair<Int, Int>>() // (profileId, policy)
    var succeed = true
    fun write(profileId: Int, policy: Int): Boolean {
      calls += profileId to policy
      return succeed
    }
  }

  @Test
  fun auto_does_not_forbid_headset_client() {
    // The actual field-bug fix: AUTO (which today resolves to the raw path)
    // must NOT mark the HFP-client profile FORBIDDEN, or it silently poisons
    // the Shizuku/root channels afterwards. If the `channelMode == "RAW"` gate
    // is reverted, this fails.
    assertFalse(ConnectionPolicyGuard().shouldForbidHeadsetClient("AUTO"))
  }

  @Test
  fun raw_does_forbid_headset_client() {
    // Only the explicit, sticky RAW choice sacrifices the HFP-client profile.
    assertTrue(ConnectionPolicyGuard().shouldForbidHeadsetClient("RAW"))
  }

  @Test
  fun privileged_modes_forbid_nothing() {
    val guard = ConnectionPolicyGuard()
    for (mode in listOf("SHIZUKU", "ROOT", "DIRECT", "AUTO")) {
      assertFalse("$mode must not forbid the HFP-client profile", guard.shouldForbidHeadsetClient(mode))
    }
  }

  @Test
  fun original_policy_recorded_once() {
    // A second recordOriginal after the app has already written FORBIDDEN must
    // NOT overwrite the stored original - restore would otherwise be a no-op.
    val guard = ConnectionPolicyGuard()
    var stackValue = HiddenHfp.POLICY_ALLOWED // the pre-write original
    guard.recordOriginals(ADDR) { stackValue }
    stackValue = HiddenHfp.POLICY_FORBIDDEN // the app's own write is now visible
    guard.recordOriginals(ADDR) { stackValue }

    val fake = FakeWrite()
    val results = guard.restore(ADDR, fake::write)

    // Every guarded profile was recorded exactly once and restored to the
    // ORIGINAL (ALLOWED), never the FORBIDDEN that the app itself wrote.
    assertEquals(guard.guardedProfiles.size, results.size)
    assertEquals(guard.guardedProfiles.size, fake.calls.size)
    assertTrue(fake.calls.all { it.second == HiddenHfp.POLICY_ALLOWED })
  }

  @Test
  fun restore_writes_back_recorded_value() {
    val guard = ConnectionPolicyGuard()
    guard.recordOriginals(ADDR) { HiddenHfp.POLICY_ALLOWED }
    val fake = FakeWrite()

    val first = guard.restore(ADDR, fake::write)
    assertEquals(guard.guardedProfiles.size, first.size)
    // Wrote exactly the recorded value back for each guarded profile.
    assertTrue(fake.calls.all { it.second == HiddenHfp.POLICY_ALLOWED })
    assertTrue(fake.calls.map { it.first }.toSet() == guard.guardedProfiles.toSet())

    // Restoring again is a no-op: the record was forgotten.
    val second = guard.restore(ADDR, fake::write)
    assertTrue(second.isEmpty())
    assertEquals(guard.guardedProfiles.size, fake.calls.size) // no extra writes
  }

  @Test
  fun restore_keeps_record_when_write_fails() {
    val guard = ConnectionPolicyGuard()
    guard.recordOriginals(ADDR) { HiddenHfp.POLICY_ALLOWED }

    val fake = FakeWrite().apply { succeed = false }
    val failed = guard.restore(ADDR, fake::write)
    assertTrue(failed.isNotEmpty())
    assertTrue(failed.all { !it.applied })

    // Record kept, so a later retry succeeds.
    fake.succeed = true
    val retry = guard.restore(ADDR, fake::write)
    assertTrue(retry.isNotEmpty())
    assertTrue(retry.all { it.applied })
  }

  @Test
  fun repair_sets_all_guarded_profiles_allowed() {
    val guard = ConnectionPolicyGuard()
    guard.recordOriginals(ADDR) { HiddenHfp.POLICY_FORBIDDEN }

    val fake = FakeWrite()
    val results = guard.repair(ADDR, fake::write)

    // All four guarded profiles are written with ALLOWED.
    assertEquals(guard.guardedProfiles.size, results.size)
    assertTrue(fake.calls.all { it.second == HiddenHfp.POLICY_ALLOWED })
    assertTrue(fake.calls.map { it.first }.toSet() == guard.guardedProfiles.toSet())
    // Repair restores the recorded entry too.
    assertFalse(guard.hasRecorded(ADDR))
  }

  @Test
  fun restore_is_noop_when_nothing_recorded() {
    val guard = ConnectionPolicyGuard()
    val fake = FakeWrite()
    val results = guard.restore(ADDR, fake::write)
    assertTrue(results.isEmpty())
    assertTrue(fake.calls.isEmpty())
  }
}