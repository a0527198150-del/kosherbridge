package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * The connection-policy bookkeeping behind the profile guard, extracted from
 * [HfpClientManager] into a small unit that the actual field-bug fix (the
 * `channelMode == "RAW"` gate for profile 16, plus the record/restore/repair
 * mechanism) can be tested against with a fake read/write.
 *
 * This is pure string/int bookkeeping plus two injected operations (a policy
 * read and a policy write), with no Android runtime calls, no coroutines and no
 * hidden API — [HfpClientManager] owns the blocking reads/writes, the logging,
 * and the coroutine dispatch, and delegates only the bookkeeping here. Existing
 * behaviour is unchanged by the extraction.
 */
class ConnectionPolicyGuard {

  /** The profile ids [recordOriginals]/[repair] operate on. */
  val guardedProfiles = listOf(
    BluetoothProfile.HEADSET, // 1
    16,                        // HEADSET_CLIENT — the Shizuku/root channels drive it
    BluetoothProfile.A2DP,     // 2
    11,                        // A2DP_SINK
  )

  /** Original value read BEFORE the first FORBIDDEN write, keyed "address:profileId". */
  private val recorded = ConcurrentHashMap<String, Int>()

  /**
   * Read-only view of the currently recorded originals, keyed "address:profileId".
   * [HfpClientManager] uses it to reconcile this in-memory state with its
   * persisted copy (write on record, clear on a successful restore/repair).
   */
  val recordedSnapshot: Map<String, Int> get() = recorded.toMap()

  /**
   * Seeds the recorded originals from [HfpClientManager]'s persistence (e.g.
   * the originals a previous process run wrote to DataStore), so restore still
   * works after a process restart without re-reading every policy first.
   */
  fun seedRecorded(entries: Map<String, Int>) {
    recorded.putAll(entries)
  }

  /**
   * The originals recorded for one device, keyed by profile id - never by
   * string key. The persistence glue (and anything else outside this class)
   * must not reconstruct or decompose `"address:profileId"` keys: a MAC
   * address is full of ':' so parsing the profile id back out of a key with
   * `substringAfter(':')` grabs the wrong segment. The guard owns [policyKey]
   * and everything that knows the key format lives here.
   */
  fun recordedFor(address: String): Map<Int, Int> =
    guardedProfiles.mapNotNull { profileId ->
      recorded[policyKey(address, profileId)]?.let { profileId to it }
    }.toMap()

  private fun policyKey(address: String, profileId: Int) = "$address:$profileId"

  /**
   * Whether profile 16 (HFP-client) may be set to FORBIDDEN for a channel mode.
   * Only the explicit, sticky RAW choice may sacrifice it: FORBIDDEN is a
   * persistent per-device policy, and disabling the HFP-client profile breaks
   * the Shizuku/root/DIRECT channels afterwards. AUTO (which today resolves to
   * the raw path) and the privileged channels leave it untouched.
   */
  fun shouldForbidHeadsetClient(channelMode: String): Boolean = channelMode == "RAW"

  /**
   * Records the pre-write original policy for every guarded profile of one
   * device, once each. A later read may already observe the app's own FORBIDDEN
   * value — putIfAbsent keeps the first (true) original so restore is not a
   * no-op. Non-readable policies are skipped.
   */
  fun recordOriginals(
    address: String,
    unreadableValue: Int = HiddenHfp.POLICY_UNREADABLE,
    read: (profileId: Int) -> Int,
  ) {
    for (profileId in guardedProfiles) {
      val key = policyKey(address, profileId)
      if (recorded.containsKey(key)) continue
      val current = read(profileId)
      if (current != unreadableValue) recorded.putIfAbsent(key, current)
    }
  }

  /** True when any original has been recorded for [address] (restore has something to do). */
  fun hasRecorded(address: String): Boolean =
    guardedProfiles.any { recorded.containsKey(policyKey(address, it)) }

  /** Outcome of one per-profile restore/repair write. */
  data class Result(val profileId: Int, val original: Int?, val applied: Boolean)

  /**
   * Restores every recorded original for one device by writing it back; an
   * entry is forgotten only when its write actually succeeded, so a failed
   * write leaves the record for a later retry. Returns one [Result] per
   * restored profile, in [guardedProfiles] order. A no-op when nothing was
   * recorded.
   *
   * The profile id comes from [guardedProfiles] (not parsed out of the key):
   * keys are "<address>:<profileId>" and a MAC address itself contains ':', so
   * a naive split would grab the wrong segment.
   */
  fun restore(
    address: String,
    write: (profileId: Int, policy: Int) -> Boolean,
  ): List<Result> = guardedProfiles.mapNotNull { profileId ->
    val original = recorded[policyKey(address, profileId)] ?: return@mapNotNull null
    val applied = write(profileId, original)
    if (applied) recorded.remove(policyKey(address, profileId))
    Result(profileId, original, applied)
  }

  /**
   * The escape hatch: writes ALLOWED for every guarded profile of one device,
   * whether or not an original was ever recorded, and forgets any recorded
   * original that is thereby effectively restored. Returns one [Result] per
   * guarded profile.
   */
  fun repair(
    address: String,
    allowedPolicy: Int = HiddenHfp.POLICY_ALLOWED,
    write: (profileId: Int, policy: Int) -> Boolean,
  ): List<Result> = guardedProfiles.map { profileId ->
    val applied = write(profileId, allowedPolicy)
    if (applied) recorded.remove(policyKey(address, profileId))
    Result(profileId, null, applied)
  }
}