package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.OutputStream

/**
 * Transport-agnostic view of a connected HFP link. The production path wraps a
 * [BluetoothSocket] and speaks the HFP AT-command protocol over RFCOMM.
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
