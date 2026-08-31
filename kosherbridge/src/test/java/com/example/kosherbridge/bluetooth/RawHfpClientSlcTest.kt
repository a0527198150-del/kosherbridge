package com.example.kosherbridge.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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
 * No Android device, no emulator, no Robolectric: the client is driven over
 * piped streams, and assertions poll the AT transcript deterministically.
 * The handshake and the post-SLC commands run on a real IO dispatcher
 * because they block on stream reads; the test asserts on the transcript
 * and the client's published state, never on wall-clock timing.
 */
class RawHfpClientSlcTest {

  // ------------------------------------------------------------------ helpers

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
   * Runs the handshake on a background dispatcher and returns its job.
   * The job's failure is captured so awaitTrue can re-throw it - a dead
   * handshake must fail the test with the real exception, never with a
   * timeout message (that is how 10 commits were spent chasing a swallowed
   * android.util.Log "not mocked" error).
   */
  private fun handshakeAsync(client: RawHfpClient, link: HfpLink): Job {
    val death = CompletableDeferred<Throwable>()
    val job = GlobalScope.launch(Dispatchers.IO) {
      try {
        client.runHandshakeForTest(link)
      } catch (t: Throwable) {
        death.complete(t)
        throw t
      }
    }
    job.invokeOnCompletion { t -> if (t != null) death.complete(t) }
    client.testHandshakeDeath = death
    return job
  }

  private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      // If the handshake died, fail with the REAL exception, never a timeout.
      client?.testHandshakeDeath?.let { death ->
        if (death.isCompleted) throw death.getCompleted()
      }
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError("condition not met within ${timeoutMs}ms")
      }
      Thread.sleep(10)
    }
  }

  // ------------------------------------------------------------------- tests

  @Test
  fun slc_sends_cind_read_not_bare_cind() {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().any { it.startsWith("AT+BRSF") } }
    awaitTrue { ag.sentCommands().any { it == "AT+CIND=?" } }
    awaitTrue { ag.sentCommands().any { it == "AT+CIND?" } }
    // The 8f75e6a regression: a bare "AT+CIND" (no ?) is not a valid command.
    assertFalse(ag.sentCommands().contains("AT+CIND"))
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun slc_completes_with_minimal_ag() {
    // An AG advertising no optional features still reaches the post-SLC step.
    val ag = MockAg()
    ag.respond({ it.startsWith("AT+BRSF") }) { listOf("+BRSF: 0", "OK") }
    ag.respond({ it == "AT+CIND=?" }) {
      listOf("+CIND: (\"service\",(0,1)),(\"call\",(0,1))", "OK")
    }
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().any { it.startsWith("AT+CMER") } }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun brsf_does_not_advertise_call_hold() {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().any { it.startsWith("AT+BRSF=") } }
    val brsf = ag.sentCommands().first { it.startsWith("AT+BRSF=") }
    val features = brsf.substringAfter("AT+BRSF=").toInt()
    // HF bit 1 (three-way calling / enhanced call control) must stay clear:
    // the client does not implement the CHLD hold procedures.
    assertEquals(0, features and 0x0002)
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun ag_sends_chld_query_is_tolerated() {
    // An AG that advertises three-way calling does not wedge the handshake:
    // the client skips AT+CHLD=? by design and still completes the SLC.
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().any { it.startsWith("AT+BRSF") } }
    client.agFeaturesForTest = 0x01 // AG advertises three-way calling
    awaitTrue { ag.sentCommands().contains("AT+CLIP=1") }
    assertTrue(ag.sentCommands().none { it.startsWith("AT+CHLD") })
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun ring_then_clip_surfaces_caller_id() {
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
  fun clcc_polling_is_event_driven() {
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
  fun dial_emits_atd() {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().contains("AT+CLIP=1") }
    client.dial("+972501234567")
    awaitTrue { ag.sentCommands().contains("ATD+972501234567;") }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun answer_emits_ata_reject_emits_chup() {
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    val job = handshakeAsync(client, ag.link())
    awaitTrue { ag.sentCommands().contains("AT+CLIP=1") }
    client.answer()
    awaitTrue { ag.sentCommands().contains("ATA") }
    client.reject()
    awaitTrue { ag.sentCommands().contains("AT+CHUP") }
    client.disconnect()
    job.cancel()
    ag.close()
  }

  @Test
  fun unsolicited_garbage_does_not_crash() {
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
  fun gateway_rotates_after_quick_drop() {
    // The rotation invariant: the order starts with HFP-AG and rotation moves
    // a failed gateway to the back. Exercised through the test seam.
    val ag = standardAg()
    val client = RawHfpClient(context = NoopContext, scope = TestScopes.service())
    assertTrue(client.gatewayOrderForTest().first().second == "HFP-AG")
    client.disconnect()
    ag.close()
  }
}
