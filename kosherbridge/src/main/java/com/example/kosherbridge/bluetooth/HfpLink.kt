package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Transport-agnostic view of a connected HFP link. The production path wraps a
 * [BluetoothSocket]; the simulation path uses an in-memory pipe pair so the
 * exact same AT-command logic can be exercised on an emulator (which has no
 * Bluetooth radio) against a simulated kosher phone ([MockAg]).
 */
interface HfpLink {
  /** Line-oriented input (unsolicited events + responses from the AG). */
  val input: BufferedReader

  /** Raw output for AT commands to the AG. */
  val output: OutputStream

  /** Whether the underlying transport still reports an open link. */
  val isOpen: Boolean

  fun close()
}

/** Wraps a real RFCOMM [BluetoothSocket] as an [HfpLink]. */
class BluetoothHfpLink(
  private val socket: BluetoothSocket,
  override val input: BufferedReader,
  override val output: OutputStream,
) : HfpLink {
  override val isOpen: Boolean
    get() = runCatching { socket.isConnected }.getOrDefault(false)

  override fun close() {
    runCatching { socket.close() }
  }
}

/**
 * In-memory link for simulation. Two instances share a pair of piped streams:
 * what one writes the other reads. [close] shuts both ends down so the remote
 * reader observes EOF and the session loop ends naturally.
 */
class LoopbackHfpLink(
  override val input: BufferedReader,
  override val output: OutputStream,
  private val onClose: () -> Unit = {},
) : HfpLink {
  @Volatile
  private var open = true

  override val isOpen: Boolean
    get() = open

  override fun close() {
    if (!open) return
    open = false
    runCatching { input.close() }
    runCatching { output.close() }
    onClose()
  }

  companion object {
    /** Client link (what RawHfpClient holds) paired with the AG-side link. */
    fun createPair(bufferSize: Int = 64 * 1024): Pair<LoopbackHfpLink, LoopbackHfpLink> {
      val clientToAg = PipedInputStream(bufferSize)
      val agToClient = PipedInputStream(bufferSize)
      val clientOutput = PipedOutputStream(clientToAg)
      val agOutput = PipedOutputStream(agToClient)
      val client = LoopbackHfpLink(
        BufferedReader(InputStreamReader(agToClient, Charsets.ISO_8859_1)),
        clientOutput,
      )
      val ag = LoopbackHfpLink(
        BufferedReader(InputStreamReader(clientToAg, Charsets.ISO_8859_1)),
        agOutput,
      )
      return client to ag
    }
  }
}
