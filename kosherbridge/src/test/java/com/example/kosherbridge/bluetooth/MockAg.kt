package com.example.kosherbridge.bluetooth

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.Writer

/**
 * A scripted Audio Gateway (the kosher phone's side of HFP) for JVM tests.
 *
 * Speaks the AG side of the AT protocol over piped character streams — no
 * device, no emulator, no Robolectric. The client under test sees an ordinary
 * [HfpLink]; this class plays the phone and records everything the client
 * sends, so tests can assert on the exact AT transcript.
 *
 * Scripting model: each [respond] rule maps one AT command to the lines the
 * AG sends back (a rule may emit several lines; OK/ERROR included). Rules are
 * matched in order; the first whose [predicate] accepts the command wins.
 * Unsolicited events (+CIEV, RING, +CLIP, ...) are injected with [emit].
 */
class MockAg {

  /** Everything the client sent, in order, one AT command per entry. */
  val received = mutableListOf<String>()

  private val rules = mutableListOf<Rule>()
  private val pending = ArrayDeque<String>()

  private val clientReader = PipedReader()
  private val clientWriter = PipedWriter(clientReader)

  private val agReader = PipedReader()
  private val agWriter = PipedWriter(agReader)

  private class Rule(val predicate: (String) -> Boolean, val respond: (String) -> List<String>)

  init {
    // Default AG behaviour: answer everything with OK unless a test overrides.
    respond({ true }) { listOf("OK") }
  }

  // ------------------------------------------------------------------ scripting

  /** Adds a response rule: when the client sends a command matching [on], the AG sends [lines]. */
  fun respond(on: (String) -> Boolean, lines: (command: String) -> List<String>) {
    rules.add(0, Rule(on, lines)) // newest rule wins
  }

  /** Convenience: respond to commands whose trimmed text equals [command]. */
  fun respondTo(command: String, lines: List<String>) {
    respond({ it.trim() == command }) { lines }
  }

  /** Injects an unsolicited AG event (+CIEV, RING, +CLIP, +CLCC, ...). */
  fun emit(line: String) {
    pending.add(line)
    drain()
  }

  /** Commands the client has sent so far, joined for assertions. */
  fun sentCommands(): List<String> = received.toList()

  // ------------------------------------------------------------------ plumbing

  /** The client-side link handed to RawHfpClient. */
  fun link(): HfpLink = MockAgLink()

  /**
   * Reads everything the client has written so far into [received] and
   * writes scripted responses back. Called on demand from [pump].
   */
  fun pump() {
    val buffer = CharArray(256)
    while (true) {
      val ready = runCatching { agReader.ready() }.getOrDefault(false)
      if (!ready) break
      val n = agReader.read(buffer)
      if (n <= 0) break
      val text = String(buffer, 0, n)
      for (raw in text.split('\r')) {
        val command = raw.trim()
        if (command.isEmpty()) continue
        received.add(command)
        val rule = rules.firstOrNull { it.predicate(command) }
        val lines = rule?.respond?.invoke(command) ?: listOf("OK")
        lines.forEach { pending.add(it) }
      }
    }
    drain()
  }

  /** Writes pending AG lines to the client stream. */
  private fun drain() {
    while (pending.isNotEmpty()) {
      val line = pending.removeFirst()
      agWriter.write(line)
      agWriter.write("\r\n")
    }
    agWriter.flush()
  }

  fun close() {
    runCatching { clientWriter.close() }
    runCatching { agWriter.close() }
    runCatching { clientReader.close() }
    runCatching { agReader.close() }
  }

  /**
   * The [HfpLink] the client talks to. The client's output stream is what the
   * AG reads (agReader); the client's input stream is what the AG writes
   * (clientWriter). Reads block until the test pumps a scripted line, which
   * is what makes the harness deterministic under virtual time.
   */
  private inner class MockAgLink : HfpLink {
    override val input: BufferedReader =
      BufferedReader(object : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int = clientReader.read(cbuf, off, len)
        override fun close() = clientReader.close()
      })

    override val output: OutputStream = object : OutputStream() {
      override fun write(b: Int) {
        agWriter.write(b)
      }

      override fun write(b: ByteArray, off: Int, len: Int) {
        agWriter.write(String(b, off, len, Charsets.UTF_8))
        // Deliver eagerly per write so pump() sees complete commands.
        agWriter.flush()
      }

      override fun flush() {
        agWriter.flush()
      }
    }

    override val isOpen: Boolean get() = open

    override fun close() {
      open = false
      runCatching { clientReader.close() }
    }
  }

  @Volatile private var open = true

  /** Simulates the AG closing the channel (the phone dropped the link). */
  fun dropLink() {
    open = false
    runCatching { clientWriter.close() }
  }
}
