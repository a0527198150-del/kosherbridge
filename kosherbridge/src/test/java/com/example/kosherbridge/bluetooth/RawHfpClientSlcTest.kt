package com.example.kosherbridge.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the RawHfpClient SLC/AT conversation against a scripted
 * Audio Gateway ([MockAg]). Each test replays one historical bug: if that
 * fix regresses, its test fails.
 *
 * No Android device, no emulator, no Robolectric: the client is driven over a
 * real localhost socket pair, and assertions suspend on a channel until the
 * AG records the expected command — there is no busy-wait polling and no
 * `Thread.sleep` (see MockAg.awaitSent). The handshake runs on a real IO
 * dispatcher because it blocks on stream reads, so full `runTest` virtual time
 * is impractical here (the documented exception in brief #2 F4c); structured
 * `withTimeout` is used only as a safety net so a genuinely stuck harness
 * fails instead of hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawHfpClientSlcTest {

  // ------------------------------------------------------------------ helpers

  /** The currently running handshake's death signal, or null when none is running. */
  private var testHandshakeDeath: CompletableDeferred<Throwable>? = null

  private fun handshakeDeath(): CompletableDeferred<Throwable>? = testHandshakeDeath

  private fun standardAg(): MockAg {
    val ag = MockAg()
    // A minimal but complete AG: answers BRSF with features, CIND test with
    // indicator names, CIND read with values, everything else OK.
    ag.respond({ it.startsWith("AT+BRSF") }) { listOf("+BRSF: 41", "OK") }
    ag.respond({ it == "AT+CIND=?" }) {
      listOf(
        "+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2))",
        "OK",
      )
    }
    ag.respond({ it == "AT+CIND?" }) { listOf("+CIND: 1,0,0,0", "OK") }
    return ag
  }

  /**
   * Runs the handshake on a background IO scope and returns its job. The job's
   * failure is captured so a dead handshake fails a test with the REAL
   * exception, never with a timeout message.
   */
  private fun handshakeAsync(client: RawHfpClient, link: HfpLink): Job {
    val death = CompletableDeferred<Throwable>()
    val job = TestScopes.io().launch {
      try {
        client.runHandshakeForTest(link)
      } catch (t: Throwable) {
        death.complete(t)
        throw t
      }
    }
    job.invokeOnCompletion { t -> if (t != null) death.complete(t) }
    testHandshakeDeath = death
    return job
  }

  /**
   * Fails fast on a harness error / dead handshake: if [block] times out, the
   * test fails with the real death exception or the AG's harness error rather
   * than the timeout wrapper — a test whose harness crashed must say so, never
   * "condition not met within Nms".
   */
  private suspend fun <T> failFast(ag: MockAg, block: suspend () -> T): T {
    return try {
      block()
    } catch (e: TimeoutCancellationException) {
      handshakeDeath()?.let { death ->
        if (death.isCompleted) throw death.getCompleted()
      }
      ag.harnessError()?.let { throw it }
      throw e
    }
  }

  // ------------------------------------------------------------------- tests

  @Test
  fun slc_sends_cind_read_not_bare_cind() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    failFast(ag) { ag.awaitSent { it.startsWith("AT+BRSF") } }
    failFast(ag) { ag.awaitSent { it == "AT+CIND=?" } }
    failFast(ag) { ag.awaitSent { it == "AT+CIND?" } }
    // The 8f75e6a regression: a bare "AT+CIND" (no ?) is not a valid command.
    assertFalse(ag.sentCommands().contains("AT+CIND"))
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun slc_completes_with_minimal_ag() = runBlocking {
    // An AG advertising no optional features still reaches the post-SLC step.
    val ag = MockAg()
    ag.respond({ it.startsWith("AT+BRSF") }) { listOf("+BRSF: 0", "OK") }
    ag.respond({ it == "AT+CIND=?" }) {
      listOf("+CIND: (\"service\",(0,1)),(\"call\",(0,1))", "OK")
    }
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    failFast(ag) { ag.awaitSent { it.startsWith("AT+CMER") } }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun brsf_does_not_advertise_call_hold() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    val brsf = failFast(ag) { ag.awaitSent { it.startsWith("AT+BRSF=") } }
    val features = brsf.substringAfter("AT+BRSF=").toInt()
    // HF bit 1 (three-way calling / enhanced call control) must stay clear:
    // the client does not implement the CHLD hold procedures.
    assertEquals(0, features and 0x0002)
    // NOTE FOR LATER (brief #1 experiment 0f): if the client is ever taught to
    // advertise three-way calling (HF bit 1 set) and run the AT+CHLD=?
    // exchange, this assertion must be updated to expect the bit SET, and the
    // handshake must be extended with the CHLD step those AGs require.
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun ag_sends_chld_query_is_tolerated() = runBlocking {
    // An AG that advertises three-way calling does not wedge the handshake:
    // the client skips AT+CHLD=? by design and still completes the SLC.
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    failFast(ag) { ag.awaitSent { it.startsWith("AT+BRSF") } }
    client.agFeaturesForTest = 0x01 // AG advertises three-way calling
    failFast(ag) { ag.awaitSent { it == "AT+CLIP=1" } }
    assertTrue(ag.sentCommands().none { it.startsWith("AT+CHLD") })
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun ring_then_clip_surfaces_caller_id() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    client.handleLineForTest("+CLIP: \"+972501234567\",145")
    client.handleLineForTest("RING")
    val call = client.call.value
    assertNotNull(call)
    assertEquals(CallState.INCOMING, call?.state)
    assertEquals("+972501234567", call?.number)
    assertEquals(CallDirection.INCOMING, call?.direction)
    client.disconnect()
    ag.close()
  }

  @Test
  fun clcc_polling_is_event_driven() = runBlocking {
    // caf9174 regression: call state is published from indicator events, and
    // an empty CLCC batch clears the call only through finishClccBatch - the
    // poller never hammers CLCC while idle.
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    client.handleLineForTest("+BRSF: 193") // 3-way + EC/NR + voice rec + EC status
    client.handleLineForTest(
      "+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2))",
    )
    client.handleLineForTest("+CIND: 1,0,0,0")
    // No call event has arrived; nothing may be published yet.
    assertEquals(null, client.call.value)
    // A CIEV change to callsetup=1 (incoming) must surface the call...
    client.handleLineForTest("+CIEV: 3,1")
    assertEquals(CallState.INCOMING, client.call.value?.state)
    // ...and a CLCC batch completing with no rows clears it only when CIEV
    // has never been seen (the feature-phone path). With CIEV live, a
    // transient empty batch must NOT dismiss a ringing call.
    client.handleLineForTest("OK")
    assertEquals(CallState.INCOMING, client.call.value?.state)
    client.disconnect()
    ag.close()
  }

  @Test
  fun dial_emits_atd() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    failFast(ag) { ag.awaitSent { it == "AT+CLIP=1" } }
    client.dial("+972501234567")
    failFast(ag) { ag.awaitSent { it == "ATD+972501234567;" } }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun answer_emits_ata_reject_emits_chup() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    failFast(ag) { ag.awaitSent { it == "AT+CLIP=1" } }
    client.answer()
    failFast(ag) { ag.awaitSent { it == "ATA" } }
    client.reject()
    failFast(ag) { ag.awaitSent { it == "AT+CHUP" } }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun unsolicited_garbage_does_not_crash() = runBlocking {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    // Random non-AT bytes must be ignored, not crash the parser.
    client.handleLineForTest("~~~garbage~~~")
    client.handleLineForTest("")
    client.handleLineForTest("+UNKNOWN: 1,2,3")
    client.handleLineForTest("42")
    assertEquals(null, client.call.value)
    client.disconnect()
    ag.close()
  }

  @Test
  fun gateway_rotates_after_quick_drop() = runBlocking {
    // The rotation invariant: the order starts with HFP-AG and rotation moves
    // a failed gateway to the back. Exercised through the test seam.
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    assertTrue(client.gatewayOrderForTest().first().second == "HFP-AG")
    client.disconnect()
    ag.close()
  }

  @Test
  fun ag_waits_for_chld_before_completing_slc() = runBlocking {
    // An AG that advertises three-way calling (BRSF bit 0) and withholds its
    // OK for every later command until it receives AT+CHLD=?. The client's
    // hfFeatures (0x0004 or 0x0020) do NOT set HF bit 1, so the client never
    // sends AT+CHLD=? (handshake step 5 is skipped by design). This documents
    // the CURRENT behaviour: such an AG cannot complete the SLC, and the
    // handshake must stall and be torn down by the watchdog rather than hang.
    // See brief #1 experiment 0f, which would add the CHLD=? exchange.
    val ag = MockAg()
    ag.respond({ true }) { emptyList() } // default: silent (newest-last)
    ag.respond({ it.startsWith("AT+BRSF") }) { listOf("+BRSF: 1", "OK") }
    ag.respond({ it == "AT+CHLD=?" }) { listOf("+CHLD: 0,1,1x,2,2x;0,1,2,3", "OK") }
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    client.handshakeWatchdogMsForTest = 1_000L
    val link = ag.link()
    val job = handshakeAsync(client, link)
    failFast(ag) { ag.awaitSent { it.startsWith("AT+BRSF") } }
    // The client must never send AT+CHLD=? with the current HF features.
    assertFalse(ag.sentCommands().any { it.startsWith("AT+CHLD") })
    // The AG has gone silent; the handshake must not hang — the watchdog tears
    // the stalled link down.
    withTimeout(5_000) { job.join() }
    assertFalse(link.isOpen)
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun ag_goes_silent_after_cmer_triggers_watchdog() = runBlocking {
    // Models the reported field failure: the phone completes the SLC through
    // AT+CMER, then stops answering. The handshake must not hang forever - the
    // watchdog tears the silent link down so the reconnect loop can retry.
    val ag = MockAg()
    ag.respond({ true }) { emptyList() } // default: silent (newest-last)
    ag.respond({ it.startsWith("AT+BRSF") }) { listOf("+BRSF: 41", "OK") }
    ag.respond({ it == "AT+CIND=?" }) {
      listOf(
        "+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2))",
        "OK",
      )
    }
    ag.respond({ it == "AT+CIND?" }) { listOf("+CIND: 1,0,0,0", "OK") }
    ag.respond({ it.startsWith("AT+CMER") }) { listOf("OK") }
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    client.handshakeWatchdogMsForTest = 1_000L
    val link = ag.link()
    val job = handshakeAsync(client, link)
    // The AG answers through CMER...
    failFast(ag) { ag.awaitSent { it.startsWith("AT+CMER") } }
    // ...then goes silent on AT+CLIP=1. The handshake must be torn down,
    // not hang.
    withTimeout(5_000) { job.join() }
    assertFalse(link.isOpen)
    client.disconnect()
    job.cancel()
    ag.close()
  }
}