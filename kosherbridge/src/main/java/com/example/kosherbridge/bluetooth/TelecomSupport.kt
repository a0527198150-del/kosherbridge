package com.example.kosherbridge.bluetooth

/**
 * Pure decision logic for the TELECOM channel — the one channel that needs no
 * root, no Shizuku, no hidden API and no privileged permission.
 *
 * ## Why this channel exists
 *
 * Every other channel fights the platform for the phone's single hands-free
 * slot. This one lets the platform win, and then reads the result.
 *
 * When the player's own Bluetooth stack connects to the kosher phone with the
 * HFP-Client profile, AOSP's `HfpClientConnectionService` bridges the phone's
 * calls into the Telecom framework: it registers one `PhoneAccount` per
 * connected device, whose **account id is the device's Bluetooth MAC address**,
 * and turns each call on the phone into a Telecom call. That service carries no
 * automotive or feature gate — it runs on any build whose stack has the
 * HFP-Client profile at all.
 *
 * Telecom then broadcasts `ACTION_PHONE_STATE_CHANGED` for those calls
 * (`PhoneStateBroadcaster` skips only *external* calls, and includes the phone
 * number for anything that is not *self-managed* — an HFP-client call is
 * neither), and `TelecomManager.acceptRingingCall()` / `endCall()` act on them
 * behind nothing but the `ANSWER_PHONE_CALLS` runtime permission. Placing a
 * call through the phone is `placeCall()` aimed at that device's PhoneAccount.
 *
 * Crucially, **call audio needs no app code at all** on this channel: the
 * system's HFP-Client owns the SCO link and routes the voice to the player's
 * speaker and microphone itself. That is the whole reason this channel can do
 * what the raw RFCOMM channel structurally cannot.
 *
 * ## What lives here
 *
 * Only the parts that are pure string/enum bookkeeping, so they are unit-tested
 * on the JVM without a device: picking the right PhoneAccount out of the list
 * Telecom hands back, and turning a coarse phone-state string into the app's
 * own [CallInfo]. [TelecomBridge] owns the Android calls, the permissions and
 * the broadcast receiver, and delegates every decision here.
 */
object TelecomSupport {

  /**
   * Simple name of the AOSP service that bridges HFP-Client calls into Telecom.
   * A PhoneAccount published by this component is a kosher-phone account rather
   * than, say, a SIM or a VoIP app.
   */
  const val HFP_CONNECTION_SERVICE = "HfpClientConnectionService"

  /**
   * The parts of an `android.telecom.PhoneAccountHandle` this logic needs,
   * lifted out of the Android type so it can be built in a JVM test.
   *
   * @param componentClassName the handle's `ComponentName.getClassName()`
   * @param id the handle's `getId()` — for an HFP account this is the paired
   *   device's MAC address
   */
  data class AccountRef(val componentClassName: String, val id: String)

  /** True when this account was published by the HFP-Client Telecom bridge. */
  fun isHfpAccount(account: AccountRef): Boolean =
    account.componentClassName.substringAfterLast('.') == HFP_CONNECTION_SERVICE

  /**
   * Picks the account that represents [deviceAddress] — the kosher phone.
   *
   * Prefers an exact MAC match so a player paired to several phones dials
   * through the right one. Falls back to any HFP account when the address is
   * unknown or does not match (the user may have paired the phone outside the
   * app), and returns null when the platform published no HFP account at all —
   * which is exactly the signal that this player's stack has no working
   * HFP-Client profile, and therefore that this channel cannot be used.
   *
   * MAC comparison is case-insensitive: Android renders addresses upper-case,
   * but a value that round-tripped through storage may not be.
   */
  fun selectAccount(accounts: List<AccountRef>, deviceAddress: String?): AccountRef? {
    val hfp = accounts.filter { isHfpAccount(it) }
    if (hfp.isEmpty()) return null
    val wanted = deviceAddress?.trim().orEmpty()
    if (wanted.isNotEmpty()) {
      hfp.firstOrNull { it.id.equals(wanted, ignoreCase = true) }?.let { return it }
    }
    return hfp.first()
  }

  /** True when the platform exposes a usable HFP-Client Telecom bridge. */
  fun isChannelUsable(accounts: List<AccountRef>): Boolean = accounts.any { isHfpAccount(it) }

  /**
   * Maps one `ACTION_PHONE_STATE_CHANGED` broadcast to the app's call model.
   *
   * The broadcast is deliberately coarse — it carries only RINGING / OFFHOOK /
   * IDLE plus a number — so two things are approximated, and both are
   * approximations this app can live with:
   *
   * - **Direction** cannot be read from the broadcast, so it comes from
   *   [outgoingPending]: the bridge sets that when *this app* placed the call.
   *   A call the user dialled on the phone itself therefore reads as incoming,
   *   which is the correct guess for a bridge whose whole purpose is surfacing
   *   the phone's calls on the player.
   * - **OFFHOOK covers both "still ringing out" and "answered"**, with no way
   *   to tell them apart, so it maps to [CallState.ACTIVE]. For an outgoing
   *   call this means the call log's duration starts at dial time rather than
   *   at answer time.
   *
   * @param state the `TelephonyManager.EXTRA_STATE` string
   * @param number `EXTRA_INCOMING_NUMBER`; null/blank when the app lacks
   *   READ_CALL_LOG, in which case the caller is shown without a number
   * @param outgoingPending whether this app placed the call that is in flight
   * @return the call to publish, or null when the line is idle
   */
  fun callFor(state: String?, number: String?, outgoingPending: Boolean): CallInfo? {
    val clean = number?.trim()?.takeIf { it.isNotEmpty() }
    return when (state) {
      "RINGING" -> CallInfo(CallState.INCOMING, clean, CallDirection.INCOMING)
      "OFFHOOK" -> CallInfo(
        CallState.ACTIVE,
        clean,
        if (outgoingPending) CallDirection.OUTGOING else CallDirection.INCOMING,
      )
      else -> null
    }
  }

  /** True once the line went idle, so the bridge can forget a pending dial. */
  fun isIdle(state: String?): Boolean = state != "RINGING" && state != "OFFHOOK"
}
