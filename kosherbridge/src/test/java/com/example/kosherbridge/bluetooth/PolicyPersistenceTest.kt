package com.example.kosherbridge.bluetooth

import com.example.kosherbridge.data.local.PolicyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake [PolicyStore] for the JVM tests: records every save/clear call and
 * keeps a plain map. Mirrors the production key shape ("address:profileId") so
 * the round trip is exercised, not the DataStore implementation. */
private class FakePolicyStore : PolicyStore {
  val entries = LinkedHashMap<String, Int>()
  val saves = mutableListOf<Triple<String, Int, Int>>() // (address, profileId, policy)
  val clears = mutableListOf<Pair<String, Int>>() // (address, profileId)

  override suspend fun save(address: String, profileId: Int, policy: Int) {
    saves += Triple(address, profileId, policy)
    entries["$address:$profileId"] = policy
  }

  override suspend fun clear(address: String, profileId: Int) {
    clears += address to profileId
    entries.remove("$address:$profileId")
  }

  override suspend fun loadAll(): Map<String, Int> = entries.toMap()
}

/**
 * Tests for the persistence glue between [ConnectionPolicyGuard] and
 * [PolicyStore] — the seam that lived untested inside HfpClientManager while
 * the guard itself got eight tests (F5). The N1 bug — decomposing a policy key
 * with `substringAfter(':')`, which swallows MAC addresses full of colons and
 * makes the parse return null every time — lived exactly here, and this suite
 * is its regression guard.
 */
class PolicyPersistenceTest {

  /** A MAC address full of ':' — the exact shape that broke the key parsing. */
  private val ADDR = "AA:BB:CC:DD:EE:FF"

  private fun guardWithOriginals(): ConnectionPolicyGuard {
    val guard = ConnectionPolicyGuard()
    guard.recordOriginals(ADDR) { HiddenHfp.POLICY_ALLOWED }
    return guard
  }

  @Test
  fun recording_persists_every_guarded_profile() = runTest {
    // The glue HfpClientManager's recordPolicyOriginals used to run inline,
    // and its key-parse bug meant setRecordedPolicy was NEVER called. This
    // pins the expected shape: exactly one save per guarded profile, with the
    // right profile ids (not zero saves from a parse that returns null).
    val guard = guardWithOriginals()
    val store = FakePolicyStore()

    PolicyPersistence(store).persistRecorded(guard, ADDR)

    assertEquals(guard.guardedProfiles.size, store.saves.size)
    assertEquals(guard.guardedProfiles.toSet(), store.saves.map { it.second }.toSet())
    assertTrue(store.saves.all { it.first == ADDR }) // all saves for THIS address
    // And they actually landed in the store (the bug left the store empty).
    assertEquals(guard.guardedProfiles.size, store.entries.size)
  }

  @Test
  fun persisted_originals_seed_the_guard() = runTest {
    val store = FakePolicyStore()
    PolicyPersistence(store).persistRecorded(guardWithOriginals(), ADDR)

    // A fresh guard = a restarted process: nothing is recorded in memory...
    val restarted = ConnectionPolicyGuard()
    assertFalse(restarted.hasRecorded(ADDR))

    // ...until the persisted originals are loaded back in at startup.
    PolicyPersistence(store).seedGuard(restarted)
    assertTrue(restarted.hasRecorded(ADDR))
  }

  @Test
  fun successful_restore_clears_the_persisted_record() = runTest {
    val guard = guardWithOriginals()
    val store = FakePolicyStore()
    PolicyPersistence(store).persistRecorded(guard, ADDR)

    // Every restore write succeeds: all records are cleared — and only for
    // the profiles the write actually covered (all guarded ones here).
    val results = guard.restore(ADDR) { _, _ -> true }
    PolicyPersistence(store).clearApplied(ADDR, results)

    assertTrue(store.clears.isNotEmpty())
    assertEquals(guard.guardedProfiles.toSet(), store.clears.map { it.second }.toSet())
    assertTrue(store.entries.isEmpty())
  }

  @Test
  fun failed_restore_keeps_the_persisted_record() = runTest {
    val guard = guardWithOriginals()
    val store = FakePolicyStore()
    PolicyPersistence(store).persistRecorded(guard, ADDR)

    // Every write is refused by the stack: nothing is cleared, so a later
    // repair/retry still has the originals to work with.
    val results = guard.restore(ADDR) { _, _ -> false }
    PolicyPersistence(store).clearApplied(ADDR, results)

    assertTrue(store.clears.isEmpty())
    assertEquals(guard.guardedProfiles.size, store.entries.size)
  }

  @Test
  fun mac_address_keys_round_trip() = runTest {
    // The N1 regression guard: an address full of colons must survive
    // save -> loadAll -> seed -> restore intact.
    val store = FakePolicyStore()
    PolicyPersistence(store).persistRecorded(guardWithOriginals(), ADDR)

    val restarted = ConnectionPolicyGuard()
    PolicyPersistence(store).seedGuard(restarted)

    val restored = mutableListOf<Pair<Int, Int>>() // (profileId, original)
    restarted.restore(ADDR) { profileId, original ->
      restored += profileId to original
      true
    }

    // Every guarded profile restored, to the ORIGINAL recorded value — proving
    // the key never got mangled by a colon split on the way back.
    assertEquals(restarted.guardedProfiles.size, restored.size)
    assertEquals(restarted.guardedProfiles.toSet(), restored.map { it.first }.toSet())
    assertTrue(restored.all { it.second == HiddenHfp.POLICY_ALLOWED })
    // A successful restore cleared the persisted record.
    assertTrue(store.entries.isEmpty())
  }
}