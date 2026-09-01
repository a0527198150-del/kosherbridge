package com.example.kosherbridge.bluetooth

import com.example.kosherbridge.data.local.PolicyStore

/**
 * The persistence glue between [ConnectionPolicyGuard] and a [PolicyStore]:
 * write-through on record, one-time seed on process start, and clear-once-restored.
 *
 * Extracted from [HfpClientManager] so the record → save → load → seed → clear
 * round trip is JVM-testable — the F5 review extracted the guard and covered it
 * with tests, but this glue stayed inline in the manager and was never tested,
 * and the N1 bug (decomposing a MAC-containing policy key with an early-':' split)
 * lived exactly here: the tested half was correct, the untested half was broken.
 *
 * Neither this class nor its callers ever parse a policy key: the profile ids
 * come from the guard (which owns the key format), and the store treats keys as
 * opaque.
 */
class PolicyPersistence(private val store: PolicyStore) {

  /**
   * Persists whatever the guard recorded for one device — one save per guarded
   * profile, with the profile ids the guard itself resolved. Call after
   * [ConnectionPolicyGuard.recordOriginals] so the originals survive a process
   * restart.
   */
  suspend fun persistRecorded(guard: ConnectionPolicyGuard, address: String) {
    for ((profileId, original) in guard.recordedFor(address)) {
      store.save(address, profileId, original)
    }
  }

  /**
   * Reloads every persisted original into the guard — called once at startup so
   * restore still works after a process restart without re-reading every policy
   * first.
   */
  suspend fun seedGuard(guard: ConnectionPolicyGuard) {
    guard.seedRecorded(store.loadAll())
  }

  /**
   * Clears the persisted record of every profile whose restore/repair write
   * actually succeeded; a refused write keeps its record so a later attempt can
   * retry.
   */
  suspend fun clearApplied(address: String, results: List<ConnectionPolicyGuard.Result>) {
    for (r in results) {
      if (r.applied) store.clear(address, r.profileId)
    }
  }
}