package com.example.kosherbridge.data.local

/**
 * The persistence seam behind the connection-policy bookkeeping
 * ([com.example.kosherbridge.bluetooth.ConnectionPolicyGuard] +
 * [com.example.kosherbridge.bluetooth.PolicyPersistence]). The original
 * connection policy this app recorded before the first FORBIDDEN write must
 * survive a process restart — connection policy itself is a persistent
 * per-device setting held by the Bluetooth stack, so an in-memory-only record
 * dies with the process and restore becomes impossible after any restart.
 *
 * [SettingsRepository] is the production DataStore-backed implementation; the
 * JVM test suite uses a fake, which is exactly why this seam exists.
 *
 * Keys are `"address:profileId"` and callers must treat them as opaque: a MAC
 * address is full of ':', so decomposing the profile id back out of a key
 * with a string split grabs the wrong segment.
 */
interface PolicyStore {

  /** Persists the original policy recorded for (address, profile). */
  suspend fun save(address: String, profileId: Int, policy: Int)

  /** Removes a recorded original once it has been successfully restored. */
  suspend fun clear(address: String, profileId: Int)

  /** Loads every persisted original, keyed `"address:profileId"` -> original Int. */
  suspend fun loadAll(): Map<String, Int>
}