package com.example.kosherbridge.bluetooth

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

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
 * matched in order; the first whose [predicate] accepts the command wins.
 * Unsolicited events (+CIEV, RING, +CLIP, ...) are injected with [emit].
 *
 * A daemon pump thread runs automatically, reading client commands and
 * writing scripted responses so the SLC handshake completes without manual
 * intervention.
 */
class MockAg {

  /** Everything the client sent, in order, one AT command per entry. */
  @Volatile var received = mutableListOf<String>()
    private set

  private val rules = mutableListOf<Rule>()

  // Socket pair: server listens, client connects, accepted = AG side
  private val serverSocket: ServerSocket
  private val clientSocket: Socket
  private val agSocket: Socket

  private val agInput: BufferedReader
  private val clientOutput: OutputStream

  private val clientInput: BufferedReader
  private val agOutput: OutputStream

  private class Rule(val predicate: (String) -> Boolean, val respond: (String) -> List<String>)

  /** Background thread that continuously pumps: reads client commands,
   *  matches rules, and writes responses. */
  private val pumpThread: Thread

  init {
    // Default AG behaviour: answer everything with OK unless a test overrides.
    respond({ true }) { listOf("OK") }

    // Create a connected socket pair over localhost.
    serverSocket = ServerSocket(0)
    clientSocket = Socket("localhost", serverSocket.localPort)
    agSocket = serverSocket.accept()
    serverSocket.close()

    // AG side: reads from client, writes responses to client
    agInput = BufferedReader(InputStreamReader(agSocket.getInputStream()))
    agOutput = agSocket.getOutputStream()

    // Client side: writes commands to AG, reads responses from AG
    clientOutput = clientSocket.getOutputStream()
    clientInput = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

    pumpThread = Thread({
      try {
        while (!agSocket.isClosed) {
          val line = agInput.readLine() ?: break
          val command = line.trim()
          if (command.isEmpty()) continue
          synchronized(received) {
            received.add(command)
          }
          val rule = rules.firstOrNull { it.predicate(command) }
          val lines = rule?.respond?.invoke(command) ?: listOf("OK")
          for (l in lines) {
            agOutput.write((l + "\r\n").toByteArray(Charsets.US_ASCII))
          }
          agOutput.flush()
        }
      } catch (_: Throwable) {
        // Socket closed — normal when the test tears down.
      }
    }, "MockAg-pump")
    pumpThread.isDaemon = true
    pumpThread.start()
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
  @Synchronized
  fun emit(line: String) {
    runCatching {
      agOutput.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
      agOutput.flush()
    }
  }

  /** Commands the client has sent so far. */
  fun sentCommands(): List<String> = synchronized(received) { received.toList() }

  // ------------------------------------------------------------------ plumbing

  /** The client-side link handed to RawHfpClient. */
  fun link(): HfpLink = SocketLink()

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
