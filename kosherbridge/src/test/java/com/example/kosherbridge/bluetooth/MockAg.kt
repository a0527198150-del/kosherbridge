package com.example.kosherbridge.bluetooth

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * A scripted Audio Gateway (the kosher phone's side of HFP) for JVM tests.
 *
 * Speaks the AG side of the AT protocol over a localhost socket pair — no
 * device, no emulator, no Robolectric. The client under test sees an ordinary
 * [HfpLink]; this class plays the phone and records everything the client
 * sends, so tests can assert on the exact AT transcript.
 *
 * Scripting model: each [respond] rule maps one AT command to the lines the
 * AG sends back (a rule may emit several lines; OK/ERROR included). Rules are
 * matched newest-first; all rules are registered BEFORE the pump thread starts
 * (the pump starts lazily on the first [link()] call), so there is no race
 * between registering a rule and the pump reading it. Unsolicited events
 * (+CIEV, RING, +CLIP, ...) are injected with [emit].
 *
 * Synchronization: the full transcript and the rule list are guarded by an
 * internal lock; awaiting is channel-based, never a busy-wait. A harness bug
 * surfaces as [harnessError] rather than a silent pump death followed by a
 * test timeout.
 */
class MockAg {

  private val lock = Any()
  private val received = mutableListOf<String>()
  private val unconsumed = mutableListOf<String>()
  private val rules = ArrayList<Rule>()
  /** Signalled whenever the pump adds a command, to wake [awaitSent]. */
  private val arrived = Channel<Unit>(Channel.UNLIMITED)

  @Volatile private var pumpStarted = false
  @Volatile private var pumpError: Throwable? = null

  // Socket pair: server listens, client connects, accepted = AG side.
  private val serverSocket: ServerSocket
  private val clientSocket: Socket
  private val agSocket: Socket

  private val agInput: BufferedReader
  private val clientOutput: OutputStream

  private val clientInput: BufferedReader
  private val agOutput: OutputStream

  private class Rule(val predicate: (String) -> Boolean, val respond: (String) -> List<String>)

  private val pumpThread: Thread

  init {
    // Default AG behaviour: answer everything with OK unless a test overrides.
    // Registered first so it reads last (newest rule wins).
    respond({ true }) { listOf("OK") }

    // Create a connected socket pair over localhost.
    serverSocket = ServerSocket(0)
    clientSocket = Socket("localhost", serverSocket.localPort)
    agSocket = serverSocket.accept()
    serverSocket.close()

    agInput = BufferedReader(InputStreamReader(agSocket.getInputStream()))
    agOutput = agSocket.getOutputStream()

    clientOutput = clientSocket.getOutputStream()
    clientInput = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

    pumpThread = Thread(::pumpLoop, "MockAg-pump")
    pumpThread.isDaemon = true
    // The pump does NOT start here: it starts lazily on the first link(), by
    // which point the test has registered all of its respond() rules.
  }

  /** Starts the pump exactly once; safe to call from any thread. */
  private fun ensureStarted() {
    if (pumpStarted) return
    synchronized(lock) {
      if (pumpStarted) return
      pumpStarted = true
      pumpThread.start()
    }
  }

  private fun pumpLoop() {
    try {
      while (!agSocket.isClosed) {
        val line = agInput.readLine() ?: break
        val command = line.trim()
        if (command.isEmpty()) continue
        val response = synchronized(lock) {
          received.add(command)
          unconsumed.add(command)
          rules.firstOrNull { it.predicate(command) }
            ?.respond?.invoke(command) ?: listOf("OK")
        }
        arrived.trySend(Unit)
        for (l in response) {
          agOutput.write((l + "\r\n").toByteArray(Charsets.US_ASCII))
        }
        agOutput.flush()
      }
    } catch (e: IOException) {
      // Expected teardown case: a test closes the socket. Only treat it as a
      // harness failure if neither side chose to close.
      if (!agSocket.isClosed && !clientSocket.isClosed) pumpError = e
    } catch (e: Throwable) {
      // Anything unexpected is a harness bug - surface it so the test fails
      // with the real error instead of a misleading timeout.
      pumpError = e
    }
  }

  // ------------------------------------------------------------------ scripting

  /** Adds a response rule: when the client sends a command matching [on], the AG sends [lines]. */
  fun respond(on: (String) -> Boolean, lines: (command: String) -> List<String>) {
    synchronized(lock) {
      rules.add(0, Rule(on, lines)) // newest rule wins
    }
  }

  /** Convenience: respond to commands whose trimmed text equals [command]. */
  fun respondTo(command: String, lines: List<String>) {
    respond({ it.trim() == command }) { lines }
  }

  /** Injects an unsolicited AG event (+CIEV, RING, +CLIP, +CLCC, ...). */
  fun emit(line: String) {
    ensureStarted()
    synchronized(lock) {
      runCatching {
        agOutput.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
        agOutput.flush()
      }
    }
  }

  /** Commands the client has sent so far, in order (full transcript). */
  fun sentCommands(): List<String> = synchronized(lock) { received.toList() }

  /**
   * Suspends until the client sends a command matching [predicate] and returns
   * it. Non-matching commands stay buffered for later awaits. This replaces the
   * old busy-wait polling: the test suspends on a channel instead of sleeping
   * against a wall-clock deadline. [timeoutMs] is only a safety net so a truly
   * stuck harness fails the test rather than hanging it forever.
   */
  suspend fun awaitSent(timeoutMs: Long = 20_000, predicate: (String) -> Boolean): String {
    ensureStarted()
    var result: String? = null
    withTimeout(timeoutMs) {
      while (result == null) {
        synchronized(lock) {
          val idx = unconsumed.indexOfFirst { predicate(it) }
          if (idx >= 0) result = unconsumed.removeAt(idx)
        }
        if (result == null) arrived.receive()
      }
    }
    return result!! // non-null once withTimeout returns
  }

  /** Any harness failure the pump hit (null when the pump is healthy). */
  fun harnessError(): Throwable? = pumpError

  // ------------------------------------------------------------------ plumbing

  /** The client-side link handed to RawHfpClient. Starts the pump on first call. */
  fun link(): HfpLink {
    ensureStarted()
    return SocketLink()
  }

  fun close() {
    runCatching { clientSocket.close() }
    runCatching { agSocket.close() }
    pumpThread.interrupt()
  }

  /**
   * The [HfpLink] the client talks to. Uses the client side of the socket
   * pair so reads/writes go over a real TCP connection to the AG side.
   */
  private inner class SocketLink : HfpLink {
    override val input: BufferedReader = clientInput
    override val output: OutputStream = clientOutput
    override val isOpen: Boolean get() = !clientSocket.isClosed

    override fun close() {
      runCatching { clientSocket.close() }
    }
  }

  /** Simulates the AG closing the channel (the phone dropped the link). */
  fun dropLink() {
    runCatching { agSocket.close() }
  }
}