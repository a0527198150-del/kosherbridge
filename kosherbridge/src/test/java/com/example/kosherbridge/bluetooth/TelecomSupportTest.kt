package com.example.kosherbridge.bluetooth

import com.example.kosherbridge.bluetooth.TelecomSupport.AccountRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the decision logic of the TELECOM channel — the one channel that
 * delivers call audio with no root, no Shizuku and no hidden API.
 *
 * Everything here is pure, so the whole channel's judgement is exercised on the
 * JVM: which PhoneAccount belongs to the kosher phone, whether the platform
 * bridges HFP calls at all, and how a coarse phone-state broadcast becomes a
 * call. The Android half ([TelecomBridge]) holds no decisions of its own.
 */
class TelecomSupportTest {

  private val PHONE = "AA:BB:CC:DD:EE:FF"
  private val OTHER = "11:22:33:44:55:66"

  private fun hfp(id: String) =
    AccountRef("com.android.bluetooth.hfpclient.HfpClientConnectionService", id)

  private fun sim(id: String = "0") =
    AccountRef("com.android.phone.PhoneAccountRegistrar", id)

  // ------------------------------------------------------------- account identity

  @Test
  fun hfp_account_is_recognised_by_its_service() {
    assertTrue(TelecomSupport.isHfpAccount(hfp(PHONE)))
  }

  @Test
  fun a_sim_or_voip_account_is_not_an_hfp_account() {
    // The channel must never dial the player's own SIM (or a VoIP app) thinking
    // it is the kosher phone.
    assertFalse(TelecomSupport.isHfpAccount(sim()))
    assertFalse(
      TelecomSupport.isHfpAccount(AccountRef("com.example.voip.CallingService", "acct")),
    )
  }

  @Test
  fun account_match_uses_the_simple_class_name_not_a_substring() {
    // A third-party account must not qualify just by mentioning the name.
    assertFalse(
      TelecomSupport.isHfpAccount(AccountRef("com.evil.FakeHfpClientConnectionServiceShim", "x")),
    )
  }

  // ------------------------------------------------------------- account selection

  @Test
  fun selects_the_account_matching_the_paired_phone() {
    val accounts = listOf(sim(), hfp(OTHER), hfp(PHONE))
    assertEquals(PHONE, TelecomSupport.selectAccount(accounts, PHONE)?.id)
  }

  @Test
  fun address_match_is_case_insensitive() {
    // Android renders MACs upper-case, but a value that round-tripped through
    // storage may not be - a case difference must not send the call to the
    // wrong phone.
    val accounts = listOf(hfp(PHONE))
    assertEquals(PHONE, TelecomSupport.selectAccount(accounts, PHONE.lowercase())?.id)
  }

  @Test
  fun falls_back_to_any_hfp_account_when_the_address_is_unknown() {
    // The user may have paired the phone from Android settings, so the app has
    // no stored address - dialling through the only HFP account is still right.
    val accounts = listOf(sim(), hfp(OTHER))
    assertEquals(OTHER, TelecomSupport.selectAccount(accounts, null)?.id)
    assertEquals(OTHER, TelecomSupport.selectAccount(accounts, "")?.id)
  }

  @Test
  fun never_selects_a_non_hfp_account() {
    // With only a SIM account present the answer must be "no account", not the
    // SIM: dialling the player's own SIM would place a real call from the wrong
    // device - the exact thing this bridge exists to avoid.
    assertNull(TelecomSupport.selectAccount(listOf(sim()), PHONE))
  }

  @Test
  fun no_accounts_at_all_means_no_selection() {
    assertNull(TelecomSupport.selectAccount(emptyList(), PHONE))
  }

  // ------------------------------------------------------------- channel usability

  @Test
  fun channel_is_usable_only_when_an_hfp_account_exists() {
    // This is the app's reflection-free answer to "does this player support
    // hands-free at all": the platform either published the account or it did
    // not.
    assertTrue(TelecomSupport.isChannelUsable(listOf(sim(), hfp(PHONE))))
    assertFalse(TelecomSupport.isChannelUsable(listOf(sim())))
    assertFalse(TelecomSupport.isChannelUsable(emptyList()))
  }

  // ------------------------------------------------------------- call mapping

  @Test
  fun ringing_becomes_an_incoming_call_with_the_number() {
    val call = TelecomSupport.callFor("RINGING", "+972501234567", outgoingPending = false)
    assertEquals(CallState.INCOMING, call?.state)
    assertEquals("+972501234567", call?.number)
    assertEquals(CallDirection.INCOMING, call?.direction)
  }

  @Test
  fun ringing_without_a_number_still_surfaces_the_call() {
    // Without READ_CALL_LOG the broadcast carries no number (Android 9+). The
    // call must still reach the screen - losing caller ID is acceptable,
    // missing the call entirely is not.
    val call = TelecomSupport.callFor("RINGING", null, outgoingPending = false)
    assertEquals(CallState.INCOMING, call?.state)
    assertNull(call?.number)
  }

  @Test
  fun a_blank_number_is_normalised_to_null() {
    // Some builds deliver an empty string rather than omitting the extra; the
    // UI must show "unknown", never an empty caller name.
    assertNull(TelecomSupport.callFor("RINGING", "   ", outgoingPending = false)?.number)
  }

  @Test
  fun offhook_is_active_and_carries_the_direction_of_the_dial() {
    assertEquals(
      CallDirection.OUTGOING,
      TelecomSupport.callFor("OFFHOOK", "0500000000", outgoingPending = true)?.direction,
    )
    assertEquals(
      CallDirection.INCOMING,
      TelecomSupport.callFor("OFFHOOK", "0500000000", outgoingPending = false)?.direction,
    )
    assertEquals(
      CallState.ACTIVE,
      TelecomSupport.callFor("OFFHOOK", null, outgoingPending = false)?.state,
    )
  }

  @Test
  fun idle_and_unknown_states_clear_the_call() {
    assertNull(TelecomSupport.callFor("IDLE", null, outgoingPending = false))
    assertNull(TelecomSupport.callFor(null, null, outgoingPending = true))
    assertNull(TelecomSupport.callFor("SOMETHING_ELSE", null, outgoingPending = false))
  }

  @Test
  fun idle_detection_matches_the_states_that_clear_the_call() {
    // isIdle drives forgetting a pending dial, so it must agree with callFor:
    // exactly the states that produce no call are the idle ones.
    for (state in listOf("IDLE", null, "SOMETHING_ELSE")) {
      assertTrue("$state should be idle", TelecomSupport.isIdle(state))
      assertNull(TelecomSupport.callFor(state, null, outgoingPending = false))
    }
    for (state in listOf("RINGING", "OFFHOOK")) {
      assertFalse("$state should not be idle", TelecomSupport.isIdle(state))
    }
  }
}
